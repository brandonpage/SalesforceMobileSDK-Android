# sqlite-vector \+ SmartStore: Feasibility Analysis

## Difficulty Level: Hard (but tractable)

There are three tiers of challenge:

---

## Tier 1 — Native Library Integration (Hardest Part)

This is the single biggest obstacle. SmartStore uses SQLCipher (net.zetetic:sqlcipher-android:4.10.0), which is a custom build of SQLite with AES-256 encryption. sqlite-vector is a SQLite runtime-loadable extension. The problem:

- SQLCipher disables load\_extension() by default for security. Even if you enable it, the extension must be compiled against the same SQLite headers that SQLCipher uses internally — not stock SQLite.  
- The sqlite-vector Android package is built against stock SQLite, not SQLCipher. You can't just drop in the .so and call SELECT load\_extension('vector').  
- The real path: You'd need to either:  
  1. **Compile sqlite-vector into a custom SQLCipher build** — link the vector extension statically into the SQLCipher native library so it's available automatically. This means forking/customizing the SQLCipher Android build and bundling sqlite-vector's C code directly.  
  2. **Compile sqlite-vector against SQLCipher's internal SQLite headers** and enable load\_extension() via PRAGMA — risky from a security standpoint and fragile across SQLCipher version upgrades.

Option 1 is more robust but means maintaining a custom SQLCipher build. This is a significant ongoing maintenance burden.

---

## Tier 2 — SmartStore Schema & Index Integration (Medium)

Assuming native integration is solved, the SmartStore API changes are moderate:

1. **New Type enum value**: Add vector("BLOB") to SmartStore.Type (line 1530). This tells SmartStore to create a BLOB column for the vector data.  
     
2. **New TypeGroup membership**: Vector columns would be value\_extracted\_to\_column — the embedding gets extracted from the JSON document and stored in a physical BLOB column.  
     
3. **IndexSpec changes**: IndexSpec would need new metadata fields:  
     
   - dimension (int) — vector dimensionality (e.g., 384, 768, 1536\)  
   - distanceMetric (enum) — L2, cosine, dot product  
   - vectorType (enum) — float32, float16, int8, etc.

   

4. **Soup registration**: registerSoup() would need to call vector\_init() after table creation to register the vector column with the extension.  
     
5. **Data projection**: projectIndexedPaths() would need to convert JSON arrays (the embedding) to the BLOB format that sqlite-vector expects (via vector\_as\_f32()).  
     
6. **QuerySpec changes**: A new query type (e.g., vector\_search or similar) alongside exact, range, like, match, smart. This would generate the JOIN-based scan SQL:

```sql
SELECT t.id, v.distance FROM TABLE_1 t
  JOIN vector_quantize_scan('TABLE_1', 'table_1_3', ?, 20) v
  ON t.id = v.rowid
```

7. **Smart SQL**: The SmartSqlHelper would need to handle vector-specific syntax, possibly {soupName:embedding SIMILAR TO ? LIMIT 20} or a new function-style reference.

---

## Tier 3 — API Surface for SDK Consumers (Straightforward)

New public APIs needed:

```kotlin
// IndexSpec extension
IndexSpec("embedding", SmartStore.Type.vector,
    dimension = 384,
    distanceMetric = DistanceMetric.COSINE,
    vectorType = VectorType.FLOAT32)

// New QuerySpec factory
QuerySpec.buildVectorSearchSpec(
    soupName = "articles",
    vectorPath = "embedding",
    queryVector = floatArrayOf(...),
    k = 20,  // top-K results
    orderPath = null  // optional secondary sort
)

// Convenience on SmartStore
smartStore.vectorSearch(
    soupName = "articles",
    path = "embedding",
    queryVector = floatArrayOf(...),
    k = 20
): JSONArray
```

---

## API Changes for SDK Consumers

