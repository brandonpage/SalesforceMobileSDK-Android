# SDK-only Publisher-shaped startup A/B on Galaxy S25 Ultra

**Date:** 2026-09-30

**Device:** Samsung Galaxy S25 Ultra (`SM-S938U1`), Android 16

**Comparison:** Mobile SDK `13.2.1` versus `14.0.0`, using one identical authenticated sample application

## Result

Changing only the Mobile SDK dependency produced a small but repeatable regression on this device.

| Measurement | 13.2.1 P50 | 14.0.0 P50 | Change | 13.2.1 range | 14.0.0 range |
|---|---:|---:|---:|---:|---:|
| Android `am start -W` `TotalTime` | 100 ms | 106 ms | **+6 ms / +6.0%** | 98-103 ms | 100-114 ms |
| Publisher-shaped authenticated session bootstrap | 18 ms | 23 ms | **+5 ms / +27.8%** | 17-19 ms | 19-24 ms |
| Authenticated client ready from process start | 43 ms | 48 ms | **+5 ms / +11.6%** | 41-44 ms | 45-51 ms |
| SDK initialization | 3 ms | 3 ms | 0 ms | 3-4 ms | 3-4 ms |
| SDK-owned activity resume section | 4 ms | 4 ms | 0 ms | 2-4 ms | 4-5 ms |

This establishes an SDK-only startup effect under Publisher-shaped account initialization, but not one remotely close to the Publisher product delta reproduced on the same S25 Ultra. Publisher's `NativeLoad` moved by +191 ms and `AppColdStart` by +468 ms. The SDK-only app moved by 5-6 ms at its nearest available boundaries.

The boundaries are not identical, so 5-6 ms must not be literally subtracted from the Publisher telemetry marker. As a magnitude check, however, it is only approximately 3% of the 191 ms `NativeLoad` shift. This strengthens the conclusion that most of the Publisher regression comes from application/dependency integration outside the SDK account work isolated so far.

## Distribution summary

Seven measured force-stop launches followed one unmeasured post-install launch for each endpoint.

| Measurement | Version | Mean | P50 | P75 | Minimum | Maximum |
|---|---|---:|---:|---:|---:|---:|
| `TotalTime` | 13.2.1 | 99.7 ms | 100 ms | 100 ms | 98 ms | 103 ms |
| `TotalTime` | 14.0.0 | 105.9 ms | 106 ms | 107 ms | 100 ms | 114 ms |
| Session bootstrap | 13.2.1 | 17.7 ms | 18 ms | 18 ms | 17 ms | 19 ms |
| Session bootstrap | 14.0.0 | 22.3 ms | 23 ms | 24 ms | 19 ms | 24 ms |
| Client ready | 13.2.1 | 42.7 ms | 43 ms | 43 ms | 41 ms | 44 ms |
| Client ready | 14.0.0 | 48.0 ms | 48 ms | 50 ms | 45 ms | 51 ms |

All measured samples ended at Android thermal status 0. Battery temperature stayed between 29.4 C and 29.7 C. The final series ran 14.0.0 first and 13.2.1 second, reversing the order used during preliminary validation.

Sanitized samples are in [`results/sdk-sample-13.2.1.csv`](results/sdk-sample-13.2.1.csv) and [`results/sdk-sample-14.0.0.csv`](results/sdk-sample-14.0.0.csv). The exact force-stop harness is [`run_sdk_sample_cold_start_benchmark.sh`](run_sdk_sample_cold_start_benchmark.sh).

## Controlled design

The experiment used one source tree, package name, signing identity, authenticated account, build type, Android Gradle Plugin, Kotlin version, application-owned direct dependencies, compile/target SDK, and device. Only the locally published Mobile SDK dependency changed; its transitive dependency graph changed with it and is part of the SDK-level effect being measured.

The sample intentionally reproduces the Publisher-relevant initialization topology:

- it installs a custom `SalesforceSDKManager` before calling `initNative()`, which bypasses RC3's base-manager all-user feature hydration just as Publisher currently does;
- it configures Publisher's eleven additional OAuth keys before reading the user;
- application startup resolves `currentUser` and creates the first authenticated client;
- the same persisted authenticated account is retained across both APKs; authentication is setup and is not measured; and
- each measured sample force-stops the package before launch, matching the process-cold, account-persisted state used by the Publisher automation.

Both APKs were unminified release-like builds signed with the same local debug key. One unmeasured force-stop launch followed each in-place APK installation so package-update and first-launch effects were not included in the measured series. Seven measured launches then ran with an eight-second cooldown.

The sample also independently reproduced the known unshrunk packaging increase:

| APK | Size |
|---|---:|
| SDK 13.2.1 | 34,361,566 bytes |
| SDK 14.0.0 | 60,704,192 bytes |
| Change | **+26,342,626 bytes / +76.7%** |

APK binaries and the private authentication configuration are intentionally excluded from the public branch.

## What this experiment does and does not isolate

The 18 -> 23 ms session-bootstrap result is the closest SDK-only measurement to Publisher's application-start user/client setup. It includes Publisher's extra account fields and the `ClientManager`/`RestClient` work that can run in its startup graph.

The sample activity extends `SalesforceActivity` so it can expose the SDK lifecycle callback and a deterministic client-ready marker. Publisher activities do not extend `SalesforceActivity`, so the activity-resume result and the 43 -> 48 ms process-to-client boundary are diagnostic sample measurements, not direct Publisher marker equivalents.

The sample does not issue Publisher's gating auth-configuration request through `HttpAccess.DEFAULT`, and therefore does not include the generic User-Agent account lookup previously measured at approximately +3.62 ms on this S25 Ultra. Adding that cumulative cost to the +5 ms bootstrap remains far below the +191 ms Publisher `NativeLoad` delta, although concurrency means the two costs cannot be added as guaranteed wall time.

This is a seven-sample triage series, not a release gate. It does not provide a stable P95, independent install cohorts, or counterbalanced AB/BA evidence. It also compares public 13.2.1 with 14.0.0, whereas the historical Publisher baseline uses `13.0.2.10-publisher-internal`.

## Next attribution cells

1. Add `13.0.2.10-publisher-internal` to this identical sample to bridge Publisher's actual historical SDK baseline to public 13.2.1.
2. Run the same current Publisher source and build graph twice while changing only the SDK dependency. This is now the highest-value test because the controlled sample leaves nearly all of the product delta unexplained.
3. Add exact synchronous trace sections around Publisher's `ProcessCreated` to `IsCommunitySelected` interval, especially dependency injection, coroutine scheduling, event storage, and the gating auth-config request.
4. Add the proposed 14.0.1 correctness-preserving account patch as a fourth sample cell to measure its attainable improvement rather than inferring it from operation counts.
5. Before a release decision, counterbalance endpoint order across independent install cohorts and collect enough samples for confidence intervals and P95.
