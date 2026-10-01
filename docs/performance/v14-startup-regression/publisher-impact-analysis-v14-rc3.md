# Publisher Android impact analysis for Mobile SDK 14 RC3

**Publisher source:** `mobilecommunities/Communities-Android` at `fde623b358c` (`@W-24339298: Consume MSDK 14 rc3 in Master`)

**SDK comparison:** `v13.2.1` to `v14.0.0-rc.3`

**Analysis date:** 2026-09-30

## Conclusion

The account/client and User-Agent regressions are present in Publisher, but Publisher's integration avoids two of the largest generic sample-app paths:

- Publisher does not extend `SalesforceActivity`, so the SDK's synchronous `SalesforceActivityDelegate.onResume() -> getRestClient()` path does not run on each activity resume.
- Publisher installs its `CommunitySDKManager` singleton before calling `initNative()`. The RC3 feature hydration is only invoked when the base SDK creates `INSTANCE`, and `SmartStoreSDKManager.init()` does not invoke it. Consequently, Publisher currently does not pay the measured one-account all-user feature-hydration cost: 15.0 ms on the Pixel and 3.16 ms on the S25 Ultra.

Publisher does pay for first authenticated client creation and the generic User-Agent interceptor. Publisher-shaped benchmarks on a Pixel 8 Pro and Galaxy S25 Ultra, including its eleven additional OAuth keys, measured:

| Device and path | v13.2.1 P50 | RC3 P50 | RC3 change | RC3 P95 |
|---|---:|---:|---:|---:|
| Pixel: first authenticated user/client bootstrap, 1 account | 20.90 ms | 47.89-53.43 ms | **+26.99 to +32.53 ms** | 90.85-97.40 ms |
| Pixel: generic User-Agent evaluation, 1 account | 0.027 ms | 12.10-12.15 ms | **+12.08 to +12.13 ms/request** | 25.89-32.89 ms |
| S25 Ultra: first authenticated user/client bootstrap, 1 account | 6.64 ms | 14.20 ms | **+7.56 ms** | 16.23 ms |
| S25 Ultra: generic User-Agent evaluation, 1 account | 0.018 ms | 3.64 ms | **+3.62 ms/request** | 4.69 ms |
| S25 Ultra: first authenticated user/client bootstrap, 5 accounts | 6.48 ms | 14.64 ms | **+8.16 ms** | 17.85 ms |
| S25 Ultra: generic User-Agent evaluation, 5 accounts | 0.018 ms | 3.72 ms | **+3.70 ms/request** | 5.02 ms |

For the normal established-install startup graph, one authenticated client bootstrap is scheduled during application startup and one gating `HttpAccess.DEFAULT` auth-config request runs before Publisher's Native Load marker ends. Together they expose approximately **39-45 ms on the Pixel** but only **11.18 ms on the S25 Ultra** of cumulative local regression eligible to affect that interval. A first-ever launch can add another approximately 12 ms on the Pixel or 3.62 ms on the S25 because Publisher blocks on its publisher-config request. The client bootstrap is launched in `BackgroundScope`, so it can overlap other startup work—or finish outside the marker—rather than extending wall time one-for-one.

That is material, but it does **not** explain the full Publisher regression for the likely one-account state. A five-sample authenticated Publisher A/B on the same Galaxy S25 Ultra reproduced `NativeLoad` at 329 -> 520 ms (**+191 ms / +58.1%**) and `AppColdStart` at 3,598 -> 4,066 ms (**+468 ms / +13.0%**). The isolated 11.18 ms of eligible account/User-Agent work is only about **5.9%** of that locally reproduced Native Load delta. This makes the distinction concrete: the Publisher regression is real and reproducible on the available device, but most of it is outside the account operations already isolated by the component harness. The conclusion changes if the benchmark retains many accounts, triggers duplicate concurrent user refreshes, or makes additional early requests not visible in the reviewed source/configuration.

## Physical Publisher reproduction on Galaxy S25 Ultra

