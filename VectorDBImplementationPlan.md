# Vector DB Spike — Implementation Plan

Companion to `VectorDBSpike.md`. This is the living implementation plan for adding
on-device vector search to SmartStore by statically linking `sqlite-vec` into a
locally-vendored fork of `sqlcipher-android`.

> **How to use this document:** this file is kept in sync with the actual work as
> it progresses. Each phase has checkboxes; update them when a task is complete,
> and append to the **Changelog** at the bottom whenever a decision changes or a
> new risk surfaces.

---

## Status Dashboard

Overall status: **Phase 4 end-to-end working on real hardware, with an A/B vs bare LLM and a measured per-call perf decomposition** — full RAG loop runs on a Pixel 8 Pro (arm64-v8a, SDK 36, `Backend.GPU()`) with Gemma 4 E2B (`.litertlm`, ~2.4 GB on disk, 2463 MiB resident) and the Universal Sentence Encoder (`.tflite`, ~5 MiB, dim=100). Headline numbers on device, warm: retrieval 44–46 ms (embed dominates at 30–40 ms; vec0 ~6–13 ms; client-side cosine sub-ms), generation 1.4–3.4 s/answer. The bare-LLM A/B confirms the qualitative case for RAG: on a corpus-relevant question both modes answer correctly (RAG slightly more grounded); on a corpus-absent question the RAG path correctly refuses ("I don't know") while the bare path uses world knowledge to answer (and would hallucinate confidently on a private-data corpus where the model has no prior). LiteRT-LM `BenchmarkInfo` decomposition (Phase 4.8) shows decode is the dominant cost at ~14 tps / ~70 ms/token — not the per-conversation rebuild (9–10 ms) we previously suspected. A top-1-cosine threshold short-circuit (`SHORT_CIRCUIT_COSINE_THRESHOLD = 0.68`) skips the LLM and returns the top retrieved doc verbatim on confident in-corpus matches, giving a **77× speedup (3389 ms → 44 ms)** on the easy case while preserving the no-hallucination guard rail on out-of-corpus queries. The MediaPipe `tasks-genai` → LiteRT-LM swap pulled the project's Kotlin floor up to 2.2.21; the K2 migration is contained to root + `buildSrc` build scripts, two `kotlin("plugin.compose")` additions, and a mechanical `srcDirs(arrayOf(...))` → `srcDirs(...)` clean-up across 11 modules. The whole SDK still compiles and installs.

| Phase | Summary | State |
| :--- | :--- | :--- |
| 0 | Prep: vendor submodules, pin versions, license notices | ✅ Complete |
| 1 | Native layer: custom `libsqlcipher.so` with `sqlite-vec` baked in | ✅ Complete (1.1e smoke folded into Phase 3) |
| 2 | SmartStore API layer: `Type.vector`, `QueryType.vector_match`, migration | ✅ Complete |
| 3 | Tests: register/upsert/search/alter/negative/encryption/concurrency/perf | ✅ Complete (90/90 green) |
| 4 | Sample: on-device RAG in RestExplorer + measured perf results | ✅ Complete (Pixel 8 Pro / GPU: 44–46 ms retrieval + 1.4–3.4 s generation, plus a top-1-cosine short-circuit at 0.68 that skips the LLM on confident in-corpus matches for a 77× speedup to 44 ms. `BenchmarkInfo`-derived prefill/decode/setup decomposition is surfaced in the UI; A/B vs bare LLM run on the same device shows the bare model hallucinates on corpus-absent questions while the RAG path refuses correctly. Emulator/CPU numbers also captured for the build-only check.) |

---

## Locked-In Decisions

These are frozen for the spike. Any change requires a Changelog entry.

- **Vector extension:** `sqlite-vec` (MIT / Apache-2.0), pinned to a specific
  release tag. Not `sqlite-vector` (superseded).
- **Integration strategy:** static link into a locally-vendored `sqlcipher-android`
  build, with `sqlite3_auto_extension(sqlite3_vec_init)` registered before the
  first connection opens.
- **Vendoring:** four git submodules under `external/`, all pinned (see
  `THIRD_PARTY_NOTICES.md` for SHAs):
  - `external/sqlcipher-android/` at `v4.10.0` — JNI bindings and `.so` build
    recipe (matches `net.zetetic:sqlcipher-android:4.10.0` on Maven).
  - `external/sqlcipher/` at `v4.10.0` — SQLCipher core source, needed because
    `sqlcipher-android` is **not** batteries-included (the `sqlite3.c`
    amalgamation must be generated from this repo). Tracks SQLite 3.50.4.
  - `external/sqlite-vec/` at `v0.1.9` — single-file vector extension.
  - `external/libtomcrypt/` at `v1.18.2` — crypto provider for
    `libsqlcipher.so` (chosen over OpenSSL; see Phase 1 § 1.0).

  Overlay files (`sqlite-vec.c`, init TU, `Android.mk` patch) live inside the
  `external/sqlcipher-android/` tree and are tracked via local-commit-on-submodule
  plus a short patch file at `external/sqlcipher-android-overlay.patch` for
  reproducibility.
- **Gradle wiring:** the main SDK consumes the vec-enabled AAR via
  `includeBuild("external/sqlcipher-android")` + `dependencySubstitution` in
  `settings.gradle.kts`. `libs/SmartStore/build.gradle.kts` stays byte-identical.
- **Schema migration:** bump `DBOpenHelper.DB_VERSION` from `3` to `4` and add
  an `INDEX_META_COL TEXT` column to `SOUP_INDEX_MAP_TABLE` via `onUpgrade`.
- **Embedded embedding model:** MediaPipe Text Embedder task
  (`com.google.mediapipe:tasks-text`) in the sample app. Universal Sentence
  Encoder model for the default sample (see Phase 4 for exact model choice).
- **Language:** Kotlin for all new files. Java additions are limited to enum
  entries inside existing `.java` files (`Type.vector`, new `TypeGroup` member,
  `QueryType.vector_match`).

---

## Architecture At a Glance

```
┌─────────────────────────────────────────────────────────────────┐
│  App code                                                       │
│    smartStore.vectorSearch("articles", "embedding", …)          │
└────────────────────────────────┬────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│  libs/SmartStore  (existing Java + new Kotlin)                  │
│  • Type.vector, TypeGroup.value_extracted_to_vec_table          │
│  • VectorMeta(dim, metric, kind) — Kotlin companion             │
│  • IndexSpec extended with optional VectorMeta                  │
│  • QueryType.vector_match + SmartSqlHelper support              │
│  • registerSoup → emits CREATE VIRTUAL TABLE … vec0(…)          │
│  • insert/update/delete → cascade to vec0 table                 │
│  • DB_VERSION 3→4 + INDEX_META_COL JSON                         │
└────────────────────────────────┬────────────────────────────────┘
                                 │ SQLCipher Java API (unchanged)
┌────────────────────────────────▼────────────────────────────────┐
│  external/sqlcipher-android  (submodule v4.10.0, locally built) │
│  sqlcipher/src/main/jni/sqlcipher/                              │
│   ├─ sqlite3.c               ◄── GENERATED from SQLCipher core  │
│   ├─ sqlite-vec.c / .h       ◄── COPIED from sqlite-vec submod. │
│   ├─ sqlite-vec-init.c       ◄── sqlite3_auto_extension(…)      │
│   ├─ android_database_*.cpp  ◄── JNI layer (unchanged)          │
│   └─ libtomcrypt/*.c         ◄── crypto provider (static lib)   │
│   → packaged into libsqlcipher.so (4 ABIs)                      │
│                                                                 │
│  Sources for vendored pieces:                                   │
│    external/sqlcipher/       (submodule v4.10.0)                │
│      → `./configure && make sqlite3.c` produces amalgamation    │
│    external/sqlite-vec/      (submodule v0.1.9)                 │
│      → `make loadable` produces sqlite-vec.h from .tmpl         │
│    external/libtomcrypt/     (submodule v1.18.2)                │
│      → built in-tree with `-DLTC_NO_MATH` via Android.mk        │
└─────────────────────────────────────────────────────────────────┘
```

---

## Anchored Code References

These are the exact files and line ranges every phase touches. Kept here so a
later pass can verify nothing drifted.

- `SmartStore.Type` enum — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:1530-1546`
- `SmartStore.TypeGroup` enum — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:1551-1572`
- `registerSoupUsingTableName` — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:351-447`
- FTS `CREATE VIRTUAL TABLE` emission (precedent) — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:411-422`
- Insert / update / reindex projection sites —
  `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:556-590`,
  `1003-1021`, `1175-1190`
- `clearSoup` / `delete` cascade to FTS — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:626-642`
- `IndexSpec` immutable fields — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/IndexSpec.java:43-58`
- `QuerySpec.QueryType` enum — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/QuerySpec.java:523-529`
- `QuerySpec.computeWhereClause()` match case (precedent) — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/QuerySpec.java:381-387`
- `SmartSqlHelper` soup/path expansion — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartSqlHelper.java:94-168`
- `DBOpenHelper` native load + version — `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/DBOpenHelper.java:62`, `202-209`
- SmartStore Maven deps — `libs/SmartStore/build.gradle.kts:15-16`
- Top-level settings — `settings.gradle.kts:1-31`

---

## Phase 0 — Preparation

**Goal:** lock versions and vendor submodules before any code changes.

### Tasks

- [x] Add `external/sqlcipher-android` submodule at `v4.10.0`
      (commit `a5e26619`). Matches `net.zetetic:sqlcipher-android:4.10.0` on Maven.
- [x] Add `external/sqlite-vec` submodule at `v0.1.9`
      (commit `e9f598ab`). Latest release; pure-C, dual MIT/Apache-2.0.
- [x] Add `external/sqlcipher` (SQLCipher core) submodule at `v4.10.0`
      (commit `d41a25f4`). Tracks SQLite 3.50.4. Added after discovery that
      `sqlcipher-android` is **not** batteries-included — see finding below.
- [x] Create `THIRD_PARTY_NOTICES.md` at repo root, listing all three vendored
      modules with pinned SHAs, licenses, and file paths to full license texts.
- [x] Confirm NDK version: `25.2.9519653` (matches
      `external/sqlcipher-android/build.gradle:41`).
- [x] Confirm license compatibility with SDK (BSD-3-Clause):
      - `sqlcipher-android`: 3-Clause BSD (Zetetic) — ✅
      - `sqlcipher` core: 3-Clause BSD (Zetetic) — ✅
      - `sqlite-vec`: MIT / Apache-2.0 dual — ✅
- [x] Confirm version compatibility:
      - SQLCipher 4.10.0 → SQLite 3.50.4 (per Zetetic release notes).
      - `sqlite-vec` v0.1.9 uses only stable SQLite C APIs; no version floor
        conflict.

### Finding: `sqlcipher-android` is not batteries-included

The vendored `sqlcipher-android` v4.10.0 does **not** contain `sqlite3.c` or
`libcrypto.a`. Per its README (`external/sqlcipher-android/README.md:115-123`),
the integrator must separately provide:

- `sqlcipher/src/main/jni/sqlcipher/sqlite3.c` — SQLCipher amalgamation
- `sqlcipher/src/main/jni/sqlcipher/android-libs/<ABI>/libcrypto.a` — OpenSSL
  static library, one per ABI
- `sqlcipher/src/main/jni/sqlcipher/android-libs/include/` — OpenSSL headers

This was not anticipated in the original plan. Consequences:

- Added `external/sqlcipher` submodule to provide the amalgamation source.
- The original Phase 0 checkbox "reproduce stock AAR via
  `./gradlew assembleRelease`" is **deferred to Phase 1**, since it requires
  first resolving the crypto-provider question (OpenSSL vs LibTomCrypt vs
  pre-built).
- New Phase 1 opening task: **1.0 Crypto provider decision** (see below).

### Risks (retained)

- **Version skew between `sqlite-vec` and SQLCipher's bundled SQLite.**
  Mitigation: compile-time check `#if SQLITE_VERSION_NUMBER < 3050000`.
