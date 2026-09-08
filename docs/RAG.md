# RAG / Knowledge Base System

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

## Overview

Prométhé ships a pluggable Retrieval-Augmented Generation (RAG) system, referred to internally
as the **Knowledge Base**. It lets the agent ingest text documents, chunk them, embed them, store
the vectors, and later retrieve the most relevant passages for a natural-language query — combining
vector similarity search with keyword (FTS5) search via Reciprocal Rank Fusion, with optional
re-ranking and semantic caching.

The system is **disabled by default** (`RAG_ENABLED=false`). When disabled, no `KnowledgeBase` is
constructed and the `knowledge_search` / `knowledge_ingest` / `knowledge_delete` agent tools are not
registered.

---

## Architecture

### Core interfaces (`shared/src/commonMain/kotlin/dev/promethe/core/rag/`)

| File | Role |
|---|---|
| `EmbeddingService.kt` | Provider-agnostic interface for text embedding (`embed`, `embedBatch`, `testConnection`, `close`) |
| `VectorStore.kt` | Provider-agnostic interface for vector storage/retrieval (`upsert`, `upsertBatch`, `search`, `delete`, `deleteBySource`, `count`, `testConnection`, `close`) |

### Implementation (`shared/src/jvmMain/kotlin/dev/promethe/core/rag/`)

| File | Role |
|---|---|
| `KnowledgeBase.kt` | High-level façade — orchestrates ingest (chunk → embed → store) and search (embed query → hybrid search → return chunks). Single entry point used by the rest of Prométhé. |
| `EmbeddingServiceFactory.kt` | Builds an `EmbeddingService` from a provider name |
| `EmbeddingProviders.kt` | Concrete `EmbeddingService` implementations: `OllamaEmbeddingService`, `OpenAICompatibleEmbeddingService`, `GeminiEmbeddingService`, `VoyageEmbeddingService`, `CohereEmbeddingService` |
| `VectorStoreFactory.kt` | Builds a `VectorStore` from a store-type name |
| `SqliteVecStore.kt` | Embedded `VectorStore` using SQLite + manual cosine similarity, plus an FTS5 virtual table for keyword search |
| `ChromaVectorStore.kt`, `QdrantVectorStore.kt`, `PineconeVectorStore.kt`, `MilvusVectorStore.kt` | Remote/Docker-backed `VectorStore` implementations |
| `DocumentChunker.kt` | Fixed-size chunking (Markdown heading-aware, code block-aware, or plain-text paragraph/sentence splitting) |
| `SemanticChunker.kt` | Embedding-based chunking — splits on similarity drops between consecutive sentence embeddings |
| `HybridSearchEngine.kt` | Combines vector search + FTS5 keyword search using Reciprocal Rank Fusion (RRF) |
| `ReRanker.kt` | `ReRanker` interface + `EmbeddingReRanker` (bi-encoder cross-similarity re-ranking) |
| `SemanticCache.kt` | Caches search results keyed by query-embedding similarity |
| `KnowledgeTools.kt` | Agent tools: `KnowledgeSearchTool`, `KnowledgeIngestTool`, `KnowledgeDeleteTool` |

### Wiring

`KnowledgeBase` is constructed with an `EmbeddingService`, a `VectorStore`, and optionally a
`DocumentChunker`, a `SemanticChunker`, a `chunkingStrategy` (`FIXED` or `SEMANTIC`), a `ReRanker`,
and a `SemanticCache`. Internally it builds a `HybridSearchEngine`, wiring the vector store in as
the `HybridSearchEngine.FtsSearcher` when it implements that interface (only `SqliteVecStore` does).

Two places construct `KnowledgeBase` in the running app:

- `IntegrationRegistrar.kt` (`shared/src/jvmMain/kotlin/dev/promethe/core/IntegrationRegistrar.kt`,
  around lines 231-270) — builds it from env-var config at startup and registers the three
  `KnowledgeTools` with `ToolRegistry` if `RAG_ENABLED=true`. It only passes a `DocumentChunker`
  (no semantic chunker, re-ranker, or semantic cache), so the agent-tool path always uses fixed-size
  chunking, hybrid search without re-ranking, and no caching.
