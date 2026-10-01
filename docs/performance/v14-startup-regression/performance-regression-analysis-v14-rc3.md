# Salesforce Mobile SDK Android performance regression analysis

**Comparison:** `v13.2.1` (`144e68fb2`) to current `dev` HEAD (`65026220e`, functionally equivalent to `v14.0.0-rc.3` for the Android runtime paths analyzed)

**Analysis date:** 2026-09-30

## Executive conclusion

RC3 is being promoted to GA. This analysis therefore evaluates correctness-preserving improvements suitable for a potential 14.0.1 patch; it is not a release recommendation for 14.0 GA.

The largest confirmed, directly actionable SDK contributor is repeated, synchronous reconstruction of complete `UserAccount` objects through Android `AccountManager` during SDK initialization, authenticated activity resume, `RestClient` construction, and ordinary API requests. The physical-device measurements show that this is material, but they do not support attributing the entire Publisher cold regression to account hydration alone. The underlying changes protect important correctness properties: per-user feature flags must be present on the first request, a `ClientManager` must remain bound to the exact persisted account, and RTR refreshes must use live persisted credentials and serialize competing refreshes. Those properties must remain intact.

The 14.0.1 opportunity is to carry the same exact account and freshly hydrated user snapshot through client construction, and to read only the small persisted projection required for startup feature hydration. This removes redundant reads without caching refresh tokens, weakening liveness checks, delaying feature availability, or changing RTR winner/loser coordination.

The static operation counts now have physical-device timing context, and the absolute cost is strongly device-dependent. On a Pixel 8 Pro, RC3's one-account feature hydration costs 15.0 ms, fresh manager/client construction regresses by 45.0 ms, and the SDK-owned activity-resume section regresses by 4.7 ms. On a Galaxy S25 Ultra, the same values are 3.16 ms, +6.69 ms, and +2.72 ms. Both devices confirm the same redundant work, but the Samsung result sharply reduces how much of Publisher's end-to-end delta can be assigned to it. Detailed distributions and limitations are in [`performance-measurements-pixel8pro.md`](performance-measurements-pixel8pro.md) and [`performance-measurements-galaxy-s25-ultra.md`](performance-measurements-galaxy-s25-ultra.md).

An authenticated Publisher APK A/B on the Galaxy S25 Ultra then reproduced the product regression itself: `NativeLoad` P50 moved from 329 to 520 ms (**+191 ms / +58.1%**) and `AppColdStart` from 3,598 to 4,066 ms (**+468 ms / +13.0%**), closely matching the supplied report's +202 ms and +390 ms. The previously isolated 11.18 ms of Publisher-eligible account/User-Agent work is only about **5.9%** of the reproduced Native Load delta. The regression is therefore not an artifact of the report device, but most of it remains outside the account operations measured so far. Full samples are in [`publisher-performance-measurements-galaxy-s25-ultra.md`](publisher-performance-measurements-galaxy-s25-ultra.md).

For one authenticated account and no application-defined extra OAuth fields, static call-path analysis finds:

| Path | v13.2.1 synchronous `AccountManager` operations | RC3 operations | Measured context |
|---|---:|---:|---|
| SDK initialization before `AppCreateComplete` | 0 | ~45 | RC3 feature hydration, Pixel/S25: 15.0/3.16 ms for 1 account; 76.4/16.15 ms for 5; 505.3/63.34 ms for 20 |
| First authenticated activity resume and first `RestClient` construction | ~44 | ~184 | Warmed fresh-manager/client proxy, Pixel: 23.1 -> 68.1 ms (+45.0); S25: 3.11 -> 9.80 ms (+6.69) |
| Cold path total: SDK init + first resume | ~44 | ~229 | Supplied app benchmark: 3,484 -> 3,874 ms P50 (+390 ms); Native Load +202 ms |
| Later authenticated resume | ~44 | ~94 | SDK-owned lifecycle section, Pixel: 17.2 -> 21.9 ms (+4.7); S25: 11.07 -> 13.78 ms (+2.72) |
| Ordinary authenticated API request | 0 feature-related account reads | ~47 before network I/O | Generic User-Agent, Pixel: 0.05 -> 12.87 ms (+12.82); S25: 0.014 -> 3.24 ms (+3.23) per evaluation |

These remain useful as call-path explanations, not as a latency model: primitive account reads have different costs, and the measured paths overlap. They are conservative for multiple stored accounts and for apps that persist additional OAuth keys. Perfetto and instrumented call counters should still be used to confirm device-level scheduling and IPC.

