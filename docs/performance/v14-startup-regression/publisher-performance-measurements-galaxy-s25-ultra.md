# Publisher cold-start reproduction on Galaxy S25 Ultra

**Date:** 2026-09-30

**Device:** Samsung Galaxy S25 Ultra (`SM-S938U1`), Android 16

**Workload:** Publisher Playground, authenticated Aura user, force-stop cold launch

## Result

The local S25 Ultra run reproduced both regressions from the supplied Publisher report.

| Publisher marker | 262.010 local P50 | 266.000 local P50 | Local change | Original report change |
|---|---:|---:|---:|---:|
| `NativeLoad` | 329 ms | 520 ms | **+191 ms / +58.1%** | +202 ms / +60.5% |
| `AppColdStart` | 3,598 ms | 4,066 ms | **+468 ms / +13.0%** | +390 ms / +11.2% |
| `CommunityLoad` | 3,260 ms | 3,535 ms | +275 ms / +8.4% | +101 ms / +3.2% |
| `SplashLoad` | 3,244 ms | 3,516 ms | +272 ms / +8.4% | +55 ms / +1.8% |
| Android `am start -W` `TotalTime` | 535 ms | 579 ms | +44 ms / +8.2% | Not reported |

This is more than directional agreement. The local `NativeLoad` regression is within 11 ms and 2.4 percentage points of the report, while the local cold-start regression is within 78 ms and 1.8 percentage points. The regression is therefore reproducible on the available S25 Ultra.

## Raw measurements

One uncounted authenticated force-stop launch was used to validate marker capture and warm install/code state. Five measured launches followed for each APK, with a 20-second cooldown after every sample.

| Version | Sample | `NativeLoad` | `CommunityLoad` | `AppColdStart` | `am TotalTime` | Battery temperature |
|---|---:|---:|---:|---:|---:|---:|
| 262.010 | 1 | 331 ms | 3,451 ms | 3,787 ms | 533 ms | 26.2 C |
| 262.010 | 2 | 327 ms | 2,986 ms | 3,319 ms | 535 ms | 26.3 C |
| 262.010 | 3 | 329 ms | 3,531 ms | 3,863 ms | 538 ms | 26.4 C |
| 262.010 | 4 | 334 ms | 3,260 ms | 3,598 ms | 541 ms | 26.6 C |
| 262.010 | 5 | 329 ms | 2,811 ms | 3,146 ms | 534 ms | 26.7 C |
| 266.000 | 1 | 520 ms | 3,703 ms | 4,230 ms | 576 ms | 27.5 C |
| 266.000 | 2 | 516 ms | 3,896 ms | 4,420 ms | 579 ms | 27.7 C |
| 266.000 | 3 | 523 ms | 3,295 ms | 3,825 ms | 580 ms | 27.8 C |
| 266.000 | 4 | 514 ms | 3,334 ms | 3,857 ms | 568 ms | 27.9 C |
| 266.000 | 5 | 523 ms | 3,535 ms | 4,066 ms | 585 ms | 27.9 C |

The `NativeLoad` ranges do not overlap: 327-334 ms for 262.010 and 514-523 ms for 266.000. That phase has very little run-to-run noise on this device. `AppColdStart` includes the Aura page/network render and is correspondingly noisier, but all five candidate samples exceed all but one baseline sample.

Sanitized raw CSVs are in [`results`](results), and the reusable harness is [`run_publisher_cold_start_benchmark.sh`](run_publisher_cold_start_benchmark.sh). The per-sample marker logs are intentionally excluded from the public fork because they contain a test org identifier and site URL; the tables below retain the derived marker intervals.

## Test setup

- Used the same Capricorn Coffee Aura site and designated test account recorded in Jenkins run 23.
- Used the authenticated state established before measurement; authentication itself was not timed.
- Matched the original notification, foreground-location, and background-location permission onboarding.
- Each sample cleared logcat, force-stopped the application, verified that its process was gone, launched `CommunitiesWebviewActivity`, and waited for authenticated `NativeLoad` and `AppColdStart` events.
- The screen remained on and the device remained on Wi-Fi.
- Publisher's event durations are the primary measurements. `am start -W` is included only as a secondary Android activity-launch measurement.

## APK provenance and limitations

