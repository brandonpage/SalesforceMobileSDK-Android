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
@file:JvmName("SmartStoreVectorSearch")

package com.salesforce.androidsdk.smartstore.store

import org.json.JSONArray

/**
 * Vector DB spike — public top-level API for k-NN search over a soup that has
 * at least one [SmartStore.Type.vector] index.
 *
 * Kotlin callers can use the extension form:
 * ```kotlin
 * val hits = smartStore.vectorSearch(
 *     soupName   = "articles",
 *     path       = "embedding",
 *     queryVector = queryEmbedding,    // FloatArray
 *     k          = 10,
 * )
 * ```
 *
 * Java callers should use [SmartStoreVectorSearch.vectorSearch]:
 * ```java
 * JSONArray hits = SmartStoreVectorSearch.vectorSearch(
 *     smartStore, "articles", "embedding", queryEmbedding, 10);
 * ```
 *
 * Both paths build a [QuerySpec] with [QuerySpec.QueryType.vector_match] and
 * delegate to the existing [SmartStore.query] pipeline, so pagination,
 * Smart-SQL rewriting, explain-plan capture, and the result JSON shape are
 * identical to any other SmartStore query. Returned records include the
 * selected projections from the main soup table, ordered by vec0 distance
 * (ascending).
 */

/**
 * Kotlin extension on [SmartStore]. When compiled, this also surfaces as a
 * Java-friendly static:
 *
 *     SmartStoreVectorSearch.vectorSearch(smartStore, soupName, path,
 *         queryVector, k, selectPaths, pageSize, pageIndex)
 *
 * thanks to the file's `@file:JvmName("SmartStoreVectorSearch")` annotation
 * and `@JvmOverloads` on the function.
 */
@JvmOverloads
fun SmartStore.vectorSearch(
    soupName: String,
    path: String,
    queryVector: FloatArray,
    k: Int = 10,
    selectPaths: Array<String>? = null,
    pageSize: Int = k,
    pageIndex: Int = 0,
): JSONArray {
    val spec = QuerySpec.buildVectorMatchQuerySpec(
        soupName,
        selectPaths,
        path,
        queryVector,
        k,
        /* orderPath = */ null,
        /* order     = */ QuerySpec.Order.ascending,
        pageSize,
    )
    return query(spec, pageIndex)
}