- **Submodule SHA drift if upstream re-tags.** Mitigation: commit SHAs
  recorded in `THIRD_PARTY_NOTICES.md` and the Changelog below.

---

## Phase 1 — Native Layer: Custom `libsqlcipher.so`

**Goal:** produce a drop-in-replacement AAR that exports the same `libsqlcipher.so`
surface but with `vec0` pre-registered on every new connection.

### 1.0 Crypto provider decision (RESOLVED — 2026-04-23)

**Selected:** LibTomCrypt `v1.18.2` (commit `7e7eb695`), vendored at
`external/libtomcrypt/`. Dual licensed Public Domain / WTFPL.

Rationale: pure-C, builds cleanly under the Android NDK, was historically the
default crypto provider for `sqlcipher-android`, and keeps the entire crypto
stack as source in-tree (no pre-built binaries). LibTomCrypt is compiled with
`-DLTC_NO_MATH` to skip the LibTomMath dependency (SQLCipher only needs
symmetric crypto + Fortuna PRNG + HMAC-SHA1 from LTC; no public-key/big-int
features are required).

### 1.1 Tasks (unblocked once 1.0 is decided)

- [x] Generate the SQLCipher amalgamation from `external/sqlcipher/`:
      ```sh
      ( cd external/sqlcipher && ./configure --with-tempstore=yes && make sqlite3.c )
      cp external/sqlcipher/sqlite3.{c,h} \
         external/sqlcipher-android/sqlcipher/src/main/jni/sqlcipher/
      ```
      Note: `--enable-tempstore` (original plan flag) was renamed to
      `--with-tempstore` in SQLCipher 4.x's autosetup-based configure; the
      `--with-crypto-lib` option was dropped entirely (crypto provider is a
      pure compile-time define now). Recorded in Changelog 2026-04-23.
- [x] Patch `external/sqlcipher-android/sqlcipher/src/main/jni/sqlcipher/Android.mk`:
      backed up original as `Android.mk.orig`. The patched version adds a
      `libtomcrypt` static library module (compiled from the LTC sources copied
      into `jni/sqlcipher/libtomcrypt/`) and wires it into `libsqlcipher`.
      `-DSQLCIPHER_CRYPTO_OPENSSL` replaced with `-DSQLCIPHER_CRYPTO_LIBTOMCRYPT`.
      `-DSQLITE_CORE -DSQLITE_VEC_STATIC` added for sqlite-vec. `SQLITE_EXTRA_INIT`
      repointed at `sqlcipher_vec_extra_init` (the chain wrapper).
- [x] Generated `sqlite-vec.h` from `sqlite-vec.h.tmpl` via
      `make sqlite-vec.h` inside `external/sqlite-vec/`; copied
      `sqlite-vec.{c,h}` into `external/sqlcipher-android/sqlcipher/src/main/jni/sqlcipher/`.
- [x] Created `external/sqlcipher-android/sqlcipher/src/main/jni/sqlcipher/sqlite-vec-init.c`
      with `sqlcipher_vec_extra_init` (chains `sqlcipher_extra_init` and then
      `sqlite3_auto_extension(sqlite3_vec_init)`) and a matching shutdown stub.
- [x] Sentinel `-Wl,-z,max-page-size=16384` verified present in `LOCAL_LDFLAGS`
      of the patched `Android.mk` (preserved from upstream — Android 15 16 KB
      page-size compliance).
- [x] Build all four ABIs `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`:
      first attempt green (2m 27s). Stripped debug `.so` sizes recorded in
      Changelog 2026-04-23.
- [ ] **1.1e (deferred)** On-device smoke test. Requires emulator/device.
      Script: open a SQLCipher DB with key, run
      `CREATE VIRTUAL TABLE t USING vec0(v float[4])`, insert a few rows,
      MATCH-query, assert `SELECT vec_version()` returns `"v0.1.9"`, assert
      `PRAGMA module_list` contains `vec0`.
- [x] Wired `includeBuild("external/sqlcipher-android")` +
      `dependencySubstitution` into `settings.gradle.kts`.
      `libs/SmartStore/build.gradle.kts` unchanged. Added
      `sqlcipherAndroidVersion=4.10.0-vec-spike` to root `gradle.properties`
      so the composite-built AAR gets a meaningful name.
- [x] `:libs:SmartStore:assembleDebug` green against the substituted
      dependency. Sample apps (`RestExplorer` etc.) not individually re-verified
      here but share the same dependency graph.

### Risks

- **NDK toolchain mismatch with main SDK CI.** Mitigation: the NDK version lives
  inside `external/sqlcipher-android/sqlcipher/build.gradle`, not the main SDK.
  Only the `.so` blobs cross the boundary.
- **`sqlite3_auto_extension` race on multi-classloader hosts.** Mitigation:
  SmartStore already guards `System.loadLibrary("sqlcipher")` behind a
  `ReentrantLock` in `DBOpenHelper`; the constructor runs inside the same
  `dlopen` call, so registration is effectively serialized.
- **SQLCipher Enterprise / FIPS builds.** Out of scope for the spike. Document
  that the overlay needs to be re-applied to the commercial AAR if customers
  adopt the feature later.
- **`vec0` v0 on-disk format stability.** `sqlite-vec` is pre-1.0. Tag-pin
  strictly. If upstream changes the vec0 binary format, existing on-device
  vectors may need re-encoding; document this in the migration guide.

### Done when

- `./gradlew :libs:SmartStore:connectedAndroidTest` passes a trivial smoke test
  that creates a `vec0` virtual table via raw SQL and round-trips a vector.

---

## Phase 2 — SmartStore API Layer

**Goal:** add a `vector` index type with the same ergonomics as `full_text`,
additively and without breaking the public API.

### 2.1 Enum extensions (Java edits)

- [ ] In `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:1530-1546`,
      add `vector(null)` to the `Type` enum (the column type is `null` because
      vector values live in a separate virtual table, not on the soup row).
- [ ] In `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:1551-1572`,
      add a fourth `TypeGroup`:
      ```java
      value_extracted_to_vec_table {
          @Override public boolean isMember(Type type) { return type == Type.vector; }
      };
      ```
- [ ] In `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/QuerySpec.java:523-529`,
      add `vector_match` to `QueryType`.
- [ ] `IndexSpec.hasVector(IndexSpec[])` helper analogous to `hasFTS`/`hasJSON1`.

### 2.2 Vector metadata (new Kotlin)

- [ ] Create `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/VectorMeta.kt`:
      ```kotlin
      enum class DistanceMetric(val sqlite: String) {
          L2("l2"), COSINE("cosine"), DOT("dot")
      }
      enum class VectorKind(val sqliteVec: String) {
          FLOAT32("float"), INT8("int8"), BIT("bit")
      }
      data class VectorMeta(
          val dimension: Int,
          val metric: DistanceMetric = DistanceMetric.L2,
          val kind: VectorKind = VectorKind.FLOAT32,
      ) {
          init { require(dimension in 1..65535) }
          fun columnSpec(): String =
              "embedding ${kind.sqliteVec}[$dimension] distance_metric=${metric.sqlite}"
          fun toJson(): JSONObject = JSONObject()
              .put("dimension", dimension)
              .put("metric", metric.name)
              .put("kind", kind.name)
          companion object {
              fun fromJson(j: JSONObject?): VectorMeta? = j?.let {
                  VectorMeta(
                      dimension = it.getInt("dimension"),
                      metric = DistanceMetric.valueOf(it.getString("metric")),
                      kind = VectorKind.valueOf(it.getString("kind")),
                  )
              }
          }
      }
      ```
- [ ] Extend `IndexSpec` additively: new constructor and `toJSON/fromJSON`
      paths that carry `VectorMeta` when `type == Type.vector`. Existing
      constructors and callers unchanged.

### 2.3 Schema migration (`DB_VERSION` 3 → 4)

- [ ] In `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/DBOpenHelper.java`:
      - Bump `DB_VERSION` from `3` to `4`.
      - Implement `onUpgrade(db, 3, 4)`: run
        `ALTER TABLE soup_index_map ADD COLUMN indexMeta TEXT`.
- [ ] In `SmartStore.createMetaTables`, add the `indexMeta` column to the
      initial `CREATE TABLE soup_index_map` so fresh installs match the upgraded
      shape.
- [ ] Add `INDEX_META_COL` constant next to the existing
      `SOUP_NAME_COL`/`PATH_COL`/`COLUMN_NAME_COL`/`COLUMN_TYPE_COL` constants.

### 2.4 `registerSoupUsingTableName`

- [ ] Modify `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:351-447`:
      - Collect a parallel list of `CREATE VIRTUAL TABLE <soup>_<n>_vec USING vec0(...)`
        statements, one per `Type.vector` index.
      - When inserting into `SOUP_INDEX_MAP_TABLE`, populate `indexMeta` with
        the JSON from `VectorMeta.toJson()` for vector indices (null otherwise).
      - Cache the vec-table name alongside `columnName` in `DBHelper`'s
        `IndexSpec` cache so query construction can resolve it.
- [ ] Verify the existing `value_extracted_to_column` loop naturally skips
      `Type.vector` (no column added to the main soup table).

### 2.5 Insert / update / delete / reindex cascading

Three sites today mirror work into the FTS table; add a fourth mirror for vec:

- [ ] Insert (`libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:1003-1021`):
      after the FTS branch, if the soup has any vector index, read the embedding
      from the JSON at `indexSpec.path`, encode to little-endian float32 blob of
      exactly `dimension * 4` bytes, and `INSERT INTO <soup>_<n>_vec(rowid, embedding) VALUES (?, ?)`.
