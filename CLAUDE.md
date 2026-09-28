# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

oncall-agent — a Spring Boot 3.5 (Java 17) backend that indexes documents into a Milvus vector store for RAG (retrieval-augmented generation). It's currently a service-layer-only codebase: there are no REST controllers yet. Code comments, log messages, and exception messages are written in Chinese — match that style.

## Commands

Maven wrapper (Maven 3.9.16); shell is Git Bash on Windows, so use `./mvnw`:

```bash
./mvnw compile                # compile
./mvnw spring-boot:run        # run (port 9900)
./mvnw test                   # all tests
./mvnw test -Dtest=OncallAgentApplicationTests            # single test class
./mvnw test -Dtest=OncallAgentApplicationTests#contextLoads  # single test method
./mvnw package                # build jar
```

## External Dependencies (required at startup)

The app fails to start unless both are available:

- **Milvus** at `localhost:19530` (configurable via `milvus.*` in `application.yml`). Connected at startup by `MilvusClientFactory`, which also auto-creates the `biz` collection and its IVF_FLAT/L2 index if missing.
- **DashScope API key**: read from the `DASHSCOPE_API_KEY` env var (application.yml has a fallback default). Set as a static field (`Constants.apiKey`) in `VectorEmbeddingService.init()`.

Because `@SpringBootTest` boots the full context, even `contextLoads` requires both Milvus and a valid API key.

## Architecture

Document ingestion pipeline (no controller triggers it yet — call the services or wire one up):

1. **`DocumentChunkService`** (`service/`) — chunks a document: first splits by Markdown headings (`#{1,6}`), then splits oversized sections by paragraph boundaries with overlap (`document.chunk.max-size`=800, `overlap`=100). Each chunk carries its section title.
2. **`VectorEmbeddingService`** (`service/`) — generates embeddings via Alibaba DashScope SDK, model `text-embedding-v4` (`dashscope.embedding.model`). Both single (`generateEmbedding`) and batch (`generateEmbeddings`) APIs.
3. **`VectorIndexService`** (`service/`) — orchestrates indexing one file: read → delete existing rows for that file → chunk → embed each chunk → insert into Milvus.

### Milvus layer

- `MilvusProperties` (`config/`) — `@ConfigurationProperties(prefix = "milvus")` POJO.
- `MilvusClientFactory` (`client/`) — builds `MilvusServiceClient`, creates the `biz` collection (schema: `id` VarChar primary key, `vector` FloatVector, `content` VarChar, `metadata` JSON) and index on first run.
- `MilvusConfig` (`config/`) — exposes the client as a singleton Spring bean, closes it in `@PreDestroy`.
- `MilvusConstants` (`constant/`) — collection name, vector dimension, field length limits.

### Key invariants

- **`MilvusConstants.VECTOR_DIM` (1024) must match the embedding model's output dimension.** Changing `dashscope.embedding.model` requires updating this constant (and the collection would need re-creating). Note: the comment there says the dim comes from "豆包" but the actual provider is DashScope — the comment is stale.
- **Deterministic IDs**: chunk ID = `UUID.nameUUIDFromBytes(sourcePath + "_" + chunkIndex)` — same file always maps to the same IDs, and `deleteExistingData` deletes by `metadata["_source"] == <path>` before re-inserting, making re-indexing idempotent.
- **`loadCollection` status 65535 is treated as success** ("collection already loaded") — this special case is intentional in `VectorIndexService`.
- Paths are normalized to forward slashes before being stored in `metadata["_source"]`; keep that convention consistent for the delete-by-source expression to work.

### Config classes (`config/`)

- `DocumentChunkConfig` / `FileUploadConfig` — `@ConfigurationProperties` POJOs (`document.chunk.*`, `file.upload.*`).
- `WebMvcConfig` — CORS allow-all, static resources from `classpath:/static`, UTF-8 message converters (fixes Chinese garbled text in responses).
- `DashScopeConfig` — `RestClient.Builder` bean backed by OkHttpClient with 180s timeouts, for future LLM HTTP calls.

## Quirk: vendored Spring sources at repo root

`org/springframework/http/client/*.java` at the repo root are copied-in Spring Framework sources (5.x era) for `OkHttp3ClientHttpRequestFactory` — the class `DashScopeConfig` depends on. They are NOT part of the Maven build (only `src/main/java` is compiled); don't edit them and don't move them into `src/`.
