# Publisher same-source SDK-only A/B on Galaxy S25 Ultra

**Date:** 2026-09-30

**Device:** Samsung Galaxy S25 Ultra (`SM-S938U1`), Android 16

**Publisher source:** `fde623b358c371773de39d7d07915c9772a1e104`, dev facade

**Status:** guest validation complete; authenticated measurement pending a Publisher-authorized test account

## Result

The current Publisher source and build graph were built twice, changing the locked Mobile SDK graph from 13.2.1 to `14.0.0-rc.3`. In guest launches, RC3 added only 9-15 ms at Publisher's stable `NativeLoad` boundary. This validates the direct A/B build and measurement path, but it does not exercise the authenticated account work at the center of the investigation.

| Measurement | 13.2.1 A1 P50 (range) | RC3 P50 (range) | 13.2.1 A2 reverse P50 (range) |
|---|---:|---:|---:|
| `NativeLoad` | 329 ms (324-334) | 338 ms (331-341) | 323 ms (320-323) |
| Android `am start -W` `TotalTime` | 827 ms (820-832) | 843 ms (832-854) | 814 ms (812-821) |
| `AppColdStart` | 2,139 ms (2,083-2,479) | 2,474 ms (2,064-2,644) | 2,342 ms (1,937-2,541) |
| `CommunityLoad` | 1,814 ms (1,757-2,148) | 2,141 ms (1,731-2,303) | 2,019 ms (1,614-2,221) |
| `SplashLoad` | 1,726 ms (1,673-2,059) | 2,023 ms (1,623-2,196) | 1,937 ms (1,525-2,133) |

The first baseline-to-RC3 comparison is +9 ms / +2.7% for `NativeLoad`; the reverse baseline makes the RC3 separation 15 ms / +4.6%. The two baseline series bracketed RC3 and the reverse baseline ran at a higher battery temperature, yet remained faster at `NativeLoad`. This is consistent with a small common SDK/dependency cost, not the +191 ms authenticated product shift reproduced from the historical-to-current Publisher APKs.

The page-dependent markers are much noisier. `AppColdStart` P50 rose by 335 ms against the first baseline, but its ranges overlap and the reverse baseline itself moved 203 ms from the first baseline. Five, five, and three samples are insufficient to separate SDK effects from page/network variation at those boundaries. They should not be presented as a stable SDK regression.

All samples ended at Android thermal status 0. Battery temperature was 29.7-30.0 C for baseline A1, 30.6-31.2 C for RC3, and 31.4 C for reverse baseline A2.

## Controlled build design

- Both APKs use the same Publisher commit, dev facade, package, application source, build toolchain, and signing setup.
- Only the resolved Mobile SDK dependency graph and its lock state change between endpoints.
- Current Publisher source references APIs introduced after 13.2.1. The same temporary compatibility layer was used in both builds: it invokes the newer APIs reflectively when available and retains the pre-v14 fallback on 13.2.1. It preserves the v14 DPoP/RTR behavior rather than deleting hardening from the RC3 endpoint.
- The compatibility changes and dependency locks were restored after building. The internal Publisher checkout is clean.
- Each sample force-stopped the process before launch and used Publisher's own telemetry markers. The sequence was five baseline launches, five RC3 launches, then three reverse-order baseline launches.

The APKs and raw marker logs remain local. They are not included in the public fork. Only numeric, sanitized samples are published:

- [`results/publisher-sdk-only-13.2.1-guest.csv`](results/publisher-sdk-only-13.2.1-guest.csv)
- [`results/publisher-sdk-only-14.0.0-rc.3-guest.csv`](results/publisher-sdk-only-14.0.0-rc.3-guest.csv)
- [`results/publisher-sdk-only-13.2.1-guest-reverse.csv`](results/publisher-sdk-only-13.2.1-guest-reverse.csv)

## Authentication blocker and next step

The authenticated run requires an account authorized for Publisher's dev community. The account used by the generic SDK UI tests was attempted only through its approved local configuration, but Publisher did not establish an authenticated session. No credential values or private configuration were copied into the experiment or public branch.

Once an authorized Publisher account is established outside the timed loop, rerun the same force-stop sequence and require Publisher's `isUserLoggedIn` marker to be true for every retained sample. Five launches per endpoint are enough to determine whether the direct SDK-only `NativeLoad` shift is remotely close to 190 ms; a larger counterbalanced series is required for a release-quality estimate.

Until that authenticated cell is complete, this experiment supports only two claims:

1. The same-source Publisher SDK-only build pair is viable.
2. The common guest-path RC3 cost is small at the stable `NativeLoad` boundary.

It does not yet measure Publisher's full current-user, first-client, RTR, feature, or authenticated request path.