For a one-account base-SDK app, the isolated feature step plus fresh-manager/client gap exposes about 60 ms on the Pixel but only 9.85 ms on the S25 Ultra before request fan-out. Ten generic User-Agent evaluations represent about 128 ms of cumulative local preparation on the Pixel and 32 ms on the S25. Those cumulative values are not automatically startup wall time because calls can overlap. The large device spread proves that static operation counts cannot be converted to one universal latency estimate.

That generic total does not apply unchanged to Publisher. Publisher caches its client, does not extend `SalesforceActivity`, and currently skips the base feature-hydration call through its SmartStore/custom-manager initialization sequence. On the Pixel, its measured one-account first-client gap is +27-33 ms and the one gating User-Agent evaluation is about +12 ms. On the S25 Ultra, those costs are +7.56 ms and +3.62 ms, or about 11.18 ms cumulative eligible work before Native Load ends. The account paths are therefore material and actionable, but are not established as the whole Publisher regression. The app-specific analysis is in [`publisher-impact-analysis-v14-rc3.md`](publisher-impact-analysis-v14-rc3.md).

The separate APK/Dex dependency investigation has been moved to [`binary-footprint-analysis-v14.md`](binary-footprint-analysis-v14.md) so it can evolve independently from the authenticated runtime analysis.

## Scope and method

- Reviewed the complete `v13.2.1..dev` range: 525 commits, 317 changed files, 35,771 insertions, and 7,690 deletions.
- Deep-reviewed production changes on application initialization, account resolution, REST-client construction, authentication, push initialization, app attestation, SmartStore, dependency loading, and Android manifest startup components.
- Compared the supplied authenticated and guest performance distributions, including raw and IQR-filtered percentiles.
- Built `SalesforceSDK` and `SmartStore` from both endpoints with the same local JDK/Android SDK environment. Consumer artifact-size results are documented separately.
- Performed static critical-path and `AccountManager` operation accounting.
- Ran matched component and lifecycle benchmarks from both endpoints on a physical Pixel 8 Pro and Galaxy S25 Ultra; full results are in [`performance-measurements-pixel8pro.md`](performance-measurements-pixel8pro.md) and [`performance-measurements-galaxy-s25-ultra.md`](performance-measurements-galaxy-s25-ultra.md).
- Ran five authenticated force-stop Publisher cold launches per APK on the Galaxy S25 Ultra, using Publisher's own `NativeLoad` and `AppColdStart` markers.

Current `dev` is two commits past tag `v14.0.0-rc.3` (`863835e9a`). The Android SDK differences after RC3 are version-string changes; the shared submodule changes are also generated version-string updates. The analyzed startup behavior is therefore representative of RC3.

## Supplied benchmark evidence

Source report: internal Publisher Aura adhoc performance report, build 23.

Environment shown by the report: baseline `262.010 (1326201007)`, candidate `266.000 (1326600504)`, SM-S947B, Android, Wi-Fi, 2026-09-26. The Jenkins source-run configuration instead identifies device `R5GL15EQB2Z` as `Galaxy S26+` / Splunk model `SM-S947U`, so the exact report-device label is internally inconsistent. The report's regression rule requires at least 20 samples and both a 100 ms absolute and 10% relative P50 increase.

Subsequent APK provenance analysis found that this is not a direct `v13.2.1` versus RC3 comparison. Publisher `release-262.010` declares Mobile SDK `13.0.2.10-publisher-internal`; Publisher master still declared `14.0.0-rc.2` when the report ran, and consumed RC3 on 2026-09-29. The version codes decode to Jenkins builds 7 and 504. The report therefore measures complete Publisher product builds with intervening application, SDK, dependency, and toolchain changes. It is valid product-level regression evidence and motivation for the SDK analysis, but it cannot by itself attribute the full delta to `v13.2.1..v14.0.0-rc.3`.

### Local Publisher reproduction

Five authenticated force-stop launches per APK on the available Galaxy S25 Ultra reproduced the report closely:

| Metric | Local baseline P50 | Local candidate P50 | Local change | Report change |
|---|---:|---:|---:|---:|
| Native Load | 329 ms | 520 ms | **+191 ms / +58.1%** | +202 ms / +60.5% |
| Cold | 3,598 ms | 4,066 ms | **+468 ms / +13.0%** | +390 ms / +11.2% |

The baseline came from exact `release-262.010` source with report-matching version code; the candidate was the current Jenkins master build 1326 because report build 504 was no longer retained. This confirms a stable product-level regression on the S25 Ultra, not SDK-only attribution. The baseline uses `13.0.2.10-publisher-internal`, the current candidate is RC3-era, and intervening Publisher/toolchain changes remain confounded.

