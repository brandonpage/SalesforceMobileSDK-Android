# Publisher same-source SDK-only A/B on Galaxy S25 Ultra

**Date:** 2026-09-30

**Device:** Samsung Galaxy S25 Ultra (`SM-S938U1`), Android 16

**Publisher source:** `fde623b358c371773de39d7d07915c9772a1e104`, dev facade

**Status:** authenticated and guest measurements complete

## Authenticated result

The current Publisher source and build graph were built twice, changing the locked Mobile SDK graph from 13.2.1 to `14.0.0-rc.3`. The authenticated A-B-A-B series does not reproduce the reported `NativeLoad` regression, but it does reproduce a similarly sized overall cold-start regression after the native boundary.

| Measurement | 13.2.1 combined P50 | RC3 combined P50 | Change | 13.2.1 cohort P50s | RC3 cohort P50s |
|---|---:|---:|---:|---:|---:|
| `NativeLoad` | 465 ms | 471.5 ms | **+6.5 ms / +1.4%** | 466 / 464 ms | 461 / 475 ms |
| Android `am start -W` `TotalTime` | 872.5 ms | 882.5 ms | **+10 ms / +1.1%** | 870 / 875 ms | 880 / 902 ms |
| `AppColdStart` | 2,954 ms | 3,374 ms | **+420 ms / +14.2%** | 3,063 / 2,864 ms | 3,316 / 3,432 ms |
| Derived post-native interval (`AppColdStart - NativeLoad`) | 2,499.5 ms | 2,906 ms | **+406.5 ms / +16.3%** | 2,593 / 2,370 ms | 2,855 / 2,957 ms |

Each endpoint has eight measured launches: five in the first cohort and three after reversing back to that endpoint. All samples explicitly reported `isUserLoggedIn=true`.

The stable `NativeLoad` result is unambiguous: the full SDK graph change adds only 6.5 ms in the combined series, and its per-cohort shift ranges from -5 ms to +11 ms. This is nowhere near the +191 ms authenticated `NativeLoad` regression in the historical-to-current Publisher product A/B.

The later page-dependent interval tells a different story. RC3's combined `AppColdStart` is 420 ms slower, strikingly close to the +390 ms supplied report and +468 ms local historical-product result. Calculating `AppColdStart - NativeLoad` for every launch before taking the median places 406.5 ms of the shift after the native boundary.

`CommunityLoad` and `SplashLoad` must not be added to one another or directly reconciled by subtracting their independently calculated P50s. `CommunityLoad` starts when `NativeLoad` ends and runs to the visually-ready JavaScript callback. `SplashLoad` starts later and ends at that same callback, so it is an overlapping subset of `CommunityLoad`. Also, the initial harness stopped capturing as soon as the required cold/native lines appeared; one 13.2.1 sample therefore missed the immediately following community/splash log lines. The raw values remain published, but their standalone P50 deltas are not used for attribution. The harness now waits for all four marker lines.

This is evidence of a candidate-associated post-native delay in the short current-source A/B. It is not yet proof that the SDK graph caused the delay or the historical product regression: the phase attribution does not match, the series is small, and the post-native interval includes WebView, server, and network variability.

All samples ended at Android thermal status 0. Battery temperature rose gradually from 29.0-29.2 C in 13.2.1 A1 to 29.9-30.1 C in the final RC3 cohort. The alternating endpoint result and absence of thermal throttling make temperature an implausible explanation for the post-native direction.

Sanitized authenticated samples are in:

- [`results/publisher-sdk-only-13.2.1-authenticated.csv`](results/publisher-sdk-only-13.2.1-authenticated.csv)
- [`results/publisher-sdk-only-14.0.0-rc.3-authenticated.csv`](results/publisher-sdk-only-14.0.0-rc.3-authenticated.csv)
- [`results/publisher-sdk-only-13.2.1-authenticated-reverse.csv`](results/publisher-sdk-only-13.2.1-authenticated-reverse.csv)
- [`results/publisher-sdk-only-14.0.0-rc.3-authenticated-reverse.csv`](results/publisher-sdk-only-14.0.0-rc.3-authenticated-reverse.csv)

## Marker equivalence with the official benchmark

The local harness does not redefine `NativeLoad`:

