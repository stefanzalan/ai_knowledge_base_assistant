-- V2: Knowledge base schema
--
-- Design notes:
-- * Financial product and lifecycle status are stored as varchar with CHECK
--   constraints mirroring the Java enums (FinancialProduct, DocumentStatus).
--   This keeps a single Java-side definition while enforcing integrity in DB.
-- * document_version.is_current is enforced by a partial unique index so a
--   document can never have two "current" versions.
-- * vector_store follows the exact structure Spring AI 2.0.2 PgVectorStore
--   expects (id uuid PK, content text, metadata json, embedding vector(N)),
--   with two deliberate deviations:
--     - metadata is jsonb (Spring AI inserts ?::jsonb and only requires the
--       column to exist; jsonb enables efficient JSON-path filtering).
--     - id is a FK to document_chunk.id, giving relational traceability from
--       every vector record to its chunk. Spring AI accepts caller-provided
--       UUID document ids, so the vector row shares the chunk's UUID.
--   vector_store.content duplicates document_chunk.content because the
--   Spring AI schema mandates a content column; document_chunk remains the
--   domain source of truth.

CREATE TABLE document (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title             varchar(500)  NOT NULL,
    original_filename varchar(500)  NOT NULL,
    source_uri        varchar(1000) NOT NULL,
    product           varchar(20)   NOT NULL,
    status            varchar(30)   NOT NULL DEFAULT 'UPLOADED',
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT document_product_check CHECK (product IN ('HOME_LOAN', 'PERSONAL_LOAN', 'BANK_ACCOUNT')),
    CONSTRAINT document_status_check  CHECK (status IN ('UPLOADED', 'PROCESSING', 'PENDING_APPROVAL', 'ACTIVE', 'FAILED', 'ARCHIVED'))
);

CREATE INDEX document_product_idx ON document (product);
CREATE INDEX document_status_idx  ON document (status);

CREATE TABLE document_version (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     uuid        NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    version_number  integer     NOT NULL,
    source_checksum varchar(128) NOT NULL,
    status          varchar(30) NOT NULL DEFAULT 'UPLOADED',
    is_current      boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_version_status_check CHECK (status IN ('UPLOADED', 'PROCESSING', 'PENDING_APPROVAL', 'ACTIVE', 'FAILED', 'ARCHIVED')),
    CONSTRAINT document_version_number_unique  UNIQUE (document_id, version_number),
    CONSTRAINT document_version_checksum_unique UNIQUE (document_id, source_checksum)
);

-- At most one current version per document.
CREATE UNIQUE INDEX document_version_one_current_idx
    ON document_version (document_id) WHERE is_current;

CREATE INDEX document_version_document_idx ON document_version (document_id);

CREATE TABLE document_chunk (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_version_id uuid        NOT NULL REFERENCES document_version (id) ON DELETE CASCADE,
    chunk_index         integer     NOT NULL,
    content             text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_chunk_order_unique UNIQUE (document_version_id, chunk_index)
);

CREATE INDEX document_chunk_version_idx ON document_chunk (document_version_id);

-- Spring AI PGVectorStore table (public.vector_store).
-- id doubles as FK to document_chunk.id: when documents are added to the
-- vector store, the Spring AI Document id is set to the chunk UUID.
CREATE TABLE vector_store (
    id         uuid PRIMARY KEY REFERENCES document_chunk (id) ON DELETE CASCADE,
    content    text,
    metadata   jsonb,
    embedding  vector(1024)
);

CREATE INDEX spring_ai_vector_index ON vector_store USING hnsw (embedding vector_cosine_ops);