The local reproduction used Publisher's authenticated force-stop workload and Publisher's own telemetry markers. The baseline was built from `release-262.010` at `34e9047e` with report-matching version `262.010 (1326201007)`. The candidate was Jenkins master build 1326, `266.000 (1326601326)`, because the original build-504 artifact had expired.

| Publisher marker | 262.010 local P50 | 266.000 local P50 | Local change | Original report change |
|---|---:|---:|---:|---:|
| `NativeLoad` | 329 ms | 520 ms | **+191 ms / +58.1%** | +202 ms / +60.5% |
| `AppColdStart` | 3,598 ms | 4,066 ms | **+468 ms / +13.0%** | +390 ms / +11.2% |
| `CommunityLoad` | 3,260 ms | 3,535 ms | +275 ms / +8.4% | +101 ms / +3.2% |
| `SplashLoad` | 3,244 ms | 3,516 ms | +272 ms / +8.4% | +55 ms / +1.8% |

The `NativeLoad` ranges did not overlap: 327-334 ms versus 514-523 ms. This is only one five-run install cohort, not a release-quality statistical series, but its agreement with the independent report is much too close to dismiss as ordinary device noise. Full provenance, raw samples, derived marker intervals, and limitations are in [`publisher-performance-measurements-galaxy-s25-ultra.md`](publisher-performance-measurements-galaxy-s25-ultra.md).

The report page identifies its device as `SM-S947B`; the Jenkins source-run configuration instead records device ID `R5GL15EQB2Z`, display name `Galaxy S26+`, and Splunk model `SM-S947U`. That discrepancy should be corrected, but it no longer blocks the central finding: the available `SM-S938U1` S25 Ultra reproduces essentially the same regression.

## Actual report lifecycle and a mismatched entry point

### The supplied report is not a 13.2.1-to-RC3 A/B

The APK provenance materially broadens the report comparison:

- Publisher branch `release-262.010` at `34e9047e` declares Mobile SDK `13.0.2.10-publisher-internal`, not `13.2.1`.
- The report's baseline version code, `1326201007`, decodes through Publisher's `VersionUtil` as `defaultVersionCode=13_262_010_00` plus Jenkins build **7**.
- Publisher master was on Mobile SDK `14.0.0-rc.2` at commits `02b2ea03` and `67333bc8`, immediately before the 2026-09-26 report. RC3 was consumed later at `fde623b3` on 2026-09-29.
- The candidate version code, `1326600504`, decodes as `defaultVersionCode=13_266_000_00` plus Jenkins build **504**.

Therefore the report is best described as Publisher `release-262.010` build 7 versus Publisher master build 504, including an older Publisher-specific SDK baseline, RC2, and all intervening application/dependency/toolchain changes. It remains valid evidence of a product regression, but it is not direct evidence of the isolated `13.2.1 -> 14.0.0-rc.3` SDK delta analyzed elsewhere in this document.

The entry point supplied in the follow-up, `clwrOnMainLoginTestMu.js`, is **not** the workload that produced the original report. The report embeds `useCases: ["AuraGuest", "AuraLogin"]`, and source run #23 records `UsecaseChoice = adhoc2 (Aura, tabs)`. The CLWR-on-main loop therefore must not be used to interpret this report.

The Aura automation that existed when the source run began is `soleil-q4mobile@1670e0b3cc5829bdc231467b2bc3a518854e47b6`; its relevant files are unchanged at the later supplied commit `32f28f438dd27bc9a93ad5517703678196862b0f`. The authenticated loop performs the interactive login only in iteration one. Both `ClickLoginAuraTestMu` and `PAppLogin` explicitly skip login after a successful previous iteration, and the logout action is commented out. Each iteration then performs warm/background setup, **one** force-stop/relaunch for the cold start, waits 20 seconds, and performs another background/foreground transition.