### Authenticated results

| Metric | Baseline P50 | Candidate P50 | P50 change | Tail evidence |
|---|---:|---:|---:|---:|
| Cold | 3,484 ms | 3,874 ms | +390 ms / +11.2% | P75 +29.9%; P95 4,816 -> 6,451 ms (+33.9%) |
| Native Load | 334 ms | 536 ms | +202 ms / +60.5% | P95 348 -> 2,342 ms (+573.1%) |
| Warm | 11 ms | 19 ms | +8 ms / +72.7% | P95 37 -> 48 ms (+29.7%) |
| Community | 3,109 ms | 3,210 ms | +101 ms / +3.2% | P95 -2.6% |
| Splash | 3,057 ms | 3,112 ms | +55 ms / +1.8% | P95 -9.3% |

The cold and Native Load medians breach the report's SLO. Warm has a large percentage regression but remains below the report's 100 ms absolute threshold.

IQR filtering does not remove the signal:

- Cold: 3,446 -> 3,861 ms, +12.0%; P95 +29.0%.
- Native Load: 334 -> 533 ms, +59.6%; P95 +57.2%.
- Warm: 11 -> 19 ms, +72.7%; P95 +53.8%.

### Guest control

The corresponding raw guest medians are much smaller:

- Cold: 2,415 -> 2,522 ms, +4.4%.
- Native Load: 205 -> 229 ms, +24 ms / +12.0%.
- Warm: 6 -> 6 ms, 0%.
- Community: +1.6%.
- Splash: -8.1%.

IQR-filtered guest results remain similar: Cold +3.9%, Native Load +24 ms / +12.0%, Warm 0%, Community +2.0%, Splash -4.8%.

This authenticated/guest split is the strongest experimental clue. It points most of the incremental regression toward authenticated bootstrap and early request work rather than general rendering, Community load, or Splash rendering. It does not identify account hydration as the only authenticated contributor. The residual guest delta is consistent with a smaller common dependency/binary cost.

### What the actual report automation measures

The follow-up identified `clwrOnMainLoginTestMu.js`, but the original report did not run that use case. Its embedded metadata names `AuraGuest` and `AuraLogin`, and Jenkins source run #23 records `UsecaseChoice = adhoc2 (Aura, tabs)`. At the Aura source revision present when that run began (`1670e0b3cc5829bdc231467b2bc3a518854e47b6`), the authenticated loop logs in only during iteration one, skips both login actions after a successful prior iteration, leaves logout disabled, and performs one force-stop/relaunch per iteration for the cold sample.

The measured state is therefore a persisted authenticated cold start: the process and SDK in-memory caches are new, while the Android account remains. Interactive authentication, per-iteration logout, account recreation, and RTR rotation are outside the intended interval. This supports the state used by the Publisher-shaped benchmarks and does not increase the measured SDK-account contribution.

At the report's exact perf-tools revision (`0cfbf6f036ef09f2bd4eb1872f2055ddbcf0b732`), the SQL selects telemetry by run filters, non-null organization ID, app name, and event source; it does not join a metric to an Appium action or iteration. The Aura loop has only one intended cold relaunch, but raw timestamps are still needed to exclude any extra first-launch marker. The report contains 102 baseline versus 123 candidate authenticated Cold/Native events and 174 versus 160 guest Cold events, so install/run-cohort comparisons should accompany the aggregate percentiles. Confidence intervals should cluster-bootstrap whole install cycles rather than treating the five within-install iterations as independent samples. The guest and authenticated loops each perform one cold relaunch but use different surrounding waits and foreground transitions, which makes authenticated-minus-guest subtraction directional rather than perfectly paired.

## Confirmed account-path contributor: full hydration on critical paths

### 1. SDK initialization now reads every persisted user

Commit `364ca9e49` added persistent per-user feature flags and `hydratePerUserFeatures()`.

Current code:

- `libs/SalesforceSDK/src/com/salesforce/androidsdk/app/SalesforceSDKManager.kt:1634-1646` calls `userAccountManager.authenticatedUsers` and iterates every user.
- `UserAccountManager.getAuthenticatedUsers()` at `UserAccountManager.java:271-287` builds a complete `UserAccount` for every stored Android account.
- `SalesforceSDKManager.init()` invokes this synchronously at `SalesforceSDKManager.kt:2307-2309`.
- `AppCreateComplete` is emitted only afterward at `SalesforceSDKManager.kt:2311-2314`.