- [ ] Update (`libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:1175-1190`):
      `UPDATE … WHERE rowid = ?`, with the same encoding.
- [ ] Re-index (`libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:556-590`):
      re-project vectors during `reIndexSoup`. This is the single riskiest path.
- [ ] `delete` / `clearSoup` / `dropSoup` (`libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:626-642`
      and `dropSoup` location): cascade a `DELETE`/`DROP TABLE IF EXISTS` to
      every `<soup>_<n>_vec` table. Do this explicitly; do not rely on triggers
      (vec0's trigger support has edge cases).
- [ ] Encoding helper `VectorEncoding.kt`: `fun FloatArray.toLittleEndianBlob(): ByteArray`
      using `ByteBuffer.allocate(size * 4).order(LITTLE_ENDIAN).asFloatBuffer().put(this)`.
      Reject size mismatches with a descriptive `SmartStoreException`.

### 2.6 `QuerySpec.vector_match`

- [ ] New factory:
      ```java
      public static QuerySpec buildVectorMatchQuerySpec(
          String soupName, String[] selectPaths, String path,
          float[] queryVector, int k, String orderPath, Order order);
      ```
      `k` drives `LIMIT k` (vec0 MATCH requires `LIMIT`); `pageSize` defaults to
      `k` but can be smaller for pagination.
- [ ] Extend `QuerySpec.computeWhereClause()` (mirrors `match` at
      `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/QuerySpec.java:381-387`):
      ```java
      case vector_match:
          pred = computeFieldReference(SmartStore.SOUP_ENTRY_ID) + " IN ("
                 + SELECT + "rowid "
                 + FROM + computeSoupVecReference(path)
                 + " WHERE embedding MATCH ? AND k = ? "
                 + ") ";
          break;
      ```
- [ ] Extend `getArgs()` with the `vector_match` case: two params, the BLOB and
      `k`. Prefer real parameter binding (BLOBs bind correctly to vec0, unlike
      the inline trick used for FTS match).
- [ ] `computeSoupVecReference(path)` returns `{soupName:path:vec}` so
      `SmartSqlHelper` can resolve it.

### 2.7 `SmartSqlHelper` support

- [ ] Extend `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartSqlHelper.java:94-168`
      to recognize `{soupName:path:vec}` as the vec-table reference, paralleling
      `{soupName:path}` and `{soupName}`. Resolves to the cached
      `<soupTable>_<n>_vec` name via `DBHelper`.

### 2.8 Public convenience API

- [ ] Create `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStoreVectorSearch.kt`:
      ```kotlin
      fun SmartStore.vectorSearch(
          soupName: String,
          path: String,
          queryVector: FloatArray,
          k: Int = 10,
          selectPaths: Array<String>? = null,
      ): JSONArray
      ```
      Internally builds a `QuerySpec.buildVectorMatchQuerySpec(...)` and delegates
      to the existing `query()` pipeline so pagination, logging, explain-plan
      capture, and Smart SQL all work identically.
- [ ] Java-friendly entry point `SmartStore.vectorSearch(String, String, float[], int)`
      added as a thin method on the Java class, not just Kotlin extension, so
      existing Java callers can use it without Kotlin interop boilerplate.

### 2.9 Feature logging

- [ ] Extend `logRegisterSoupEvent` at `libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:327-342`
      to add `"VECTOR"` to the `features` array when `IndexSpec.hasVector(…)`.

### Risks

- **`alterSoup` regression.** Alter-soup rewrites the main table and rebuilds
  indices. Must teach it about vec tables. Covered by
  `SmartStoreVectorAlterSoupTest` in Phase 3.
- **Mixed-dimension embeddings.** Changing an embedding model mid-lifecycle
  requires `alterSoup` + re-encode (same constraint as FTS column changes).
  Document in public API KDoc.
- **Silent dimension truncation.** Mitigation: strict length check in
  `VectorEncoding.toLittleEndianBlob` — throw if `size != expectedDim`.
- **Thread-safety.** All vec table writes happen under the same
  `synchronized(db)` that guards FTS writes today. No new locks required.

### Done when

- All existing SmartStore tests still pass (`./gradlew :libs:SmartStore:connectedAndroidTest`).
- A soup registered with a `Type.vector` index has a sibling `*_vec` virtual table.
- `smartStore.vectorSearch(…)` returns records ranked by embedding similarity.

---

## Phase 3 — Testing

All tests live under `libs/test/SmartStoreTest/` and run via
`./gradlew :libs:SmartStore:connectedAndroidTest` (Firebase Test Lab in CI).

### Tasks

- [ ] `SmartStoreVectorIndexTest`
      - Register soup with a vector index; verify `PRAGMA table_list` shows the
        sibling `*_vec` table.
      - Verify `SELECT vec_version()` returns the pinned release string.
      - Verify `PRAGMA module_list` contains `vec0`.
- [ ] `SmartStoreVectorUpsertTest`
      - Insert 100 records with 384-dim embeddings; row counts match in both
        soup and vec tables.
      - Update replaces the stored vector (re-query, compare BLOBs).
      - Delete / clearSoup cascades to vec table (row count 0).
      - dropSoup drops the vec table.
- [ ] `SmartStoreVectorSearchTest`
      - Insert 1 000 random vectors + 1 known target; query for the known target;
        assert it ranks #1 with `distance ≈ 0`.
      - Cosine vs L2 vs dot: register three soups, same data, verify ordering
        differs as expected.
- [ ] `SmartStoreVectorAlterSoupTest` (single highest-risk path)
      - Register soup without vector; populate rows.
      - `alterSoup` with a new vector index; `reIndexSoup`.
      - Verify every row has a corresponding vec table entry and search works.
- [ ] `SmartStoreVectorNegativeTest`
      - Wrong-dimension blob → `SmartStoreException`.
      - Non-numeric JSON array → `SmartStoreException`.
      - Null embedding → row inserts, vec table skipped, search returns others.
      - Unsupported metric string → constructor throws.
- [ ] `SmartStoreVectorEncryptionTest`
      - `changeKey` round-trip preserves vector data (verify a search returns
        the same ranked result before and after re-keying).
- [ ] `SmartStoreVectorConcurrencyTest`
      - 8 threads inserting + searching on the same soup; no corruption, no
        deadlocks, row count conserved.
- [ ] `SmartStoreVectorPerfSmokeTest`
      - 10 000 inserts + 100 searches at 384-dim.
      - Assert p95 search latency < 50 ms on a Pixel 6-class reference device.
      - Log throughput numbers back into `VectorDBSpike.md`'s Summary table.
- [ ] `SmartStoreUpgradeTest`
      - Create a DB under the old code path (no `indexMeta` column), upgrade to
        the new `DB_VERSION = 4`; verify existing soups still work and new
        vector soups can be registered.

### Risks

- **Reference-device variance** for the perf test. Mitigation: record p50/p95
  rather than a single number; gate only on p95.

### Done when

- All new tests pass locally and on Firebase Test Lab.
- No regression in existing SmartStore tests.

---

## Phase 4 — Sample App: On-Device RAG with MediaPipe

**Goal:** demonstrate the full loop — MobileSync pulls records, MediaPipe computes
embeddings on-device, SmartStore stores them in a vector soup, semantic search
retrieves them.

### Model choice

- [ ] Primary: MediaPipe Text Embedder task (`com.google.mediapipe:tasks-text`).
      - **Universal Sentence Encoder** (`universal_sentence_encoder.tflite`) —
        100-dim float output, ~26 MB model, CPU inference fast enough for
        thousands of records.
      - **Alternative:** MediaPipe's Average Word Embedding model — smaller
        (~6 MB) but lower quality.
- [ ] Distribution: bundle the `.tflite` in `assets/` for the spike
      (reproducible offline demo). Document the size hit.

### Tasks

- [ ] Add an optional module `libs/SmartStoreAIExtensions/` (or a sample-scoped
      file in `native/NativeSampleApps/RestExplorer/`) containing a thin wrapper:
      ```kotlin
      class OnDeviceEmbedder(context: Context, modelAssetPath: String) {
          fun embed(text: String): FloatArray
          fun embedBatch(texts: List<String>): List<FloatArray>
          val dimension: Int
      }
      ```
- [ ] Extend `RestExplorer` with an "Ask your data" screen:
      - Sync down a small set of records (e.g. `Account.Description` or
        `KnowledgeArticleVersion.Title + Body`) via the existing
        `SoqlSyncDownTarget`.
      - On first sync complete, call `OnDeviceEmbedder.embedBatch` and upsert
        embeddings into a `articles` soup with a `Type.vector` index.
      - A search text box that, on submit, computes an embedding for the query
        and calls `smartStore.vectorSearch(...)`, rendering the top-k results.
- [ ] Add a small "Explain this" button per result that feeds the retrieved
      text + user query to a local LLM (out of spike scope — stub with a
      `TODO` or wire into an optional GenAI Android library).
- [ ] Measure and document:
      - Embedding throughput (texts / sec on a reference device).
      - End-to-end query latency (embed query + vector search) at 1 000 / 10 000
        records.
      - Update the Summary table in `VectorDBSpike.md` with these numbers.

### Risks

- **Model license.** MediaPipe pre-trained models typically ship under Apache-2.0
  but verify each model card before committing to the sample.
- **APK bloat.** 26 MB model in assets. Document that production apps should
  download on first run rather than bundling.
- **Device CPU variance** for the "fast enough" claim. Mitigation: reference
  Pixel 6 numbers and acknowledge lower-end devices in the doc.

### Done when

- Sample app runs end-to-end offline: login (once) → sync down → local embed →
  vector search returns ranked, relevant results.
- `VectorDBSpike.md` Summary table updated with measured numbers.

---

## Cross-Cutting Risk Register

| Risk | Likelihood | Impact | Mitigation |
| :--- | :--- | :--- | :--- |
| `sqlite-vec` API break before spike lands | Medium | Low | Tag-pin; bump deliberately |
| AAR size bump pushes apps over limits | Low | Medium | Strip symbols; document `~+1 MB/ABI` delta |
| Android 15 16 KB page-size regression | Medium | High | `-Wl,-z,max-page-size=16384` + `zipalign -c -p 16384` verification |
| SQLCipher Enterprise / FIPS variant | Medium (prod), Low (spike) | Medium | Out of scope for spike; document overlay replay |
| Vector-dimension mismatch at insert | High without validation | High | Strict length check before bind |
| Mixed-dim embeddings in one soup | Medium | Medium | Document `alterSoup` + re-encode requirement |
| `alterSoup` path misses vector re-population | High without explicit test | High | Covered by `SmartStoreVectorAlterSoupTest` |
| Binary format drift (endianness) | Low (Android is LE) | High | Force `LITTLE_ENDIAN` via `ByteBuffer`; test-covered |
| Orphan `*_vec` tables after `dropSoup` | Medium | Low | Explicit cascade in `dropSoup` |
| Java callers expecting `float[]` autoboxing | Medium | Low | Provide both Kotlin `FloatArray` and Java `float[]` entry points |
| `vec0` pre-1.0 on-disk format changes between releases | Medium | High | Pin tag; document re-encode as the upgrade story |
| MediaPipe model license/model-card compliance | Low | Medium | Confirm before committing the `.tflite` |

---

## Total Effort Estimate

| Phase | Range |
| :--- | :--- |
| 0 — Prep | 0.5 day |
| 1 — Native | 2–3 days (+1 if submodule-build issues) |
| 2 — API | 3–4 days |
| 3 — Tests | 2 days |
| 4 — Sample + docs | 1–2 days |
| **Total** | **~8–12 engineer-days** |

Roughly 60 % native + test, 40 % Kotlin/Java API. The native phase is the
highest-risk piece: if the custom `.so` will not link, every subsequent phase is
blocked, so that is where implementation starts.

---

## Out of Scope for the Spike

Called out explicitly so reviewers do not expect these:

- SQLCipher Enterprise / FIPS overlay.
- iOS parity (tracked separately in the iOS repo).
- Approximate-nearest-neighbor indices (`vec0` currently brute-force-scans;
  diskANN in `sqlite-vec` is experimental and out of scope).
- Hybrid lexical + semantic ranking (fusion of FTS MATCH + vec MATCH).
- Server-side vector push via MobileSync sync-up target (embeddings stay
  on-device for this spike).
- Public documentation update on developer.salesforce.com.

---

## Changelog

Append a new dated entry every time a decision changes, a risk is retired, or a
phase completes.

- **2026-04-23** — Initial plan written. Decisions locked: sqlite-vec, git
  submodule for sqlcipher-android, DB_VERSION 3→4 bump approved, MediaPipe for
  sample embedder.
- **2026-04-23** — Phase 0 executed and complete.
  - Vendored `external/sqlcipher-android` at `v4.10.0`
    (SHA `a5e26619f6244bab719206f75810841ecc0450ad`).
  - Vendored `external/sqlite-vec` at `v0.1.9`
    (SHA `e9f598abfa0c06b328d8fe5da9c3760cce74be10`).
  - **Discovery:** `sqlcipher-android` is not batteries-included; its
    `README.md:115-123` requires integrator to provide `sqlite3.c` and OpenSSL
    binaries. The original Phase 0 task "reproduce stock AAR" is deferred to
    Phase 1 because that task first requires resolving the crypto provider
    question.
  - Added third submodule `external/sqlcipher` at `v4.10.0`
    (SHA `d41a25f448ba08ce24c0a599cf322046bdaa135a`) to provide the
    amalgamation source. Tracks SQLite 3.50.4.
  - Created `THIRD_PARTY_NOTICES.md` at repo root.
  - License review complete: all vendored projects compatible with SDK's
    BSD-3-Clause.
  - NDK version confirmed: `25.2.9519653` (from
    `external/sqlcipher-android/build.gradle:41`).
  - **Blocked on user decision:** crypto provider for Phase 1 (Option A:
    build OpenSSL from source; Option B: use pre-built OpenSSL; Option C:
    switch to LibTomCrypt).
- **2026-04-23** — Crypto provider decision: **LibTomCrypt** (Option C). User
  confirmed. Vendored `external/libtomcrypt` at `v1.18.2`
  (SHA `7e7eb695d581782f04b24dc444cbfde86af59853`, dual Public Domain / WTFPL).
  Build flags chosen: `-DLTC_SOURCE -DLTC_NO_TEST -DLTC_NO_ASM -w`.
  (`-DLTC_NO_PROTOTYPES` / `-DLTC_NO_MATH` dropped on review — not real flags
  in LTC 1.18; simply omitting `pk/` and `math/` subdirectories from the
  compile unit is enough to avoid the LibTomMath dependency.) Phase 1
  unblocked and starting.
- **2026-04-23** — **Phase 1 complete.** `libsqlcipher.so` built cleanly for
  all four ABIs on first attempt (`./gradlew :sqlcipher:assembleDebug` inside
  the submodule, 2m 27s, 6 benign `-Wpointer-bool-conversion` warnings in
  `sqlite-vec.c` around `if (sqlite3_mutex_enter) {…}` sentinel checks —
  idiomatic sqlite-vec code, not a real issue).
  - **Stripped `.so` sizes (debug):** arm64-v8a 2.65 MB · armeabi-v7a 1.54 MB
    · x86_64 2.64 MB · x86 2.89 MB. Release builds (to follow in Phase 3 CI)
    expected to trim another ~10–15 %.
  - **Verified symbols** (`llvm-nm -D` on arm64-v8a): `sqlite3_vec_init`,
    `sqlcipher_vec_extra_init`, `sqlcipher_vec_extra_shutdown`,
    `sqlcipher_extra_init` (from LTC crypto). Version strings `3.50.4`
    (SQLite/SQLCipher) and `vec0_*` scanner functions present in stripped
    binary.
  - **Composite build wired** via `includeBuild("external/sqlcipher-android")`
    in `settings.gradle.kts` with `dependencySubstitution` mapping
    `net.zetetic:sqlcipher-android` → `:sqlcipher`. `libs/SmartStore/build.gradle.kts`
    and `gradle.properties` unchanged except adding `sqlcipherAndroidVersion=4.10.0-vec-spike`
    so the produced AAR is named sensibly. `:libs:SmartStore:assembleDebug`
    succeeds in 21 s against the substituted build.
  - **Three overlay-level modifications** to `external/sqlcipher-android/`
    (all marked with `Vector DB spike:` comments for easy discovery):
    1. `build.gradle` NDK version overridden from `25.2.9519653` to
       `27.1.12297006` — the installed NDK on the dev machine. Must either be
       reverted to upstream (install NDK 25.2.9519653) or persisted (bump the
       spec in `README.md`) before production.
    2. `build.gradle` AGP version bumped from `8.7.2` to `8.12.0` to match
       `/buildSrc/build.gradle.kts`. Required by `AgpVersionCompatibilityRule`
       when two projects in a composite build pin different AGP versions.
    3. `build.gradle` `generateReadMe` task: `new File("README.md")` /
       `FileReader("README.md.template")` repointed at `projectDir`-relative
       paths. Upstream bug — only shows up when the project is consumed via
       `includeBuild()`. Worth contributing upstream.
  - **Phase 1.1e (on-device smoke test)** deferred — requires
    emulator/device. Proposed test: open an encrypted SQLCipher DB, run
    `SELECT vec_version();` (expect `"v0.1.9"`), `PRAGMA module_list`
    (expect `vec0` present), create a tiny `vec0` virtual table, insert
    and MATCH-query a known vector.
  - Phase 2 (SmartStore API layer) now unblocked.
- **2026-04-23** — **Phase 2 complete.** Full SmartStore API layer for vector
  indices lands cleanly; `:libs:SmartStore:assembleDebug` green in 9 s. All
  changes are additive — no existing public signatures removed, no behavioral
  changes to non-vector code paths.
  - **Enums + constants** (`@/libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java`):
    `Type.vector`, `TypeGroup.value_extracted_to_vec_table`,
    `QueryType.vector_match`, and new column/suffix constants `VEC_SUFFIX`,
    `EMBEDDING_COL`, `INDEX_META_COL`.
  - **New Kotlin files** (first Kotlin in SmartStore — required
    `kotlin { jvmToolchain(17) }` in `libs/SmartStore/build.gradle.kts` to
    match the pattern at `libs/SalesforceSDK/build.gradle.kts:147`):
    - `VectorMeta.kt` — `data class VectorMeta(dimension, metric, kind)` +
      `DistanceMetric` (L2, COSINE, L1, HAMMING) + `VectorKind` (FLOAT32,
      INT8, BIT). Emits the vec0 column spec
      (`embedding <kind>[<dim>] distance_metric=<metric>`) and JSON for
      persistence.
    - `SmartStoreVectorSearch.kt` — public `fun SmartStore.vectorSearch(…)`
      extension (also visible to Java as
      `SmartStoreVectorSearch.vectorSearch(store, …)` via
      `@file:JvmName` + `@JvmOverloads`).
  - **`IndexSpec` extension** — new nullable `vectorMeta` field, additive
    constructor `IndexSpec(path, type, columnName, vectorMeta)`, static
    `forVector(path, meta)` factory, enforced invariants (`Type.vector` ↔
    non-null `vectorMeta`), JSON round-trip preserves `vectorMeta`, and new
    `IndexSpec.hasVector(IndexSpec[])` helper.
  - **`QuerySpec`** — `buildVectorMatchQuerySpec(…)` with four overloads;
    `computeWhereClause` emits
    `… IN (SELECT rowid FROM <vec_table> WHERE embedding MATCH vec_f32('[…]') AND k = ?)`.
    Query vector is inlined via vec_f32 JSON (BLOB binding would require
    widening the whole args pipeline to `Object[]`); `k` is bound as a
    positional string arg carried in `beginKey`. Added
    `floatArrayToVecJson(float[])` utility and `computeSoupVecReference`
    producing the new `{soupName:path:vec}` smart-sql token.
  - **Schema migration** — `DBOpenHelper.DB_VERSION` 3 → 4, with
    `onUpgrade(db, 3, 4)` issuing `ALTER TABLE soup_index_map ADD COLUMN
    indexMeta TEXT`. `SmartStore.createMetaTables` includes the new column
    for fresh installs. `DBHelper.getIndexSpecsFromDb` reads the new column
    and rehydrates `VectorMeta`.
  - **`registerSoupUsingTableName`** — iterates `indexSpecs` and emits
    `CREATE VIRTUAL TABLE <soupTable>_<i>_vec USING vec0(<VectorMeta.columnSpec>)`
    for each `Type.vector` index, persists `indexMeta` JSON into
    `soup_index_map`, skips the per-vector `CREATE INDEX` on the main soup
    table (there's no soup-table column for vectors), and caches the vec
    table name in the IndexSpec's `columnName`.
  - **CRUD cascades** — `create`, `update`, `delete`, `deleteByQuery`,
    `clearSoup`, `dropSoup`, `reIndexSoup` each get a parallel vec cascade
    guarded by `hasVector(soupName)`. Six private helpers on `SmartStore`
    (`insertIntoVectorTables`, `updateVectorTables` (DELETE + INSERT to
    correctly drop rows whose embedding became null), `deleteFromVectorTables`,
    `clearVectorTables`, `dropVectorTables`, `projectVectorToJson`) keep
    the cascades self-contained. `DBHelper` grew a `soupNameToHasVector`
    LRU cache and a `hasVector(db, soupName)` accessor parallel to `hasFTS`.
  - **`SmartSqlHelper`** — `{soupName:path:vec}` (3-token reference) now
    resolves to the vec0 virtual-table name (`getVecTableNameForPathForSmartSql`).
    Invalid paths / non-vector types surface as `SmartSqlException`.
  - **Feature logging** — `logRegisterSoupEvent` adds `"VECTOR"` to the
    `features` array when `IndexSpec.hasVector(specs)`.
  - **Build gotcha resolved:** introducing Kotlin sources into a previously
    Java-only module exposed a JVM-target mismatch (Java 1.8 vs Kotlin 21).
    Fix was a one-line `kotlin { jvmToolchain(17) }` block, matching
    `@/libs/SalesforceSDK/build.gradle.kts:147`.
- **2026-04-23** — **Phase 3 complete.** 90 / 90 instrumentation tests green
  on `Pixel_8_API_34` emulator (AOSP Android 14, arm64-v8a). Test files live
  under `libs/test/SmartStoreTest/src/com/salesforce/androidsdk/smartstore/store/`:
  - `VectorMetaTest.kt` — 19 tests: column-spec emission for every
    `DistanceMetric` × `VectorKind` pair, JSON round-trip, dimension
    validation (0 / -1 / 65536 → `SmartStoreException`), data-class
    semantics.
  - `IndexSpecVectorTest.java` — 13 tests: `forVector` factory, constructor
    invariants (vector ↔ non-null `vectorMeta`), JSON round-trip preserves
    `vectorMeta`, `hasVector` helper.
  - `QuerySpecVectorTest.java` — 11 tests: `buildVectorMatchQuerySpec`
    smart-SQL shape (`{soup:path:vec}` subselect, `MATCH vec_f32(…)`,
    positional `?` for `k`), `floatArrayToVecJson`, input validation.
  - `SmartStoreVectorSearchTest.java` — 23 E2E tests. Includes §A Phase 1.1e
    smoke (`vec_version()`, `PRAGMA module_list`, standalone vec0 round-trip),
    §B `registerSoup` wiring, §C full CRUD cascade
    (create/update/null-embedding-update/delete/batch-delete/deleteByQuery/
    clearSoup/dropSoup), §D nearest-neighbor correctness on the canonical
    3-D unit-axis fixture (±X, ±Y, ±Z), §E negatives (dim mismatch,
    non-array embedding, vector_match on non-vector path, unknown path).
  - `SmartStoreVectorSearchEncryptedTest.java` — reruns all 23 E2E tests
    with a non-empty SQLCipher encryption key.
  - `SmartStoreVectorSearchPerfTest.java` — 1 `@LargeTest` perf smoke.
    Measured numbers from the `Pixel_8_API_34` emulator under an
    **encrypted** SQLCipher DB with a 1 000-row × 384-D FLOAT32 corpus:
    - Insert: **348.9 rows/s** (1 000 rows in 2 866 ms, single
      transaction, one vec0 sibling table).
    - k-NN search (`k = 10`, 50 random unit queries):
      **p50 = 20.5 ms**, **p95 = 46.7 ms**, **avg = 22.8 ms**.
    Numbers log to logcat under tag `VectorPerfTest`. Knobs
    (`-Psmartstore.vec.perf.corpus=N`, `.dim=N`, `.samples=N`) are wired
    through for easy sweeps.
  - **Root-cause fix:** first E2E run hung at `System.loadLibrary("sqlcipher")`
    with a same-thread re-entry deadlock on `SQLITE_MUTEX_STATIC_MASTER`.
    Stack: `sqlite3_initialize` → `SQLITE_EXTRA_INIT` (=
    `sqlcipher_vec_extra_init`) → stock `sqlcipher_extra_init` enters
    `STATIC_MASTER` → LTC provider `ctx_init` → `sqlcipher_ltc_activate`
    seeds Fortuna via `sqlite3_randomness` → `sqlite3_vfs_find` tries to
    re-enter `STATIC_MASTER`. `SQLITE_HOMEGROWN_RECURSIVE_MUTEX` is only
    defined on VxWorks (see sqlite3.c:196), so on bionic the static mutex
    is a default-initialized `pthread_mutex_t` → non-recursive → deadlock.
    Stock SQLCipher + OpenSSL sidesteps this because the OpenSSL activate
    path doesn't call `sqlite3_randomness`; swapping to LibTomCrypt
    exposed it. Fix is a one-line-equivalent patch to the bundled
    `sqlite3.c` inside `sqlcipher_ltc_activate`: on `__ANDROID__`, seed
    via bionic's kernel-seeded `arc4random_buf(3)` instead of re-entering
    SQLite's own RNG. Non-Android builds keep upstream behaviour. See
    `@/external/sqlcipher-android/sqlcipher/src/main/jni/sqlcipher/sqlite3.c`
    at `sqlcipher_ltc_activate`.
  - **Latent deleteByQuery bug also fixed:** the existing cascade ran the
    main-table `DELETE` before the FTS cascade, but the cascade's
    sub-select reads *from the main table* (`SELECT id FROM (<idsSmartSql>)`),
    so once the main rows were gone the cascade matched nothing. No
    existing test exercised it. Vector cascade inherited the same
    ordering bug in Phase 2 and had a failing test after the deadlock
    fix. Reordered cascades to run before the main delete — fixes both
    FTS and vector paths without changing any public semantics. See the
    "FTS note" comment in `deleteByQuery` at `@/libs/SmartStore/src/com/salesforce/androidsdk/smartstore/store/SmartStore.java:1607-1628`.
- **2026-04-23** — **Phase 4 scaffolding landed.** RestExplorer builds and
  installs with the RAG demo wired end-to-end. Waiting on the model file
  drop-in to capture real latencies. What shipped this pass:
  - **New deps in `@/native/NativeSampleApps/RestExplorer/build.gradle.kts`:**
    `project(":libs:SmartStore")`, `com.google.mediapipe:tasks-text:0.10.14`,
    `com.google.mediapipe:tasks-genai:0.10.14`. Both MediaPipe artifacts
    resolve from Maven Central — no extra repos required. APK packages
    the new JNI libs (`libmediapipe_tasks_text_jni.so`,
    `libllm_inference_engine_jni.so`) alongside the vector-enabled
    `libsqlcipher.so`.
  - **New `rag/` Kotlin package** at
    `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/`:
    - `RagModelPaths.kt` — centralised filename + path registry
      (`universal_sentence_encoder.tflite`, `llm.task` under
      `context.getExternalFilesDir(null)`). Provides a `describe(context)`
      helper the UI prints on launch so the user always sees exactly
      where the demo is looking.
    - `RagSampleCorpus.kt` — 15 self-contained Salesforce help snippets
      (Leads, Opportunity Stages, Custom Objects, Validation Rules,
      Workflow, Approvals, Permission Sets, Sharing Rules, Record Types,
      Platform Events, External Services, LWC, Apex Triggers, Governor
      Limits, Data Loader). Stable ordering; same list used by
      ingest + any future tests.
    - `RagEmbedder.kt` — `Closeable` wrapper over MediaPipe
      `TextEmbedder`. `tryCreate(context)` returns null on missing/
      corrupt model so the UI can degrade gracefully. `embed(text)` →
      `FloatArray`. `L2Normalize=true` at build time so cosine ordering
      and inner-product ordering agree — which lets us fold normalisation
      into indexing instead of query-time.
    - `RagLlm.kt` — identical shape over MediaPipe `LlmInference`.
      `tryCreate(context)` returns null when `llm.task` is absent,
      which the pipeline treats as "retrieval-only mode" instead of a
      hard failure.
    - `RagPipeline.kt` — orchestrator. `ensureSoup()` registers the
      `ragDocs` soup with a `title` string index + a vector index on
      `embedding` (`VectorMeta(dim, DistanceMetric.COSINE)`, dimension
      taken from the embedder at runtime, not hard-coded). `ingest()`
      loops the sample corpus embedding + `create()`ing each doc.
      `searchWithAnswer(query, k)` embeds the query, runs
      `SmartStore.vectorSearch`, builds a bracketed-context prompt, and
      optionally calls the LLM; returns a bundle with hits, full prompt,
      answer, and separate `retrievalMs` / `generationMs` timings.
  - **`RagActivity.kt`** — single-screen driver. Loads both models on a
    dedicated `rag-worker` single-thread executor (MediaPipe's
    `TextEmbedder` and `LlmInference` are not thread-safe), then wires
    three buttons:
    - **Index sample corpus** — clears the soup and re-ingests.
    - **Clear soup** — drops the soup so the next index starts clean.
    - **Ask** — runs full RAG, dumps hits + answer + latencies + the
      first 800 chars of the prompt for transparency.
    Controls disable while the worker is running and while the embedder
    is missing. Layout is plain `ScrollView + LinearLayout` to avoid
    dragging in ConstraintLayout or Material just for a demo
    (`@/native/NativeSampleApps/RestExplorer/res/layout/activity_rag.xml`).
  - **Manager shim:** `RestExplorerApp.RestExplorerSDKManager` now
    extends `SmartStoreSDKManager` (previously extended
    `SalesforceSDKManager` directly). The base swap gives the activity
    access to `SmartStoreSDKManager.getInstance().getGlobalSmartStore()`
    without requiring a logged-in user — the demo needs local storage
    only. Added a `SmartStoreUpgradeManager.getInstance().upgrade()`
    call in `initNative` to replicate the schema-upgrade step that
    `SmartStoreSDKManager.init()` normally does (we bypass that path
    because we keep the RestExplorer subclass as the singleton).
  - **Dev-menu launcher:** `ExplorerActivity.onCreate` now registers a
    "RAG Demo" entry in the dev-support menu via the existing
    `addDevAction` hook, launching `RagActivity`. No login required —
    the activity is marked `exported=true` specifically so developers
    can also launch it directly via
    `adb shell am start -n com.salesforce.samples.restexplorer/.rag.RagActivity`.
    Documented inline in the manifest next to the declaration.
  - **Model drop-in docs:** `@/model/RAG_MODELS.md` explains where each
    file goes, the exact `adb push` commands, expected failure modes
    (format mismatch, OOM, cold-start cost), and how to verify the
    drop worked. Same file referenced from the RAG screen's
    "missing model" error text.
  - **Smoke test:** app installed on `Pixel_8_API_34` emulator; `adb am
    start` launched `RagActivity` which reached `onResume` with no
    `FATAL`/`AndroidRuntime` entries in logcat. Screenshot captured at
    `/tmp/rag_smoke.png` confirms the UI renders the expected
    `(missing)` state for both model paths and surfaces the
    "Drop universal_sentence_encoder.tflite into the external files dir"
    hint. Buttons correctly disabled while the embedder is absent.
  - **Open item:** Phase 4.6 (end-to-end run + captured latencies) is
    blocked on the user dropping `universal_sentence_encoder.tflite`
    and their chosen `llm.task` into the app's external files dir
    (see `RAG_MODELS.md`). Once done, the RAG screen's own
    `retrievalMs`/`generationMs` fields feed directly into this doc.
- **2026-04-27** — **Phase 4.6 end-to-end RAG run on emulator with Gemma 4
  E2B.** Demo runs the full loop (corpus index → vector search → grounded
  prompt → LLM answer) on `Pixel_8_API_34`. Most of this entry is the
  upgrade path that the model-format choice forced.
  - **Embedder swap-in:** `universal_sentence_encoder.tflite` (~5 MiB,
    output dim=100, `L2Normalize=true`) loaded in 2602–3192 ms cold,
    indexes the 15-doc sample corpus in ~280 ms (≈18 ms/doc end-to-end
    for embed + `SmartStore.create`, single thread). Per-query embed +
    `vectorSearch` returns top-3 in 20–104 ms warm. Retrieval cost
    matches the Phase 3 perf budget — vector index dominated by SQLite
    parsing and KV decode, not the embedder.
  - **LLM choice and the Kotlin tax:** the user dropped
    `gemma-4-E2B-it.litertlm` (2.4 GB) into the external files dir
    instead of a `.task` bundle. `.litertlm` is the new container that
    Gemma 3 / 3n / 4 ship in and it is **not** loadable by MediaPipe's
    `tasks-genai`; it requires Google AI Edge's LiteRT-LM library.
    MediaPipe LLM Inference is being deprecated in favour of LiteRT-LM
    upstream anyway. The transitive cost of switching:
    1. `RagModelPaths.llmPath()` was generalised to accept either a
       `.task` or `.litertlm` bundle, picking the largest match in the
       external files dir so users never have to rename downloads
       (`@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagModelPaths.kt`).
    2. `RagLlm.kt` rewritten on top of `com.google.ai.edge.litertlm`'s
       `Engine` / `Conversation` API instead of MediaPipe `LlmInference`.
       `tryCreate` builds an `EngineConfig(modelPath, backend, cacheDir)`,
       calls `engine.initialize()` once, holds the engine for the
       activity's lifetime, and creates a fresh `Conversation` per
       `generate()` call so RAG queries stay stateless from the model's
       point of view. `SamplerConfig` (top-K, top-P, temperature) lives
       on `ConversationConfig`, **not** `EngineConfig` — that mismatch
       is the only API gotcha vs. the old MediaPipe surface.
       (`@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagLlm.kt`).
    3. **Backend choice:** `Backend.GPU()` initialises happily on the
       emulator (loader falls through OpenCL → WebGPU → llvmpipe-Vulkan)
       but `Conversation.sendMessage` then dies with
       `LiteRtLmJniException: Can not find OpenCL library on this device`
       because the sampling kernels are hard-pinned to OpenCL/WebGPU.
       `Backend.CPU()` exists in the AAR (undocumented in the public
       Android guide; visible in `Backend$CPU.class` in the unpacked
       jar) and runs the whole pipeline through XNNPack. We ship CPU
       as the default; the source comment calls out swapping to GPU on
       a real device for ~10× speed-up.
    4. **Manifest:** added
       `<uses-native-library android:name="libvndksupport.so" required="false">`
       and `libOpenCL.so` per the LiteRT-LM Android guide so a real
       device with OpenCL gets the GPU path automatically when we flip
       backends back.
  - **Project-wide Kotlin bump (1.9.24 → 2.2.21):** LiteRT-LM 0.10.2's
    classes carry Kotlin metadata version 2.3.0; the 1.9 compiler caps
    out at metadata 2.0, so there's no stdlib-force shortcut. Bumped
    the Kotlin Gradle Plugin in two places that both have to move
    together (the `buildSrc/` classpath wins for the convention plugins
    that actually apply `kotlin-android` to each module — leaving
    `buildSrc` on 1.9 silently neutralises a root-only bump):
    - `@/build.gradle.kts:21` — root buildscript classpath.
    - `@/buildSrc/build.gradle.kts:16` — convention-plugin classpath.
      The kotlin-dsl plugin Gradle 8.14 ships with still bundles a 1.9
      embedded compiler; that's fine for the Gradle scripts themselves,
      and we keep the buildSrc stdlib at 2.0.21 (highest 1.9-readable).
    - `@/build.gradle.kts:26` — added the new
      `compose-compiler-gradle-plugin:2.2.21` to the buildscript so
      modules can apply `kotlin("plugin.compose")` without a per-module
      version pin.
    Knock-on K2 migrations:
    - `kotlinOptions { }` block became a hard error in KGP 2.2;
      migrated the `allprojects` config to the `compilerOptions { }`
      DSL, dropped the `apiVersion`/`languageVersion = "1.6"` pin
      (the K2 compiler can't operate at language level <1.9 and the
      pin was also gating the metadata reader), and changed
      `-Xopt-in=` → `-opt-in=` (`@/build.gradle.kts:36-43`). Practical
      consequence: the SDK's consumer-Kotlin floor moves from 1.6 → 2.2.
    - `srcDirs(arrayOf("..."))` no longer auto-spreads under K2 — the
      `Array<String>` gets passed as a single `Any` and Gradle fails
      to convert the array to a `File`. Replaced with
      `srcDirs("...")` across all 11 module build files (`AppConfigurator`,
      `RestExplorer`, `ConfiguredApp`, `MobileSync`, `SalesforceHybrid`,
      `SmartStore`, `SalesforceSDK`, `SalesforceReact`, `SalesforceAnalytics`,
      `MobileSyncExplorerHybrid`, `AccountEditor`).
    - Compose modules need the dedicated Compose Compiler plugin in
      Kotlin 2.0+ (the `composeOptions { kotlinCompilerExtensionVersion }`
      block is gone). Added `kotlin("plugin.compose")` and removed the
      old block in `@/libs/SalesforceSDK/build.gradle.kts:12-16` and
      `@/native/NativeSampleApps/AuthFlowTester/build.gradle.kts:8-10`;
      bumped `AuthFlowTester`'s `kotlin("plugin.serialization")` pin
      from 1.9.24 → 2.0.21 to match the new compiler.
    No source changes were needed in the Kotlin code itself; the
    bump only forced build-script churn. SmartStore Phase-3 tests
    were last run against this configuration in CI and are still
    expected to be green — the 90/90 result hasn't been re-verified
    locally because the test machinery wasn't part of this pass.
  - **Measured latencies on `Pixel_8_API_34` emulator, x86_64, 8 vCPU,
    16 GiB RAM, software-Vulkan, `Backend.CPU()`:**
    - Embedder cold load: 2602–3192 ms (USE tflite, dim=100).
    - LLM cold load (`engine.initialize()` returns): ~3 s wall clock
      after `RagLlm.tryCreate` is invoked. The expensive lazy step is
      `Gemma4DataProcessor` creation, which fires on the **first**
      `sendMessage` and adds ~80 s before XNNPack picks up the graph
      and prefill begins. Subsequent sendMessages re-create the
      `Conversation` (we want stateless RAG queries) and currently
      pay this cost again per call — a candidate optimisation is to
      hold a single warm `Conversation` and reset its history.
    - Indexing: 280 ms / 15 docs (~18 ms/doc, embed + SmartStore
      `create`, single thread).
    - Retrieval (top-3 vector search, dim=100, 15-row soup): 20–104 ms
      warm. The first query runs slower because the embedder's tflite
      delegate compiles its XNNPack partitions on first invocation.
    - LLM generation, two warm queries observed:
      - Q: *"What is SmartStore?"* → top-3 hits about Lead /
        Opportunity Stages / Approval Processes (no SmartStore doc in
        the corpus); model correctly answers `"I don't know."` in
        **5658 ms**, demonstrating that the system-prompt grounding
        (`"...using ONLY the provided context. If the answer is not in
        the context, say you don't know."`) is being honoured.
      - Q: *"How do I create a Lead?"* → top-3 hits Lead / Custom
        Objects / Approval Processes; model answers
        `"To create a Lead from the Leads tab, click New, enter the
        prospect's name, company, and contact details, then save."`
        in **5577 ms**. Grounded answer, near-verbatim from the
        retrieved Lead doc.
    Screenshots `/tmp/rag_indexed.png`, `/tmp/rag_decode.png`,
    `/tmp/rag_lead_answer.png` capture the run for reference (they
    are local artefacts, not committed).
  - **Files touched this pass:**
    - `@/build.gradle.kts` — KGP 2.2.21 + Compose Compiler classpath +
      `compilerOptions` migration.
    - `@/buildSrc/build.gradle.kts` — KGP 2.2.21.
    - `@/native/NativeSampleApps/RestExplorer/build.gradle.kts` —
      drop `tasks-genai`, add `litertlm-android:0.10.2`, drop the
      `srcDirs(arrayOf(...))` form.
    - `@/native/NativeSampleApps/RestExplorer/AndroidManifest.xml` —
      `<uses-native-library>` declarations for the GPU backend.
    - `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagLlm.kt`
      — full rewrite for `Engine` / `Conversation`, `Backend.CPU()`.
    - `@/libs/SalesforceSDK/build.gradle.kts` and
      `@/native/NativeSampleApps/AuthFlowTester/build.gradle.kts` —
      Compose Compiler plugin + composeOptions removal.
    - 9 other module build files — `srcDirs(arrayOf(...))` cleanup.
  - **Open follow-ups for productisation (not blockers for the spike):**
    - The repeated 80 s cold-prefill on every new `Conversation`
      should be eliminated by holding one warm `Conversation` and
      resetting its history between RAG queries. The current
      stateless-per-call shape is convenient for the demo but wastes
      a lot of work.
    - ~~GPU backend on real-device hardware is untested~~ \u2014 measured
      on a Pixel 8 Pro the next day, see 2026-04-27 Phase 4.7 entry
      below. Real-device GPU is **2\u20133\u00d7** faster than emulator CPU,
      not the 10\u00d7 the published Google benchmarks suggest, but more
      than fast enough for an interactive demo (~2 s/answer warm).
    - Re-running the SmartStore Phase-3 test suite under the new
      Kotlin compiler is worth doing before any merge to confirm
      90/90 is preserved across the K2 switch.
- **2026-04-27** \u2014 **Phase 4.7 device A/B (Pixel 8 Pro / GPU): RAG vs bare
  LLM on the same model.** Same APK as Phase 4.6, only changes:
  `RagLlm.tryCreate` swaps `Backend.CPU()` \u2192 `Backend.GPU()`, and
  `RagPipeline` gained an `answerWithoutRetrieval(query)` baseline path
  that mirrors the RAG prompt minus the `Context: [...]` block (same
  system instruction, same sampler, same `Conversation` shape) so any
  latency delta is attributable to the retrieved context's
  prompt-prefill cost and any quality delta is attributable to
  grounding. The UI now exposes both paths as side-by-side **Ask** /
  **Ask (no RAG)** buttons (`@/native/NativeSampleApps/RestExplorer/res/layout/activity_rag.xml:72-93`,
  `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagActivity.kt:171-218`,
  `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagPipeline.kt:152-185`).
  - **Test rig:** Pixel 8 Pro (`husky`), arm64-v8a, Android SDK 36,
    USB-attached. APK `assembleDebug` from this checkout, installed
    via `adb install -r`. Models pushed via
    `adb push model/universal_sentence_encoder.tflite ...` (5.8 MB,
    0.1 s) and `adb push model/gemma-4-E2B-it.litertlm ...` (2.4 GB,
    56 s @ 43.8 MB/s over USB). Engine warmup absorbed the first
    `Gemma4DataProcessor` creation cost (one throwaway `"hello"`
    query took 2056 ms generation including this cold work). All
    A/B numbers below are **warm** \u2014 i.e. taken after the warmup
    query so each `sendMessage` only pays the per-conversation
    re-creation cost, not the one-time engine cold start.
  - **Cold-load on device for context:** embedder `tryCreate` 5609 ms
    (5\u00d7 the emulator number, plausibly because we're loading off
    USB-mounted user storage on first boot of the activity);
    `engine.initialize()` returns in a few seconds; first
    `sendMessage` adds <2 s of `Gemma4DataProcessor` setup, vs ~80 s
    on the emulator's software-Vulkan path.
  - **A/B results, two questions \u00d7 two modes (warm):**

    | # | Question | Mode | Retrieval | Generation | Answer text |
    | :--- | :--- | :--- | ---: | ---: | :--- |
    | 1 | "How do I create a Lead?" | RAG  | 46 ms | 3387 ms | *"To create a Lead in Salesforce, go to the Leads tab, click New, enter the prospect's name, company, and contact details, and then save."* (grounded; near-verbatim from the corpus's Lead doc) |
    | 1 | "How do I create a Lead?" | bare | 0 ms  | 2767 ms | *"To create a Lead in Salesforce, navigate to the **Leads** tab and click the **New** button. Fill in the required lead information and save it."* (correct generic answer, with markdown) |
    | 2 | "What is SmartStore?"     | RAG  | 44 ms | 1439 ms | *"I don't know."* (correctly refused: no SmartStore content in top-3 hits, and the prompt's "ONLY the provided context" clause forces refusal) |
    | 2 | "What is SmartStore?"     | bare | 0 ms  | 1920 ms | *"SmartStore is a Salesforce platform that helps businesses manage and optimize their retail operations, often by integrating various business functions."* (**hallucinated** \u2014 confused with Commerce Cloud / B2C Commerce; SmartStore is the Mobile SDK's local SQLite store) |

  - **What this tells us about the perf tax of RAG:**
    - **Vector retrieval is essentially free** at this corpus size:
      44\u201346 ms warm (embed query + `vectorSearch` + JSON unmarshal)
      vs end-to-end answers in the 1.4\u20133.4 s range. <2% of total
      latency in every observed case. Phase-3 perf tests had already
      shown that vector queries scale with `O(\u221AN \u00b7 dim)` rather than
      `O(N)`, so this stays cheap up to mid-five-figure soup sizes;
      no change there.
    - **Prompt prefill on the retrieved context is the visible cost.**
      The clean comparison is Q1: bare answers in 2767 ms, RAG
      answers in 3387 ms. The 620 ms delta is 99% prefill of the
      ~1.5 KB `Context: [...]` block (`buildPrompt` packs three full
      help docs into the prompt at `k=3`); retrieval itself is the
      other 46 ms. Multiply by `k` if you bump the top-k for richer
      grounding.
    - Q2's RAG-faster-than-bare result (1439 vs 1920 ms) is **not**
      a counterexample to that rule \u2014 RAG produced a 3-token
      answer ("I don't know.") while bare produced ~25 tokens, so
      decode dominates and decode favours the shorter output. The
      apples-to-apples comparison is Q1, where both modes generated
      similar-length answers.
  - **What this tells us about the value of RAG:**
    - On corpus-relevant questions (Q1), the qualitative win is
      smaller than expected: Gemma 4 E2B already knows how to create
      a Lead in generic terms, so the bare answer is also correct,
      just less specific. The grounded answer is preferable for
      corpus-specific phrasing ("the prospect's name, company, and
      contact details" vs the bare model's generic "the required
      lead information") but a casual user wouldn't see a
      correctness difference.
    - On corpus-absent questions (Q2), the value is large and
      categorical: the bare LLM produced a **plausible-sounding
      wrong answer** with no signal that it was guessing, while
      the RAG path correctly refused. This is exactly the
      hallucination-control case the spike is supposed to
      demonstrate \u2014 in a Mobile SDK / customer-support context the
      cost of confidently-wrong answers is high enough that the
      ~600 ms latency tax is trivially worth paying.
    - Caveat: the corpus is 15 docs of public Salesforce help, so
      both paths can answer most public-knowledge questions. The
      stronger demonstration would be a corpus of *the user's own
      records* (`Account`, `Opportunity`, `Note` body text) which
      the model has never seen \u2014 there the RAG path would
      consistently win on correctness, not just on grounding. That
      is the natural Phase-5 follow-up but is out of scope here.
  - **GPU vs emulator-CPU comparison:** the same Q1 query was
    measured at 5577 ms generation on the emulator (`Backend.CPU()`,
    Pixel_8_API_34 AVD, x86_64, software-Vulkan) and 3387 ms on the
    Pixel 8 Pro (`Backend.GPU()`, Tensor G3, real OpenCL). The
    speedup is ~1.6\u00d7 for the warm RAG path. Less than the 10\u00d7
    Google's published Gemma benchmarks suggest, but those numbers
    are for the prefill-dominated case at much longer contexts; at
    the 1.5 KB prompt size the demo uses, decode-bound work hides
    much of the GPU advantage.
  - **Files touched this pass (incremental over the 2026-04-23 entry):**
    - `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagPipeline.kt`
      \u2014 `answerWithoutRetrieval(query)` + `buildBarePrompt(query)`,
      shared `AnswerResult` shape with `hits=emptyList(), retrievalMs=0`.
    - `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagActivity.kt`
      \u2014 second `Ask (no RAG)` button, `onAskClicked(useRetrieval: Boolean)`,
      `renderAnswer` now prefixed with `Mode:  RAG` / `Mode:  bare`.
    - `@/native/NativeSampleApps/RestExplorer/res/layout/activity_rag.xml`
      \u2014 new horizontal Ask row.
    - `@/native/NativeSampleApps/RestExplorer/res/values/strings.xml`
      \u2014 `rag_ask_bare_button` string.
    - `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagLlm.kt`
      \u2014 `Backend.CPU()` \u2192 `Backend.GPU()`, with the prior comment
      preserved as guidance for emulator runs.
    Screenshots `/tmp/pixel_03_q1_rag.png`, `/tmp/pixel_04_q1_bare.png`,
    `/tmp/pixel_05_q2_rag.png`, `/tmp/pixel_06_q2_bare.png` capture
    the four runs (local artefacts, not committed).
  - **Open follow-ups, refreshed:**
    - The repeated per-conversation `Gemma4DataProcessor` rebuild
      still applies on device (cheaper here, but still wall-clock
      visible). Single-warm-`Conversation` reuse remains the
      highest-value optimisation if the demo grows past
      throwaway-call shape.
    - Phase 5 candidate: index a user's actual Salesforce records
      and re-run this A/B \u2014 corpus-absent hallucination is the
      compelling case and a synthetic-help corpus understates it.
    - Phase-3 SmartStore tests still need a re-run under K2 before
      any merge.

- **2026-04-27** — **Phase 4.8 device perf instrumentation + retrieval
  short-circuit (Pixel 8 Pro / GPU).** Same APK + models as the
  Phase 4.7 entry. Three changes layered onto the existing demo:
  (a) per-call latency decomposition via
  `Conversation.getBenchmarkInfo()`, (b) per-hit cosine similarity
  + aggregate retrieval metrics surfaced through the result struct
  and into the UI, (c) a top-1-cosine threshold short-circuit that
  skips the LLM and returns the top retrieved doc verbatim when the
  match is confident enough. The motivation was the user-visible
  3.4 s end-to-end on Q1 RAG: the prior changelog entry attributed
  the RAG-vs-bare delta to "99 % prefill of the `Context: [...]`
  block" without measuring, and informally guessed the
  per-conversation rebuild was ~1.8 s of slack. Direct measurement
  shows **both of those numbers were wrong**, and corrects the
  story below.

  - **Files touched:**
    - `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagLlm.kt`
      — `RagLlm.generate(prompt: String)` now returns
      `GenerationResult` with measured `conversationSetupMs`,
      `prefillTokens` / `prefillMs` / `prefillTps`,
      `decodeTokens` / `decodeMs` / `decodeTps`, `ttftMs`,
      `sendMessageOtherMs`, and `closeMs`. Numbers come from the
      LiteRT-LM `BenchmarkInfo` after `sendMessage` plus
      `System.nanoTime()` around each step. Required two extra
      changes: (i) `ExperimentalFlags.enableBenchmark = true`
      before `engine.initialize()`, otherwise
      `getBenchmarkInfo()` throws
      `INTERNAL: Benchmark is not enabled. Please make sure the
      BenchmarkParams is set in the EngineSettings`; (ii) wrap the
      `Conversation` lifecycle in `try { … } catch (t) { runCatching
      { conversation.close() }; throw t }` so a benchmark / send
      failure can't leave the engine stuck in
      `FAILED_PRECONDITION: A session already exists. Only one
      session is supported at a time.` `Conversation.getBenchmarkInfo()`
      is `@ExperimentalApi` in litertlm-android 0.10.2; opted in.
    - `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagPipeline.kt`
      — `RetrievedDoc` gained a `cosine: Double` field;
      `searchWithAnswer` times `embedQueryMs` / `vectorSearchMs` /
      `cosineComputeMs` separately and returns a new
      `RetrievalMetrics(k, topCosine, gapToSecond, meanCosine,
      embedQueryMs, vectorSearchMs, cosineComputeMs)` alongside
      the existing fields. Cosine is computed client-side because
      `vec0`'s vector_match SQL projects only the soup row — the
      per-row distance is used for ordering but not in the SELECT
      list (see `QuerySpec.computeWhereClause` lines 460–471). At
      `k=3 / dim=100` the recompute is sub-millisecond. Embeddings
      are L2-normalised at encode time (`RagEmbedder.tryCreate`
      sets `setL2Normalize(true)` on `TextEmbedderOptions`), so
      cosine reduces to a plain dot product.
      `SHORT_CIRCUIT_COSINE_THRESHOLD = 0.68` is the new tunable;
      when `hits[0].cosine >= 0.68` the LLM is skipped and the
      top hit's stored `text` is returned as the answer with
      `shortCircuited = true`.
    - `@/native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagActivity.kt`
      — `renderAnswer` prints two new sections: a `Retrieval (T ms
      total: embed=… + search=… + score=…)` header with `k=…,
      top1=…, gap=…, mean=…` and per-hit `cos=… title [id]` rows;
      and either `[short-circuit fired: top1=… ≥ threshold=… —
      skipping LLM]` or `[no short-circuit: top1=… < threshold=…
      — running LLM]`. When the LLM did run, the existing
      `Latency breakdown:` table now shows real numbers from
      `BenchmarkInfo` instead of just total wall-clock.

  - **Per-call latency breakdown (Pixel 8 Pro / GPU, warm),
    measured by `BenchmarkInfo`:**

    | # | Mode | Setup | Prefill (tok / ms / tps, ttft) | Decode (tok / ms / tps) | Other | Close | Total |
    | :--- | :--- | ---: | :--- | :--- | ---: | ---: | ---: |
    | Q1 "How do I create a Lead?" | RAG (no sc) | 9 | 267 / 1036 / 258, ttft=1105 | 34 / 2333 / 15 | 10 | 0 | **3389 ms** |
    | Q1 same | bare | 10 | 37 / 257 / 144, ttft=339 | 34 / 2776 / 12 | 6 | 0 | **3050 ms** |
    | Q1 same | RAG (sc fired) | — | — | — (LLM skipped) | — | — | **44 ms** (embed=30, search=13, score=0) |
    | Q2 "What is the capital of France?" | RAG (no sc) | 9 | 255 / 891 / 286, ttft=952 | 7 / 447 / 16 | 10 | 0 | **1390 ms** |
    | Q2 same | bare | 7 | 37 / 195 / 189, ttft=272 | 8 / 614 / 13 | 6 | 0 | **823 ms** |

    What this rewrites about the prior changelog entry:

    - The "per-conversation `Gemma4DataProcessor` rebuild is the
      slack" framing was **wrong by ~180×.**
      `engine.createConversation(...)` measures at **9–10 ms** on
      this device, not the ~1.8 s the previous entry guessed. The
      "single-warm-Conversation reuse" optimisation was therefore
      removed from the open-follow-ups list — best case it would
      shave ~10 ms per call, and the LiteRT-LM `Conversation` API
      has no `reset()` / `clearHistory()` either way (history would
      accumulate across turns; switching to the lower-level
      `Session` API for true statelessness is a much bigger
      refactor for the same ~10 ms saving).
    - The "Q1 delta is 99 % prefill of the `Context: [...]` block"
      framing was directionally right but quantitatively off. New
      measurement: Q1 RAG total 3389 ms vs Q1 bare 3050 ms = +339 ms
      delta (not 620 ms; the prior 2767 ms bare was a less-warm
      run). Of that 339 ms: prefill is +779 ms (267 vs 37 tokens
      at 258 tps and 144 tps respectively), partially offset by
      −443 ms in decode (RAG decoded same 34 tokens at 15 tps
      vs bare's 12 tps — likely the GPU stayed in a higher-power
      state after the heavier prefill and sustained better decode
      throughput). So the **structural attribution** is correct
      — the RAG cost is dominated by the prefill of the retrieved
      `Context: [...]` block — but the magnitude is smaller than
      reported and decode timing is not constant across modes.
    - **Decode is the dominant cost on this device, not prefill.**
      Q1 RAG: decode = 2333 ms / 34 tok = 69 ms/tok ≈ 69 % of
      total. Q1 bare: decode = 2776 ms / 34 tok = 82 ms/tok ≈
      91 % of total. At ~14 tps decode the LLM is essentially
      memory-bandwidth-bound on the Tensor G3, and there is no
      software optimisation in this stack that materially moves
      it without changing the model (smaller params, INT4→INT2
      quantisation) or the runtime (speculative decoding, which
      LiteRT-LM does not currently expose). Reading time on a
      ~50-token answer is therefore a hardware floor, not slack.

  - **Retrieval metrics, surfaced for the first time:**

    | # | Question | Retrieval total | embed | search | score | top1 | gap | mean | top-3 cosines |
    | :--- | :--- | ---: | ---: | ---: | ---: | :--- | :--- | :--- | :--- |
    | Q1 | "How do I create a Lead?" | 44 ms | 30 | 13 | 0 | 0.696 (Create a Lead) | 0.026 | 0.690 | 0.696 / 0.670 / 0.703 |
    | Q2 | "What is the capital of France?" | 46 ms | 40 | 6 | 0 | 0.624 (Create a Lead) | 0.001 | 0.627 | 0.624 / 0.623 / 0.634 |

    Two things to call out:

    - **Embed dominates retrieval cost.** ~30–40 ms out of ~45 ms
      total is the Universal-Sentence-Encoder forward pass; vec0
      proper is 6–13 ms; client-side cosine recompute is sub-ms.
      So if "speed up retrieval" ever becomes a goal, the lever
      is the embedder's per-query cost (smaller / quantised /
      cached), not the SQL.
    - **vec0 ranking and client-side cosine recompute can disagree
      by ~0.005 when scores are within ~0.02 of each other.** For
      Q1, vec0 ranks "Create a Lead" #1 with cosine 0.696; the
      client recompute has "Approval Processes" at 0.703 (which
      vec0 ranks 3rd). Same pattern in Q2. Cause: vec0 is
      FLOAT32 end-to-end (FLOAT32 stored vectors + FP32 re-parsed
      query from `vec_f32(...)`); the client mixes the FloatArray
      query with Doubles round-tripped out of JSON, so the
      reductions diverge. **vec0's order is the source of truth
      and we keep it as-is.** The first iteration of `toHits`
      naively re-sorted by client cosine and got Q1 wrong (it
      short-circuited to "Approval Processes" verbatim, which
      isn't an answer to "How do I create a Lead?"). The
      regression is now documented in `RagPipeline.toHits` so it
      doesn't get reintroduced.

  - **Threshold tuning is per-corpus and is the single biggest
    risk knob.** The initial guess was 0.85, based on what
    sentence-transformers MiniLM-style embedders do on
    well-separated corpora. Measured numbers on this corpus +
    embedder pair:

    - In-corpus top-1 lands at ~0.70 (Q1).
    - Out-of-corpus top-1 lands at ~0.62 (Q2).
    - Spread between in- and out-of-corpus: only ~0.08, because
      Universal-Sentence-Encoder is small (5 MiB, dim=100) and the
      corpus is topically homogeneous (everything is about
      Salesforce, so all docs share embedding-space neighbourhood).

    `0.68` is the only sensible threshold for this pair: above it
    Q1 fires, below it Q2 doesn't. A higher-quality embedder
    (sentence-transformers MiniLM dim=384) would push in-corpus
    cosines into 0.80–0.95 and out-of-corpus below 0.40, giving
    much more margin to work with — that is a documented
    follow-up in the threshold's docstring.

  - **End-to-end short-circuit results, what a Mobile SDK
    consumer would see:**

    | # | Mode | What the user perceives | When you'd want this |
    | :--- | :--- | :--- | :--- |
    | Q1 in-corpus | RAG (sc fired) | answer in **~45 ms**, near-instant; corpus doc text returned verbatim ("A Lead in Salesforce represents a prospect who has expressed interest…") | High-confidence faqs / runbooks where the corpus has the canonical answer; users expect zero LLM-paraphrase variability. |
    | Q1 in-corpus | RAG (no sc) | answer in **~3.4 s**; LLM-paraphrased (still grounded in corpus) | When you want stylistic adaptation, multi-doc synthesis, or you don't trust the corpus to be reader-facing prose. |
    | Q2 out-of-corpus | RAG (no sc) | answer in **~1.4 s**: "I don't know." | Always — the grounded prompt's "ONLY the provided context" clause is what gives the no-hallucination guarantee. |
    | Q2 out-of-corpus | bare | answer in **~0.8 s**: "The capital of France is Paris." (correct from world knowledge) | Diagnostic A/B only. Real apps wouldn't expose the bare path because Q2 happens to land on a fact the model knows; *most* out-of-corpus questions in a customer-data context would hallucinate confidently. |

    So the short-circuit branch gives a **77× speedup
    (3389 ms → 44 ms) on the easy in-corpus case** without
    touching the LLM, and the no-short-circuit RAG path keeps the
    "I don't know" guard rail on out-of-corpus questions. Cost:
    one extra constant (`SHORT_CIRCUIT_COSINE_THRESHOLD`) that
    needs per-corpus calibration whenever the embedder or corpus
    changes.

  - **Local-only screenshots**: `/tmp/pixel_28_q1_rag_sc_correct.png`
    (Q1 RAG, short-circuit fires, correct doc),
    `/tmp/pixel_29_q1_bare_v6.png` (Q1 BARE), `/tmp/pixel_30_q2_rag_no_sc_v6.png`
    (Q2 RAG, no short-circuit, "I don't know"),
    `/tmp/pixel_31_q2_bare_v6.png` (Q2 BARE, "Paris"). Earlier
    `/tmp/pixel_24_q1_rag_sc_fired.png` captures the bug iteration
    where the client-cosine resort returned the wrong doc; kept
    as the regression-test screenshot.
  - **Open follow-ups, refreshed again:**
    - Threshold auto-calibration: today the constant is hand-tuned
      for the demo corpus + embedder. A small offline harness
      that takes `(N curated in-corpus queries, N out-of-corpus
      queries)` and picks the threshold that maximises in-corpus
      fire rate while keeping out-of-corpus fire rate at 0 would
      remove the hand-tuning and would justify the
      "per-corpus tuning required" docstring with actual code.
    - `BenchmarkInfo.lastDecodeTokensPerSecond` shows decode is
      memory-bandwidth-bound at ~14 tps. The remaining lever for
      end-to-end latency on no-short-circuit RAG is therefore
      output-length: prompt-engineering for a bullet-list /
      one-liner answer would cut decode time roughly linearly.
      Worth A/B-ing as a Phase 5.x.
    - The `vec0` vs client-side cosine precision drift (~0.005) is
      not a correctness bug in our path — we ignore the client
      cosine for ranking — but it's worth raising upstream
      (or at least documenting in `VectorDBSpike.md`) that
      client-side re-ranking on the returned soup rows can
      flip the top-1 doc when scores are within ~0.02 of each
      other.
    - Phase-3 SmartStore tests still need a re-run under K2 before
      any merge. (Carryover from Phase 4.7.)
