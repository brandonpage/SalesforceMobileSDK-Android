# Android account-path performance measurements

**Comparison:** `v13.2.1` (`144e68fb2`) versus current `dev` (`65026220e`, representative of v14.0 RC3)

**Device:** Google Pixel 8 Pro (`husky`), Android 17 / API 37, arm64

**Measurement date:** 2026-09-30

**Related measurement:** The matched Galaxy S25 Ultra run is documented in [`performance-measurements-galaxy-s25-ultra.md`](performance-measurements-galaxy-s25-ultra.md). It confirms the same paths with substantially lower absolute account-system cost.

## What the operation counts mean in milliseconds

The static `AccountManager` operation counts are not a useful latency model by themselves. On this device, a cached `getUserData()` call is only about 0.001-0.002 ms, while reconstructing and decrypting a complete `UserAccount` is about 12-17 ms at P50. The material cost comes from repeatedly listing accounts, reading and decrypting many fields, allocating a complete object, validating it, and rebuilding client state—not from treating each counted call as if it cost the same amount.

The most useful one-account results are:

| Measured path | v13.2.1 P50 | RC3 P50 | Absolute change | Relative change | RC3 P95 |
|---|---:|---:|---:|---:|---:|
| One complete `buildUserAccount()` | 12.11 ms | 16.76 ms | +4.65 ms | +38.4% | 22.26 ms |
| Reverse lookup: `buildAccount(user)` | 0.67 ms | 12.67 ms | +12.01 ms | +1,803% | 19.79 ms |
| `ClientManager` construction | 0.002 ms | 15.80 ms | +15.80 ms | not meaningful from near zero | 24.00 ms |
| `AccMgrAuthTokenProvider` construction | 1.19 ms | 18.87 ms | +17.68 ms | +1,487% | 30.49 ms |
| Warmed construction of a fresh manager and `RestClient` | 23.11 ms | 68.09 ms | +44.98 ms | +194.6% | 95.28 ms |
| User-Agent computation used by the generic interceptor | 0.051 ms | 12.87 ms | +12.82 ms | about 255x | 20.42 ms |
| SDK work inside same-user activity `onResume()` | 17.22 ms | 21.89 ms | +4.67 ms | +27.1% | 45.31 ms |

These rows overlap and must not be added together. For example, fresh client construction includes manager/provider work, and activity resume benefits from RC2's cached `ClientManager`. The individual rows are diagnostic probes showing where time is available to recover.

The practical interpretation is:

- RC3 adds about **15 ms P50** to native startup for immediate feature hydration with one stored account. This grows to **76 ms with five accounts** and **505 ms with twenty accounts**.
- After RC2's manager cache is warm, the SDK-owned section of an ordinary authenticated activity resume is about **4.7 ms slower at P50** for one account and **9.2 ms slower** for twenty accounts on this device.
- Any subsystem that constructs a fresh manager/client rather than hitting the activity cache can pay about **45 ms more at P50** for one account in this harness.
- The redundant current-user User-Agent computation is about **12.8 ms P50 per request** before network I/O. An app making many API calls can therefore accumulate much more cost than startup metrics alone reveal.

### Publisher-shaped bootstrap

Publisher's integration caches its `RestClient`, registers eleven additional OAuth keys, does not extend `SalesforceActivity`, and currently skips the base SDK's all-user feature hydration. A dedicated probe therefore timed its actual first-session shape instead of applying the generic sample-app totals:

| Measured path | v13.2.1 P50 | RC3 P50 | Absolute change |
|---|---:|---:|---:|
| First authenticated user/client bootstrap, 1 account | 20.90 ms | 47.89-53.43 ms | +26.99 to +32.53 ms |
| Generic User-Agent evaluation, 1 account | 0.027 ms | 12.10-12.15 ms | +12.08 to +12.13 ms per request |
| First authenticated user/client bootstrap, 5 accounts | 23.51 ms | 62.59 ms | +39.08 ms |
| Generic User-Agent evaluation, 5 accounts | 0.062 ms | 11.79 ms | +11.72 ms per request |