One `buildUserAccount()` currently performs 42 `getUserData` calls, one additional account-name `getUserData`, and one `getPassword`, with decryption for the persisted values (`UserAccountManager.java:547-665`). That is 44 synchronous account reads per user. Including `getAccountsByType`, initialization adds approximately 45 operations for one user and scales linearly with the number of accounts.

Immediate hydration is a correctness requirement because persisted feature markers must be available on the first API request, including for non-current users. The expensive part is not the timing; it is the projection. The payload needs only encrypted org ID, user ID, and `KEY_FEATURE_FLAGS`. To preserve the current `buildUserAccount()` filtering semantics exactly, the projected reader should also check encrypted auth token and instance URL, because accounts missing any of auth token, instance URL, org ID, or user ID are currently excluded. The same synchronous all-account behavior can therefore be implemented as `1 + 5N` account operations rather than `1 + 44N`, without admitting malformed accounts.

This work lands directly in Native Load for apps initialized through the base `SalesforceSDKManager` path. On the Pixel harness it costs 15.0 ms P50 for one account, 76.4 ms for five, and 505.3 ms for twenty. On the Galaxy S25 Ultra it costs 3.16, 16.15, and 63.34 ms respectively. Publisher is an important exception: it calls `setInstance()` before `initNative()`, and neither the guarded base initializer nor `SmartStoreSDKManager.init()` hydrates features in that state. Publisher therefore does not currently pay this cost, but also does not receive the new persisted-feature behavior. The 14.0.1 implementation should make initialization consistent while using the five-field projection.

### 2. Multi-user `ClientManager` refactor added repeated reconstruction

Commit `716ee6625` refactored `ClientManager` around a user-bound account. It improved correctness for multi-user routing, but the implementation repeatedly converts between `Account` and fully hydrated `UserAccount`:

- `SalesforceSDKManager.getRestClient()` reads the current account and builds a full user at `SalesforceSDKManager.kt:1780-1785`.
- A cache miss constructs `ClientManager(appContext, user)` at `SalesforceSDKManager.kt:1723-1739`.
- That constructor calls `UserAccountManager.buildAccount(user)` at `ClientManager.java:94-100`.
- `buildAccount(user)` scans accounts and calls `buildUserAccount(account)` before comparing account name plus `UserAccount.equals()` at `UserAccountManager.java:675-685`.

`UserAccount.equals()` compares only org ID and user ID, so RC3 still makes an identity comparison rather than comparing mutable credentials. However, it hydrates all 44 fields merely to obtain those two IDs. The exact-account invariant can be preserved by comparing Android account name/type plus targeted org-ID and user-ID reads, as v13.2.1 did for identity. Alternate-community accounts that share a Salesforce org/user remain disambiguated by account name.

### 3. The token-provider constructor rehydrates the same user again

`ClientManager.createRestClient()` already has a freshly built, validated `UserAccount`, but constructs `AccMgrAuthTokenProvider(this)` at `ClientManager.java:190-199`.

The provider constructor then calls `clientManager.getValidatedUser(false)` at `ClientManager.java:448-462`. That method:

1. checks `accountExists()`;
2. calls `buildUserAccount(account)` again; and
3. calls `validateUser()`, which checks `accountExists()` a second time.

This adds about 46 synchronous account operations to every `RestClient` construction without obtaining information that was not already available to the caller. Live credential rehydration is essential inside `getNewAuthToken()`, where a different provider may already have rotated the token. It is not necessary in the SDK-internal construction path when `createRestClient()` has just validated a snapshot from the manager's exact bound account. An internal snapshot-accepting constructor can remove this duplicate while the public constructor and every live refresh guard retain their existing behavior.

### 4. `RestClient` construction scans and hydrates all accounts again

On the first client for an identity, `RestClient.setOkHttpClientBuilder()` calls `getUserFromOrgAndUserId()` at `RestClient.java:261-274` so the User-Agent interceptor is bound to the client user rather than mutable current-user state. That identity binding is required for multi-user correctness.

`getUserFromOrgAndUserId()` currently calls `getAuthenticatedUsers()`, which fully hydrates every account before finding the matching org/user (`UserAccountManager.java:711-724`). For one account this adds another 45 operations to cold client construction; for `N` accounts it adds `1 + 44N`.

The caller already has the exact, freshly validated `UserAccount`. Passing that snapshot into an SDK-internal `RestClient` constructor preserves the correct per-user User-Agent and eliminates the scan. A fallback public-constructor path can retain current resolution behavior for API compatibility.

### 5. Ordinary API requests compute the User-Agent twice and consult current user

