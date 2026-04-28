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

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

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

import java.util.Arrays;
import java.util.Random;

/**
 * Vector DB spike — perf smoke. Populates a soup with a mid-size corpus
 * (default 1,000 rows of 384-D FLOAT32 embeddings, encrypted SQLCipher) and
 * measures:
 *
 * <ul>
 *     <li>Insert throughput (rows/sec) over the whole batch (single
 *         transaction, to isolate vec0 cost from transaction overhead).</li>
 *     <li>k-NN search latency at {@code k=10}, p50 / p95 over
 *         {@link #SEARCH_SAMPLES} random queries.</li>
 * </ul>
 *
 * <p>Marked {@link LargeTest} and annotated with {@code @Test} so it runs
 * via the standard instrumentation runner; numbers show up in logcat
 * under the {@value #TAG} tag and are attached to the plan's Phase 4
 * Changelog entry. Dial {@link #CORPUS_SIZE} via a system property if you
 * need to push harder.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SmartStoreVectorSearchPerfTest extends SmartStoreTestCase {

    private static final String TAG = "VectorPerfTest";

    private static final String SOUP          = "articles";
    private static final String EMBEDDING     = "embedding";
    private static final String TITLE         = "title";

    /** Keep this reasonable — instrumentation runner times out around 10 minutes. */
    private static final int CORPUS_SIZE   = propOr("smartstore.vec.perf.corpus", 1_000);
    private static final int VECTOR_DIM    = propOr("smartstore.vec.perf.dim",    384);
    private static final int SEARCH_SAMPLES = propOr("smartstore.vec.perf.samples", 50);
    private static final int K             = 10;
    private static final long SEED         = 0xC0FFEEL;

    // Persistent seed means same corpus every run → comparable numbers.
    private final Random rng = new Random(SEED);

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
     * Encrypted by default — we care about perf under the same SQLCipher
     * constraints as a real Mobile SDK app.
     */
    @Override
    protected String getEncryptionKey() {
        return "smartstore-vec-perf-key";
    }

    @Test
    public void perfInsertAndSearch() throws JSONException {
        registerSoup(store, SOUP, new IndexSpec[] {
                new IndexSpec(TITLE, Type.string),
                IndexSpec.forVector(EMBEDDING, new VectorMeta(VECTOR_DIM)),
        });

        // Pre-generate corpus embeddings so the insert loop measures write
        // throughput, not RNG cost.
        float[][] corpus = new float[CORPUS_SIZE][];
        for (int i = 0; i < CORPUS_SIZE; i++) {
            corpus[i] = randomUnitVector(VECTOR_DIM);
        }

        long insertStart = System.nanoTime();
        SQLiteDatabase db = dbOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < CORPUS_SIZE; i++) {
                JSONObject elt = new JSONObject();
                elt.put(TITLE, "article-" + i);
                elt.put(EMBEDDING, floatsToJSONArray(corpus[i]));
                // Pass handleTx=false so each create doesn't open its own tx.
                store.create(SOUP, elt, false);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        long insertElapsedNs = System.nanoTime() - insertStart;

        // Verify the corpus landed correctly before measuring search.
        Assert.assertEquals(CORPUS_SIZE, store.countQuery(
                QuerySpec.buildAllQuerySpec(SOUP, null, null, 1)));

        // Pre-generate queries as well.
        float[][] queries = new float[SEARCH_SAMPLES][];
        for (int i = 0; i < SEARCH_SAMPLES; i++) {
            queries[i] = randomUnitVector(VECTOR_DIM);
        }

        long[] latencyNs = new long[SEARCH_SAMPLES];
        for (int i = 0; i < SEARCH_SAMPLES; i++) {
            long start = System.nanoTime();
            JSONArray hits = store.query(
                    QuerySpec.buildVectorMatchQuerySpec(SOUP, EMBEDDING, queries[i], K, K),
                    0);
            latencyNs[i] = System.nanoTime() - start;
            Assert.assertEquals("Expected k=" + K + " hits", K, hits.length());
        }

        long[] sorted = latencyNs.clone();
        Arrays.sort(sorted);
        double insertThroughput = CORPUS_SIZE / (insertElapsedNs / 1e9);
        double p50Ms = sorted[sorted.length / 2] / 1e6;
        double p95Ms = sorted[(int) (sorted.length * 0.95)] / 1e6;
        double avgMs = mean(latencyNs) / 1e6;

        Log.i(TAG, String.format(
                "[perf] corpus=%d dim=%d encrypted=%s k=%d samples=%d",
                CORPUS_SIZE, VECTOR_DIM, !getEncryptionKey().isEmpty(), K, SEARCH_SAMPLES));
        Log.i(TAG, String.format(
                "[perf] insert: %d rows in %.2f ms (%.1f rows/s)",
                CORPUS_SIZE, insertElapsedNs / 1e6, insertThroughput));
        Log.i(TAG, String.format(
                "[perf] search: p50=%.2f ms  p95=%.2f ms  avg=%.2f ms",
                p50Ms, p95Ms, avgMs));
    }

    // --------------------------------------------------------------------

    private float[] randomUnitVector(int dim) {
        float[] v = new float[dim];
        double sumSq = 0.0;
        for (int i = 0; i < dim; i++) {
            v[i] = (float) rng.nextGaussian();
            sumSq += v[i] * v[i];
        }
        float norm = (float) Math.sqrt(sumSq);
        if (norm == 0f) norm = 1f;
        for (int i = 0; i < dim; i++) {
            v[i] /= norm;
        }
        return v;
    }

    private static JSONArray floatsToJSONArray(float[] v) throws JSONException {
        JSONArray arr = new JSONArray();
        for (float x : v) {
            arr.put((double) x);
        }
        return arr;
    }

    private static double mean(long[] xs) {
        double sum = 0.0;
        for (long x : xs) sum += x;
        return sum / xs.length;
    }

    /**
     * Look up a tunable via {@code System.getProperty} so CI or developers
     * can dial the corpus without recompiling. Instrumentation arguments go
     * through {@code -P} on the gradle cli.
     */
    private static int propOr(String key, int defaultValue) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
