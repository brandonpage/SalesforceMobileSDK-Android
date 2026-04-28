/*
 * Copyright (c) 2026-present, salesforce.com, inc.
 * All rights reserved.
 *
 * Vector DB spike Phase 4: on-device RAG sample.
 */
package com.salesforce.samples.restexplorer.rag

import com.salesforce.androidsdk.smartstore.store.DistanceMetric
import com.salesforce.androidsdk.smartstore.store.IndexSpec
import com.salesforce.androidsdk.smartstore.store.QuerySpec
import com.salesforce.androidsdk.smartstore.store.SmartStore
import com.salesforce.androidsdk.smartstore.store.VectorMeta
import com.salesforce.androidsdk.smartstore.store.vectorSearch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full RAG orchestrator: given a [SmartStore], a [RagEmbedder], and an
 * optional [RagLlm], this class wires the three together into three
 * actions the demo UI needs:
 *
 *   1. [ensureSoup]       \u2014 register the soup + vector index if missing.
 *   2. [ingest]           \u2014 embed the sample corpus and upsert every row.
 *   3. [searchWithAnswer] \u2014 embed the query, fetch top-k, build a RAG
 *                          prompt, and (if the LLM is loaded) generate an
 *                          answer. Returns both sides so the UI can show
 *                          retrieval results even when the generator is
 *                          not available.
 *
 * The embedder determines the vector dimension at runtime (see
 * [RagEmbedder.dimension]). We cache the dim on first use so the soup
 * registration happens exactly once per process.
 */
