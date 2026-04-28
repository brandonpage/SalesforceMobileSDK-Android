/*
 * Copyright (c) 2026-present, salesforce.com, inc.
 * All rights reserved.
 *
 * Vector DB spike Phase 4: on-device RAG sample.
 */
package com.salesforce.samples.restexplorer.rag

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper around Google AI Edge's [Engine] / `Conversation` API
 * (the public successor to MediaPipe's deprecated `LlmInference`). The
 * demo keeps this generator decoupled from the embedder \u2014 the vector
 * DB plumbing (embed + search) must work even when the user has not
 * dropped an LLM bundle yet. [tryCreate] returns null in that case and
 * [RagPipeline] falls back to retrieval-only mode.
 *
 * Single-threaded by construction: a single [Engine] is held for the
 * lifetime of the activity, and each [generate] call spins up its own
 * `Conversation`, sends a single user message, then closes it. That
 * matches the one-shot grounded-prompt shape we want for RAG and
 * sidesteps any cross-request state.
 */
class RagLlm private constructor(
    private val engine: Engine,
    private val conversationConfig: ConversationConfig,
    val modelSize: Long,
    val modelPath: String,
    val backendDescription: String,
    val maxTokens: Int
) : Closeable {

    /**
     * Runs synchronous generation. Creates a fresh `Conversation` so
     * each call is stateless from the model's perspective \u2014 no chat
     * history bleeds across RAG queries. Blocking; the caller is
     * expected to be on a background thread.
     *
     * The returned [GenerationResult] decomposes the wall-clock cost
     * into buckets that map to a transformer's actual cost structure
     * on mobile:
     *
     *   - `conversationSetupMs`: `engine.createConversation(...)` only.
     *     Per-call fixed overhead (KV-cache alloc, `Gemma4DataProcessor`
     *     re-init, sampler bind). Does not scale with prompt length;
     *     this is the slack we give up by not reusing a warm
     *     `Conversation`.
     *   - `prefillMs` / `prefillTokens` / `prefillTps`: pulled from
     *     [com.google.ai.edge.litertlm.BenchmarkInfo] after `sendMessage`.
     *     Prefill is GPU compute-bound and scales with prompt length \u2014
     *     this is the only term that grows when RAG adds retrieved
     *     context.
     *   - `decodeMs` / `decodeTokens` / `decodeTps`: also from
     *     `BenchmarkInfo`. Decode is memory-bandwidth bound and scales
     *     with output length, not with whether the answer is "easy"
     *     or in-context. Per-output-token cost is essentially a
     *     hardware constant for this model on this device.
     *   - `sendMessageOtherMs`: `sendMessage` total wall-clock minus
     *     prefill minus decode. Surfaces JNI bridging, tokenisation,
     *     sampling, response-building overhead.
     *   - `closeMs`: `Conversation.close()`.
     *
     * `Conversation.getBenchmarkInfo()` is marked `@ExperimentalApi`
     * in litertlm-android 0.10.2 \u2014 the contract may change in a
     * later release. We opt in here because it's the only way to
     * get prefill/decode token counts and rates without dropping
     * down to the lower-level `Session` API.
     */
    @OptIn(ExperimentalApi::class)
    fun generate(prompt: String): GenerationResult {
        val t0 = System.nanoTime()
        val conversation = engine.createConversation(conversationConfig)
        val tSetup = System.nanoTime()
        // The LiteRT-LM Engine only allows one live `Conversation` at a
        // time \u2014 attempting to create a second one throws
        // `FAILED_PRECONDITION: A session already exists`. So if any
        // step below throws (e.g. `getBenchmarkInfo()` when the
        // global benchmark flag is unexpectedly off), the conversation
        // *must* still be closed or the next `generate()` call dies.
        // Hence the try/finally.
        try {
            // sendMessage returns a Message whose toString() renders
            // the assistant text \u2014 that's why the existing demo
            // already gets clean prose out of `.toString().trim()`. We
            // grab BenchmarkInfo *before* close() because the C++ side
            // tears the per-conversation benchmark recorder down on close.
            val message = conversation.sendMessage(prompt)
            val tSent = System.nanoTime()
            // `Conversation.getBenchmarkInfo()` is exposed as a Java-style
            // getter, not a Kotlin property accessor (the LiteRT-LM source
            // declares it as `fun getBenchmarkInfo()`), so the explicit
            // function-call form is the only one that compiles. It also
            // requires `ExperimentalFlags.enableBenchmark = true` to have
            // been set before `engine.initialize()` \u2014 see [tryCreate].
            val benchmark = conversation.getBenchmarkInfo()
            val tBench = System.nanoTime()
            val text = message.toString().trim()
            // Time the close itself so we can attribute any per-call
            // teardown cost. Has been ~0\u20132 ms in practice but worth
            // measuring rather than assuming.
            val tBeforeClose = System.nanoTime()
            conversation.close()
            val tClose = System.nanoTime()

            val sendMsgMs = (tSent - tSetup) / 1_000_000L
            val prefillTokens = benchmark.lastPrefillTokenCount
            val prefillTps = benchmark.lastPrefillTokensPerSecond
            val decodeTokens = benchmark.lastDecodeTokenCount
            val decodeTps = benchmark.lastDecodeTokensPerSecond
            val prefillMs = if (prefillTps > 0.0) ((prefillTokens / prefillTps) * 1000.0).toLong() else 0L
            val decodeMs = if (decodeTps > 0.0) ((decodeTokens / decodeTps) * 1000.0).toLong() else 0L
            val sendMsgOtherMs = (sendMsgMs - prefillMs - decodeMs).coerceAtLeast(0L)

            return GenerationResult(
                text = text,
                totalMs = (tClose - t0) / 1_000_000L,
                conversationSetupMs = (tSetup - t0) / 1_000_000L,
                sendMessageMs = sendMsgMs,
                prefillTokens = prefillTokens,
                prefillMs = prefillMs,
                prefillTps = prefillTps,
                ttftMs = (benchmark.timeToFirstTokenInSecond * 1000.0).toLong(),
                decodeTokens = decodeTokens,
                decodeMs = decodeMs,
                decodeTps = decodeTps,
                sendMessageOtherMs = sendMsgOtherMs,
                benchmarkFetchMs = (tBench - tSent) / 1_000_000L,
                closeMs = (tClose - tBeforeClose) / 1_000_000L,
            )
        } catch (t: Throwable) {
            // Best-effort close on the failure path so the engine
            // doesn't get stuck in "session already exists" mode.
            // Swallow secondary close failures \u2014 the original throwable
            // is the more interesting one to surface.
            runCatching { conversation.close() }
            throw t
        }
    }

    override fun close() {
        engine.close()
    }

    /**
     * Per-call timing decomposition. See [generate] for what each
     * field means. The `tps` numbers are throughput during that
     * specific phase, not averages over the whole call \u2014 they are
     * the right thing to compare across runs (CPU vs GPU, warm vs
     * cold) because they normalise out token-count differences.
     */
    data class GenerationResult(
        val text: String,
        val totalMs: Long,
        val conversationSetupMs: Long,
        val sendMessageMs: Long,
        val prefillTokens: Int,
        val prefillMs: Long,
        val prefillTps: Double,
        val ttftMs: Long,
        val decodeTokens: Int,
        val decodeMs: Long,
        val decodeTps: Double,
        val sendMessageOtherMs: Long,
        val benchmarkFetchMs: Long,
        val closeMs: Long,
    )

    companion object {
        /** Default context window for the demo. Conservative for small LLMs. */
        const val DEFAULT_MAX_TOKENS: Int = 512

        /**
         * Load the LLM from [RagModelPaths.llmPath]. Returns null if the
         * file is missing, the format is unsupported, or engine
         * initialisation fails (e.g. OpenCL unavailable on an emulator
         * lacking GPU compute). Callers should surface the missing-model
         * case via [RagModelPaths.describe]; generation is optional in
         * this demo.
         *
         * The cache directory is set to the app's `cacheDir` so the
         * second cold-start can reuse compiled kernels and shave time
         * off startup. The setting is documented as optional in the
         * LiteRT-LM Android guide.
         */
        @OptIn(ExperimentalApi::class)
        @JvmStatic
        fun tryCreate(
            context: Context,
            maxTokens: Int = DEFAULT_MAX_TOKENS
        ): RagLlm? {
            val file: File = RagModelPaths.llmPath(context)
            if (!file.isFile || file.length() <= 0L) return null
            // Enable the per-conversation benchmark recorder *before*
            // the engine boots. Without this flag,
            // `Conversation.getBenchmarkInfo()` throws INTERNAL with
            // "Benchmark is not enabled. Please make sure the
            // BenchmarkParams is set in the EngineSettings". The flag
            // is a singleton on `ExperimentalFlags`, so setting it
            // once per process before `engine.initialize()` is enough.
            ExperimentalFlags.enableBenchmark = true
            // Backend choice: GPU on real-device hardware (Adreno /
            // Mali / Tensor cores all expose OpenCL or Vulkan compute,
            // which the LiteRT-LM sampling kernels need). The AAR also
            // exposes `Backend.CPU(numOfThreads)` \u2014 undocumented in the
            // public Android guide but visible in the unpacked jar \u2014
            // which is the only choice that runs on a stock Android
            // emulator (AVD images do not ship `libOpenCL.so` or a
            // WebGPU implementation, and the emulator's software
            // Vulkan adapter is rejected by the sampler factory).
            // The Phase 4.6 emulator run used `Backend.CPU()`; swap
            // back to that here only if testing again on an emulator.
            val backend = Backend.GPU()
            return try {
                // SamplerConfig lives on `ConversationConfig` (not
                // `EngineConfig`) per the LiteRT-LM Android API \u2014 the
                // sampler is per-conversation, the engine just hosts the
                // weights. topP/temperature are `Double`, not `Float`.
                val conversationConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.95,
                        temperature = 0.7,
                    ),
                )
                val engineConfig = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = backend,
                    cacheDir = context.cacheDir.absolutePath,
                )
                val engine = Engine(engineConfig)
                engine.initialize()
                RagLlm(
                    engine = engine,
                    conversationConfig = conversationConfig,
                    modelSize = file.length(),
                    modelPath = file.absolutePath,
                    backendDescription = backend.javaClass.simpleName,
                    maxTokens = maxTokens,
                )
            } catch (t: Throwable) {
                android.util.Log.e(
                    "RagLlm",
                    "Failed to load LiteRT-LM Engine from ${file.absolutePath}",
                    t
                )
                null
            }
        }
    }
}