The one-account RC3 bootstrap P95 was 90.85-97.40 ms versus 27.00 ms at v13.2.1. Device thermal status remained 0. The full source-to-marker mapping and attribution are in [`publisher-impact-analysis-v14-rc3.md`](publisher-impact-analysis-v14-rc3.md).

For a one-account app, the separate startup feature step (~15 ms) and the fresh manager/client regression (~45 ms) expose roughly 60 ms of local SDK opportunity before request fan-out. Ten ordinary requests would add about 128 ms of cumulative User-Agent preparation at the measured median. That cumulative figure is not automatically 128 ms of startup wall time—OkHttp dispatch and concurrent calls may overlap—but it explains why the number of early API calls is an essential benchmark dimension.

This component evidence is consistent with the supplied application benchmark, where the authenticated candidate regressed by 390 ms at cold-start P50 and 202 ms in Native Load, while guest cold start moved only 107 ms. The SDK probes explain meaningful pieces of that result; they do not claim that all 390 ms comes from one account operation.

## Scaling by stored account count

### Immediate feature hydration during SDK initialization

This is new synchronous work in RC3. v13.2.1 has no corresponding startup feature-hydration step.

| Stored accounts | RC3 P50 | RC3 P95 | Approximate P50 per account |
|---:|---:|---:|---:|
| 1 | 14.98 ms | 22.01 ms | 14.98 ms |
| 5 | 76.36 ms | 105.03 ms | 15.27 ms |
| 20 | 505.30 ms | 625.62 ms | 25.26 ms |

The one- and five-account results are close to linear at roughly 15 ms per account. The twenty-account cell is worse than linear and demonstrates why full-object hydration of every account is risky for tail latency. Immediate feature availability is still a correctness requirement; these measurements support replacing the full 44-field object build with the small validated feature-state projection described in the main analysis, not deferring the work.

### SDK work inside authenticated activity resume

The timer surrounds `SalesforceActivity.onResume()` on the main thread and therefore includes the synchronous SDK `getRestClient()` path, but excludes `ActivityScenario` transition overhead and excludes application rendering/network work. Each cell has ten warm-ups and 100 measured transitions.

| Stored accounts | v13.2.1 wall P50 / P95 | RC3 wall P50 / P95 | Wall P50 change | Thread CPU P50, v13.2.1 -> RC3 |
|---:|---:|---:|---:|---:|
| 1 | 17.22 / 26.61 ms | 21.89 / 45.31 ms | +4.67 ms / +27.1% | 6.55 -> 9.17 ms |
| 5 | 18.32 / 32.15 ms | 23.89 / 45.38 ms | +5.57 ms / +30.4% | 7.11 -> 9.96 ms |
| 20 | 28.59 / 38.40 ms | 37.75 / 57.90 ms | +9.16 ms / +32.0% | 14.31 -> 14.69 ms |

The earlier test-framework measurement of roughly 55 ms was rejected: it timed the entire `ActivityScenario.moveToState()` call and was dominated by framework coordination. The values above come from an in-activity main-thread timer around the SDK lifecycle call.

The twenty-account wall-time increase is much larger than its thread-CPU increase, consistent with more time waiting on account-system work rather than simply executing more Java/Kotlin instructions on the activity thread.

### Warmed fresh manager/client construction

This probe creates a new `ClientManager` from the current `UserAccount` and calls `peekRestClient()`. It represents a cache miss or another subsystem creating its own client, not the RC2 same-manager cache-hit activity path. Each cell has ten warm-ups and 100 measured constructions.

| Stored accounts | v13.2.1 P50 / P95 | RC3 P50 / P95 | P50 change |
|---:|---:|---:|---:|
| 1 | 23.11 / 27.56 ms | 68.09 / 95.28 ms | +44.98 ms / +194.6% |
| 5 | 10.92 / 17.08 ms | 51.29 / 74.95 ms | +40.37 ms / +369.8% |
| 20 | 27.96 / 38.33 ms | 89.14 / 124.79 ms | +61.18 ms / +218.8% |

The non-monotonic baseline medians show why the account-count rows should be treated as device observations rather than a mathematical cost model. The consistent finding is the large RC3 absolute gap and its heavier tail.