- `RagRoutes.kt` (`gateway/src/jvmMain/kotlin/dev/promethe/gateway/RagRoutes.kt`) — builds/rebuilds
  it from the persisted `RagConfig` (via `CredentialsStore`) whenever the config is changed through
  the HTTP API or the Settings UI, and re-registers `KnowledgeSearchTool` on `ToolRegistry`. Same
  chunker-only wiring — no semantic chunking, re-ranker, or semantic cache here either.

Note: there is a separate, simpler `LocalVectorStore` / `VectorSearchTool` under
`dev.promethe.core.tools.ai` (in-memory cosine similarity + JSON persistence, tested by
`shared/src/jvmTest/kotlin/dev/promethe/core/tools/ai/LocalVectorStoreTest.kt`). It is not part of
the `KnowledgeBase` RAG pipeline described here.

---

## Embedding providers & vector stores

### Embedding providers (`EmbeddingServiceFactory`)

| Provider key | Implementation | Default model | Default dimensions |
|---|---|---|---|
| `ollama` | `OllamaEmbeddingService` | `nomic-embed-text` | 768 |
| `openai` | `OpenAICompatibleEmbeddingService` (`https://api.openai.com`) | `text-embedding-3-small` | 1536 |
| `litellm` | `OpenAICompatibleEmbeddingService` (proxy, default `http://localhost:4000`) | — | — |
| `mistral` | `OpenAICompatibleEmbeddingService` (`https://api.mistral.ai`) | `mistral-embed` | 1024 |
| `gemini` | `GeminiEmbeddingService` | `text-embedding-004` | 768 |
| `voyage` | `VoyageEmbeddingService` | `voyage-3` | 1024 |
| `cohere` | `CohereEmbeddingService` | `embed-v4.0` | 1024 |

Unknown provider names fall back to Ollama. `EmbeddingServiceFactory.create()` accepts an explicit
`dimensions` parameter (default 768) which is passed through regardless of provider.

Known embedding models (with pricing/description metadata used by the Settings UI) are listed in
`EmbeddingModelInfo.KNOWN_MODELS` in `api/src/commonMain/kotlin/dev/promethe/api/v1/RagModels.kt` —
covers Ollama (`nomic-embed-text`, `mxbai-embed-large`, `all-minilm`, `snowflake-arctic-embed`),
OpenAI (`text-embedding-3-small/large`), Gemini (`text-embedding-004`,
`text-multilingual-embedding-002`), Voyage (`voyage-3`, `voyage-3-lite`, `voyage-code-3`), Mistral
(`mistral-embed`), and Cohere (`embed-v4.0`).

### Vector stores (`VectorStoreFactory`)

| Store key | Implementation | Default endpoint |
|---|---|---|
| `sqlite_vec` / `sqlite` | `SqliteVecStore` (embedded, zero-config, default) | local file (`vectors.db` if no path given) |
| `chroma` | `ChromaVectorStore` | `http://localhost:8000` |
| `qdrant` | `QdrantVectorStore` | `http://localhost:6333` |
| `pinecone` | `PineconeVectorStore` | requires explicit `indexUrl` |
| `milvus` / `zilliz` | `MilvusVectorStore` | `http://localhost:19530` |

Unknown store types fall back to `SqliteVecStore`. `SqliteVecStore` is documented as "optimized for
simplicity and small-to-medium datasets (< 100K vectors)"; the doc comment recommends
Qdrant/Pinecone/Milvus for experiments with larger datasets; this does not change the project's non-production status.

`SqliteVecStore` stores embeddings as BLOBs (serialized `FloatArray`) in a plain table plus an
FTS5 virtual table (`tokenize='unicode61'`) kept in sync via SQL triggers on insert/delete/update —
this FTS5 table is what powers the keyword side of hybrid search.

---

## Search pipeline

### Ingestion (`KnowledgeBase.ingest`)