Thus the report's authenticated cold sample is the state we modeled: process memory and the SDK's in-memory caches are lost, while the persisted Android account and its OAuth fields remain. Login, logout, account creation, and RTR rotation are not part of the intended cold interval. The actual Aura loop has one forced cold relaunch per iteration, not the two present in the unrelated CLWR-on-main loop.

The report query at the exact perf-tools revision, `0cfbf6f036ef09f2bd4eb1872f2055ddbcf0b732`, selects every Publisher telemetry row in the configured run window with a non-null organization ID and `payload_eventSource` equal to `AppColdStart` or `NativeLoad`. It does not join events to an Appium action or iteration identifier. That is compatible with the one-cold-relaunch Aura loop, but it means the raw time series is still needed to prove that install/login startup emitted no additional authenticated marker.

The report contains 102 baseline versus 123 candidate authenticated Cold/Native events, and 174 versus 160 guest Cold events. Those unequal event counts do not invalidate medians, but they show the data is an aggregate over many five-iteration cycles rather than a single balanced paired run. The five iterations within one install are correlated and must not be treated as five independent installations when calculating confidence. Baseline and candidate should be compared by install/run cohort as well as in aggregate, using a cluster bootstrap over install cycles, so that time-of-day, device state, and telemetry-delivery differences cannot change the mixture. The guest and authenticated loops each contain one cold relaunch, although their surrounding waits and foreground transitions are not identical; guest remains a useful directional control rather than a perfectly paired subtraction.

## How Publisher initializes the SDK

The relevant cold-start sequence is:

1. `CommunitiesApp.onCreate()` calls `appPresenter.initialize()`.
2. `CommunitiesAppPresenter.bindServicesAndManagers()` explicitly calls `GlobalServices.sdkManager()`.
3. `CommunitySDKManager.setupInstance()` calls `setInstance(sdkInstance)` and then `initNative(...)`.
4. `CommunitiesAppPresenter.setupUserManagementAndSecurity()` launches `CommunityUserManager.refreshSessionLoop()` in `BackgroundScope`.
5. `refreshUserSessionInfo()` resolves `userAccountManager.currentUser`, then obtains `sdkManager.clientManager?.peekRestClient()` and caches the resulting `RestClient` in `UserSessionInfo`.
6. Later callers normally use `CommunityUserManager.getRestClient()`, which returns that cached client.

Source locations:

- `app/src/main/java/com/mysalesforce/community/app/CommunitiesApp.kt:22-25`
- `app/src/main/java/com/mysalesforce/community/app/presenter/CommunitiesAppPresenter.kt:124-150,158-188`
- `app/src/main/java/com/mysalesforce/community/sdk/CommunitySDKManager.kt:94-128`
- `app/src/main/java/com/mysalesforce/community/service/CommunityUserManager.kt:174-253,452-489,657-680`

### First-client cost applies once, not on every request

Publisher's `UserSessionInfo` retains the `RestClient`. `getRestClient()` returns `current.restClient` when present. Its `OkHttpRestService` also retains the client's `OkHttpClient`. This is good integration behavior: it prevents the RC3 manager/provider construction cost from recurring for every application request.

The first authenticated bootstrap still exercises all of the confirmed RC3 duplication:

- full `currentUser` reconstruction;
- `ClientManager(UserAccount)` reverse lookup through `buildAccount(user)`;
- provider construction and its second validation/hydration;
- first `RestClient` builder resolution through `getUserFromOrgAndUserId()`.

The app-specific benchmark times that complete sequence, rather than adding overlapping lower-level probe rows.

### Publisher adds eleven OAuth-key reads to every full hydration

After SDK initialization, Publisher sets these `additionalOauthKeys`:

`lightning_domain`, `visualforce_domain`, `lightning_sid`, `content_domain`, `content_sid`, `cookie-clientSrc`, `cookie-sid_Client`, `sidCookieName`, `__Secure-has-sid`, `parent_sid`, and `token_format`.

