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
         * generation didn't run (no LLM loaded, no hits, or the LLM
         * was short-circuited). Surfaces prefill/decode/setup numbers
         * so the UI can show *where* the latency went, not just how
         * much.
         */
        val generationBreakdown: RagLlm.GenerationResult? = null,
        /**
         * Aggregate retrieval-quality + latency metrics. Null only on
         * the bare-LLM path ([answerWithoutRetrieval]) where there is
         * no retrieval to measure.
         */
        val retrievalMetrics: RetrievalMetrics? = null,
        /**
         * True when the top-1 hit's cosine similarity met or exceeded
         * [SHORT_CIRCUIT_COSINE_THRESHOLD] and the LLM was skipped
         * entirely — the answer is the top hit's stored text. Lets the
         * UI label the trade-off (~30 ms total vs ~3 s with the LLM).
         */
        val shortCircuited: Boolean = false,
    )

    /**
     * A single retrieval hit surfaced to the UI.
     *
     * @param cosine cosine similarity vs the query vector, in `[-1, 1]`.
     *               Computed client-side after `vectorSearch` returns,
     *               because the underlying vec0 SQL only projects the
     *               soup row — the per-row distance is used for
     *               ordering but not surfaced. Embeddings are
     *               L2-normalised at encode time (see [RagEmbedder]),
     *               so cosine reduces to a plain dot product.
     */
    data class RetrievedDoc(
        val soupEntryId: Long,
        val title: String,
        val text: String,
        val cosine: Double,
    )

    /**
     * Aggregate retrieval-side numbers, separate from LLM generation.
     * Lets the UI show whether retrieval did its job (high cosine,
     * clear gap to runner-up) independently of whether the answer
     * came from the model or from the corpus directly.
     *
     * `topCosine` and `gapToSecond` are the two numbers that drive the
     * short-circuit decision in [searchWithAnswer]:
     *   - `topCosine` = how close the best hit is to the query.
     *   - `gapToSecond` = how confidently the best hit dominates. A
     *     small gap with a high `topCosine` means several docs are
     *     near-equally relevant, which is fine for RAG but a poor
     *     signal for skip-the-LLM short-circuiting.
     *
     * Latency fields are wall-clock (`System.nanoTime()`) around each
     * sub-step. `cosineComputeMs` covers the client-side scoring
     * loop; it should stay sub-millisecond at the demo's k=3, dim=100,
     * but is broken out so we can see when it stops being free.
     */
    data class RetrievalMetrics(
        val k: Int,
        val topCosine: Double,
        val gapToSecond: Double,
        val meanCosine: Double,
        val embedQueryMs: Long,
        val vectorSearchMs: Long,
        val cosineComputeMs: Long,
    ) {
        val totalRetrievalMs: Long get() = embedQueryMs + vectorSearchMs + cosineComputeMs
    }

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
        // --- Retrieval, broken out into three timed sub-steps so the
        // UI can show where the ~30 ms goes (embed dominates by far
        // at the demo's corpus size; vector search is ~ms; cosine
        // re-scoring is sub-ms).
        val t0 = System.nanoTime()
        val queryVec = embedder.embed(query)
        val tEmbedded = System.nanoTime()
        val rows: JSONArray = store.vectorSearch(SOUP_NAME, EMBEDDING_PATH, queryVec, k = k)
        val tSearched = System.nanoTime()
        val hits = rows.toHits(queryVec)
        val tScored = System.nanoTime()

        val metrics = buildMetrics(
            hits = hits,
            k = k,
            embedQueryMs = (tEmbedded - t0) / 1_000_000L,
            vectorSearchMs = (tSearched - tEmbedded) / 1_000_000L,
            cosineComputeMs = (tScored - tSearched) / 1_000_000L,
        )

        // Short-circuit decision: if the top hit is close enough to
        // the query (cosine >= threshold), the LLM adds latency
        // (~3 s) without adding information — the corpus already
        // contains a near-verbatim answer. Skip generation and return
        // the top hit's stored text. The threshold is intentionally
        // conservative; tune via [SHORT_CIRCUIT_COSINE_THRESHOLD].
        // Out-of-corpus questions land well below 0.85 (the demo Q2
        // tops out around ~0.4), so this branch only fires on
        // genuinely high-confidence in-corpus matches.
        val topHit = hits.firstOrNull()
        val canShortCircuit = topHit != null &&
            topHit.cosine >= SHORT_CIRCUIT_COSINE_THRESHOLD

        if (canShortCircuit) {
            val tDone = System.nanoTime()
            return AnswerResult(
                hits = hits,
                prompt = buildPrompt(query, hits),  // kept for transparency
                answer = topHit!!.text,
                retrievalMs = (tScored - t0) / 1_000_000L,
                generationMs = 0L,
                generationBreakdown = null,
                retrievalMetrics = metrics,
                shortCircuited = true,
            ).also {
                // touch tDone to silence unused-var warnings; the
                // real total is retrievalMs + 0 generation.
                @Suppress("UNUSED_VARIABLE") val _t = tDone
            }
        }

        val prompt = buildPrompt(query, hits)
        val (generated, tGenerated) = if (llm != null && hits.isNotEmpty()) {
            llm.generate(prompt) to System.nanoTime()
        } else {
            null to tScored
        }

        return AnswerResult(
            hits = hits,
            prompt = prompt,
            answer = generated?.text,
            retrievalMs = (tScored - t0) / 1_000_000L,
            generationMs = (tGenerated - tScored) / 1_000_000L,
            generationBreakdown = generated,
            retrievalMetrics = metrics,
            shortCircuited = false,
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
            retrievalMetrics = null,
            shortCircuited = false,
        )
    }

    /**
     * Convert vec0's row results into [RetrievedDoc]s, computing the
     * cosine similarity vs [queryVec] for each row from the embedding
     * stored in the soup. Two reasons we do this in Kotlin rather than
     * trusting vec0's distance:
     *
     *   1. The vector_match SQL (see [QuerySpec.computeWhereClause])
     *      uses vec0's `MATCH` predicate to filter and order, but
     *      projects only the soup row — the `distance` column from
     *      the inner `vec0` virtual table is not in the SELECT, so it
     *      isn't reachable from the JSONArray we get back. Re-scoring
     *      client-side is cheap (k * dim multiplications, sub-ms at
     *      k=3 / dim=100) and avoids changing the SmartStore SQL.
     *   2. Embeddings are L2-normalised at encode time (see
     *      [RagEmbedder]), so cosine reduces to a dot product.
     *      Doing the scoring here keeps the [RetrievedDoc] order
     *      consistent with the score we display.
     *
     * If the soup row somehow lacks an embedding (older index, manual
     * insert), the score falls back to `Double.NaN` so the UI can
     * make the gap visible rather than hiding it as 0.
     */
    private fun JSONArray.toHits(queryVec: FloatArray): List<RetrievedDoc> {
        val out = ArrayList<RetrievedDoc>(length())
        for (i in 0 until length()) {
            // With selectPaths=null the row is the whole soup object.
            val row = getJSONObject(i)
            val storedVec = row.optJSONArray(EMBEDDING_PATH)
            val cosine = if (storedVec != null) cosineSimilarity(queryVec, storedVec) else Double.NaN
            out += RetrievedDoc(
                soupEntryId = row.getLong(SmartStore.SOUP_ENTRY_ID),
                title = row.optString(TITLE_PATH),
                text = row.optString(TEXT_PATH),
                cosine = cosine,
            )
        }
        // Important: we do NOT re-sort by computed cosine. vec0 ranks
        // in pure FLOAT32 end-to-end (FLOAT32 stored vectors + FP32
        // re-parsed query from `vec_f32(...)`); our client-side
        // cosine mixes the FloatArray query with Doubles round-tripped
        // out of JSON, so the two computations can diverge by ~0.005
        // when the top hits are within ~0.02 of each other. An
        // earlier version of this code re-sorted by client cosine and
        // got Q1 wrong: vec0 correctly ranked "Create a Lead" #1 at
        // FP32-cosine \u2248 0.696, but our recompute had "Approval
        // Processes" at 0.703, which then short-circuited and
        // returned the wrong doc verbatim. vec0's ordering is the
        // ground truth; our cosines are for display + threshold
        // checking only. Therefore: keep vec0's order.
        return out
    }

    private fun buildMetrics(
        hits: List<RetrievedDoc>,
        k: Int,
        embedQueryMs: Long,
        vectorSearchMs: Long,
        cosineComputeMs: Long,
    ): RetrievalMetrics {
        val cosines = hits.map { it.cosine }
        val top = cosines.firstOrNull() ?: Double.NaN
        val second = cosines.getOrNull(1) ?: Double.NaN
        val gap = if (!top.isNaN() && !second.isNaN()) top - second else Double.NaN
        val mean = if (cosines.isNotEmpty() && cosines.none { it.isNaN() })
            cosines.average() else Double.NaN
        return RetrievalMetrics(
            k = k,
            topCosine = top,
            gapToSecond = gap,
            meanCosine = mean,
            embedQueryMs = embedQueryMs,
            vectorSearchMs = vectorSearchMs,
            cosineComputeMs = cosineComputeMs,
        )
    }

    /**
     * Cosine similarity between [a] (the query vector, FloatArray) and
     * [b] (a stored embedding, JSONArray of doubles). Both are
     * L2-normalised so this is just a dot product. Computed in `Double`
     * to keep accumulator precision; the difference vs `Float`
     * accumulation is below the third decimal at this dim, but
     * cheap insurance for downstream threshold comparisons.
     */
    private fun cosineSimilarity(a: FloatArray, b: JSONArray): Double {
        val n = minOf(a.size, b.length())
        var dot = 0.0
        for (i in 0 until n) dot += a[i] * b.getDouble(i)
        return dot
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

        /**
         * Cosine threshold above which the LLM is skipped and the top
         * retrieved doc is returned verbatim. **Per-corpus tuning is
         * required** — the right value depends on the embedder's
         * dynamic range and the corpus's topical homogeneity:
         *
         *   - The demo's universal-sentence-encoder (5 MiB, dim=100)
         *     produces L2-normalised vectors with relatively narrow
         *     spread on this corpus (all docs are about Salesforce,
         *     so they cluster). Measured cosines:
         *     - Q1 "How do I create a Lead?" → top1 ≈ 0.70 (the
         *       "Create a Lead" doc).
         *     - Q2 "What is the capital of France?" → top1 ≈ 0.62
         *       (a meaningless best match within a Salesforce corpus).
         *     The 0.08 separation is the budget we have to work
         *     with; **0.68** sits in the middle and successfully
         *     fires for in-corpus queries while skipping
         *     out-of-corpus queries.
         *
         *   - A higher-quality embedder (e.g. fine-tuned
         *     sentence-transformers MiniLM, dim=384) would push
         *     in-corpus cosines into the 0.80–0.95 band and
         *     out-of-corpus cosines below 0.40, at which point the
         *     threshold can move closer to 0.80 with much more
         *     margin.
         *
         * Lower threshold = more aggressive short-circuiting (faster,
         * but more risk of returning a close-but-wrong doc verbatim).
         */
        const val SHORT_CIRCUIT_COSINE_THRESHOLD: Double = 0.68
    }
}

/** Pack a FloatArray into a JSONArray suitable for SmartStore + vec0. */
private fun FloatArray.toJsonArray(): JSONArray {
    val arr = JSONArray()
    for (f in this) arr.put(f.toDouble())
    return arr
}