- `CommunitiesAppPresenter.trackPerformanceMetrics()` starts `AppColdStart` and `NativeLoad` together with the same pre-initialization offset.
- `CommunityWebViewEngine.loadUrlInWebview()` ends `NativeLoad` immediately before starting `CommunityLoad` and loading the main WebView URL.
- `CommunitiesMarkerScope` builds one completed `InstrumentationEvent`, stores it for telemetry, and then logs that same event and duration as `OKAY NativeLoad ... +Nms`.
- The official report selects the stored Publisher telemetry event whose source is `NativeLoad`; the local harness parses the logged duration from the same event object.

Every retained local launch emitted exactly one `NativeLoad` and one `AppColdStart`. The historical-product local A/B used this same extraction and reproduced the official report's approximately 190 ms native shift. The flat same-source result is therefore an experimental-scope difference, not a different `NativeLoad` definition.

## Guest validation

The earlier guest A-B-A validation showed the same small native effect but much noisier page markers.

| Measurement | 13.2.1 A1 P50 (range) | RC3 P50 (range) | 13.2.1 A2 reverse P50 (range) |
|---|---:|---:|---:|
| `NativeLoad` | 329 ms (324-334) | 338 ms (331-341) | 323 ms (320-323) |
| Android `am start -W` `TotalTime` | 827 ms (820-832) | 843 ms (832-854) | 814 ms (812-821) |
| `AppColdStart` | 2,139 ms (2,083-2,479) | 2,474 ms (2,064-2,644) | 2,342 ms (1,937-2,541) |
| `CommunityLoad` | 1,814 ms (1,757-2,148) | 2,141 ms (1,731-2,303) | 2,019 ms (1,614-2,221) |
| `SplashLoad` | 1,726 ms (1,673-2,059) | 2,023 ms (1,623-2,196) | 1,937 ms (1,525-2,133) |

Guest `NativeLoad` places the RC3 penalty at 9-15 ms. Its page-marker ranges overlap substantially, so it does not independently establish the authenticated post-native shift.

## Controlled build design

- Both APKs use the same Publisher commit, dev facade, package, application source, build toolchain, and signing setup.
- Only the resolved Mobile SDK dependency graph and its lock state change between endpoints.
- Current Publisher source references APIs introduced after 13.2.1. The same temporary compatibility layer was used in both builds: it invokes the newer APIs reflectively when available and retains the pre-v14 fallback on 13.2.1. It preserves the v14 DPoP/RTR behavior rather than deleting hardening from the RC3 endpoint.
- The compatibility changes and dependency locks were restored after building. The internal Publisher checkout is clean.
- Each sample force-stopped the process before launch and used Publisher's own telemetry markers. An unmeasured validation launch followed each in-place APK installation. The authenticated sequence was five 13.2.1 launches, five RC3 launches, three 13.2.1 launches, then three RC3 launches.

The APKs and raw marker logs remain local. They are not included in the public fork. Only numeric, sanitized samples are published:

- [`results/publisher-sdk-only-13.2.1-guest.csv`](results/publisher-sdk-only-13.2.1-guest.csv)
- [`results/publisher-sdk-only-14.0.0-rc.3-guest.csv`](results/publisher-sdk-only-14.0.0-rc.3-guest.csv)
- [`results/publisher-sdk-only-13.2.1-guest-reverse.csv`](results/publisher-sdk-only-13.2.1-guest-reverse.csv)

## Authentication and next step

The Publisher-authorized account was read from a local file outside every repository and authenticated once before the measured force-stop loop. Authentication was not timed. Account state was preserved by installing each APK in place under the same package and signing identity. No credential values or private configuration were copied into the experiment or public branch.

Before constructing hybrid dependency builds, add request/response timing or a WebView network trace from `NativeLoad` through the visually-ready callback. If that proves the delay is local to a changed SDK-owned component, isolate it with a one-variable hybrid build—for example, hold RC3 SDK code constant while reverting only the relevant dependency family to its 13.2.1 versions. That is what “dependency-bundle A/B” means; it is not a new definition of `NativeLoad`.

This experiment supports three claims:

1. The same-source Publisher SDK-only build pair is viable.
2. The full SDK graph does not reproduce the approximately 190 ms native-phase regression in current Publisher source.
3. The RC3 endpoint has a candidate-associated authenticated post-native regression of approximately 406.5 ms in this short series.

It does not yet establish that an SDK runtime or transitive dependency causes the post-native shift, or prove that the same mechanism produced the historical report.