Three keys (`cookie-clientSrc`, `cookie-sid_Client`, and `sidCookieName`) are exact duplicates of storage keys RC3 has already read before the additional-key loop. Seven more are OAuth-name aliases of typed fields (`lightning_domain`, `visualforce_domain`, `lightning_sid`, `content_domain`, `content_sid`, `parent_sid`, and `token_format`). `__Secure-has-sid` is genuinely app-specific.

The three exact duplicates can be reused without changing semantics. Reusing the seven aliases is also plausible, but only after migration/RTR tests prove that legacy and current records always keep the alias and typed-key values identical; correctness should win if they can diverge. Publisher could eventually migrate its consumers to typed `UserAccount` properties, but the 14.0.1 optimization should not silently collapse aliases without that proof.

## Paths Publisher does not exercise

### No SDK activity-delegate reconstruction on resume

`CommunitiesWebviewActivity` extends `AppCompatActivity`, not `SalesforceActivity`. Its `onResume()`:

1. calls `super.onResume()`;
2. immediately ends `AppWarmStart`; and
3. later reads `userManager.getRestClient()`, which normally returns the cached client.

Therefore, the generic sample-app `SalesforceActivityDelegate.onResume()` results (+4.7 ms P50 on the Pixel and +2.72 ms on the S25 Ultra for one account) are not directly applicable to Publisher's warm marker.

Publisher's process-lifecycle `CommunitySDKManager.onStart()` does launch `userManager.refresh()` in the background. In a stable same-user session that performs one full `currentUser` reconstruction but does not rebuild the client. The Publisher-shaped Pixel probe measured an isolated +1.97 to +4.07 ms; the S25 probe measured +0.32 ms. It may contend with the short warm interval, but it is not synchronously inside the marker and cannot be equated to the reported +8 ms without a trace.

### The new all-user feature hydration is skipped

RC3 calls `hydratePerUserFeatures()` only inside the `if (INSTANCE == null)` block in `SalesforceSDKManager.init()`. Publisher first calls `setInstance(sdkInstance)`. The SmartStore initialization path also has no hydration call. Thus the Publisher path reaches initialization with a non-null instance and does not execute `hydratePerUserFeatures()`.

This is not an optimization to preserve. It is a behavioral inconsistency for SmartStore/custom-manager apps: persisted SDK per-user feature markers are not guaranteed to be available on their first request. A 14.0.1 change should make base, SmartStore, and custom-manager initialization consistent while using the proposed five-field feature projection. Doing so preserves immediate correctness without adding the measured full-object cost to Publisher (15.0 ms for one account on the Pixel; 3.16 ms on the S25 Ultra).

## Request fan-out before Publisher markers

Every RC3 `HttpAccess.DEFAULT` client starts with the no-argument `UserAgentInterceptor`. In an authenticated process it calls `getUserAgent("", null)`, which reconstructs `currentUser`. An authenticated `RestClient` also has a second, user-bound interceptor; the generic interceptor's work is redundant and its header is overwritten.

Publisher's startup calls map to the markers as follows:

| Work | Transport | Generic RC3 User-Agent cost? | Gates Native Load? | Gates Cold? |
|---|---|---:|---:|---:|
| Auth configuration (`/.well-known/auth-configuration`) | `HttpAccess.DEFAULT` | Yes, once (~12 ms Pixel / ~3.6 ms S25 P50) | **Yes** | Yes |
| Publisher configuration (`/.well-known/mobile-publisher-configuration`) | `HttpAccess.DEFAULT` | Yes, once | Only on first-ever launch/site switch; otherwise asynchronous | Only on first-ever launch/site switch |
| Enhanced-domain redirect check | `HttpURLConnection` | No | Yes when applicable | Yes |
| Base-document connectivity check | `HttpURLConnection` | No | Yes | Yes |
| First WebView community navigation | WebView | No SDK interceptor | Native Load ends immediately before it | Yes |
| Security validation OPTIONS request | cached SDK `OkHttpClient` | Yes when configured | No; launched asynchronously from `onResume()` | Can overlap |
| Surface/API access check | cached SDK `OkHttpClient` | Yes, once | No; starts after visually-ready callback | Does not gate the visually-ready success path |
| Connected-app settings fetch | cached SDK `OkHttpClient` | Yes, once | No | Starts after content is shown; does not gate success |