`HttpAccess.createNewClientBuilder()` installs a no-argument `UserAgentInterceptor`. Authenticated `RestClient` construction then installs a second interceptor bound to its user. On a regular API request without a `UserAccount` request tag:

1. the generic interceptor calls `getUserAgent("", null)`;
2. `getUserAgent()` calls `userAccountManager.currentUser`, causing approximately 47 account operations for one account; and
3. the bound interceptor computes the User-Agent again and overwrites the header with the correct client user's value.

The second interceptor repairs multi-user correctness, but the first lookup is redundant and reads mutable current-user state on every request. Authenticated clients should have exactly one effective User-Agent resolution, bound to the client/request user. Token-refresh requests should continue using the request-scoped `UserAccount` tag added for RTR/User-Agent correctness.

### 6. Feature-flag persistence rewrites unrelated credentials

`persistUserFeatureFlags()` mutates a `UserAccount`, resolves its `Account`, and calls `updateAccount()`. `updateAccount()` writes the entire auth bundle and password, including access token, refresh token, token type, DPoP metadata, and UI SID (`UserAccountManager.java:522-536`, `757-829`).

Feature persistence requires only `KEY_FEATURE_FLAGS`. A targeted encrypted `setUserData` write would be substantially cheaper and avoid allowing a stale caller snapshot to overwrite credentials advanced by an RTR refresh. It would also correctly clear the persisted key when the last flag is removed; `buildAuthBundle()` currently omits `KEY_FEATURE_FLAGS` for an empty set, so the old persisted value is not explicitly cleared.

### 7. This work runs on every activity resume

`SalesforceActivityDelegate.onResume()` calls `SalesforceSDKManager.getRestClient()` at `SalesforceActivityDelegate.java:81-92`. `RenditionComplete` is emitted only after the callback. Account resolution and client construction therefore affect both cold rendition and warm/resume measurements.

## Static operation model

The following assumes one stored account, no extra application-defined OAuth fields, a valid authenticated user, and a first-use client-manager cache miss.

### v13.2.1 resume

| Component | Operations |
|---|---:|
| `getCurrentAccount()` in `getRestClient()` | 1 account-list + 2 identity reads = 3 |
| `getCurrentAccount()` again in `peekRestClient()` | 3 |
| One full `buildUserAccount()` | 36 field reads + account name + password = 38 |
| **Total** | **~44** |

The old auth-token provider accepted the already resolved instance URL, auth token, and refresh token; its constructor did not rehydrate the account.

### RC3 first resume

| Component | Operations |
|---|---:|
| Resolve current account | 3 |
| Build current `UserAccount` | 44 |
| First `ClientManager` construction via `buildAccount(user)` | 1 account-list + 44-field reconstruction = 45 |
| Validate caller-provided user | 1 account-list |
| Provider constructor `getValidatedUser()` | 1 account-list + 44-field reconstruction + 1 second account-list = 46 |
| First `RestClient` builder resolves its user by hydrating all accounts | 1 account-list + 44-field reconstruction = 45 |
| **First-resume total** | **~184** |
| Startup feature hydration before resume | **~45** |
| **Cold initialization + first-resume total** | **~229** |

### RC3 later resume

The one-entry `ClientManager` cache removes the 45-operation construction on a same-user cache hit, but the current account/user and provider are still rebuilt:

`3 + 44 + 1 + 46 = ~94` operations.

### RC3 ordinary API request

The generic User-Agent interceptor resolves `currentUser` before the bound-user interceptor overwrites the header:

`getCurrentAccount()` (3) + `buildUserAccount()` (44) = approximately 47 operations per request for one account. This is outside the activity-resume total above and can affect the first data request as well as steady-state API latency.

### Scaling

- Startup hydration is `1 + 44N` operations for `N` persisted accounts.
- `getCurrentAccount()` reads two identity fields per candidate until it finds the stored current user.
- `buildAccount(user)` can perform a 44-field hydration for every same-name/identity candidate it tests.
- First client construction calls `getUserFromOrgAndUserId()`, which hydrates all `N` accounts.
- Each configured additional OAuth key adds another persisted field read to every full hydration.

This makes multi-user clients especially vulnerable to tail latency, consistent with the very large Native Load P95 regression.

## Why RC2 did not solve the regression

Commit `834aac511` correctly identified the dropped account-resolution cache and added:

- cached current-user identity;
- a single-entry current-user `ClientManager` cache; and
- an overload that lets `getRestClient()` pass its already built user to `peekRestClient()`.

Those changes remove one class of duplicate lookup. They do not remove:

