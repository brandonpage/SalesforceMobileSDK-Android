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

import com.salesforce.androidsdk.smartstore.store.SmartStore.SmartStoreException
import org.json.JSONObject

/**
 * Vector DB spike. Metadata for a [SmartStore.Type.vector] index: how many
 * components each embedding has, which element type they are stored as, and
 * which distance metric the sibling vec0 virtual table is configured with.
 *
 * Persisted as JSON in the `indexMeta` column of `soup_index_map` (added in
 * `DB_VERSION = 4`). See `VectorDBImplementationPlan.md` §2.2.
 */
data class VectorMeta @JvmOverloads constructor(
    val dimension: Int,
    val metric: DistanceMetric = DistanceMetric.L2,
    val kind: VectorKind = VectorKind.FLOAT32,
) {
    init {
        // vec0 allows 1..65535 components per row. Same guard catches
        // accidental 0-dim specs and obvious overflows.
        if (dimension !in 1..65535) {
            throw SmartStoreException(
                "VectorMeta dimension must be in 1..65535 (was $dimension)"
            )
        }
    }

    /**
     * Column spec fragment for `CREATE VIRTUAL TABLE … USING vec0(…)`.
     * Example: `embedding float[384] distance_metric=l2`.
     */
    fun columnSpec(): String =
        "${SmartStore.EMBEDDING_COL} ${kind.sqliteVec}[$dimension] " +
            "distance_metric=${metric.sqliteVec}"

    /**
     * Serialize to the JSON form stored in `soup_index_map.indexMeta`.
     */
    fun toJSON(): JSONObject = JSONObject()
        .put(KEY_DIMENSION, dimension)
        .put(KEY_METRIC, metric.name)
        .put(KEY_KIND, kind.name)

    companion object {
        const val KEY_DIMENSION = "dimension"
        const val KEY_METRIC = "metric"
        const val KEY_KIND = "kind"

        /**
         * Parse a [VectorMeta] from JSON. Returns `null` if the input is null
         * (used by [IndexSpec.fromJSON] for backwards-compat with pre-v4 rows).
         */
        @JvmStatic
        fun fromJSON(json: JSONObject?): VectorMeta? {
            if (json == null) return null
            return try {
                VectorMeta(
                    dimension = json.getInt(KEY_DIMENSION),
                    metric = DistanceMetric.valueOf(json.getString(KEY_METRIC)),
                    kind = VectorKind.valueOf(json.getString(KEY_KIND)),
                )
            } catch (e: Exception) {
                throw SmartStoreException(
                    "Failed to parse VectorMeta from JSON: $json", e
                )
            }
        }
    }
}

/**
 * Distance metric used by vec0 when ranking MATCH hits. The upstream
 * `distance_metric=` option accepts the lowercase strings in [sqliteVec].
 */
enum class DistanceMetric(val sqliteVec: String) {
    /** Euclidean L2 distance (vec0 default). */
    L2("l2"),

    /** Cosine distance, i.e. `1 - cosine_similarity`. */
    COSINE("cosine"),

    /** Manhattan / L1 distance. */
    L1("l1"),

    /** Hamming distance. Requires [VectorKind.BIT]. */
    HAMMING("hamming"),
}

/**
 * Storage representation of each vector element inside a vec0 row.
 */
enum class VectorKind(val sqliteVec: String) {
    /** 32-bit little-endian IEEE-754 float. vec0 default. */
    FLOAT32("float"),

    /** Signed 8-bit integer. 4× more compact than FLOAT32. */
    INT8("int8"),

    /** 1 bit per component (packed). Used with [DistanceMetric.HAMMING]. */
    BIT("bit"),
}