The key boundary is `CommunityWebViewEngine.loadUrlInWebview()`: it ends `NativeLoad` before calling `super.loadUrl(url)`. The community WebView's page/network work is therefore outside Native Load and does not use the SDK interceptor. `AppColdStart` ends when the visually-ready JavaScript callback is consumed.

For an established install, only the auth-config request is visibly serialized through the expensive generic interceptor before Native Load ends. This is why multiplying either device's per-request result by an arbitrary request count would overstate Publisher's critical-path impact.

## Startup concurrency and tail risk

`CommunityUserManager.awaitUserReady()` is documented as blocking until `ready` is true, but its implementation is `ready.first { true }`; that predicate accepts the current `false` value immediately. As a result:

- application initialization does not actually wait for authenticated client bootstrap;
- preload code does not establish the documented user-ready barrier;
- first client construction can overlap Hilt/activity/WebView preparation; and
- the process-lifecycle `onStart()` refresh can overlap the initial refresh on a slow run.

This behavior existed before RC3, so it is not itself the version regression. It does make the more expensive RC3 bootstrap nondeterministic in Publisher's marker intervals and can amplify tails through CPU/AccountManager contention. RC3's `resolveClientManager()` cache is intentionally check-then-act rather than atomic, so two overlapping first callers can both construct managers before the last cache write wins.

Changing `awaitUserReady()` to `ready.first { it }` is a correctness decision, not a performance shortcut: it may convert currently overlapped work into serialized Native Load. The immediate next step should be tracing the existing behavior and counting concurrent refresh/bootstrap executions before changing that barrier.

## Putting the supplied report in context

The supplied report shows:

| Metric | Full Publisher delta | Guest delta | Authenticated-specific difference | Confirmed Publisher-shaped SDK opportunity |
|---|---:|---:|---:|---:|
| Native Load P50 | +202 ms | +24 ms | ~+178 ms | Pixel: ~39-45 ms established / ~51-57 ms first launch. S25: ~11.18 ms established / ~14.80 ms first launch |
| Cold P50 | +390 ms | +107 ms | ~+283 ms | Same device-dependent startup work is included, but no additional known SDK request is serialized between first WebView load and visually ready |
| Warm P50 | +8 ms | 0 ms | +8 ms | No direct activity-delegate path; background same-user refresh is +2-4 ms on Pixel and +0.32 ms on S25 in isolation |

The confirmed paths are consistent with a meaningful authenticated penalty and with heavier tails. They are not large enough, in Publisher's normal cached-client topology, to establish that the SDK account work explains the entire median regression.

The comparison is also not an SDK-only A/B. The report spans Publisher `262.010` on `13.0.2.10-publisher-internal` to Publisher `266.000` on RC2, with application, dependency, and toolchain changes in the same interval. The later RC3 consumption commit itself changes only the RC2-to-RC3 dependency version/locks. Remaining attribution must separate SDK runtime changes from those application/dependency changes, and a separate matched `13.2.1 -> 14.0` SDK test should not be labeled as a reproduction of the historical product comparison.

## Correctness-preserving fixes with direct Publisher value