1. **Chunk** — either `ChunkingStrategy.FIXED` (`DocumentChunker`, default) or
   `ChunkingStrategy.SEMANTIC` (`SemanticChunker`).
   - `DocumentChunker` picks a strategy by file extension: Markdown (`md`/`markdown`) splits on
     headings and preserves heading text as `heading` metadata; code extensions
     (`kt`, `java`, `py`, `js`, `ts`, `go`, `rs`, `c`, `cpp`, `cs`) split on function/class/`def`
     boundaries; everything else splits on paragraph/sentence boundaries. Oversized sections are
     further split with `splitByTokenLimit` (rough estimate: 1 token ≈ 4 chars), respecting
     `maxChunkSize` (default 512 tokens) and `overlapSize` (default 50 tokens).
   - `SemanticChunker` splits the document into sentences, batch-embeds them, computes cosine
     similarity between consecutive sentence embeddings, and places a chunk boundary wherever
     similarity drops below `similarityThreshold` (default 0.75). Chunks below `minChunkTokens`
     (default 50) are merged with a neighbor; chunks above `maxChunkTokens` (default 512) are
     force-split via `DocumentChunker.splitByTokenLimit`.
2. **Embed** — all chunks are embedded in one `embedBatch` call.
3. **Store** — chunks are upserted as `VectorItem`s with id `"${docId}_chunk_${index}"` and metadata
   including `doc_id`.
4. The in-memory document registry (`documents: MutableMap<String, DocumentInfo>`) is updated, and
   any configured `SemanticCache` is fully invalidated (`invalidateAll()`), since new content may
   now be more relevant than what was cached.

### Search (`KnowledgeBase.search` → `HybridSearchEngine.search`)

`KnowledgeBase.search(query, topK, mode)` first checks the `SemanticCache` (if configured); on a
miss it delegates to `HybridSearchEngine`, then stores the fresh results back into the cache.

`HybridSearchEngine.SearchMode` has three values:

- **`VECTOR_ONLY`** — embeds the query and calls `VectorStore.search`.
- **`KEYWORD_ONLY`** — calls `FtsSearcher.keywordSearch` (throws `IllegalStateException` if no FTS
  searcher is configured).
- **`HYBRID`** (default) — runs both retrievers (over-fetching `topK * 4` candidates from each) and
  fuses them with **Reciprocal Rank Fusion (RRF)**. If no FTS searcher is available, hybrid mode
  silently falls back to vector-only.

RRF formula (see `HybridSearchEngine.reciprocalRankFusion`):

```
RRF(d) = vectorWeight / (rrfK + rank_vec(d)) + ftsWeight / (rrfK + rank_fts(d))
```

Defaults: `rrfK = 60`, `vectorWeight = 0.6`, `ftsWeight = 0.4` (also the defaults `KnowledgeBase`
passes through to its internal `HybridSearchEngine`). RRF is rank-based rather than score-based, so
it works even though vector search returns cosine similarity (`[0,1]`, higher is better) and FTS5
returns a BM25 rank (negative, lower is "better" — `SqliteVecStore.keywordSearch` negates it before
returning). Fused results carry `vec_rank`, `fts_rank`, and `rrf_score` metadata.

`SqliteVecStore.keywordSearch` escapes the query for FTS5 by splitting on whitespace and joining
terms with `OR`, each quoted (e.g. `"foo" OR "bar"`).

### Re-ranking (optional)

If a `ReRanker` is supplied to `KnowledgeBase`, `HybridSearchEngine` over-fetches `topK * 3`
candidates and passes them through `ReRanker.rerank(query, candidates, topK)` before returning.

The only implementation, `EmbeddingReRanker`, is a bi-encoder approximation of a cross-encoder: it
re-embeds the query and each candidate's content, computes cosine similarity, and blends it with
the original retrieval score:

```
blendedScore = (1 - rerankerWeight) * originalScore + rerankerWeight * crossSimilarity
```

Default `rerankerWeight = 0.7`. Result metadata gains `reranker_cross_sim`, `reranker_original`,
and `reranker_blended`.

### Semantic cache (optional)

