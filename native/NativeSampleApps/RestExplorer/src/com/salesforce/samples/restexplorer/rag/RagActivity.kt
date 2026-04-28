/*
 * Copyright (c) 2026-present, salesforce.com, inc.
 * All rights reserved.
 *
 * Vector DB spike Phase 4: on-device RAG sample.
 */
package com.salesforce.samples.restexplorer.rag

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.salesforce.androidsdk.smartstore.app.SmartStoreSDKManager
import com.salesforce.samples.restexplorer.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-screen driver for the on-device RAG demo. Everything happens on
 * this one activity so the sample is easy to follow end-to-end:
 *
 *   1. On `onCreate`, tries to load the embedder + (optional) LLM from
 *      the app's external files dir (see [RagModelPaths]). Both loads
 *      are best-effort; if the embedder is missing the screen surfaces
 *      a readable error and disables the controls.
 *
 *   2. "Index sample corpus" embeds every doc in [RagSampleCorpus] into
 *      the `ragDocs` soup. Re-running wipes and re-indexes so the row
 *      count stays stable at [RagSampleCorpus.docs]`.size`.
 *
 *   3. "Ask" embeds the query, runs top-k vector search, optionally
 *      feeds the retrieved context into the LLM, and dumps all three
 *      latencies (embed / search / generate) plus the answer into the
 *      results TextView.
 *
 * All blocking work runs on a single-thread executor \u2014 neither
 * `TextEmbedder` nor `LlmInference` is safe to share across threads,
 * and the single-threaded executor doubles as implicit serialization
 * for the soup writes.
 */
class RagActivity : Activity() {

    // --- UI -----------------------------------------------------------

    private lateinit var modelStatusText: TextView
    private lateinit var indexButton: Button
    private lateinit var clearButton: Button
    private lateinit var queryInput: EditText
    private lateinit var askButton: Button
    private lateinit var askBareButton: Button
    private lateinit var resultsView: TextView

    // --- State --------------------------------------------------------

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rag-worker").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)

    private var pipeline: RagPipeline? = null
    private var embedder: RagEmbedder? = null
    private var llm: RagLlm? = null

    // --- Lifecycle ----------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rag)
        title = getString(R.string.rag_title)

        modelStatusText = findViewById(R.id.rag_model_status)
        indexButton = findViewById(R.id.rag_index_btn)
        clearButton = findViewById(R.id.rag_clear_btn)
        queryInput = findViewById(R.id.rag_query)
        askButton = findViewById(R.id.rag_ask_btn)
        askBareButton = findViewById(R.id.rag_ask_bare_btn)
        resultsView = findViewById(R.id.rag_results)

        setControlsEnabled(false)
        modelStatusText.text = buildString {
            append(RagModelPaths.describe(this@RagActivity))
            append("\n\n").append(getString(R.string.rag_initializing))
        }

        indexButton.setOnClickListener { onIndexClicked() }
        clearButton.setOnClickListener { onClearClicked() }
        askButton.setOnClickListener { onAskClicked(useRetrieval = true) }
        askBareButton.setOnClickListener { onAskClicked(useRetrieval = false) }

        // Spin model loading off the UI thread: MediaPipe unpacks the
        // tflite and (for the LLM) multi-gigabyte mmap on `create`.
        worker.execute { initPipelineOnWorker() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shutdown order: stop accepting new work, then release native
        // graphs. Both MediaPipe handles own file descriptors; leaking
        // them on config change would slowly exhaust emulator FDs.
        worker.shutdown()
        try { embedder?.close() } catch (t: Throwable) { Log.w(TAG, "embedder.close failed", t) }
        try { llm?.close() } catch (t: Throwable) { Log.w(TAG, "llm.close failed", t) }
    }

    // --- Init ---------------------------------------------------------

    private fun initPipelineOnWorker() {
        val t0 = System.nanoTime()
        val loadedEmbedder = RagEmbedder.tryCreate(this)
        val loadedLlm = RagLlm.tryCreate(this)
        val store = SmartStoreSDKManager.getInstance().getGlobalSmartStore()
        val tLoaded = System.nanoTime()

        val status = buildString {
            append(RagModelPaths.describe(this@RagActivity))
            append('\n')
            if (loadedEmbedder == null) {
                append('\n').append(getString(R.string.rag_embedder_missing))
            } else {
                append("\nEmbedder loaded: dim=")
                append(loadedEmbedder.dimension)
                append(", ").append(loadedEmbedder.modelSize / 1024 / 1024).append(" MiB")
                append(", load=").append((tLoaded - t0) / 1_000_000L).append(" ms")
                if (loadedLlm == null) {
                    append('\n').append(getString(R.string.rag_llm_missing_hint))
                } else {
                    append("\nLLM loaded: ")
                    append(loadedLlm.modelSize / 1024 / 1024).append(" MiB")
                    append(", maxTokens=").append(loadedLlm.maxTokens)
                }
            }
        }

        runOnUiThread {
            modelStatusText.text = status
            if (loadedEmbedder != null) {
                embedder = loadedEmbedder
                llm = loadedLlm
                pipeline = RagPipeline(store, loadedEmbedder, loadedLlm)
                setControlsEnabled(true)
            }
        }
    }

