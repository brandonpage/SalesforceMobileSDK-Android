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
package com.salesforce.androidsdk.smartstore.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.salesforce.androidsdk.smartstore.store.SmartStore.SmartStoreException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vector DB spike — pure-unit tests for [VectorMeta], [DistanceMetric],
 * and [VectorKind]. No SQLCipher / SmartStore instance required.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class VectorMetaTest {

    // --- columnSpec() -----------------------------------------------------

    @Test
    fun columnSpecDefaultsFloat32AndL2() {
        val meta = VectorMeta(dimension = 384)
        assertEquals("embedding float[384] distance_metric=l2", meta.columnSpec())
    }

    @Test
    fun columnSpecInt8Cosine() {
        val meta = VectorMeta(
            dimension = 768,
            metric = DistanceMetric.COSINE,
            kind = VectorKind.INT8,
        )
        assertEquals("embedding int8[768] distance_metric=cosine", meta.columnSpec())
    }

    @Test
    fun columnSpecBitHamming() {
        // sqlite-vec pairs BIT kind with HAMMING metric (see VectorKind kdoc).
        val meta = VectorMeta(
            dimension = 1024,
            metric = DistanceMetric.HAMMING,
            kind = VectorKind.BIT,
        )
        assertEquals("embedding bit[1024] distance_metric=hamming", meta.columnSpec())
    }

    @Test
    fun columnSpecFloat32L1() {
        val meta = VectorMeta(
            dimension = 32,
            metric = DistanceMetric.L1,
            kind = VectorKind.FLOAT32,
        )
        assertEquals("embedding float[32] distance_metric=l1", meta.columnSpec())
    }

    // --- toJSON / fromJSON round-trip ------------------------------------

    @Test
    fun jsonRoundTripDefaults() {
        val original = VectorMeta(dimension = 16)
        val rebuilt = VectorMeta.fromJSON(original.toJSON())
        assertEquals(original, rebuilt)
    }

    @Test
    fun jsonRoundTripAllExplicit() {
        val original = VectorMeta(
            dimension = 512,
            metric = DistanceMetric.COSINE,
            kind = VectorKind.INT8,
        )
        val rebuilt = VectorMeta.fromJSON(original.toJSON())
        assertEquals(original, rebuilt)
    }

    @Test
    fun jsonRoundTripShape() {
        val json = VectorMeta(
            dimension = 256,
            metric = DistanceMetric.L1,
            kind = VectorKind.FLOAT32,
        ).toJSON()
        assertEquals(256, json.getInt(VectorMeta.KEY_DIMENSION))
        assertEquals("L1", json.getString(VectorMeta.KEY_METRIC))
        assertEquals("FLOAT32", json.getString(VectorMeta.KEY_KIND))
    }

    @Test
    fun fromJsonNullReturnsNull() {
        // Used by IndexSpec.fromJSON for backwards-compat with pre-v4 rows.
        assertNull(VectorMeta.fromJSON(null))
    }

    @Test
    fun fromJsonMalformedThrowsSmartStoreException() {
        val bogus = JSONObject().put(VectorMeta.KEY_DIMENSION, 64)
            .put(VectorMeta.KEY_METRIC, "NOT_A_METRIC")
            .put(VectorMeta.KEY_KIND, "FLOAT32")
        try {
            VectorMeta.fromJSON(bogus)
            fail("Expected SmartStoreException for bad metric name")
        } catch (expected: SmartStoreException) {
            assertTrue(
                "Message should mention failed parse: " + expected.message,
                expected.message!!.contains("VectorMeta"),
            )
        }
    }

    // --- dimension invariants --------------------------------------------

    @Test
    fun dimensionZeroThrows() {
        try {
            VectorMeta(dimension = 0)
            fail("Expected SmartStoreException for dimension = 0")
        } catch (expected: SmartStoreException) {
            assertTrue(expected.message!!.contains("1..65535"))
        }
    }

    @Test
    fun dimensionNegativeThrows() {
        try {
            VectorMeta(dimension = -1)
            fail("Expected SmartStoreException for dimension = -1")
        } catch (expected: SmartStoreException) {
            assertTrue(expected.message!!.contains("1..65535"))
        }
    }

    @Test
    fun dimensionTooLargeThrows() {
        try {
            VectorMeta(dimension = 65536)
            fail("Expected SmartStoreException for dimension = 65536")
        } catch (expected: SmartStoreException) {
            assertTrue(expected.message!!.contains("1..65535"))
        }
    }

    @Test
    fun dimensionMaxAccepted() {
        // vec0 allows up to 65535 components per row.
        assertEquals(65535, VectorMeta(dimension = 65535).dimension)
    }

    // --- data-class semantics --------------------------------------------

    @Test
    fun equalityAndHashCodeHoldForIdentical() {
        val a = VectorMeta(dimension = 128, metric = DistanceMetric.COSINE, kind = VectorKind.INT8)
        val b = VectorMeta(dimension = 128, metric = DistanceMetric.COSINE, kind = VectorKind.INT8)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun differentDimensionsNotEqual() {
        val a = VectorMeta(dimension = 128)
        val b = VectorMeta(dimension = 129)
        assertNotEquals(a, b)
    }

    @Test
    fun differentMetricsNotEqual() {
        val a = VectorMeta(dimension = 128, metric = DistanceMetric.L2)
        val b = VectorMeta(dimension = 128, metric = DistanceMetric.COSINE)
        assertNotEquals(a, b)
    }

    @Test
    fun differentKindsNotEqual() {
        val a = VectorMeta(dimension = 128, kind = VectorKind.FLOAT32)
        val b = VectorMeta(dimension = 128, kind = VectorKind.INT8)
        assertNotEquals(a, b)
    }

    // --- enum sqliteVec strings ------------------------------------------

    @Test
    fun distanceMetricWireStrings() {
        assertEquals("l2", DistanceMetric.L2.sqliteVec)
        assertEquals("cosine", DistanceMetric.COSINE.sqliteVec)
        assertEquals("l1", DistanceMetric.L1.sqliteVec)
        assertEquals("hamming", DistanceMetric.HAMMING.sqliteVec)
    }

    @Test
    fun vectorKindWireStrings() {
        assertEquals("float", VectorKind.FLOAT32.sqliteVec)
        assertEquals("int8", VectorKind.INT8.sqliteVec)
        assertEquals("bit", VectorKind.BIT.sqliteVec)
    }
}
