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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.salesforce.androidsdk.smartstore.store.SmartStore.SmartStoreException;
import com.salesforce.androidsdk.smartstore.store.SmartStore.Type;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Vector DB spike — pure-unit tests for the vector extensions to
 * {@link IndexSpec} ({@link IndexSpec#forVector}, {@link IndexSpec#vectorMeta},
 * JSON round-trip, {@link IndexSpec#hasVector}, and the constructor
 * invariants).
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class IndexSpecVectorTest {

    private static final VectorMeta META_384 = new VectorMeta(384);
    private static final VectorMeta META_768_COSINE_INT8 =
            new VectorMeta(768, DistanceMetric.COSINE, VectorKind.INT8);

    // --- forVector factory ----------------------------------------------

    @Test
    public void forVectorSetsTypeAndVectorMeta() {
        IndexSpec spec = IndexSpec.forVector("embedding", META_384);
        Assert.assertEquals("embedding", spec.path);
        Assert.assertEquals(Type.vector, spec.type);
        Assert.assertNull("columnName should be resolved later by registerSoup", spec.columnName);
        Assert.assertEquals(META_384, spec.vectorMeta);
    }

    @Test
    public void forVectorAcceptsNonDefaultMeta() {
        IndexSpec spec = IndexSpec.forVector("embedding", META_768_COSINE_INT8);
        Assert.assertEquals(META_768_COSINE_INT8, spec.vectorMeta);
        Assert.assertEquals(768, spec.vectorMeta.getDimension());
        Assert.assertEquals(DistanceMetric.COSINE, spec.vectorMeta.getMetric());
        Assert.assertEquals(VectorKind.INT8, spec.vectorMeta.getKind());
    }

    // --- Constructor invariants -----------------------------------------

    @Test
    public void vectorTypeWithoutMetaThrows() {
        try {
            new IndexSpec("embedding", Type.vector);
            Assert.fail("Expected SmartStoreException for Type.vector without VectorMeta");
        } catch (SmartStoreException expected) {
            Assert.assertTrue("Message should mention non-null VectorMeta: " + expected.getMessage(),
                    expected.getMessage().contains("VectorMeta"));
        }
    }

    @Test
    public void vectorTypeWithNullMetaThrowsOnFullCtor() {
        try {
            new IndexSpec("embedding", Type.vector, "COL_1", null);
            Assert.fail("Expected SmartStoreException for Type.vector with null VectorMeta");
        } catch (SmartStoreException expected) {
            Assert.assertTrue(expected.getMessage().contains("non-null VectorMeta"));
        }
    }

    @Test
    public void nonVectorTypeWithMetaThrows() {
        try {
            new IndexSpec("embedding", Type.string, null, META_384);
            Assert.fail("Expected SmartStoreException for Type.string with non-null VectorMeta");
        } catch (SmartStoreException expected) {
            Assert.assertTrue("Message should say non-vector must have null VectorMeta: " + expected.getMessage(),
                    expected.getMessage().contains("must have a null VectorMeta"));
        }
    }

    // --- JSON round-trip ------------------------------------------------

    @Test
    public void jsonRoundTripPreservesVectorMeta() throws JSONException {
        // Mirror the DB usage pattern: by the time an IndexSpec is written to
        // soup_index_map it already has a resolved columnName (the vec0
        // virtual-table name). We don't round-trip a fresh forVector() spec
        // because its null columnName would re-hydrate as "" from
        // JSONObject.optString — an existing latent quirk of IndexSpec.fromJSON,
        // not something the vector spike introduced.
        IndexSpec original = new IndexSpec(
                "embedding", Type.vector, "TABLE_1_1_vec", META_768_COSINE_INT8);
        JSONObject json = original.toJSON();

        Assert.assertEquals("embedding", json.getString("path"));
        Assert.assertEquals("vector", json.getString("type"));
        Assert.assertEquals("TABLE_1_1_vec", json.getString("columnName"));
        Assert.assertTrue("JSON should carry vectorMeta", json.has("vectorMeta"));

        IndexSpec rebuilt = IndexSpec.fromJSON(json);
        Assert.assertEquals(Type.vector, rebuilt.type);
        Assert.assertEquals(META_768_COSINE_INT8, rebuilt.vectorMeta);
        Assert.assertEquals(original, rebuilt);
    }

    @Test
    public void jsonRoundTripOmitsVectorMetaForNonVectorSpec() throws JSONException {
        IndexSpec stringSpec = new IndexSpec("name", Type.string, "COL_1");
        JSONObject json = stringSpec.toJSON();

        Assert.assertFalse("Non-vector IndexSpec should not emit vectorMeta", json.has("vectorMeta"));
        IndexSpec rebuilt = IndexSpec.fromJSON(json);
        Assert.assertNull("Non-vector IndexSpec should not rehydrate vectorMeta", rebuilt.vectorMeta);
        Assert.assertEquals(stringSpec, rebuilt);
    }

    // --- equals / hashCode pick up vectorMeta ---------------------------

    @Test
    public void equalVectorSpecsAreEqual() {
        IndexSpec a = IndexSpec.forVector("embedding", META_384);
        IndexSpec b = IndexSpec.forVector("embedding", new VectorMeta(384));
        Assert.assertEquals(a, b);
        Assert.assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void vectorSpecsWithDifferentDimensionsNotEqual() {
        IndexSpec a = IndexSpec.forVector("embedding", new VectorMeta(384));
        IndexSpec b = IndexSpec.forVector("embedding", new VectorMeta(768));
        Assert.assertNotEquals(a, b);
    }

    @Test
    public void vectorSpecsWithDifferentMetricsNotEqual() {
        IndexSpec a = IndexSpec.forVector("embedding", new VectorMeta(384, DistanceMetric.L2, VectorKind.FLOAT32));
        IndexSpec b = IndexSpec.forVector("embedding", new VectorMeta(384, DistanceMetric.COSINE, VectorKind.FLOAT32));
        Assert.assertNotEquals(a, b);
    }

    // --- hasVector static helper ----------------------------------------

    @Test
    public void hasVectorTrueWhenAnySpecIsVector() {
        IndexSpec[] specs = new IndexSpec[] {
                new IndexSpec("name", Type.string),
                IndexSpec.forVector("embedding", META_384),
                new IndexSpec("year", Type.integer),
        };
        Assert.assertTrue(IndexSpec.hasVector(specs));
    }

    @Test
    public void hasVectorFalseWhenNoSpecIsVector() {
        IndexSpec[] specs = new IndexSpec[] {
                new IndexSpec("name", Type.string),
                new IndexSpec("year", Type.integer),
                new IndexSpec("abstract", Type.full_text),
        };
        Assert.assertFalse(IndexSpec.hasVector(specs));
    }

    @Test
    public void hasVectorFalseForEmptyArray() {
        Assert.assertFalse(IndexSpec.hasVector(new IndexSpec[0]));
    }
}