- synchronous all-user feature hydration during SDK init;
- the first-use `ClientManager` constructor's full reverse lookup;
- the provider constructor's full rehydration and duplicate existence checks; or
- the first `RestClient` builder's all-account scan;
- the per-resume construction of a new `RestClient` and provider; or
- the generic User-Agent interceptor's current-user hydration on every API request.

The principal RC2 regression test, `getRestClient_resolvesCurrentAccountExactlyOnce()` at `SalesforceSDKManagerClientManagerTest.kt:623-649`, asserts one access to the `currentAccount` property. It does not count the `getUserData`, `getPassword`, or `getAccountsByType` calls made inside the remaining full hydrations. The test can pass while approximately 94 account operations still occur on a cache-hit resume, and it does not cover the per-request User-Agent lookup.

## Separate binary-footprint analysis

APK, Dex, and transitive dependency growth are documented in [`binary-footprint-analysis-v14.md`](binary-footprint-analysis-v14.md). They are intentionally separated from this authenticated runtime analysis because the remediation, validation, and customer impact differ.

## Other change areas screened

| Area | Change | Assessment |
|---|---|---|
| OkHttp | 4.12.0 -> 5.3.2 | Plausible contributor during first `RestClient`/HTTP stack initialization. It may explain part of the authenticated-only remainder after account fixes, but current evidence does not identify it as the dominant cause. Isolate in an A/B build. |
| Compose / Kotlin / AndroidX | Compose 1.8.2 -> 1.11.1, BOM 2025.07 -> 2026.05, Activity 1.10.1 -> 1.13.0, Lifecycle 2.8.7 -> 2.10.0, Core 1.16.0 -> 1.18.0, serialization 1.6.3 -> 1.11.0 | Broad class-loading risk. Binary growth is covered in the separate footprint analysis; the runtime portion needs controlled dependency-bundle A/B tests. Guest Cold +4% and Native Load +24 ms may be the common floor. |
| App attestation / Play Integrity | New Play Integrity 1.6.0 dependency and attestation client | `appAttestationClient` is lazy and only exists when a cloud project ID is supplied. It can be expensive during configured authentication, but it is not a general post-login warm-start explanation. Test enabled and disabled separately. |
| SmartStore / SQLCipher | SQLCipher 4.10.0 -> 4.17.0; SQLite also updated | Important for SmartStore clients and native-library/database-open latency. It cannot explain the supplied authenticated/guest split on its own. Benchmark first database open separately. |
| WorkManager / Firebase | WorkManager 2.10.3 -> 2.11.2; Firebase Messaging 25.0.0 -> 25.0.2 | Possible manifest/initializer and class-loading changes, but small relative to the confirmed account path. Compare merged manifests and startup traces in a minimal app. |
| SDK manifest | Added activities; no new SalesforceSDK `ContentProvider` or eager initializer | Does not support a new SDK provider as the primary cause. Transitive AndroidX providers still need comparison in merged app manifests. |
| Login/auth/DPoP | Large OAuth, DPoP, login UI, and token migration changes; six more persisted fields in a full user hydration | The extra fields raise each hydration from 38 to 44 account reads, but repetition of the entire hydration is much more important than the six-field increase. DPoP cryptography should be traced separately when enabled. |
| Push registration | Multi-user and registration refactors | Conditional on push mode and lifecycle configuration. `foregroundPushRegistrationTarget()` may call current-user resolution when foreground registration is enabled. Secondary until traced in an affected app. |
| Rendering | Community and Splash medians are near-flat or improved at the tail | The supplied evidence does not support rendering as the main regression. |

## Correctness constraints for a 14.0.1 patch

The following are invariants, not optimization candidates:

1. **Feature flags are ready before the first request.** Persisted flags for every account must be available synchronously after SDK initialization. A background or first-request lazy load would change wire behavior and is not acceptable.

2. **Clients remain bound to an exact persisted account.** Current-user changes must not retarget an existing `ClientManager`. Account name/type must continue to distinguish alternate-community records that share org ID and user ID. Removed or malformed accounts must fail closed.

3. **RTR refreshes always use live persisted credentials.** `getNewAuthToken()` must re-read the bound account after entering refresh coordination and immediately before sending the token request. A provider's construction-time refresh token must never be trusted for the network POST.

4. **Concurrent refreshes remain serialized per Salesforce identity.** Keep the shared `RefreshState`, lifecycle leases, publish generation, bounded loser wait, recent-result adoption, and winner/loser behavior. These prevent a stale rotated refresh token from producing `invalid_grant` and an unexpected logout.

5. **Logout and refresh remain race-safe.** Preserve cleanup markers and the pre/post-response liveness checks that prevent an old session from publishing credentials, sending broadcasts, or logging out a newly recreated same-identity account.

