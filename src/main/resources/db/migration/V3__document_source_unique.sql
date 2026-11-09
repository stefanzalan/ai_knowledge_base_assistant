-- V3: A logical document is identified by (source_uri, product).
-- Enforce uniqueness at the database level so concurrent ingestion
-- requests cannot create duplicate logical documents.
CREATE UNIQUE INDEX document_source_uri_product_key
    ON document (source_uri, product);
