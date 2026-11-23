-- Semantic (embedding) index for hybrid FTS + vector retrieval.
-- Requires the pgvector extension to be available on the server (e.g. pgvector/pgvector:pg17 image).
CREATE EXTENSION IF NOT EXISTS vector;

-- 1024 dimensions: Qwen3-Embedding is Matryoshka-trained, so requesting 1024 of its 4096 dims keeps
-- most of the quality at 1/4 of the storage and stays under pgvector's 2000-dim limit for HNSW should
-- an ANN index ever be needed. Changing the dimension count requires a new migration altering this column;
-- the application refuses to use semantic search while configured dimensions differ from the column.
CREATE TABLE semantic_embeddings (
    id                  UUID PRIMARY KEY,
    user_id             UUID          NOT NULL,
    source_type         VARCHAR(40)   NOT NULL,
    job_application_id  UUID,
    base_information_id UUID,
    content             TEXT          NOT NULL,
    content_hash        VARCHAR(64)   NOT NULL,
    model               VARCHAR(200)  NOT NULL,
    dimensions          INTEGER       NOT NULL,
    embedding           vector(1024),
    status              VARCHAR(20)   NOT NULL,
    attempts            INTEGER       NOT NULL DEFAULT 0,
    last_error          VARCHAR(1000),
    next_attempt_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    source_updated_at   TIMESTAMP,
    source_synced_at    TIMESTAMPTZ,
    embedded_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_semantic_embeddings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_semantic_embeddings_application FOREIGN KEY (job_application_id)
        REFERENCES job_applications (id) ON DELETE CASCADE,
    CONSTRAINT fk_semantic_embeddings_base_information FOREIGN KEY (base_information_id)
        REFERENCES google_drive_base_information (id) ON DELETE CASCADE,
    CONSTRAINT uk_semantic_embeddings_application UNIQUE (job_application_id),
    CONSTRAINT uk_semantic_embeddings_base_information UNIQUE (base_information_id),
    CONSTRAINT ck_semantic_embeddings_source CHECK (
        (source_type = 'JOB_APPLICATION' AND job_application_id IS NOT NULL AND base_information_id IS NULL)
        OR (source_type = 'PROFILE_DOCUMENT' AND base_information_id IS NOT NULL AND job_application_id IS NULL)),
    CONSTRAINT ck_semantic_embeddings_status CHECK (
        status IN ('PENDING', 'READY', 'FAILED', 'EMPTY', 'SOURCE_ERROR')),
    CONSTRAINT ck_semantic_embeddings_dimensions CHECK (
        embedding IS NULL OR vector_dims(embedding) = dimensions)
);

-- Similarity queries are always scoped to one user and scanned exactly (no ANN index): with a tenant
-- filter an HNSW/IVFFlat scan returns global neighbours first and filters afterwards, silently losing
-- recall for small tenants. Per-user row counts here are small enough for an exact scan.
CREATE INDEX idx_semantic_embeddings_user_source ON semantic_embeddings (user_id, source_type, status);
CREATE INDEX idx_semantic_embeddings_queue ON semantic_embeddings (next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED', 'SOURCE_ERROR');
