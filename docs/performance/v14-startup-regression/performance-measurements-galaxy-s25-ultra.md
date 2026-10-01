# Android account-path measurements on Galaxy S25 Ultra

**Comparison:** `v13.2.1` (`144e68fb2`) versus current `dev` (`65026220e`, representative of v14.0 RC3)

**Device:** Samsung Galaxy S25 Ultra (`SM-S938U1`, `pa3q`), Android 16 / API 36, arm64, Qualcomm `SM8750`

**Build:** `S938U1UESBCZF5`, fingerprint `samsung/pa3quew/pa3q:16/BP4A.251205.006/S938U1UESBCZF5_OYMBCZF5:user/release-keys`

**Measurement date:** 2026-09-30

## Conclusion

The Galaxy S25 Ultra confirms the same RC3 regression paths but executes the Android account work much faster than the Pixel 8 Pro used for the first component study. The result changes the absolute attribution, not the diagnosis:

- Publisher-shaped first authenticated user/client bootstrap regresses by **7.56 ms P50** on the S25 Ultra, versus **26.99-32.53 ms** on the Pixel 8 Pro.
- Publisher's generic User-Agent evaluation regresses by **3.62 ms P50 per request**, versus about **12.1 ms** on the Pixel.
- A fresh manager and `RestClient` regress by **6.69 ms P50**, versus **44.98 ms** on the Pixel.
- RC3 synchronous feature hydration costs **3.16 ms** for one account, **16.15 ms** for five, and **63.34 ms** for twenty, versus 14.98, 76.36, and 505.30 ms on the Pixel.
- The SDK-owned section of a same-user `SalesforceActivity` resume regresses by **2.72 ms P50**, versus 4.67 ms on the Pixel.

For Publisher's established-install startup shape, the measured bootstrap gap plus one gating auth-configuration User-Agent evaluation represent approximately **11.18 ms of cumulative eligible local work** on this device. A first-ever launch can add another approximately 3.62 ms for the publisher-configuration request. The bootstrap runs in a background scope, so these cumulative values do not necessarily extend the startup markers one-for-one.

This makes the isolated account paths an unlikely sole explanation for the supplied Publisher **+202 ms Native Load P50** on its different Samsung model (`SM-S947B`). The S25 total is about 5.5% of the full Native Load delta and 6.3% of the approximately +178 ms authenticated-specific delta after subtracting the guest result. The exact Publisher release still needs an on-device trace because its model, application build, scheduling, request overlap, and account state differ from this harness.

## One-account diagnostic results

Each component row has method-specific warm-ups followed by 100-1,000 measured samples, except the intentionally single-sample first-client probe. Rows overlap and must not be added together.

| Measured path | v13.2.1 P50 / P95 | RC3 P50 / P95 | P50 change |
|---|---:|---:|---:|
| One complete `buildUserAccount()` | 2.91 / 3.65 ms | 3.33 / 4.24 ms | +0.42 ms / +14.5% |
| Reverse lookup: `buildAccount(user)` | 0.095 / 0.109 ms | 3.17 / 4.25 ms | +3.08 ms |
| `ClientManager` construction | 0.0007 / 0.0007 ms | 3.20 / 4.32 ms | +3.20 ms |
| `AccMgrAuthTokenProvider` construction | 0.142 / 0.152 ms | 3.30 / 4.17 ms | +3.16 ms |
| Warmed construction of a fresh manager and `RestClient` | 3.11 / 4.05 ms | 9.80 / 11.57 ms | **+6.69 ms / +215.2%** |
| Generic User-Agent computation | 0.014 / 0.015 ms | 3.24 / 4.21 ms | **+3.23 ms** |
| SDK work inside same-user activity `onResume()` | 11.07 / 14.32 ms | 13.78 / 18.19 ms | **+2.72 ms / +24.6%** |

Primitive `AccountManager` operations themselves did not regress materially:

| Primitive operation | v13.2.1 P50 | RC3 P50 |
|---|---:|---:|
| `getAccountsByType()` | 0.046 ms | 0.044 ms |
| `getUserData()` | 0.0014 ms | 0.0008 ms |
| `getPassword()` | 0.053 ms | 0.054 ms |

As on the Pixel, the material cost appears when RC3 turns an identity lookup, provider setup, User-Agent evaluation, or feature load into a complete decrypted-user reconstruction. The lower absolute cost comes primarily from the S25 completing a full reconstruction and its account-system calls much faster.

## Publisher-shaped benchmark

This probe sets Publisher's eleven additional OAuth keys, resolves `currentUser`, constructs the first user-bound `ClientManager` and `RestClient`, and separately times the generic User-Agent path. It matches the SDK portion of Publisher's integration without network or application rendering.

| Stored accounts | Path | v13.2.1 P50 / P95 | RC3 P50 / P95 | P50 change |
|---:|---|---:|---:|---:|
| 1 | Current-user hydration | 3.37 / 4.33 ms | 3.69 / 4.72 ms | +0.32 ms |
| 1 | First user/client bootstrap | 6.64 / 8.41 ms | 14.20 / 16.23 ms | **+7.56 ms** |
| 1 | Generic User-Agent | 0.018 / 0.020 ms | 3.64 / 4.69 ms | **+3.62 ms/request** |
| 5 | Current-user hydration | 3.34 / 4.33 ms | 3.76 / 5.05 ms | +0.43 ms |
| 5 | First user/client bootstrap | 6.48 / 8.70 ms | 14.64 / 17.85 ms | **+8.16 ms** |
| 5 | Generic User-Agent | 0.018 / 0.020 ms | 3.72 / 5.02 ms | **+3.70 ms/request** |
| 20 | Current-user hydration | 3.47 / 4.52 ms | 3.64 / 4.87 ms | +0.17 ms |
| 20 | First user/client bootstrap | 6.83 / 8.99 ms | 14.93 / 18.06 ms | **+8.11 ms** |
| 20 | Generic User-Agent | 0.018 / 0.020 ms | 3.80 / 4.78 ms | **+3.78 ms/request** |