class RagPipeline(
    private val store: SmartStore,
    private val embedder: RagEmbedder,
    private val llm: RagLlm?,
) {

    /**
     * Bundle that travels from [searchWithAnswer] back to the UI.
     *
     * @param hits top-k docs, highest-similarity first.
     * @param prompt the full RAG prompt fed to the LLM, kept for the UI
     *               to display for transparency.
     * @param answer the LLM's response text, or `null` if no LLM was
     *               loaded \u2014 the UI should show a friendly placeholder.
     * @param retrievalMs wall-clock time for embed + vectorSearch.
     * @param generationMs wall-clock time for the LLM call, or 0 when
     *                     the LLM is absent.
     */
    data class AnswerResult(
        val hits: List<RetrievedDoc>,
        val prompt: String,
        val answer: String?,
        val retrievalMs: Long,
        val generationMs: Long,
        /**
         * Per-call decomposition from [RagLlm.generate]. Null when
         * generation didn't run (no LLM loaded, or no hits in the RAG
         * path). Surfaces prefill/decode/setup numbers so the UI can
         * show *where* the latency went, not just how much.
         */
        val generationBreakdown: RagLlm.GenerationResult? = null,
    )

    /** A single retrieval hit surfaced to the UI. */
    data class RetrievedDoc(
        val soupEntryId: Long,
        val title: String,
        val text: String,
    )

    /**
     * Create the soup (if absent) with one FLOAT32 / cosine vector index
     * on [EMBEDDING_PATH]. Dimension is taken from the embedder, which
     * probes the model with an empty string on first call.
     */
    fun ensureSoup() {
        if (store.hasSoup(SOUP_NAME)) return
        val specs = arrayOf(
            IndexSpec(TITLE_PATH, SmartStore.Type.string),
            IndexSpec.forVector(
                EMBEDDING_PATH,
                // Cosine + FLOAT32 (FLOAT32 is the default VectorKind);
                // embeddings are L2-normalised at encode time so cosine
                // ordering is equivalent to dot-product ordering.
                VectorMeta(embedder.dimension, DistanceMetric.COSINE),
            ),
        )
        store.registerSoup(SOUP_NAME, specs)
    }

    /**
     * Embed and upsert every doc in [RagSampleCorpus]. Returns the
     * number of rows written. Idempotent: callers may invoke this after
     * [clear] to re-index, or skip it when [docCount] already matches
     * the corpus size.
     */
    fun ingest(): Int {
        ensureSoup()
        val corpus = RagSampleCorpus.docs
        for (doc in corpus) {
            val elt = JSONObject().apply {
                put(TITLE_PATH, doc.title)
                put(TEXT_PATH, doc.text)
                put(EMBEDDING_PATH, embedder.embed(doc.text).toJsonArray())
            }
            store.create(SOUP_NAME, elt)
        }
        return corpus.size
    }

    /** Number of rows currently in the RAG soup. 0 if the soup does not exist. */
    fun docCount(): Int =
        if (store.hasSoup(SOUP_NAME))
            store.countQuery(QuerySpec.buildAllQuerySpec(SOUP_NAME, null, null, Int.MAX_VALUE))
        else 0

    /** Wipe the soup so the next [ingest] starts from a clean state. */
    fun clear() {
        if (store.hasSoup(SOUP_NAME)) store.dropSoup(SOUP_NAME)
    }

    /**
     * End-to-end RAG: embed [query], pull top-[k] nearest docs, build a
     * grounded prompt, and (optionally) generate an answer.
     *
     * The retrieval step always runs. Generation runs only if the
     * pipeline was constructed with a non-null [RagLlm]; otherwise
     * [AnswerResult.answer] is `null`.
     */
    fun searchWithAnswer(query: String, k: Int = 3): AnswerResult {
        ensureSoup()
        val t0 = System.nanoTime()
        val queryVec = embedder.embed(query)
        val rows: JSONArray = store.vectorSearch(SOUP_NAME, EMBEDDING_PATH, queryVec, k = k)
        val tRetrieved = System.nanoTime()

        val hits = rows.toHits()
        val prompt = buildPrompt(query, hits)

        val (generated, tGenerated) = if (llm != null && hits.isNotEmpty()) {
            llm.generate(prompt) to System.nanoTime()
        } else {
            null to tRetrieved
        }

        return AnswerResult(
            hits = hits,
            prompt = prompt,
            answer = generated?.text,
            retrievalMs = (tRetrieved - t0) / 1_000_000L,
            generationMs = (tGenerated - tRetrieved) / 1_000_000L,
            generationBreakdown = generated,
        )
    }

    /**
     * Bare-LLM A/B baseline: send [query] to the LLM with **no** retrieved
     * context, so the only difference vs [searchWithAnswer] is the
     * vector-DB / RAG layer. Same model, same sampler, same prompt shape
     * minus the `Context: [...]` block.
     *
     * Used to measure two things side-by-side:
     *   - **Latency delta** between RAG and bare \u2014 i.e. the prefill cost
     *     of the retrieved context. Retrieval itself adds [retrievalMs]
     *     (always single-digit-to-low-tens of ms once warm), so any
     *     bigger delta is prompt-prefill.
     *   - **Quality delta** \u2014 does the model already know the answer
     *     without the corpus, or does it hallucinate / refuse?
     *
     * Returns the same [AnswerResult] shape with `hits = emptyList()` and
     * `retrievalMs = 0` so the existing UI renderer Just Works.
     */
    fun answerWithoutRetrieval(query: String): AnswerResult {
        val prompt = buildBarePrompt(query)
        val tStart = System.nanoTime()
        val (generated, tEnd) = if (llm != null) {
            llm.generate(prompt) to System.nanoTime()
        } else {
            null to tStart
        }
        return AnswerResult(
            hits = emptyList(),
            prompt = prompt,
            answer = generated?.text,
            retrievalMs = 0L,
            generationMs = (tEnd - tStart) / 1_000_000L,
            generationBreakdown = generated,
        )
    }

    private fun JSONArray.toHits(): List<RetrievedDoc> {
        val out = ArrayList<RetrievedDoc>(length())
        for (i in 0 until length()) {
            // With selectPaths=null the row is the whole soup object.
            val row = getJSONObject(i)
            out += RetrievedDoc(
                soupEntryId = row.getLong(SmartStore.SOUP_ENTRY_ID),
                title = row.optString(TITLE_PATH),
                text = row.optString(TEXT_PATH),
            )
        }
        return out
    }

    private fun buildPrompt(query: String, hits: List<RetrievedDoc>): String =
        buildString {
            append(
                "You are a Salesforce help assistant. Answer the user's " +
                    "question using ONLY the provided context. If the answer " +
                    "is not in the context, say you don't know.\n\n"
            )
            append("Context:\n")
            hits.forEachIndexed { i, hit ->
                append('[').append(i + 1).append("] ").append(hit.title).append('\n')
                append(hit.text).append("\n\n")
            }
            append("Question: ").append(query).append('\n')
            append("Answer:")
        }

    /**
     * Bare prompt mirrors [buildPrompt] minus the context block, so an
     * A/B comparison isolates the impact of retrieval. The system
     * instruction stays "Salesforce help assistant" but drops the
     * "ONLY the provided context" clause \u2014 with no context, that clause
     * would force the model to refuse every question, which would tell
     * us nothing about its baseline quality.
     */
    private fun buildBarePrompt(query: String): String =
        buildString {
            append(
                "You are a Salesforce help assistant. Answer the user's " +
                    "question concisely.\n\n"
            )
            append("Question: ").append(query).append('\n')
            append("Answer:")
        }

    companion object {
        /** Soup name used exclusively by the RAG demo. Safe to drop anytime. */
        const val SOUP_NAME: String = "ragDocs"
        const val TITLE_PATH: String = "title"
        const val TEXT_PATH: String = "text"
        const val EMBEDDING_PATH: String = "embedding"
    }
}

/** Pack a FloatArray into a JSONArray suitable for SmartStore + vec0. */
private fun FloatArray.toJsonArray(): JSONArray {
    val arr = JSONArray()
    for (f in this) arr.put(f.toDouble())
    return arr
}
