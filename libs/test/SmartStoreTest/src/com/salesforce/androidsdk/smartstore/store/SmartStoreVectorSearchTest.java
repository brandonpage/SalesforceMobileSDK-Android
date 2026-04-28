/*
 * Copyright (c) 2026-present, salesforce.com, inc.
 * All rights reserved.
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * - Neither the name of salesforce.com, inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission of salesforce.com, inc.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.androidsdk.smartstore.store;

import android.database.Cursor;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.salesforce.androidsdk.smartstore.store.SmartStore.SmartStoreException;
import com.salesforce.androidsdk.smartstore.store.SmartStore.Type;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Vector DB spike — end-to-end instrumentation tests for the full
 * vec0-backed k-NN pipeline in SmartStore.
 *
 * <p>Structure:
 * <ul>
 *     <li>§A: phase 1.1e smoke — {@code vec_version()}, {@code PRAGMA module_list},
 *         standalone vec0 virtual table round-trip. Runs on the same
 *         SQLCipher-owned DB the rest of the tests use, so it proves the vec
 *         extension is present on both unencrypted and encrypted DBs (see the
 *         {@link SmartStoreVectorSearchEncryptedTest} subclass).</li>
 *     <li>§B: {@code registerSoup} wiring — virtual table created,
 *         {@code soup_index_map.indexMeta} populated, {@code hasVector} cache
 *         set, rehydrated specs keep their {@link VectorMeta}.</li>
 *     <li>§C: CRUD cascade — {@code create}/{@code update}/{@code delete}/
 *         {@code deleteByQuery}/{@code clearSoup}/{@code dropSoup} all
 *         correctly propagate to the sibling vec0 table.</li>
 *     <li>§D: nearest-neighbor correctness — known-vector fixtures, both
 *         {@link QuerySpec#buildVectorMatchQuerySpec} and the
 *         {@link SmartStoreVectorSearch#vectorSearch} entry.</li>
 *     <li>§E: negative paths — dim mismatch, non-array value, wrong type.</li>
 * </ul>
 *
 * <p>Intentionally kept in one file to minimise test-class
 * setUp/tearDown overhead and because every test needs the same soup
 * fixture. The perf smoke is split into the companion
 * {@link SmartStoreVectorSearchPerfTest} so it can be filtered via
 * {@link LargeTest}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class SmartStoreVectorSearchTest extends SmartStoreTestCase {

    private static final String TAG = "VectorSearchTest";

    protected static final String ARTICLES_SOUP = "articles";
    protected static final String TABLE_NAME    = "TABLE_1";
    protected static final String VEC_TABLE     = TABLE_NAME + "_1_vec"; // second idx on the soup

    protected static final String TITLE_PATH     = "title";
    protected static final String EMBEDDING_PATH = "embedding";

    // 3-D unit-ish vectors chosen so that {L2 nearest to qX} is deterministic.
    private static final float[] V_POS_X      = { 1.0f,  0.0f,  0.0f };
    private static final float[] V_POS_Y      = { 0.0f,  1.0f,  0.0f };
    private static final float[] V_POS_Z      = { 0.0f,  0.0f,  1.0f };
    private static final float[] V_NEG_X      = {-1.0f,  0.0f,  0.0f };
    private static final float[] Q_NEAR_POS_X = { 0.9f,  0.1f,  0.0f };

    protected static final int VECTOR_DIM = 3;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Unencrypted by default; {@link SmartStoreVectorSearchEncryptedTest}
     * overrides this to force SQLCipher encryption.
     */
    @Override
    protected String getEncryptionKey() {
        return "";
    }

    // ------------------------------------------------------------------
    // §A. Phase 1.1e smoke — the vec extension is available on this DB.
    // ------------------------------------------------------------------

    /**
     * {@code SELECT vec_version()} should return a non-empty string (upstream
     * emits {@code "v0.1.9"} for the pinned version; we don't assert the
     * exact value to avoid churn on bumps).
     */
    @Test
    public void testVecVersionAvailable() {
        String version = stringValue("SELECT vec_version()");
        Assert.assertNotNull("vec_version() returned null — sqlite-vec not linked?", version);
        Assert.assertFalse("vec_version() returned empty string", version.isEmpty());
        Log.i(TAG, "sqlite-vec version: " + version);
    }

    /**
     * {@code PRAGMA module_list} should include {@code vec0}; the
     * auto-registration path (in-tree loader) ran at DB open.
     */
    @Test
    public void testVec0ModuleRegistered() {
        boolean found = false;
        Cursor c = null;
        try {
            c = getDatabase().rawQuery("PRAGMA module_list", null);
            while (c.moveToNext()) {
                if ("vec0".equals(c.getString(0))) {
                    found = true;
                    break;
                }
            }
        } finally {
            safeClose(c);
        }
        Assert.assertTrue("vec0 module not registered — check sqlite-vec link step", found);
    }

    /**
     * Round-trip a tiny vec0 virtual table without any SmartStore soup
     * machinery — isolates the native layer from the SmartStore API layer
     * so a native regression is easy to spot.
     */
    @Test
    public void testStandaloneVec0RoundTrip() {
        SQLiteDatabase db = getDatabase();
        db.execSQL("DROP TABLE IF EXISTS vec_smoke");
        db.execSQL("CREATE VIRTUAL TABLE vec_smoke USING vec0(embedding float[3] distance_metric=l2)");
        try {
            db.execSQL("INSERT INTO vec_smoke(rowid, embedding) VALUES (1, '[1.0, 0.0, 0.0]')");
            db.execSQL("INSERT INTO vec_smoke(rowid, embedding) VALUES (2, '[0.0, 1.0, 0.0]')");
            db.execSQL("INSERT INTO vec_smoke(rowid, embedding) VALUES (3, '[0.0, 0.0, 1.0]')");

            Cursor c = null;
            try {
                c = db.rawQuery(
                        "SELECT rowid FROM vec_smoke " +
                                "WHERE embedding MATCH vec_f32('[0.95, 0.05, 0.0]') AND k = 1",
                        null);
                Assert.assertTrue("Expected 1 row", c.moveToNext());
                Assert.assertEquals("nearest rowid should be 1 (along +X)", 1, c.getLong(0));
            } finally {
                safeClose(c);
            }
        } finally {
            db.execSQL("DROP TABLE vec_smoke");
        }
    }

    // ------------------------------------------------------------------
    // §B. registerSoup wiring.
    // ------------------------------------------------------------------

    @Test
    public void testRegisterSoupCreatesVec0VirtualTable() throws JSONException {
        registerArticlesSoup();

        Assert.assertTrue("Main soup table should exist", hasTable(TABLE_NAME));
        Assert.assertTrue("Vec0 virtual table should exist", hasTable(VEC_TABLE));

        // The CREATE statement stored in sqlite_master should use vec0 module.
        checkCreateTableStatement(VEC_TABLE,
                "CREATE VIRTUAL TABLE " + VEC_TABLE + " USING vec0(embedding float[" + VECTOR_DIM + "]");
    }

    @Test
    public void testRegisterSoupStoresIndexMetaJson() {
        registerArticlesSoup();

        Cursor c = null;
        try {
            SQLiteDatabase db = getDatabase();
            c = db.rawQuery(
                    "SELECT indexMeta FROM " + SmartStore.SOUP_INDEX_MAP_TABLE +
                            " WHERE soupName = ? AND path = ?",
                    new String[] { ARTICLES_SOUP, EMBEDDING_PATH });
            Assert.assertTrue("Expected a row for the vector index", c.moveToFirst());
            String indexMetaJson = c.getString(0);
            Assert.assertNotNull("indexMeta should be populated for a vector index", indexMetaJson);

            JSONObject meta = new JSONObject(indexMetaJson);
            Assert.assertEquals(VECTOR_DIM, meta.getInt("dimension"));
            Assert.assertEquals("L2", meta.getString("metric"));
            Assert.assertEquals("FLOAT32", meta.getString("kind"));
        } catch (JSONException e) {
            Assert.fail("Failed to parse indexMeta JSON: " + e);
        } finally {
            safeClose(c);
        }
    }

    @Test
    public void testHasVectorCachedAfterRegister() {
        // hasVector, like hasFTS, is only defined for registered soups (it
        // loads indexSpecs from soup_index_map). After registerSoup the cache
        // should be populated and the flag true.
        registerArticlesSoup();
        Assert.assertTrue("hasVector should be true post-register",
                dbHelper.hasVector(getDatabase(), ARTICLES_SOUP));

        // Clearing the memory cache forces the flag to be rehydrated from the
        // indexMeta column — exercises the DBHelper rehydration path.
        dbHelper.clearMemoryCache();
        Assert.assertTrue("hasVector should still be true after cache clear",
                dbHelper.hasVector(getDatabase(), ARTICLES_SOUP));
    }

    @Test
    public void testIndexSpecsRehydrateVectorMeta() {
        registerArticlesSoup();
        // Drop the cache so we force a re-read from soup_index_map.
        dbHelper.clearMemoryCache();

        IndexSpec[] specs = store.getSoupIndexSpecs(ARTICLES_SOUP);
        IndexSpec vecSpec = findSpec(specs, EMBEDDING_PATH);
        Assert.assertEquals(Type.vector, vecSpec.type);
        Assert.assertNotNull("VectorMeta should rehydrate from indexMeta column", vecSpec.vectorMeta);
        Assert.assertEquals(VECTOR_DIM, vecSpec.vectorMeta.getDimension());
        Assert.assertEquals(DistanceMetric.L2, vecSpec.vectorMeta.getMetric());
        Assert.assertEquals(VectorKind.FLOAT32, vecSpec.vectorMeta.getKind());
    }

    // ------------------------------------------------------------------
    // §C. CRUD cascade.
    // ------------------------------------------------------------------

    @Test
    public void testCreateCascadesToVec0() throws JSONException {
        registerArticlesSoup();
        long id = createArticle("alpha", V_POS_X);
        assertVecRowExists(id);
    }

    @Test
    public void testCreateWithoutEmbeddingSkipsVec0() throws JSONException {
        registerArticlesSoup();
        JSONObject elt = new JSONObject();
        elt.put(TITLE_PATH, "no-embedding");
        store.create(ARTICLES_SOUP, elt);
        long id = elt.getLong(SmartStore.SOUP_ENTRY_ID);

        // Main row should exist …
        Assert.assertNotNull(store.retrieve(ARTICLES_SOUP, id));
        // … but no cascade into vec0.
        assertVecRowAbsent(id);
    }

    @Test
    public void testUpdateRewritesVec0Row() throws JSONException {
        registerArticlesSoup();
        long id = createArticle("alpha", V_POS_X);

        // Update to a different embedding along +Y.
        JSONObject updated = new JSONObject();
        updated.put(TITLE_PATH, "alpha");
        updated.put(EMBEDDING_PATH, floatsToJSONArray(V_POS_Y));
        store.update(ARTICLES_SOUP, updated, id);

        // Search along +Y should now be nearest to our row.
        long nearest = firstHitId(new float[] { 0.05f, 0.95f, 0.0f }, 1);
        Assert.assertEquals("row should be nearest to +Y after update", id, nearest);
    }

    @Test
    public void testUpdateToNullEmbeddingRemovesVec0Row() throws JSONException {
        registerArticlesSoup();
        long id = createArticle("alpha", V_POS_X);
        assertVecRowExists(id);

        JSONObject updated = new JSONObject();
        updated.put(TITLE_PATH, "alpha");
        // No embedding field → null projection → vec0 row dropped.
        store.update(ARTICLES_SOUP, updated, id);

        assertVecRowAbsent(id);
    }

    @Test
    public void testDeleteCascadesToVec0() throws JSONException {
        registerArticlesSoup();
        long id = createArticle("alpha", V_POS_X);
        assertVecRowExists(id);

        store.delete(ARTICLES_SOUP, id);
        assertVecRowAbsent(id);
    }

    @Test
    public void testDeleteBatchCascadesToVec0() throws JSONException {
        registerArticlesSoup();
        long id1 = createArticle("alpha", V_POS_X);
        long id2 = createArticle("beta",  V_POS_Y);
        long id3 = createArticle("gamma", V_POS_Z);

        store.delete(ARTICLES_SOUP, id1, id3);
        assertVecRowAbsent(id1);
        assertVecRowExists(id2);
        assertVecRowAbsent(id3);
    }

    @Test
    public void testDeleteByQueryCascadesToVec0() throws JSONException {
        registerArticlesSoup();
        long id1 = createArticle("alpha", V_POS_X);
        long id2 = createArticle("alpha", V_POS_Y);
        long id3 = createArticle("beta",  V_POS_Z);

        QuerySpec qs = QuerySpec.buildExactQuerySpec(
                ARTICLES_SOUP, TITLE_PATH, "alpha", null, null, 10);
        store.deleteByQuery(ARTICLES_SOUP, qs);

        assertVecRowAbsent(id1);
        assertVecRowAbsent(id2);
        assertVecRowExists(id3);
    }

    @Test
    public void testClearSoupEmptiesVec0() throws JSONException {
        registerArticlesSoup();
        createArticle("alpha", V_POS_X);
        createArticle("beta",  V_POS_Y);
        Assert.assertEquals("Expected 2 vec rows before clear", 2, vecRowCount());

        store.clearSoup(ARTICLES_SOUP);
        Assert.assertEquals("vec0 table should be empty after clearSoup", 0, vecRowCount());
        Assert.assertTrue("vec0 table should still exist after clearSoup", hasTable(VEC_TABLE));
    }

    @Test
    public void testDropSoupDropsVec0Table() throws JSONException {
        registerArticlesSoup();
        createArticle("alpha", V_POS_X);
        Assert.assertTrue(hasTable(VEC_TABLE));

        store.dropSoup(ARTICLES_SOUP);
        Assert.assertFalse("vec0 virtual table should be dropped with soup", hasTable(VEC_TABLE));
        Assert.assertFalse("Main soup table should be dropped", hasTable(TABLE_NAME));
    }

    // ------------------------------------------------------------------
    // §D. Nearest-neighbor correctness.
    // ------------------------------------------------------------------

    @Test
    public void testVectorMatchReturnsKNearest() throws JSONException {
        registerArticlesSoup();
        long posX = createArticle("pos_x",  V_POS_X);
        long posY = createArticle("pos_y",  V_POS_Y);
        long posZ = createArticle("pos_z",  V_POS_Z);
        long negX = createArticle("neg_x",  V_NEG_X);

        QuerySpec qs = QuerySpec.buildVectorMatchQuerySpec(
                ARTICLES_SOUP, EMBEDDING_PATH, Q_NEAR_POS_X, /* k */ 2, /* pageSize */ 2);
        JSONArray rows = store.query(qs, 0);

        Assert.assertEquals("Expected exactly k=2 hits", 2, rows.length());
        // With selectPaths=null, each row is the whole soup as a JSONObject.
        long firstId = soupEntryIdOf(rows.getJSONObject(0));
        Assert.assertEquals("Nearest should be pos_x", posX, firstId);

        // Second hit must be one of the orthogonal axes (not -X, which is farther).
        long secondId = soupEntryIdOf(rows.getJSONObject(1));
        Assert.assertTrue("Second hit should be pos_y or pos_z; got=" + secondId,
                secondId == posY || secondId == posZ);
        Assert.assertNotEquals("neg_x is farthest, must NOT be in top-2", negX, secondId);
    }

    @Test
    public void testVectorSearchKotlinEntryPoint() throws JSONException {
        registerArticlesSoup();
        long posX = createArticle("pos_x", V_POS_X);
        createArticle("pos_y", V_POS_Y);

        JSONArray rows = SmartStoreVectorSearch.vectorSearch(
                store, ARTICLES_SOUP, EMBEDDING_PATH, Q_NEAR_POS_X,
                /* k */ 1,
                /* selectPaths */ null,
                /* pageSize */ 1,
                /* pageIndex */ 0);
        Assert.assertEquals(1, rows.length());
        // selectPaths=null → each row is the whole soup as a JSONObject.
        Assert.assertEquals(posX, soupEntryIdOf(rows.getJSONObject(0)));
    }

    @Test
    public void testVectorSearchWithSelectPathsProjectsRequested() throws JSONException {
        registerArticlesSoup();
        createArticle("pos_x", V_POS_X);

        QuerySpec qs = QuerySpec.buildVectorMatchQuerySpec(
                ARTICLES_SOUP, new String[] { TITLE_PATH },
                EMBEDDING_PATH, Q_NEAR_POS_X, 1, null, QuerySpec.Order.ascending, 1);
        JSONArray rows = store.query(qs, 0);
        Assert.assertEquals(1, rows.length());
        // Only one projected column → inner array is length 1 with the title.
        JSONArray projected = rows.getJSONArray(0);
        Assert.assertEquals(1, projected.length());
        Assert.assertEquals("pos_x", projected.getString(0));
    }

    // ------------------------------------------------------------------
    // §E. Negative paths.
    // ------------------------------------------------------------------

    @Test
    public void testInsertWithDimMismatchThrows() throws JSONException {
        registerArticlesSoup();
        JSONObject elt = new JSONObject();
        elt.put(TITLE_PATH, "bad");
        // VECTOR_DIM = 3, feeding 4 components.
        elt.put(EMBEDDING_PATH, floatsToJSONArray(new float[] { 0.1f, 0.2f, 0.3f, 0.4f }));
        try {
            store.create(ARTICLES_SOUP, elt);
            Assert.fail("Expected SmartStoreException on dim mismatch");
        } catch (SmartStoreException expected) {
            Assert.assertTrue("Message should call out dim mismatch: " + expected.getMessage(),
                    expected.getMessage().contains("VectorMeta expects"));
        }
    }

    @Test
    public void testInsertWithNonArrayEmbeddingThrows() throws JSONException {
        registerArticlesSoup();
        JSONObject elt = new JSONObject();
        elt.put(TITLE_PATH, "bad");
        elt.put(EMBEDDING_PATH, "not-an-array");
        try {
            store.create(ARTICLES_SOUP, elt);
            Assert.fail("Expected SmartStoreException for non-array embedding value");
        } catch (SmartStoreException expected) {
            Assert.assertTrue("Message should call out JSON array: " + expected.getMessage(),
                    expected.getMessage().contains("must resolve to a JSON array"));
        }
    }

    @Test
    public void testVectorMatchOnNonVectorPathThrows() {
        registerArticlesSoup();
        // title is a string index; requesting it as a vector path should bubble
        // a SmartSqlException out of SmartSqlHelper once the sql is converted.
        QuerySpec qs = QuerySpec.buildVectorMatchQuerySpec(
                ARTICLES_SOUP, TITLE_PATH, new float[] { 1f, 2f, 3f }, 1, 1);
        try {
            store.query(qs, 0);
            Assert.fail("Expected SmartSqlException for vector_match on non-vector path");
        } catch (SmartSqlHelper.SmartSqlException expected) {
            Assert.assertTrue(expected.getMessage().contains("not a vector index"));
        } catch (JSONException e) {
            Assert.fail("Unexpected JSON exception: " + e);
        }
    }

    @Test
    public void testVectorMatchOnUnknownPathThrows() {
        registerArticlesSoup();
        QuerySpec qs = QuerySpec.buildVectorMatchQuerySpec(
                ARTICLES_SOUP, "nope", new float[] { 1f, 2f, 3f }, 1, 1);
        try {
            store.query(qs, 0);
            Assert.fail("Expected SmartSqlException for unknown path");
        } catch (SmartSqlHelper.SmartSqlException expected) {
            Assert.assertTrue(expected.getMessage().contains("has no index"));
        } catch (JSONException e) {
            Assert.fail("Unexpected JSON exception: " + e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Registers a soup with one {@code string} index on {@code title} and one
     * {@code vector} index on {@code embedding} (3-D, L2, FLOAT32). The vec0
     * table ends up as {@link #TABLE_NAME}{@code _1_vec} because {@code title}
     * is the 0th spec (string → main-table column) and {@code embedding} is
     * the 1st (vector → sibling vec0 table).
     */
    protected void registerArticlesSoup() {
        registerSoup(store, ARTICLES_SOUP, new IndexSpec[] {
                new IndexSpec(TITLE_PATH, Type.string),
                IndexSpec.forVector(EMBEDDING_PATH, new VectorMeta(VECTOR_DIM)),
        });
    }

    /** Insert a single {title, embedding} row and return the new soupEntryId. */
    protected long createArticle(String title, float[] embedding) throws JSONException {
        JSONObject elt = new JSONObject();
        elt.put(TITLE_PATH, title);
        elt.put(EMBEDDING_PATH, floatsToJSONArray(embedding));
        store.create(ARTICLES_SOUP, elt);
        return elt.getLong(SmartStore.SOUP_ENTRY_ID);
    }

    /** Run a one-shot vector search and return the soupEntryId of hit #0.
     * Assumes the default selectPaths=null projection, under which SmartStore
     * query returns a JSONArray of JSONObjects (each element is the whole soup).
     */
    private long firstHitId(float[] query, int k) throws JSONException {
        QuerySpec qs = QuerySpec.buildVectorMatchQuerySpec(
                ARTICLES_SOUP, EMBEDDING_PATH, query, k, k);
        JSONArray rows = store.query(qs, 0);
        Assert.assertTrue("Expected at least one hit", rows.length() > 0);
        return soupEntryIdOf(rows.getJSONObject(0));
    }

    /** Extract {@code _soupEntryId} from a row that carries the whole soup. */
    private static long soupEntryIdOf(JSONObject soupElt) throws JSONException {
        return soupElt.getLong(SmartStore.SOUP_ENTRY_ID);
    }

    protected static JSONArray floatsToJSONArray(float[] v) throws JSONException {
        JSONArray arr = new JSONArray();
        for (float x : v) {
            // JSONArray#put(double) can throw on NaN / Infinity; surface that
            // rather than silently dropping an entry.
            arr.put((double) x);
        }
        return arr;
    }

    /** @return current row count in the sibling vec0 table. */
    protected int vecRowCount() {
        return intValue("SELECT COUNT(*) FROM " + VEC_TABLE);
    }

    protected void assertVecRowExists(long rowid) {
        Assert.assertEquals("Expected vec0 row for rowid=" + rowid, 1,
                intValue("SELECT COUNT(*) FROM " + VEC_TABLE + " WHERE rowid = " + rowid));
    }

    protected void assertVecRowAbsent(long rowid) {
        Assert.assertEquals("Expected no vec0 row for rowid=" + rowid, 0,
                intValue("SELECT COUNT(*) FROM " + VEC_TABLE + " WHERE rowid = " + rowid));
    }

    protected int intValue(String sql) {
        Cursor c = null;
        try {
            c = getDatabase().rawQuery(sql, null);
            Assert.assertTrue("Expected one row for: " + sql, c.moveToFirst());
            return c.getInt(0);
        } finally {
            safeClose(c);
        }
    }

    protected String stringValue(String sql) {
        Cursor c = null;
        try {
            c = getDatabase().rawQuery(sql, null);
            Assert.assertTrue("Expected one row for: " + sql, c.moveToFirst());
            return c.getString(0);
        } finally {
            safeClose(c);
        }
    }

    protected SQLiteDatabase getDatabase() {
        return dbOpenHelper.getWritableDatabase();
    }

    private static IndexSpec findSpec(IndexSpec[] specs, String path) {
        for (IndexSpec spec : specs) {
            if (spec.path.equals(path)) return spec;
        }
        throw new AssertionError("No IndexSpec with path=" + path);
    }
}