`SemanticCache` keys entries by the original query text but matches on **embedding similarity**,
not exact text: on `get(query)`, it embeds the query and scans all cached entries for the closest
cosine similarity; a hit requires similarity ≥ `similarityThreshold` (default 0.95). It is an
LRU-style cache: `maxEntries` (default 1000), evicting the least-recently-accessed entry when full,
plus a TTL (`ttlMs`, default 3,600,000 ms / 1 hour) with expired-entry sweep on every `get`. It is
thread-safe via `ReentrantReadWriteLock` and tracks `hits`/`misses` counters exposed via `stats()`.
`KnowledgeBase.ingest()` calls `semanticCache?.invalidateAll()` after every ingestion.

Neither `IntegrationRegistrar` nor `RagRoutes` currently constructs a `ReRanker` or a
`SemanticCache` — both features exist and are unit-tested, but are not wired into the running
gateway/agent by default; a `KnowledgeBase` would need to be constructed manually (or the wiring
code extended) to use them.

---

## Configuration

Read at startup by `IntegrationRegistrar.kt` (`shared/src/jvmMain/kotlin/dev/promethe/core/IntegrationRegistrar.kt`,
~lines 231-270), via `ConfigProvider`:

| Env var | Default | Notes |
|---|---|---|
| `RAG_ENABLED` | `false` | Master switch — if `false`, no `KnowledgeBase` is built and no `knowledge_*` tools are registered |
| `RAG_EMBEDDING_PROVIDER` | `ollama` | One of `ollama`, `openai`, `litellm`, `mistral`, `gemini`, `voyage`, `cohere` |
| `RAG_EMBEDDING_MODEL` | `nomic-embed-text` | Passed to the provider's `EmbeddingService` |
| `RAG_EMBEDDING_URL` | `http://localhost:11434` | Base URL (Ollama endpoint by default; used as `baseUrl` for `litellm` too) |
| `RAG_EMBEDDING_API_KEY` | `""` | API key for cloud embedding providers |
| `RAG_VECTOR_STORE` | `sqlite_vec` | One of `sqlite_vec`/`sqlite`, `chroma`, `qdrant`, `pinecone`, `milvus`/`zilliz` |
| `RAG_VECTOR_URL` | `""` | Endpoint for remote vector stores |
| `RAG_VECTOR_API_KEY` | `""` | API key for remote vector stores |
| `RAG_VECTOR_PATH` | `<workDir>/rag_vectors` | Local path for `SqliteVecStore` |
| `RAG_EMBEDDING_DIMENSIONS` | `768` | Passed as `vectorSize` to `VectorStoreFactory` and as `dimensions` intent to the embedding service |

If `KnowledgeBase` construction throws inside `IntegrationRegistrar`, the exception is caught and
logged as a warning ("Failed to initialize RAG knowledge tools — skipping"); the agent simply runs
without the RAG tools rather than failing to start.

Separately, the gateway persists its own `RagConfig` (see `api/src/commonMain/kotlin/dev/promethe/api/v1/RagModels.kt`)
to `~/.promethe/credentials.json` via `CredentialsStore`, independent of the env vars above. Its
defaults: `enabled=false`, `embeddingProvider=OLLAMA`, `embeddingModel=nomic-embed-text`,
`embeddingBaseUrl=http://localhost:11434`, `embeddingDimensions=768`,
`vectorStoreType=SQLITE_VEC`, `chunkSize=512`, `chunkOverlap=50`. This is the config read/written by
the `/rag/config` HTTP endpoint and the Settings UI, and is loaded at gateway startup
(`ragRoutes()` calls `CredentialsStore.load()` and rebuilds the `KnowledgeBase` if `enabled=true`).

---

## Agent tools

Registered on `ToolRegistry` only when RAG is enabled (`KnowledgeTools.kt`):

| Tool | Description |
|---|---|
| `knowledge_search` | Semantic search over the knowledge base; args `query`, `topK` (default 5). Returns formatted top-K chunks with score, source, and heading. |
| `knowledge_ingest` | Ingests a document; args `content`, `filename`, optional `category` (stored as metadata). Returns doc ID, chunk count, and estimated token count. |
| `knowledge_delete` | Deletes a document by `docId`, or lists all ingested documents if `listFirst=true`. |

