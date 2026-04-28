/*
 * Copyright (c) 2026-present, salesforce.com, inc.
 * All rights reserved.
 *
 * Vector DB spike Phase 4: on-device RAG sample.
 */
package com.salesforce.samples.restexplorer.rag

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper around MediaPipe's [TextEmbedder] task. The wrapped
 * model file is resolved through [RagModelPaths] and can be swapped at
 * any time by replacing the file on disk \u2014 the wrapper owns no state
 * beyond the native handle.
 *
 * The wrapper is single-threaded by construction: MediaPipe's
 * `TextEmbedder` is safe to call from one background thread at a time,
 * and every method here simply forwards. Callers are responsible for
 * keeping calls serialised on a worker thread (the demo UI does this
 * via a single-thread executor).
 *
 * On close, the native graph is released. The wrapper implements
 * [Closeable] so it can be used in try-with-resources / `use {}`.
 */
class RagEmbedder private constructor(
    private val embedder: TextEmbedder,
    /** Byte length of the model file at load time \u2014 diagnostics only. */
    val modelSize: Long,
    /** Absolute path the graph was loaded from. */
    val modelPath: String
) : Closeable {

    /**
     * Embed [text] into a dense float vector. Returns the raw embedding
     * exactly as MediaPipe computes it; L2-normalisation is requested at
     * build time so the downstream cosine-distance metric produces
     * sensible scores.
     *
     * The vector length is determined by the underlying model and is
     * stable for the lifetime of this instance; callers can query it via
     * [dimension] after the first call or by calling [embed] on an empty
     * probe string.
     */
    fun embed(text: String): FloatArray {
        val result = embedder.embed(text)
        val embeddings = result.embeddingResult().embeddings()
        require(embeddings.isNotEmpty()) {
            "TextEmbedder returned an empty embedding list for input of length ${text.length}"
        }
        return embeddings[0].floatEmbedding()
    }

    /**
     * Number of floats in vectors produced by this embedder. Probes the
     * graph once with an empty string so callers don't have to keep an
     * extra field in sync. Result is cached after the first call.
     */
    val dimension: Int by lazy { embed("").size }

    override fun close() {
        embedder.close()
    }

    companion object {
        /**
         * Load the embedder from [RagModelPaths.embedderPath]. Returns
         * null if the file is missing or MediaPipe cannot open it; the
         * caller is expected to surface a friendly message in that case
         * (see [RagModelPaths.describe]).
         */
        @JvmStatic
        fun tryCreate(context: Context): RagEmbedder? {
            val file: File = RagModelPaths.embedderPath(context)
            if (!file.isFile || file.length() <= 0L) return null
            return try {
                val options = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder().setModelAssetPath(file.absolutePath).build()
                    )
                    // Normalise so cosine distance in the vec0 index is
                    // just "inner product" and the scores are comparable
                    // across models.
                    .setL2Normalize(true)
                    .build()
                val embedder = TextEmbedder.createFromOptions(context, options)
                RagEmbedder(embedder, file.length(), file.absolutePath)
            } catch (t: Throwable) {
                android.util.Log.e(
                    "RagEmbedder",
                    "Failed to load TextEmbedder from ${file.absolutePath}",
                    t
                )
                null
            }
        }
    }
}