The Publisher-shaped bootstrap remains nearly flat across account counts because the current synthetic user is the first matching account and the expensive RC3 reverse lookups stop when it is found. This does not remove the risk when the current account appears later in a real account list or duplicate identities must be tested.

## Scaling by stored account count

### Immediate feature hydration

This is new synchronous RC3 work. v13.2.1 has no equivalent startup feature-hydration step.

| Stored accounts | RC3 P50 | RC3 P95 | Approximate P50 per account |
|---:|---:|---:|---:|
| 1 | 3.16 ms | 4.09 ms | 3.16 ms |
| 5 | 16.15 ms | 19.22 ms | 3.23 ms |
| 20 | 63.34 ms | 68.39 ms | 3.17 ms |

The S25 result is almost perfectly linear. Immediate feature availability remains required; the result still supports reading the validated five-field feature projection instead of hydrating every credential and metadata field.

### SDK work inside authenticated activity resume

The timer surrounds `SalesforceActivity.onResume()` on the main thread and excludes `ActivityScenario` coordination, application rendering, and network work.

| Stored accounts | v13.2.1 P50 / P95 | RC3 P50 / P95 | P50 change |
|---:|---:|---:|---:|
| 1 | 11.07 / 14.32 ms | 13.78 / 18.19 ms | +2.72 ms / +24.6% |
| 5 | 10.74 / 14.09 ms | 13.78 / 16.31 ms | +3.03 ms / +28.2% |
| 20 | 11.08 / 14.59 ms | 12.79 / 16.87 ms | +1.70 ms / +15.4% |

Publisher does not extend `SalesforceActivity`, so this lifecycle row is diagnostic for SDK consumers generally and is not a direct Publisher warm-start attribution.

### Warmed fresh manager/client construction

| Stored accounts | v13.2.1 P50 / P95 | RC3 P50 / P95 | P50 change |
|---:|---:|---:|---:|
| 1 | 3.11 / 4.05 ms | 9.80 / 11.57 ms | +6.69 ms |
| 5 | 3.08 / 4.05 ms | 9.70 / 12.09 ms | +6.62 ms |
| 20 | 4.49 / 5.12 ms | 10.13 / 12.81 ms | +5.64 ms |

## Cross-device comparison

| RC3 regression at P50 | Pixel 8 Pro | Galaxy S25 Ultra |
|---|---:|---:|
| One-account feature hydration, absolute RC3 cost | 14.98 ms | 3.16 ms |
| Publisher bootstrap, 1 account | +26.99 to +32.53 ms | +7.56 ms |
| Publisher generic User-Agent, 1 account | +12.08 to +12.13 ms/request | +3.62 ms/request |
| Fresh manager/client, 1 account | +44.98 ms | +6.69 ms |
| SDK activity-resume section, 1 account | +4.67 ms | +2.72 ms |
| Feature hydration, 5 accounts, absolute RC3 cost | 76.36 ms | 16.15 ms |
| Feature hydration, 20 accounts, absolute RC3 cost | 505.30 ms | 63.34 ms |

The Pixel 8 Pro was running Android 17 / API 37; the S25 Ultra was running Android 16 / API 36. The comparison therefore demonstrates real device/platform sensitivity rather than a controlled CPU-only comparison. It should not be used to predict `SM-S947B` by interpolation.

## Method and run conditions

- Used the same instrumentation source and locally built debug APKs as the Pixel study.
- Seeded deterministic encrypted SDK accounts through the real `UserAccountManager`; no live login or network request was used.
- Disabled Android-test coverage and requested package compilation in `speed` mode before each endpoint series.
- Set all Android animation scales to zero during collection and restored their original values afterward.
- The phone was USB powered at 98-99% battery. Android thermal status was 0 before the suite and at the end of every benchmark cell. It briefly reached status 1 after collection during cleanup, then returned to 0.
- Balanced endpoint order across account counts: RC3 then v13.2.1 for one account, v13.2.1 then RC3 for five, and RC3 then v13.2.1 for twenty.
- Used 100 samples for feature hydration, manager/provider/client construction, Publisher bootstrap, and activity resume; 200 for user hydration/User-Agent paths; and 1,000 for primitive `AccountManager` calls, after method-specific warm-ups.
- Removed all synthetic benchmark accounts, stopped the test package, and restored device settings after collection.

## Limitations

- This is a component/lifecycle benchmark in a debug Android-test APK, not a Publisher Aura release build and not an end-to-end cold-start measurement.
- The supplied report used `SM-S947B`, not this `SM-S938U1`. Samsung vendor software is closer than the Pixel comparison, but the exact SoC, OS build, application state, and thermal behavior may differ.
- Synthetic records do not exercise token refresh, RTR contention, DPoP signing, app attestation, SmartStore open, WebView initialization, application rendering, or network I/O.
- Cells were balanced but not fully randomized or repeated across multiple independent device sessions. A patch acceptance gate should use a release/Macrobenchmark A/B and confidence intervals.
