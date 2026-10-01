# Salesforce Mobile SDK Android 14 binary-footprint analysis

**Comparison:** `v13.2.1` (`144e68fb2`) to current `dev` HEAD (`65026220e`, representative of `v14.0.0-rc.3` for the dependency changes analyzed)

**Analysis date:** 2026-09-30

## Purpose

This document isolates APK, Dex, and transitive dependency growth from the authenticated runtime regression analysis in [`performance-regression-analysis-v14-rc3.md`](performance-regression-analysis-v14-rc3.md). The runtime and footprint issues have different causal mechanisms, fixes, and validation plans.

## Executive finding

An unshrunk controlled RestExplorer release APK increases from 34.6 MB to 60.9 MB (+76.0%), with Dex increasing from 31.6 MB to 57.6 MB (+82.2%). The dominant new artifact is `androidx.compose.material:material-icons-extended`, a 34 MB AAR containing 11,123 class entries, even though SalesforceSDK uses only eight distinct icon symbols.

Effective R8 shrinking may remove most unused icons, so this result must not be treated as proof that every customer APK grows by 26 MB. It does prove that SDK 14 imposes a very large unshrunk transitive dependency and makes customer impact depend heavily on shrinker configuration and keep rules.

## Measured artifacts

Both endpoints were built locally with JDK 17 and the same Android SDK environment.

| Artifact | v13.2.1 | Current dev | Change |
|---|---:|---:|---:|
| SalesforceSDK release AAR | 2,009,276 B | 1,788,311 B | -220,965 B / -11.0% |
| SmartStore release AAR | 101,402 B | 100,328 B | -1,074 B / -1.1% |
| RestExplorer unshrunk release APK | 34,581,535 B | 60,851,136 B | +26,269,601 B / +76.0% |
| APK Dex payload | 31,612,552 B, 3 Dex files | 57,590,324 B, 4 Dex files | +25,977,772 B / +82.2% |

The SalesforceSDK AAR itself became smaller while the consumer APK Dex grew by almost 26 MB. The growth is therefore transitive dependency code, not packaged SalesforceSDK classes or resources.

## Primary source: extended Material icons

Commit `462fadb28` added `androidx.compose.material:material-icons-extended`; commit `f2c14459d` carried it into the version catalog.

The locally resolved `material-icons-extended-android:1.7.8` artifact measures:

- AAR: approximately 34 MB.
- Expanded `classes.jar`: 85,554,547 bytes.
- Archive entries: 11,123.

SalesforceSDK imports these eight distinct icon symbols:

- `KeyboardArrowDown`
- `Info`
- `ArrowBack`
- `MoreVert`
- `Delete` (TwoTone)
- `Build`
- `Close`
- `Fingerprint`

`ArrowBack` is used in two places, producing nine imports for eight distinct symbols.

## Customer-impact interpretation

| Consumer configuration | Expected impact |
|---|---|
| No minification/resource shrinking | High: most or all extended icon bytecode can enter the APK, as demonstrated by RestExplorer. |
| Effective R8 shrinking with compatible keep rules | Lower: unused icon classes should be removed, but build time and dependency-processing cost remain. |
| Broad Compose/library keep rules | Potentially high: keep rules may retain far more of the icon archive than intended. |
| App Bundle delivery | Download impact depends on shrinker and generated splits; installed Dex and build-processing costs still require measurement. |

This issue may contribute to common cold-start/page-fault differences, especially in unshrunk applications, but it does not explain the supplied benchmark's authenticated-versus-guest split by itself.

## Recommended changes

1. Replace the few extended icons with local vector/ImageVector definitions or equivalent icons already available from the core Compose Material artifact.
2. Remove `material-icons-extended` from SalesforceSDK's dependency graph.
3. Build representative shrunk and unshrunk consumer applications before and after removal.
4. Inspect R8 `-printusage`/mapping outputs to confirm unused icon classes are removed in the shrunk case.
5. Add CI budgets for both SDK artifacts and controlled consumer APKs rather than monitoring AAR size alone.

## Proposed footprint gates

- No unreviewed consumer APK or Dex growth above 10% relative to the previous stable SDK.
- Publish minified and unminified size deltas for release candidates.
- Record total Dex bytes, Dex file count, native-library bytes by ABI, resources, and download size.
- Flag any single transitive dependency above 5 MB unless explicitly approved.
- Do not ship a whole icon family for a small fixed set of SDK glyphs.

## Follow-up analysis

- Attribute the remaining size delta after icon removal by Maven component.
- Compare R8 results using representative customer keep-rule configurations.
- Measure cold page faults and class loading with and without the extended icon archive.
- Separate Compose/Kotlin/AndroidX runtime effects from pure packaged-size effects.
- Evaluate Play Integrity and SQLCipher native/code size independently.

## Reproduction notes

The v13.2.1 source was exported to a temporary directory with its tagged `external/shared` revision. `SalesforceSDK`, `SmartStore`, and the unminified RestExplorer release application built successfully at both endpoints. Measurements use exact generated AAR/APK byte counts and the uncompressed Dex entry sizes reported by the APK archive. The source checkout was not modified, and the pre-existing dirty `external/shared` state was preserved.