6. **Per-user User-Agent attribution never falls back to whichever user is currently selected.** Regular API requests, refresh requests, JSON credentials, and background work must retain their explicit client/request identity.

7. **A feature-only update cannot rewrite credentials.** Access token, refresh token, DPoP metadata, token type, and UI SID must be changed only by their authoritative auth paths, not incidentally from a stale `UserAccount` snapshot.

In practical terms:

| Safe patch optimization | Unsafe shortcut |
|---|---|
| Read the five-field feature projection synchronously for every valid authenticated account | Defer feature hydration until after the first request or hydrate only the current user |
| Pass an exact `(Account, UserAccount)` snapshot through construction | Re-resolve an existing client through mutable current-user state |
| Seed provider construction from a snapshot that was just read and validated | Use the construction-time refresh token for a later network refresh |
| Keep all live credential reads and shared refresh coordination inside refresh execution | Remove liveness checks, refresh-state leases, loser adoption, or logout/relogin guards |
| Write only the encrypted feature-flags key | Call whole-account `updateAccount()` from a feature-only path |
| Bind one User-Agent interceptor to the client/request user | Resolve `currentUser` during an authenticated request |

## Recommended 14.0.1 changes

### P0: high-confidence deduplication with unchanged auth semantics

1. **Keep immediate all-account feature hydration, but read a five-field projection.** Add an internal `readPersistedFeatureState(Account)` that decrypts org ID, user ID, and `KEY_FEATURE_FLAGS`, plus auth token and instance URL solely to retain the current malformed-account filter. Populate `perUserFeatures` synchronously before `AppCreateComplete`. This preserves first-request, non-current-user, and fail-closed behavior while reducing startup work from `1 + 44N` to `1 + 5N` operations.

2. **Persist only `KEY_FEATURE_FLAGS`.** Resolve the exact account by account name/type plus org/user IDs, then call `setUserData` for the encrypted flag string (or null when empty). Do not call `updateAccount()` for a feature-only change. Serialize writes per Salesforce identity and write a snapshot of the canonical in-memory set so concurrent registration/removal cannot publish an older value after a newer one. This is both faster and safer for RTR because a stale feature caller cannot roll credentials back. Add a restart test proving removal of the last flag remains removed.

3. **Carry the exact `(Account, UserAccount)` pair through internal client construction.** `getRestClient()` already obtains an exact `Account` and builds a user from that account. Add an SDK-internal `ClientManager` construction path that accepts the pair rather than calling `buildAccount(user)` again. Keep the public `ClientManager(Context, UserAccount)` constructor and its fail-closed exact-account resolution for compatibility.

4. **Seed the internal token provider from the just-validated snapshot.** Add an SDK-internal `AccMgrAuthTokenProvider(ClientManager, UserAccount)` path used only by `createRestClient()`. The public constructor can retain live hydration. Do not remove or cache any `getValidatedUser()` call inside `getNewAuthToken()`, `refreshWithState()`, winner-result adoption, post-response persistence, or terminal-error handling.

5. **Pass the same validated user into internal `RestClient` construction.** Do not call `getUserFromOrgAndUserId()` during builder creation when the exact user is already available. Keep fallback resolution for public constructors that do not receive a user snapshot.

6. **Install one effective User-Agent interceptor for authenticated clients.** It must use the bound/request user and the already hydrated `perUserFeatures` map. Preserve request-scoped `UserAccount` tags for token refresh. Eliminate the generic current-user lookup and duplicate header computation from regular authenticated requests.

These changes retain one live full-user hydration when creating a client while removing all duplicate full hydrations. For one account, the static target is approximately 54 account operations for cold SDK init plus first client construction (`6` during immediate feature hydration and `48` during client creation), about 48 for a later resume, and zero feature-related account reads on an ordinary API request. That is close to the v13.2.1 resume cost without restoring v13's unsafe current-user coupling.

### P1: follow-up runtime isolation

7. After the P0 changes, benchmark controlled dependency bundles independently: OkHttp 5; Compose/Kotlin/AndroidX; WorkManager/Firebase; Play Integrity; and SQLCipher/SQLite.

8. Benchmark feature configurations independently: one versus many users, DPoP on/off, app attestation on/off, foreground push registration on/off, SmartStore unopened/opened, and extra OAuth fields present/absent.

Binary-footprint changes and artifact-size gates remain in the separate footprint document.

## Validation plan and release gates

### Instrumentation

