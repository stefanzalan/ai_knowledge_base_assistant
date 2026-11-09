-- V4: Initialize retrievalEligible metadata on existing vector rows.
--
-- retrievalEligible=true only for chunks whose document version is the
-- currently published ACTIVE version (status='ACTIVE' AND is_current).
-- All other vectors (PENDING_APPROVAL, PROCESSING, FAILED, ARCHIVED,
-- non-current versions) are marked false so retrieval never returns them.
-- Rows are updated in place; nothing is deleted or recreated.
UPDATE vector_store v
SET metadata = jsonb_set(
        COALESCE(v.metadata, '{}'::jsonb),
        '{retrievalEligible}',
        to_jsonb(dv.status = 'ACTIVE' AND dv.is_current)
    )
FROM document_chunk c
JOIN document_version dv ON dv.id = c.document_version_id
WHERE v.id = c.id;