- The baseline is a local build of `release-262.010` at `34e9047e`, with `BUILD_NUMBER=7`. Its package, version name, and version code exactly match the report: `262.010 (1326201007)`. The S3Playground build type inherits release configuration. The local self-build flag only enables the CI-only build type and replaces the unavailable CI signing key with the standard debug signing key.
- The candidate is the supplied Jenkins `master/lastSuccessfulBuild` artifact, build 1326: `266.000 (1326601326)`. It is a current RC3-era build, not the expired report artifact `266.000 (1326600504)`, which was build 504 and used RC2.
- The five-sample series is intentionally a triage reproduction, not a release-quality statistical study. It ran baseline then candidate rather than AB/BA, and device temperature rose from 26.2-26.7 C to 27.5-27.9 C. The extremely narrow, non-overlapping `NativeLoad` ranges and agreement with the independent report make ordinary noise or mild warming an implausible explanation for the 191 ms delta.
- The report page identifies the device as `SM-S947B`. Its Jenkins source-run configuration instead records device ID `R5GL15EQB2Z`, display name `Galaxy S26+`, and Splunk model `SM-S947U`. That provenance inconsistency should be corrected, but it no longer blocks reproduction because the S25 Ultra yields essentially the same regression.

## Where the reproduced NativeLoad time appears

Publisher marker timestamps narrow the deterministic shift to the early authenticated activity path.

| Interval derived from marker log timestamps | 262.010 P50 | 266.000 P50 | Change |
|---|---:|---:|---:|
| `ProcessCreated` to first `UserAndClientRefresh` completion | 8 ms | 14 ms | +6 ms |
| First to second `UserAndClientRefresh` completion | 104 ms | 70 ms | -34 ms |
| Second `UserAndClientRefresh` completion to logged `IsCommunitySelected` | 84 ms | 336 ms | **+252 ms** |
| Logged `IsCommunitySelected` to `MainCreated` | 2 ms | 5 ms | +3 ms |
| `MainCreated` to `NativeLoad` completion | 10 ms | 11 ms | +1 ms |

The `IsCommunitySelected` event is stored and logged asynchronously, so the 252 ms interval is a localization clue rather than proof that the event call itself consumes that time. A Perfetto trace or synchronous trace sections are required to divide this interval among SDK initialization, activity construction/injection, coroutine scheduling, event storage, and Publisher work.

## Mobile SDK sample-app A/B result

The first controlled Mobile SDK sample-app experiment is complete. The Publisher reproduction proves that the test apparatus and device can see the production-sized regression; the identical-app SDK A/B then measured only a small fraction of it.

| Identical sample-app measurement | SDK 13.2.1 P50 | SDK 14.0.0 P50 | Change |
|---|---:|---:|---:|
| Android `am start -W` `TotalTime` | 100 ms | 106 ms | **+6 ms / +6.0%** |
| Publisher-shaped authenticated session bootstrap | 18 ms | 23 ms | **+5 ms / +27.8%** |
| Authenticated client ready from process start | 43 ms | 48 ms | **+5 ms / +11.6%** |

The sample held source, package, signing identity, authenticated account, toolchain, and application-owned direct dependencies constant. It installed a custom manager before `initNative()`, configured Publisher's eleven additional OAuth keys, and retained the same account across both builds. The SDK's transitive dependency graph was allowed to change because it is part of the SDK-level effect under test. Seven measured force-stop launches followed one unmeasured post-install launch per endpoint; every measured sample was at thermal status 0.

This establishes a 5-6 ms SDK-only effect, not a roughly 190 ms one. The sample omits Publisher's gating auth-config request, whose generic User-Agent cost was independently measured at about +3.62 ms on this device, but even the broader account/User-Agent estimate remains far below the product delta. Full samples and limitations are in [`sdk-only-sample-performance-measurements-galaxy-s25-ultra.md`](sdk-only-sample-performance-measurements-galaxy-s25-ultra.md).

Remaining high-value cells:

1. Publisher's actual baseline SDK, `13.0.2.10-publisher-internal`.
2. The same current Publisher source built against public `13.2.1` and final `14.0.0`, changing only the SDK dependency.
3. The proposed 14.0.1 account-path patch in the identical sample and, if compatible, Publisher.

Use one authenticated account and the same force-stop loop. Add trace sections or explicit timestamps for application initialization, current-user resolution, the first client, Publisher's auth-config request, and exact Publisher marker boundaries. A short run is enough for triage if the phase remains stable; use a larger counterbalanced series for a release decision.

The first result selects the second interpretation from the original plan: most of the Publisher regression comes from application/dependency integration outside the already benchmarked account calls. The `13.0.2.10-publisher-internal` bridge is still required to determine whether some of the historical product shift predates public 13.2.1.
