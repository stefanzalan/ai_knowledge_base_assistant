# AI Knowledge Base Assistant

AI-powered internal knowledge base assistant for financial experts. Experts ask
natural-language questions about approved knowledge base documents; the system
retrieves relevant source material and produces grounded, traceable answers —
or abstains when reliable information cannot be found.

## Tech stack

- Java 21, Spring Boot 4.1.2, Maven
- Spring AI 2.0.2 (Ollama chat + embeddings, PGVector store)
- PostgreSQL 17 + pgvector, Spring Data JPA, Flyway
- Spring Boot Actuator, SpringDoc OpenAPI
- Ollama running natively on the host (GPU-accelerated)

## Prerequisites

- JDK 21
- Docker + Docker Compose (PostgreSQL only)
- [Ollama](https://ollama.com) installed natively on Windows

## 1. Start PostgreSQL (with pgvector)

```powershell
docker compose up -d
```

This starts PostgreSQL 17 (`pgvector/pgvector:pg17`) on `localhost:5432` with a
persistent volume and a health check. Default credentials are `knowledge` /
`knowledge` (database `knowledge_base`) — override via `POSTGRES_*` env vars.

## 2. Pull the AI models (manual, one-time)

Ollama runs on the host, outside Docker, so it can use the NVIDIA GPU directly.

```powershell
ollama pull qwen3:4b
ollama pull bge-m3
```

Verify Ollama is running and check GPU utilization:

```powershell
ollama list          # models available locally
ollama ps            # running models + GPU/CPU split
```

If `ollama ps` shows `100% GPU`, inference is hardware-accelerated.

## 3. Run the application

```powershell
.\mvnw spring-boot:run
```

The app connects to Ollama at `http://localhost:11434` by default. Model calls
are only made when the AI features are used — the app starts fine without
Ollama running, but AI requests will fail with a connection error until it is.

## Configuration

All settings are externalized in `src/main/resources/application.yml` and can be
overridden with environment variables. No credentials are committed.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/knowledge_base`, `knowledge` | PostgreSQL connection |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `OLLAMA_CHAT_MODEL` | `qwen3:4b` | Chat model |
| `OLLAMA_EMBEDDING_MODEL` | `bge-m3` | Embedding model |
| `EMBEDDING_DIMENSIONS` | `1024` | Vector dimensionality |
| `AI_REQUEST_TIMEOUT` | `120s` | AI provider request timeout |
| `RETRIEVAL_TOP_K` | `5` | Chunks retrieved per query |
| `RETRIEVAL_MIN_SIMILARITY` | `0.75` | Relevance threshold for abstention |

## Endpoints

- Health: `http://localhost:8080/actuator/health`
- OpenAPI docs: `http://localhost:8080/swagger-ui.html`
- API description: `http://localhost:8080/v3/api-docs`
- Ingest a Markdown document (multipart/form-data):

```powershell
curl -X POST http://localhost:8080/api/v1/admin/documents `
  -F "file=@docs/home-loan-faq.md" `
  -F "title=Home Loan FAQ" `
  -F "product=HOME_LOAN" `
  -F "sourceUri=kb://documents/home-loan-faq"
```

- Search the knowledge base (retrieval only, no answer generation):

```powershell
curl -X POST http://localhost:8080/api/v1/retrieval/search `
  -H "Content-Type: application/json" `
  -d '{"query":"What are the eligibility requirements for a home loan?","product":"HOME_LOAN"}'
```

Example response:

```json
{
  "query": "What are the eligibility requirements for a home loan?",
  "product": "HOME_LOAN",
  "chunks": [
    {
      "content": "## Eligibility\n\nApplicants must be at least 18 years old...",
      "documentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "documentVersionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "title": "Home Loan FAQ",
      "sourceUri": "kb://documents/home-loan-faq",
      "chunkIndex": 2,
      "similarityScore": 0.87
    }
  ]
}
```

### Retrieval semantics

- **Product filtering is mandatory.** Every search is scoped to the requested
  `FinancialProduct`; chunks from other products can never be returned.
- **Only the current ACTIVE version is searchable.** Each vector row carries a
  `retrievalEligible` metadata flag. It is `false` for every newly ingested
  chunk and is only flipped to `true` — inside the same transaction that
  activates a version — for the chunks of the version being activated. All
  other versions of that document are set back to `false`. The flag is part of
  the vector-store filter expression, so ineligible vectors are excluded by the
  database query itself, before top-K selection.
- `topK` and `minSimilarity` are tunable via `RETRIEVAL_TOP_K` (default 5) and
  `RETRIEVAL_MIN_SIMILARITY` (default 0.75). An empty `chunks` list is a valid
  response when nothing eligible matches.
- To test locally, start PostgreSQL (`docker compose up -d`), pull the models
  (`ollama pull bge-m3`), ingest a document, activate its version, then call
  the search endpoint — the query embedding uses the same `bge-m3` model as
  ingestion.

## Database migrations

Flyway owns the schema (`src/main/resources/db/migration`). Hibernate runs with
`ddl-auto=validate` and `spring.ai.vectorstore.pgvector.initialize-schema=false`
so Spring AI never competes with Flyway for schema creation.

- `V1` enables the `vector`, `hstore` and `uuid-ossp` extensions.
- `V2` creates `document`, `document_version`, `document_chunk` and the
  Spring AI `vector_store` table (`embedding vector(1024)`, HNSW cosine index).
  `vector_store.id` is a foreign key to `document_chunk.id`, so every embedding
  is relationally traceable to its document, version and chunk position.
- `V3` adds a unique index on `document(source_uri, product)` — the logical
  document identity used for idempotent ingestion.
- `V4` initializes the `retrievalEligible` JSONB flag on existing `vector_store`
  rows (`true` only for chunks of the ACTIVE, current version).
- `spring.ai.vectorstore.pgvector.schema-validation=true` makes the app verify
  the `vector_store` table (columns + dimensions) at startup.

## Build & test

```powershell
.\mvnw clean verify
```

The context test uses Testcontainers (`pgvector/pgvector:pg17`), so Docker must
be running. It verifies context startup, Flyway migration and JPA validation.

## Project structure

```
com.stefanzalan.ai_knowledge_base_assistant
├── common          # shared exception handling, cross-cutting concerns
├── product         # financial product domain (FinancialProduct enum)
├── document        # KB document ingestion, versioning & activation
├── chat            # (next phase) conversations & messages
├── retrieval       # RAG retrieval pipeline + tunables
└── ai              # AI provider integration (Ollama adapter, later phases)
```

## Roadmap

1. **Done:** Markdown ingestion pipeline (upload → checksum → versioning →
   chunking → embeddings → pgvector), product metadata, lifecycle statuses,
   version activation, and the filtered retrieval endpoint.
2. **Next:** approval workflow API (PENDING_APPROVAL → ACTIVE), RAG answer
   generation with citations, chat conversations/messages.