    // --- Click handlers ----------------------------------------------

    private fun onIndexClicked() {
        val pipe = pipeline ?: return
        runOnWorkerIfIdle("Indexing…") {
            val t0 = System.nanoTime()
            pipe.clear()
            val count = pipe.ingest()
            val ms = (System.nanoTime() - t0) / 1_000_000L
            postResults("Indexed $count docs in $ms ms " +
                "(embed + SmartStore create, single thread).")
        }
    }

    private fun onClearClicked() {
        val pipe = pipeline ?: return
        runOnWorkerIfIdle("Clearing…") {
            pipe.clear()
            postResults("Soup cleared.")
        }
    }

    private fun onAskClicked(useRetrieval: Boolean) {
        val pipe = pipeline ?: return
        val query = queryInput.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "Type a question first.", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = if (useRetrieval) "RAG" else "bare"
        runOnWorkerIfIdle("Thinking ($mode)…") {
            val result = if (useRetrieval) {
                pipe.searchWithAnswer(query, k = 3)
            } else {
                pipe.answerWithoutRetrieval(query)
            }
            postResults(renderAnswer(query, result, mode))
        }
    }

    // --- Helpers ------------------------------------------------------

    private fun renderAnswer(
        query: String,
        r: RagPipeline.AnswerResult,
        mode: String,
    ): String =
        buildString {
            append("Mode:  ").append(mode).append('\n')
            append("Query: ").append(query).append("\n\n")
            if (r.hits.isEmpty()) {
                append("No retrieval (bare LLM call).\n\n")
            } else {
                append("Top-").append(r.hits.size).append(" hits (")
                    .append(r.retrievalMs).append(" ms):\n")
                r.hits.forEachIndexed { i, hit ->
                    append("  ").append(i + 1).append(". ")
                        .append(hit.title)
                        .append("  [id=").append(hit.soupEntryId).append("]\n")
                }
                append('\n')
            }
            if (r.answer != null) {
                append("Answer (").append(r.generationMs).append(" ms):\n")
                append(r.answer).append('\n')
                r.generationBreakdown?.let { gb ->
                    append("\nLatency breakdown:\n")
                    append(formatBreakdown(gb))
                }
            } else {
                append("No LLM loaded — retrieval only.\n")
                append("Top hit snippet:\n")
                append(r.hits.firstOrNull()?.text ?: "(no hits)").append('\n')
            }
            append("\n---\nPrompt sent to LLM (first 800 chars):\n")
            append(r.prompt.take(800))
            if (r.prompt.length > 800) append("\n… (").append(r.prompt.length - 800).append(" more chars truncated)")
        }

    /**
     * Render the [RagLlm.GenerationResult] as a fixed-width table the
     * monospace results TextView can print directly. Wall-clock columns
     * (`setupMs`, `sendMessageOtherMs`, `closeMs`, `totalMs`) come from
     * `System.nanoTime()` around the API calls; `prefill` / `decode` /
     * `ttft` come from `Conversation.benchmarkInfo`, which the
     * LiteRT-LM C++ runtime fills in as it runs the model. The `tps`
     * numbers are phase-local throughput — the right thing to compare
     * across runs since they normalise out token-count differences.
     */
    private fun formatBreakdown(gb: RagLlm.GenerationResult): String =
        buildString {
            append(String.format("  setup        : %5d ms  (createConversation)%n", gb.conversationSetupMs))
            append(
                String.format(
                    "  prefill      : %5d tok / %5d ms  (%.0f tps, ttft=%d ms)%n",
                    gb.prefillTokens, gb.prefillMs, gb.prefillTps, gb.ttftMs,
                )
            )
            append(
                String.format(
                    "  decode       : %5d tok / %5d ms  (%.0f tps)%n",
                    gb.decodeTokens, gb.decodeMs, gb.decodeTps,
                )
            )
            append(String.format("  send other   : %5d ms  (jni / tokenise / sample / build)%n", gb.sendMessageOtherMs))
            append(String.format("  close        : %5d ms%n", gb.closeMs))
            append(String.format("  --- total    : %5d ms%n", gb.totalMs))
        }

    private inline fun runOnWorkerIfIdle(placeholder: String, crossinline body: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(this, "Busy, please wait…", Toast.LENGTH_SHORT).show()
            return
        }
        setControlsEnabled(false)
        resultsView.text = placeholder
        worker.execute {
            try {
                body()
            } catch (t: Throwable) {
                Log.e(TAG, "RAG task failed", t)
                postResults("Error: $t")
            } finally {
                busy.set(false)
                runOnUiThread { setControlsEnabled(true) }
            }
        }
    }

    private fun postResults(text: String) = runOnUiThread { resultsView.text = text }

    private fun setControlsEnabled(enabled: Boolean) {
        indexButton.isEnabled = enabled
        clearButton.isEnabled = enabled
        askButton.isEnabled = enabled
        askBareButton.isEnabled = enabled
        queryInput.isEnabled = enabled
    }

    private companion object {
        const val TAG: String = "RagActivity"
    }
}