Tool descriptions and output strings in `KnowledgeTools.kt` are written in French (the rest of the
codebase's tool layer follows the same convention for these three tools).

---

## HTTP API

Base path: `/rag` (mounted under `/api` by the gateway routing, per `RagRoutesTest.kt`). Defined in
`gateway/src/jvmMain/kotlin/dev/promethe/gateway/RagRoutes.kt`.

| Method | Route | Description |
|---|---|---|
| `GET` | `/rag/models` | Returns `EmbeddingModelInfo.KNOWN_MODELS` (known embedding models + pricing metadata) |
| `GET` | `/rag/config` | Returns the current `RagConfig` |
| `PUT` | `/rag/config` | Updates `RagConfig`; rebuilds the `KnowledgeBase` if the config changed (or none exists yet) and `enabled=true`; re-registers `KnowledgeSearchTool`; persists to `credentials.json` |
| `POST` | `/rag/test-embedding` | Builds a temporary `EmbeddingService` from the current config and calls `testConnection()` |
| `GET` | `/rag/documents` | Lists ingested documents (empty list if RAG not initialized) |
| `POST` | `/rag/ingest` | Ingests a document (`RagIngestRequest`: `content`, `filename`, `metadata`); `503` if RAG not enabled |
| `POST` | `/rag/search` | Semantic search (`RagSearchRequest`: `query`, `topK`); `503` if RAG not enabled |
| `DELETE` | `/rag/documents/{id}` | Deletes a document by ID; `503` if RAG not enabled |
| `GET` | `/rag/status` | Connectivity/status: embedding + vector store health, provider/model/store type, dimensions, total chunks, document count |

When the `KnowledgeBase` is `null` (RAG disabled or never configured), `ingest`/`search`/`delete`
return `503 Service Unavailable`, while `documents` returns an empty list and `status` returns a
minimal `{enabled: false, provider, vectorStore}` payload — confirmed by `RagRoutesTest.kt`.

`PUT /rag/config` only rebuilds the `KnowledgeBase` when `config.enabled` is `true` and either the
config actually changed or no `KnowledgeBase` exists yet; setting `enabled=false` closes and clears
the existing `KnowledgeBase` (`knowledgeBase?.close()`).

---

## Limitations

- RAG is off by default and must be explicitly enabled (`RAG_ENABLED=true` or via `PUT /rag/config`
  with `enabled: true`).
- `SqliteVecStore.search` (vector similarity) loads **all** rows matching the filter into memory and
  computes cosine similarity in Kotlin — there is no ANN index; the code comment states it's meant
  for datasets under ~100K vectors.
- `OllamaEmbeddingService.embedBatch` has no native batch endpoint and falls back to sequential
  per-text calls to Ollama.
- Neither of the two `KnowledgeBase` construction sites in the codebase (`IntegrationRegistrar`,
  `RagRoutes`) wires up `SemanticChunker`, `ReRanker`, or `SemanticCache` — these components exist
  and are unit-tested in isolation, but the running app only ever uses fixed-size chunking, hybrid
  search without re-ranking, and no caching, unless that wiring is extended.
- Hybrid search's `KEYWORD_ONLY` mode throws `IllegalStateException` if the configured
  `VectorStore` doesn't implement `HybridSearchEngine.FtsSearcher` (only `SqliteVecStore` does) —
  keyword-only and hybrid-with-FTS search are effectively SQLite-only features; other vector stores
  fall back silently to vector-only search in `HYBRID` mode.
- `DocumentChunker`'s token counting is a rough heuristic (1 token ≈ 4 characters), not a real
  tokenizer.
- The in-memory `documents` registry inside `KnowledgeBase` (source of `listDocuments()`) is not
  persisted — it is rebuilt from nothing each time a new `KnowledgeBase` instance is constructed
  (e.g., every gateway restart, or every config change that triggers a rebuild), even though the
  underlying vector store data may still be on disk.
- There is no CLI subcommand for RAG (unlike Skills' `promethe skills ...` commands) — the system is
  driven entirely through agent tools, the HTTP API, or the Settings UI.
