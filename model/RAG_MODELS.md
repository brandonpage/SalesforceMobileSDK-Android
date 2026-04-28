# On-device RAG models for the Vector DB spike

The Phase 4 RAG demo in `RestExplorer` loads two models at runtime:

| Role | Expected filename | Download |
| :--- | :--- | :--- |
| **Embedder** (text → float vector) | `universal_sentence_encoder.tflite` (exact name required) | MediaPipe TextEmbedder model card — [`Universal Sentence Encoder`](https://storage.googleapis.com/mediapipe-tasks/text_embedder/universal_sentence_encoder.tflite). 100-dim output. |
| **Generator** (prompt → answer) *(optional)* | Any `*.task` **or** `*.litertlm` bundle (e.g. `llm.task`, `gemma-4-E2B-it.litertlm`) | Any MediaPipe LLM Inference bundle. Google / `litert-community` publish Gemma 2 / 3 / 3n / 4 variants on Hugging Face. The resolver in [`RagModelPaths.kt`](../native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagModelPaths.kt) scans the dir and picks the first matching file (preferring `llm.task` if present). |

The demo degrades gracefully:

- With **only** the embedder: retrieval-only "semantic search" — you'll see the top-k hits and a snippet from the highest-scoring doc.
- With **both** files: full RAG — retrieved context is packed into a prompt and fed to the LLM, and the answer is displayed alongside the hits.

---

## Where on the device do the files go?

Both files must live in the RestExplorer app's *external files dir*:

```
/storage/emulated/0/Android/data/com.salesforce.samples.restexplorer/files/
```

That path is:

- App-scoped (no extra permissions, no MANAGE_EXTERNAL_STORAGE).
- Writable via plain `adb push`.
- Automatically deleted when the app is uninstalled — easy to reset.

The resolver that picks the path lives in
[`RagModelPaths.kt`](../native/NativeSampleApps/RestExplorer/src/com/salesforce/samples/restexplorer/rag/RagModelPaths.kt).
Change filenames there if you want to match a different download name.

---

## Push commands

After downloading the files into this `model/` directory (or wherever you like), push them to the emulator or device:

```bash
# Adjust the source paths to match where the downloaded files actually live.
adb push model/universal_sentence_encoder.tflite \
  /storage/emulated/0/Android/data/com.salesforce.samples.restexplorer/files/

adb push model/llm.task \
  /storage/emulated/0/Android/data/com.salesforce.samples.restexplorer/files/
```

If the app hasn't been launched yet, the directory may not exist. Launch the RAG activity once (Dev Menu → "RAG Demo") to create it, then push.

Verify afterwards:

```bash
adb shell ls -lah /storage/emulated/0/Android/data/com.salesforce.samples.restexplorer/files/
```

---

## Launching the demo

1. Install the sample: `./gradlew :native:NativeSampleApps:RestExplorer:installDebug`
2. Open **RestExplorer** on the device.
3. Tap **Dev Menu** in the header, then **RAG Demo**.
4. The screen prints the absolute paths it's looking at and whether each file was found. If a file is missing, drop it in and restart the activity (Back → re-open from Dev Menu).
5. Tap **Index sample corpus** once — this embeds the 15 sample Salesforce help snippets (see `RagSampleCorpus.kt`) and writes them to the `ragDocs` soup in the *global* SmartStore DB.
6. Type a question, tap **Ask**. Latencies for embed, retrieval and generation are reported with the answer.

---

## Caveats / troubleshooting

- **Model format.** MediaPipe's LLM Inference only loads `.task` bundles. Raw `safetensors` / `.gguf` / `.bin` weights from Hugging Face must be converted first. Google publishes pre-converted `.task` bundles for Gemma 2 / 3 / 3n on Hugging Face — those are drop-in.
- **Memory.** A `.task` file larger than ~4 GB will likely OOM on mid-range phones. The demo does not set a `maxNumImages` / quant hint beyond the bundle's defaults.
- **Cold-start cost.** MediaPipe unpacks and mmap's the model on construction; expect a one-time hit of several seconds for the LLM. All loading happens on a background thread so the UI stays responsive, but controls are disabled until it completes.
- **Emulator.** x86_64 emulators can run both tasks but LLM throughput is ~5-10× slower than on arm64 hardware. Numbers captured on-device will differ materially from emulator numbers.
