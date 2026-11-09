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
| `RETRIEVAL_PRODUCT_FILTER_ENABLED` | `false` | Allow product-scoped questions |

## Endpoints (infrastructure only — no business endpoints yet)

- Health: `http://localhost:8080/actuator/health`
- OpenAPI docs: `http://localhost:8080/swagger-ui.html`
- API description: `http://localhost:8080/v3/api-docs`

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
├── document        # (next phase) KB document ingestion & versioning
├── chat            # (next phase) conversations & messages
├── retrieval       # (next phase) RAG retrieval pipeline + tunables
└── ai              # AI provider integration (Ollama adapter, later phases)
```

## Roadmap

1. **Done:** Markdown ingestion pipeline (upload → checksum → versioning →
   chunking → embeddings → pgvector), product metadata, lifecycle statuses.
2. **Next:** approval workflow (PENDING_APPROVAL → ACTIVE), RAG retrieval with
   product filtering and citation support, chat conversations/messages.
