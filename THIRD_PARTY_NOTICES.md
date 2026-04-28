# Third-Party Notices

This repository vendors (as git submodules) several third-party projects for
the Vector DB spike. This file lists each project, its version, its license,
and the path to the full license text inside the vendored submodule.

The SDK itself is licensed under the 3-Clause BSD license (see `LICENSE.md`).
All third-party dependencies listed below are redistribution-compatible with
3-Clause BSD.

---

## SQLCipher for Android — `external/sqlcipher-android/`

- **Upstream:** https://github.com/sqlcipher/sqlcipher-android
- **Pinned version:** `v4.10.0`
- **Pinned commit:** `a5e26619f6244bab719206f75810841ecc0450ad`
- **License:** 3-Clause BSD-style (Zetetic LLC)
- **License text:** `external/sqlcipher-android/LICENSE`
- **Copyright:** © 2008–2023 Zetetic LLC
- **Why vendored:** Provides the Java JNI bindings (`net.zetetic.database.sqlcipher.*`)
  and the `libsqlcipher.so` build recipe consumed by SmartStore. Vendoring lets
  us statically link the `sqlite-vec` extension into the same `.so`.

## SQLCipher (core) — `external/sqlcipher/`

- **Upstream:** https://github.com/sqlcipher/sqlcipher
- **Pinned version:** `v4.10.0`
- **Pinned commit:** `d41a25f448ba08ce24c0a599cf322046bdaa135a`
- **Tracks SQLite version:** 3.50.4
- **License:** 3-Clause BSD-style (Zetetic LLC)
- **License text:** `external/sqlcipher/LICENSE.md`
- **Copyright:** © 2025 Zetetic LLC
- **Why vendored:** `sqlcipher-android` does not ship the `sqlite3.c`
  amalgamation; it must be generated from this repository via
  `./configure --enable-tempstore=yes && make sqlite3.c`. The generated
  amalgamation is then copied into
  `external/sqlcipher-android/sqlcipher/src/main/jni/sqlcipher/sqlite3.c`
  during the Phase 1 build (see `VectorDBImplementationPlan.md`).

## sqlite-vec — `external/sqlite-vec/`

- **Upstream:** https://github.com/asg017/sqlite-vec
- **Pinned version:** `v0.1.9`
- **Pinned commit:** `e9f598abfa0c06b328d8fe5da9c3760cce74be10`
- **License:** Dual MIT / Apache-2.0 (pick either)
- **License texts:** `external/sqlite-vec/LICENSE-MIT`, `external/sqlite-vec/LICENSE-APACHE`
- **Copyright:** © 2024 Alex Garcia
- **Why vendored:** Provides the `vec0` SQLite virtual table used for
  similarity search. Pure-C, single translation unit (`sqlite-vec.c`),
  statically linked into `libsqlcipher.so` via `sqlite3_auto_extension`.

---

## LibTomCrypt — `external/libtomcrypt/`

- **Upstream:** https://github.com/libtom/libtomcrypt
- **Pinned version:** `v1.18.2`
- **Pinned commit:** `7e7eb695d581782f04b24dc444cbfde86af59853`
- **License:** Dual licensed — Public Domain / WTFPL (pick either)
- **License text:** `external/libtomcrypt/LICENSE`
- **Copyright:** © Tom St Denis
- **Why vendored:** Crypto provider for `libsqlcipher.so`, selected for the
  spike because it is pure-C (no external binary dependencies), builds cleanly
  with the Android NDK, and was historically the default crypto provider for
  `sqlcipher-android`. We patch `external/sqlcipher-android/sqlcipher/src/main/jni/sqlcipher/Android.mk`
  to set `-DSQLCIPHER_CRYPTO_LIBTOMCRYPT` instead of the repo-default
  `-DSQLCIPHER_CRYPTO_OPENSSL`. LibTomCrypt is built with `-DLTC_NO_MATH` so
  we do not need LibTomMath (only symmetric crypto + PRNG are needed by
  SQLCipher).

---

## How to regenerate license summaries

If any submodule is bumped, update the "Pinned version" and "Pinned commit"
fields in the relevant section above. Run `git submodule status` to read the
authoritative commit SHA for each.
