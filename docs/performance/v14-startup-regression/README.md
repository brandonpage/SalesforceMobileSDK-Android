# Mobile SDK 14 Android startup regression investigation

This directory preserves the analysis, physical-device measurements, and reproduction harnesses used to investigate the authenticated startup regression between the Mobile SDK 13.x and 14.0 release lines.

## Main findings

- Repeated synchronous `AccountManager` hydration is a real, correctness-preserving optimization opportunity, but it explains only part of the affected applications' startup regression.
- On a Galaxy S25 Ultra, an authenticated Publisher A/B reproduced the reported `NativeLoad` regression: 329 ms to 520 ms (+191 ms / +58.1%).
- The Publisher-eligible account and User-Agent paths isolated on that device account for approximately 11.18 ms, or 5.9%, of the reproduced `NativeLoad` change.
- The recommended 14.0.1 work removes duplicate hydration while retaining immediate feature availability, exact-account binding, live RTR credential reads, refresh coordination, and logout/relogin race protection.

Start with [performance-regression-analysis-v14-rc3.md](performance-regression-analysis-v14-rc3.md). The Publisher-specific attribution is in [publisher-impact-analysis-v14-rc3.md](publisher-impact-analysis-v14-rc3.md), and the complete local Publisher reproduction is in [publisher-performance-measurements-galaxy-s25-ultra.md](publisher-performance-measurements-galaxy-s25-ultra.md).

## Contents

- `performance-regression-analysis-v14-rc3.md`: full runtime analysis and correctness-preserving patch strategy.
- `performance-measurements-pixel8pro.md`: Mobile SDK component measurements on Pixel 8 Pro.
- `performance-measurements-galaxy-s25-ultra.md`: matched component measurements on Galaxy S25 Ultra.
- `publisher-impact-analysis-v14-rc3.md`: mapping of the confirmed SDK paths into Publisher's startup graph.
- `publisher-performance-measurements-galaxy-s25-ultra.md`: authenticated Publisher force-stop reproduction.
- `binary-footprint-analysis-v14.md`: separate APK/Dex dependency analysis.
- `run_android_cold_start_benchmark.sh`: synthetic-account SDK cold-start harness.
- `run_publisher_cold_start_benchmark.sh`: Publisher telemetry-marker harness.
- `results/`: sanitized Publisher CSV samples, including the uncounted validation launches.

The Android-test changes on this branch are benchmark instrumentation, not proposed production changes. They make the test activity exercise `SalesforceActivity`, record the synchronous resume section, disable JaCoCo overhead, and add `AccountPathBenchmarkTest` for deterministic local accounts with no network requests.

## Data handling

This fork is public. Credentials, APKs, the pre-existing dirty `external/shared` state, and raw Publisher logcat files are intentionally excluded. The raw logs contain a test org identifier and site URL; the sanitized CSVs preserve all numeric samples used in the analysis.