1. **Carry one exact account/user snapshot through first client construction.** This targets the measured +27-33 ms Pixel and +7.56 ms S25 one-account bootstrap gaps without changing RTR refresh behavior.
2. **Remove the generic current-user User-Agent evaluation from authenticated clients.** Keep one interceptor bound to the exact client/request user. This targets ~12 ms on the Pixel and ~3.6 ms on the S25 for each affected Publisher request.
3. **Reuse values for Publisher's three exact duplicate additional OAuth keys.** Preserve `additionalOauthValues` compatibility. Collapse the seven typed-field aliases only if legacy, JWT/RTR, and account-migration tests prove the persisted values cannot diverge.
4. **Make immediate feature hydration consistent across base and SmartStore/custom-manager initialization using the validated five-field projection.** This fixes the current Publisher correctness gap without introducing the full one-account cost measured on either device.
5. **Keep Publisher's `UserSessionInfo` and `OkHttpClient` reuse.** It already prevents repeated manager/client construction and should not be replaced with per-call SDK resolution.
6. **Do not weaken RTR.** Live account validation inside token refresh, shared refresh coordination, token-generation adoption, liveness checks, and logout/relogin protection remain unchanged.

## Trace needed to close the remaining gap

Instrument one production-equivalent Publisher build at both endpoints and record:

- stored account count and current account's position;
- each `refreshUserSessionInfo()` start/end and whether calls overlap;
- each `ClientManager` and `RestClient` creation;
- each `buildUserAccount()` and caller;
- each generic and bound User-Agent interceptor invocation;
- request URL category (auth config, publisher config, security OPTIONS, API access, connected-app settings), without credentials;
- `AppColdStart`, `NativeLoad`, `CommunityLoad`, and visually-ready boundaries in the same Perfetto trace; and
- main-thread, background-pool, Binder, class-load, and WebView-provider slices.
- the action timestamp for the Aura cold relaunch, the install/run cohort, and the exact `AppColdStart`/`NativeLoad` event selected by the report query.

The most useful controlled A/B is the combined correctness-preserving account/User-Agent patch in the exact Publisher release build. If Native Load recovers only the device-appropriate component estimate, bisect the remaining app/dependency bundle. If it recovers substantially more, the trace should reveal duplicate concurrent bootstrap or additional intercepted requests that static startup routing did not expose.

## Proposed physical-device reproduction and attribution experiment

The experiment should be run in two stages because the questions are different.

### Stage 1: reproduce the Publisher product delta — completed

The five-run S25 Ultra experiment reproduced the product delta: `NativeLoad` increased by 191 ms / 58.1% and `AppColdStart` by 468 ms / 13.0%. It used the exact baseline source/version code and the current Jenkins candidate rather than expired master build 504. This answers the triage question, but an AB/BA series across multiple install cycles is still required for a release gate or P95 claim.

Five launches per build are sufficient to answer the triage question—whether a roughly +202 ms Native Load and +390 ms Cold shift is visible at all on the local device. They are not enough to certify a 10% release gate or characterize P95. The five observations within one install are also one correlated cohort, not five independent installs.

### Stage 2: attribute the delta to Mobile SDK — first controlled cell completed

The first attribution cell used one identical Publisher-shaped app source tree and changed only the Mobile SDK dependency from public 13.2.1 to final 14.0.0. It reproduced Publisher's custom-manager-before-`initNative()` topology, eleven `additionalOauthKeys`, authenticated current-user resolution, and first cached client creation. Seven measured force-stop launches followed one unmeasured post-install launch for each endpoint on the S25 Ultra.

The authenticated session bootstrap moved from 18 to 23 ms P50 (+5 ms), client-ready time from process start moved from 43 to 48 ms (+5 ms), and Android `TotalTime` moved from 100 to 106 ms (+6 ms). All samples ended at thermal status 0. This confirms a small SDK-only regression but does not approach Publisher's +191 ms `NativeLoad` result. The app intentionally exposes a `SalesforceActivity` lifecycle marker that Publisher does not use, and it does not yet issue Publisher's gating auth-config request, so those boundaries are diagnostic rather than exact Publisher marker equivalents.

The exact `13.0.2.10-publisher-internal` artifact was then run in the same authenticated sample and was effectively flat with an adjacent 13.2.1 rerun: 99 -> 102 ms Android `TotalTime`, 18 -> 19 ms session bootstrap, and 41 -> 43 ms client-ready time. This closes the historical SDK bridge without revealing a missing large regression.

