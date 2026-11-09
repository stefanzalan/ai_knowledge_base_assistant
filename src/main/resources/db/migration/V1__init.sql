-- Enable pgvector for storing document chunk embeddings.
-- The vector_store table and domain schema are introduced in later migrations.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";