- Add debug/test counters and trace sections around `getAccountsByType`, `getUserData`, `getPassword`, `buildUserAccount`, `buildAccount`, `getRestClient`, provider construction, and token refresh.
- Capture Perfetto main-thread slices and Binder activity from process start through `AppCreateComplete` and `RenditionComplete`.
- Record account count and enabled feature configuration with every benchmark result.

### Benchmark matrix

- Baseline: v13.2.1.
- Candidates: RC3, each correctness-preserving P0 change independently, the combined P0 patch, then each runtime dependency bundle.
- States: guest; authenticated with 1, 5, and 20 accounts; cold process start; warm process start; repeated same-activity resumes; activity switch; user switch.
- Builds: release consumers matching affected applications. Minified/unminified artifact comparisons are covered separately.
- Devices: Galaxy S25 Ultra plus at least one lower-performance supported Android 12/13 device. If the original automation device is reused, reconcile the report's `SM-S947B` label with Jenkins' `SM-S947U` / `Galaxy S26+` metadata first.
- Samples: at least 50 measured iterations after warm-up per cell; publish P50, P75, P95, confidence intervals, raw samples, and IQR-filtered values.

### Correctness regression suite

- After process restart, persisted feature flags for the current user and a non-current user must appear on their respective first API requests.
- An account missing auth token, instance URL, org ID, or user ID must remain excluded from startup feature hydration.
- Removing the last feature flag must clear persistence and remain cleared after restart.
- Concurrent feature registration/removal for one identity must persist the newest complete set, and must not change access token, refresh token, token type, DPoP metadata, UI SID, or additional OAuth values.
- Constructing a `ClientManager`, token provider, and `RestClient` from an exact snapshot must not perform another full-account or all-account hydration.
- A regular request must use its bound user's feature flags after the selected current user changes, with zero `AccountManager` calls during User-Agent generation.
- Sequential and simultaneous RTR rotations must retain current winner/loser adoption behavior and must not cause an unexpected logout or reuse a rotated refresh token.
- Logout during refresh, removal/re-addition of the same identity, malformed account data, and alternate-community accounts sharing org/user IDs must retain the existing fail-closed and exact-account behavior.

### Proposed gates

- No P50 cold or warm regression over 10% against v13.2.1; retain the existing 100 ms absolute guard as a second signal, not as permission for large percentage regressions in small phases.
- No P95 regression over 10% for Native Load or authenticated cold start.
- Immediate feature hydration before `AppCreateComplete` must remain, with exactly the five projected reads per account rather than a complete-user hydration.
- At most one complete-user hydration per client construction/resume; zero duplicate hydrations in manager, provider, or `RestClient` construction.
- Zero `AccountManager` reads from User-Agent generation on an ordinary authenticated API request.
- Feature registration/removal must write only `KEY_FEATURE_FLAGS` and must never rewrite credential fields.
- All existing multi-user, RTR concurrency, logout/relogin, malformed-account, DPoP, and exact-account tests must continue to pass.

## Confidence and limitations

**High confidence:** repeated synchronous account hydration is a material authenticated-path regression; immediate feature availability and RTR/live-account safeguards can be preserved while removing duplicate hydration; RC2 is incomplete. It can dominate for many stored accounts, fresh client construction, or many requests.

**High confidence:** the Publisher product-level Native Load and cold-start regression is reproducible on the available Galaxy S25 Ultra. Its five-sample P50 shifts closely match the independent report, and the Native Load ranges do not overlap.

**Medium confidence:** the exact share attributable to each SDK or Publisher path. Both devices confirm the account paths, but their absolute component deltas differ by several multiples. The authenticated Publisher reproduction shows that the known account/User-Agent work explains only about 5.9% of its local Native Load shift. No Perfetto trace or instrumented call counter was captured from the Publisher APK run.

**Not yet established:** which part of the remaining common guest delta comes from OkHttp, Compose/Kotlin/AndroidX, WorkManager/Firebase, runtime/toolchain changes, or application changes between the two Publisher builds. The report compares complete app builds, so app-side changes are a possible confound. Controlled SDK-only runtime benchmarks and the proposed commit/dependency A/B matrix are required before attributing that residual.

## Reproduction notes

Both endpoints successfully built `SalesforceSDK` and `SmartStore` with JDK 17. The v13.2.1 source was exported with its tagged `external/shared` revision. Production source was not changed for measurement; the Android test manifest, test activity, module test-build coverage setting, and new benchmark class were modified in both local trees. The pre-existing dirty `external/shared` working-tree state was preserved. RestExplorer artifact measurements and their reproduction notes are in the separate binary-footprint analysis.