## Lower-level measurements

| Operation, one account | v13.2.1 P50 | RC3 P50 | Comment |
|---|---:|---:|---|
| `AccountManager.getAccountsByType()` | 0.285 ms | 0.267 ms | Essentially unchanged |
| `AccountManager.getUserData()` | 0.0018 ms | 0.0012 ms | Cached local read; not representative of a complete hydration |
| `AccountManager.getPassword()` | 0.264 ms | 0.221 ms | Essentially unchanged |
| `getAuthenticatedUsers()` | 14.70 ms | 13.95 ms | One complete account; RC3 startup calls this for all accounts |
| `getCurrentUser()` | 16.34 ms | 12.61 ms | Not itself slower in the isolated one-account probe |

These results narrow the fix target. Android's primitive account access did not regress. The regressions appear when RC3 turns identity lookups, provider setup, User-Agent generation, or feature loading into one or more complete decrypted-user reconstructions.

## Method

- Added the same Android instrumentation harness to both local source trees.
- Seeded deterministic local `UserAccount` records through the SDK's real `UserAccountManager`; no network request or login server was involved.
- Disabled Android-test coverage so Jacoco instrumentation did not distort the measured methods.
- Used `SystemClock.elapsedRealtimeNanos()` for wall time and `Debug.threadCpuTimeNanos()` for the executing thread.
- Used method-specific warm-ups, then 100-1,000 samples for primitive/component probes and 100 samples for lifecycle/client probes.
- Installed one endpoint at a time into the same package on the same device and requested package compilation before each series.
- Set Android animation scales to zero during collection; they do not affect the method-level timers.
- Avoided using credentials from `shared/test/ui_test_config.json`: the paths under test need persisted account state, not a live OAuth exchange, and synthetic records remove server/network variance without weakening the SDK code exercised.

The harness source is in each checkout at `libs/test/SalesforceSDKTest/src/com/salesforce/androidsdk/performance/AccountPathBenchmarkTest.java`; the activity timer is in `libs/test/SalesforceSDKTest/src/com/salesforce/androidsdk/MainActivity.java`.

## Limitations and rejected data

- These are debug Android-test builds, not the affected Publisher production APK. Absolute method timings should be used as order-of-magnitude and comparative evidence; the supplied Publisher report remains the production-level cold-start measurement.
- The synthetic records do not exercise an actual token refresh, DPoP signing, app attestation, push registration, SmartStore open, application rendering, or network I/O.
- An attempted whole-process cold-launch series was rejected. During the first series Android thermal status rose from 0 to 1 and launch time drifted monotonically from 564 ms to 845 ms. A paced v13.2.1 retry stayed at thermal status 0, but the RC3 test package's in-package authenticator process remained alive after synthetic account seeding and Android classified the attempted launches as warm. Because the endpoint process states were asymmetric, neither series is used above. The supplied Publisher report remains the valid whole-app cold-start evidence.
- The package compiler on Android 17 reported the debug APK at verification level despite the requested `speed` mode. Warm-ups therefore matter, and a release/Macrobenchmark follow-up is still needed before setting a patch acceptance threshold from these absolute numbers.
- The first-client cold-in-process probe had only one sample per account count and is intentionally omitted. The table uses the 100-sample warmed construction path instead.
- Lifecycle cells were run sequentially, with RC3 before v13.2.1, rather than randomized or interleaved. Gradual heating would tend to make the later baseline slower and understate the observed RC3 gap, but a randomized release-build run is still required for a formal patch gate.

## Next measurement

The decisive follow-up is to apply the correctness-preserving 14.0.1 prototype and rerun this identical harness. The expected proof points are:

1. feature hydration remains synchronous but drops from complete-user reconstruction to the validated five-field projection;
2. exact account and user snapshots are passed through manager, provider, and `RestClient` construction with no duplicate hydration;
3. regular authenticated User-Agent generation performs zero `AccountManager` reads; and
4. the measured one-account activity-resume P50 returns to within 10% of v13.2.1 without changing RTR coordination, live refresh-token reads, or logout race checks.
