/*
 * Copyright (c) 2026-present, salesforce.com, inc.
 * All rights reserved.
 *
 * Vector DB spike Phase 4: on-device RAG sample.
 *
 * Model-file discovery for the RAG demo. Both the embedder (Universal
 * Sentence Encoder) and the generator (Gemma-family LLM `.task` bundle)
 * are too large to bake into the APK, so the sample loads them at runtime
 * from the app's external files dir:
 *
 *     /storage/emulated/0/Android/data/com.salesforce.samples.restexplorer/files/
 *
 * which on a rooted emulator or real device is writable via plain
 * `adb push` \u2014 no permissions dance required.
 */
package com.salesforce.samples.restexplorer.rag

import android.content.Context
import java.io.File

/**
 * Centralised, read-only registry of expected model filenames and helpers
 * for resolving their on-disk paths. Missing files are handled at the
 * call site \u2014 callers should ask [exists] first and render a friendly
 * error message when the user has not yet dropped the model in place.
 */
object RagModelPaths {

    /**
     * Universal Sentence Encoder Lite, distributed as a raw TFLite flat
     * buffer. Wrapped at runtime by [com.google.mediapipe.tasks.text.textembedder.TextEmbedder],
     * which autodetects the graph signature.
     *
     * Canonical download: `universal_sentence_encoder.tflite` from
     * MediaPipe's model card, or any USE variant with comparable I/O
     * signature.
     */
    const val EMBEDDER_FILENAME: String = "universal_sentence_encoder.tflite"

    /**
     * Canonical / preferred filename for the LLM bundle. Used as a hard
     * coded fallback in error messages and when [llmPath] needs to point
     * somewhere stable in the missing-file case.
     *
     * Any file in the external files dir whose extension is in
     * [LLM_EXTENSIONS] is also accepted \u2014 see [findLlmFile]. This means
     * the user can `adb push` whatever name Google / `litert-community`
     * shipped (e.g. `gemma-4-E2B-it.litertlm`, `gemma3n-E2B.task`) without
     * having to rename the multi-GB file.
     */
    const val LLM_FILENAME: String = "llm.task"

    /**
     * File extensions MediaPipe `LlmInference` knows how to load. `.task`
     * is the historical bundle format; `.litertlm` is the newer LiteRT
     * "language model" container Google uses for Gemma 3 / 3n / 4 drops
     * on Hugging Face. Both go through the same loader.
     */
    private val LLM_EXTENSIONS: Set<String> = setOf("task", "litertlm")

    /** Absolute path the embedder would be loaded from. May not exist. */
    fun embedderPath(context: Context): File =
        File(context.getExternalFilesDir(null), EMBEDDER_FILENAME)

    /**
     * Absolute path the LLM would be loaded from. Resolves the first
     * `.task` / `.litertlm` file in the external files dir; falls back
     * to the canonical [LLM_FILENAME] location when nothing matches so
     * callers always have a stable path to print in error messages.
     */
    fun llmPath(context: Context): File =
        findLlmFile(context) ?: File(context.getExternalFilesDir(null), LLM_FILENAME)

    /** Convenience: true iff the embedder file is on disk and non-empty. */
    fun embedderAvailable(context: Context): Boolean =
        embedderPath(context).let { it.isFile && it.length() > 0L }

    /** Convenience: true iff a usable LLM bundle is on disk and non-empty. */
    fun llmAvailable(context: Context): Boolean =
        findLlmFile(context) != null

    /**
     * Scan the external files dir for the first non-empty LLM bundle.
     * Prefers exactly [LLM_FILENAME] (so users who follow the README
     * literally get deterministic resolution), then any other file with
     * an extension in [LLM_EXTENSIONS], sorted by name for stability.
     * Returns null when no candidate exists.
     */
    private fun findLlmFile(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val canonical = File(dir, LLM_FILENAME)
        if (canonical.isFile && canonical.length() > 0L) return canonical
        val candidates = dir.listFiles { f ->
            f.isFile && f.length() > 0L &&
                f.extension.lowercase() in LLM_EXTENSIONS
        } ?: return null
        return candidates.sortedBy { it.name }.firstOrNull()
    }

    /**
     * Human-readable status string suitable for dumping into the UI when
     * the user is troubleshooting a missing-model situation. Returns a
     * multi-line string with absolute paths, existence flags, and sizes.
     */
    fun describe(context: Context): String {
        val embedder = embedderPath(context)
        val llm = findLlmFile(context)
        return buildString {
            append("Embedder: ").append(embedder.absolutePath)
            if (embedder.isFile) append("  (").append(embedder.length()).append(" bytes)")
            else append("  (missing)")
            append('\n')
            if (llm != null) {
                append("LLM    : ").append(llm.absolutePath)
                append("  (").append(llm.length()).append(" bytes)")
            } else {
                append("LLM    : <none of *.task / *.litertlm found in ")
                append(embedder.parentFile?.absolutePath ?: "?")
                append(">  (missing, generation disabled)")
            }
        }
    }
}