A same-source Publisher SDK-only A-B-A-B is also complete. Across eight authenticated launches per endpoint, `NativeLoad` moved only 465 -> 471.5 ms (+6.5 ms / +1.4%) and Android `TotalTime` moved 872.5 -> 882.5 ms (+10 ms / +1.1%). This confirms that the full SDK graph does not reproduce the approximately 190 ms native-phase regression in current Publisher source.

However, `AppColdStart` moved 2,954 -> 3,374 ms (+420 ms / +14.2%). Calculating `AppColdStart - NativeLoad` per launch before taking the median yields 2,499.5 -> 2,906 ms (+406.5 ms / +16.3%), placing the candidate-associated shift after `NativeLoad`. `SplashLoad` overlaps `CommunityLoad`, and one baseline community/splash log line was captured too early, so their independent P50 differences are not additive attribution. The post-native result does not match the historical report's phase distribution and still includes WebView, server, and network variability. Full setup and samples are in [`publisher-sdk-only-performance-measurements-galaxy-s25-ultra.md`](publisher-sdk-only-performance-measurements-galaxy-s25-ultra.md).

The remaining cells are post-native request/WebView tracing, a one-variable dependency A/B only after the trace implicates a changed component, and the proposed 14.0.1 patch. The historical Publisher product A/B remains a separate row because it includes all intervening application and dependency changes. Full isolated-sample setup, raw samples, and limitations are in [`sdk-only-sample-performance-measurements-galaxy-s25-ultra.md`](sdk-only-sample-performance-measurements-galaxy-s25-ultra.md).

## Measurement method and limitations

- Both device benchmarks use the same real `AccountManager`, encrypted user records, `ClientManager`, provider, `RestClient`, and User-Agent implementation as the earlier SDK harness.
- It sets Publisher's exact eleven `additionalOauthKeys`, resolves current user, constructs the first user-bound manager/client, and reports 100 bootstrap samples plus 200 user/User-Agent samples.
- The one-account RC3 cell was run twice around the baseline on the Pixel; its reported range is the range of those P50/P95 results rather than a selectively chosen run. Every S25 benchmark cell ended at thermal status 0, and endpoint order was balanced across account counts.
- The five-account cells quantify sensitivity; the Publisher source exposes no normal `switchToUser` flow, so one account is the more likely production state, but the supplied report does not record account count.
- The report's embedded metadata and Jenkins parameters prove it ran Aura, not the supplied CLWR-on-main entry point. The benchmark-date Aura source was reviewed at commit `1670e0b3cc5829bdc231467b2bc3a518854e47b6`, and the report query at perf-tools commit `0cfbf6f036ef09f2bd4eb1872f2055ddbcf0b732`. Raw telemetry rows were not bundled locally, so install/run cohort balance and exclusion of any first-launch marker still require verification.
- The component figures are Publisher-shaped SDK benchmarks, but the product-level delta has now also been reproduced in authenticated Publisher APKs. The baseline was locally built from exact source/version code with debug rather than CI signing, and the candidate is current master build 1326 rather than the expired report build 504.
- Synthetic accounts were removed from the device after collection.
- Full S25 distributions and run conditions are in [`performance-measurements-galaxy-s25-ultra.md`](performance-measurements-galaxy-s25-ultra.md).
- Full Publisher product samples and derived marker intervals are in [`publisher-performance-measurements-galaxy-s25-ultra.md`](publisher-performance-measurements-galaxy-s25-ultra.md).
- The identical-app SDK dependency A/B is in [`sdk-only-sample-performance-measurements-galaxy-s25-ultra.md`](sdk-only-sample-performance-measurements-galaxy-s25-ultra.md).
- The direct same-source Publisher SDK-only A/B is in [`publisher-sdk-only-performance-measurements-galaxy-s25-ultra.md`](publisher-sdk-only-performance-measurements-galaxy-s25-ultra.md).