| Change | Breaking? | Notes |
| :---- | :---- | :---- |
| New Type.vector enum value | No | Additive |
| New IndexSpec constructor overload | No | Existing constructors unchanged |
| New QuerySpec.buildVectorSearchSpec() | No | Additive factory method |
| New SmartStore.vectorSearch() | No | Additive convenience method |
| New DistanceMetric enum | No | New type |
| New VectorType enum | No | New type |
| Increased AAR size (\~2-5MB) | Non-breaking but notable | Native .so for vector extension per ABI |
| New dependency (sqlite-vector native) | Non-breaking but notable | Requires legal/license review |

**No breaking changes.** All additions are additive. Apps that don't use vector search are unaffected.

---

## Benefits for On-Device AI Apps

1. **Retrieval-Augmented Generation (RAG) on-device**: Apps running local LLMs (via ONNX Runtime, MediaPipe, llama.cpp, etc.) can store document embeddings in SmartStore and retrieve relevant context before generating responses — all offline, all encrypted.  
     
2. **Semantic search over Salesforce data**: Instead of keyword-matching on synced records, users could search by meaning. "Find accounts similar to this one" becomes a vector query over embeddings computed from account descriptions, notes, or activity history.  
     
3. **Offline AI that respects MobileSync**: Embeddings stay in SmartStore soups alongside the records they describe. When MobileSync syncs down new records, the app computes embeddings and stores them. The same sync lifecycle, conflict resolution, and encryption guarantees apply.  
     
4. **Privacy**: All vector operations happen locally on encrypted data. No embeddings leave the device. This is a significant differentiator vs. cloud-only vector DBs.  
     
5. **Reduced latency**: No network round-trip for similarity search. Sub-millisecond queries for typical embedding sizes (384-1536 dimensions) on modern Android hardware.

---

## App Workflow

### Step 1: Soup Registration (App Startup)

```kotlin
smartStore.registerSoup("articles", listOf(
    IndexSpec("Title", SmartStore.Type.string),
    IndexSpec("Id", SmartStore.Type.string),
    IndexSpec("embedding", SmartStore.Type.vector,
        dimension = 384, distance = COSINE)
))
```

### Step 2: Sync Down (MobileSync Pulls Salesforce Records)

```kotlin
syncManager.syncDown(
    SoqlSyncDownTarget("SELECT Id, Title, Body FROM KnowledgeArticle"),
    "articles", ...
)
```

### Step 3: Compute Embeddings (On-Device AI Model)

```kotlin
for (article in smartStore.query("articles")) {
    val embedding = localModel.encode(article.getString("Body"))
    article.put("embedding", embedding.toList())
    smartStore.upsert("articles", article)
}
// SmartStore extracts the float array, converts to BLOB
// via vector_as_f32(), stores in vector column
```

### Step 4: Semantic Search (User Queries)

```kotlin
val queryEmbedding = localModel.encode(userQuery)

val results = smartStore.vectorSearch(
    soupName = "articles",
    path = "embedding",
    queryVector = queryEmbedding,
    k = 10
)
// Returns top-10 most similar articles with distance scores
```

### Step 5: RAG Generation (Optional — Feed to Local LLM)

```kotlin
val context = results.map { it.getString("Body") }
val answer = localLLM.generate(
    prompt = "Based on: $context\n\nAnswer: $userQuery"
)
```

---

## Summary

| Dimension | Assessment |
| :---- | :---- |
| Overall difficulty | Hard — primarily due to SQLCipher native integration |
| Biggest risk | Maintaining a custom SQLCipher build with sqlite-vector baked in |
| API changes | All additive, no breaking changes |
| Consumer impact | Zero for existing apps; opt-in for new apps |
| Value for on-device AI | High — enables encrypted, offline RAG and semantic search over synced Salesforce data |
| License concern | sqlite-vector's license needs legal review before bundling in a public SDK |
| Size impact | \~2-5MB additional native library per ABI |

The hardest 80% of the work is the native build integration with SQLCipher. The SmartStore API layer on top is a well-understood pattern — it mirrors exactly how FTS5 was added (new type, new query mode, virtual table integration). If you can solve the native layer, the rest follows the existing architecture cleanly.  
