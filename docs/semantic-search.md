# Ask ApplyWell: hybrid FTS + pgvector search

Ask ApplyWell's `searchApplications` tool combines PostgreSQL full-text search with vector similarity
from OpenRouter embeddings, and a new `matchApplicationsToProfile` tool ranks applications against the
user's own profile documents (Drive "base information"). Everything is off by default and FTS keeps
working unchanged when it is off or when the embeddings provider is down.

## Enabling it

1. The database server needs the [pgvector](https://github.com/pgvector/pgvector) extension installed.
   Flyway `V38` runs `CREATE EXTENSION IF NOT EXISTS vector`, which only registers an extension whose
   binaries are already on the server; with the stock `postgres` image it fails and the app will not start.
   Production runs `postgres:18`: switch it to `pgvector/pgvector:0.8.7-pg18-<debian>` **before** deploying,
   matching the Debian release of the current image (`cat /etc/os-release` in the container) so glibc
   collations, and therefore text indexes, don't change. It is built from the official `postgres:18`
   image, so the existing volume is reused as is. Back up first. Do not `apt-get install` the package
   inside a running container: it disappears when the container is recreated while the database still
   references the extension. The CI deploy only manages the `app` service, so this step is manual.
   `docker-compose.yml`, the load test and Testcontainers use `pgvector/pgvector:0.8.7-pg18`. If the
   application's DB user is not a superuser (the Docker image's `POSTGRES_USER` is), have a superuser run
   `CREATE EXTENSION vector;` in the database first.
2. Set `APP_SEMANTIC_SEARCH_ENABLED=true` and `OPENROUTER_API_KEY` (the same key the OpenRouter chat
   provider uses). The chat model and embedding model are independent.

| Variable | Default | Meaning |
| --- | --- | --- |
| `APP_SEMANTIC_SEARCH_ENABLED` | `false` | Master switch. |
| `APP_SEMANTIC_SEARCH_MODEL` | `qwen/qwen3-embedding-8b` | OpenRouter embedding model. |
| `APP_SEMANTIC_SEARCH_DIMENSIONS` | `1024` | Must equal the `vector(N)` column. Sent as `dimensions`. |
| `APP_SEMANTIC_SEARCH_TRUNCATE_TO_DIMENSIONS` | `true` | If the provider ignores `dimensions`, keep the first N and re-normalise. Only valid for Matryoshka models (Qwen3-Embedding is). |
| `APP_SEMANTIC_SEARCH_MAX_EMBEDDINGS_PER_RUN` | `64` | Cost guard: documents embedded per scheduler run. |
| `APP_SEMANTIC_SEARCH_SCHEDULER_INTERVAL_MS` | `60000` | Sync/backfill cadence. |
| `APP_SEMANTIC_SEARCH_MAX_INPUT_CHARS` / `_MAX_QUERY_CHARS` | `8000` / `500` | Request size caps. |
| `APP_SEMANTIC_SEARCH_MAX_ATTEMPTS` | `5` | Retries per content version, exponential backoff from `RETRY_BASE_DELAY` (`1m`), capped at 6h. |
| `APP_SEMANTIC_SEARCH_PROFILE_REFRESH_INTERVAL` | `24h` | How often Drive profile documents are re-read to detect changes. |
| `APP_SEMANTIC_SEARCH_MAX_COSINE_DISTANCE` | `0.65` | Vector-only hits further than this are dropped. Model-specific: retune when switching models. |
| `APP_SEMANTIC_SEARCH_CANDIDATE_LIMIT` / `_RRF_K` | `50` / `60` | Candidates per list and the RRF constant. |
| `APP_SEMANTIC_SEARCH_PROVIDER_COOLDOWN` | `60s` | After a failed query embedding, interactive search skips the provider (FTS only) for this long. |

## How it works

- **Storage** (`semantic_embeddings`, Flyway `V38`): one row per application or profile document, with
  real foreign keys (`ON DELETE CASCADE`), `user_id`, the exact embedded text, its SHA-256, model,
  dimensions, status (`PENDING`, `READY`, `FAILED`, `EMPTY`, `SOURCE_ERROR`), attempts and timestamps.
  Accessed through JDBC only; Hibernate never sees the `vector` type.
- **Sync** (`EmbeddingSyncService`, every minute): one polling loop does backfill (sources without a row),
  change detection (hash of the embedded text, so status-only edits cost nothing), model switches and
  retries. Due rows are leased with `FOR UPDATE SKIP LOCKED`, so several instances never embed the same
  row twice, and no transaction is held open across HTTP calls. A result is stored only if the row still
  holds the content and model it was computed for.
- **Embedded text**: vacancy name, organization and notes for applications; extracted text for profile
  documents. Recruiter names and other personal fields are not sent to the provider.
- **Search**: FTS (`websearch_to_tsquery`) and cosine similarity each return up to 50 candidates under the
  **same** user and structured filters, fused with Reciprocal Rank Fusion. Results carry `matchedBy`
  (`KEYWORD`, `SEMANTIC`, `KEYWORD_AND_SEMANTIC`). Short tokens (`Go`, `C#`, `AI`) and filter-only queries
  keep the old literal path. Counts/aggregates (`getApplicationStats`) remain pure SQL.
- **Profile matching**: applications are ordered by distance to the closest profile document;
  `requiredTerms` (stack, seniority, location, modality, salary wording) are checked literally against the
  vacancy text with evidence snippets. The tool returns ranks and evidence, never distances or percentages.
- **No ANN index, on purpose**: every vector query is scoped to one user and scanned exactly
  (`MATERIALIZED` CTE over the `(user_id, source_type, status)` index). HNSW/IVFFlat would rank across all
  tenants and filter afterwards, silently dropping valid neighbours. Revisit (pgvector ≥ 0.8
  `hnsw.iterative_scan`) only if a single user reaches tens of thousands of vectors.

## Switching models

- Same dimensions: change `APP_SEMANTIC_SEARCH_MODEL`. Rows from the old model are excluded from queries
  immediately (vectors from different models are never compared) and re-embedded in the background.
  Coverage drops to 0 and recovers; FTS covers the gap.
- Different dimensions: add a Flyway migration that alters `semantic_embeddings.embedding` to
  `vector(N)` (and sets `embedding = NULL`), then change `APP_SEMANTIC_SEARCH_DIMENSIONS`. If config and
  column disagree, semantic search disables itself and logs `SEMANTIC_SEARCH_DISABLED`.

## Metrics

| Metric | Meaning |
| --- | --- |
| `semantic.embeddings.latency{purpose,model,outcome}` | Provider call latency (document batches and queries). |
| `semantic.embeddings.tokens{purpose}` / `semantic.embeddings.cost.usd{purpose}` | Usage reported by OpenRouter. |
| `semantic.embeddings.inputs{purpose}` | Texts embedded. |
| `semantic.embeddings.failures{stage}` | `provider`, `source_read` (Drive) or `stale_result`. |
| `semantic.embeddings.coverage` | READY / non-empty applications for the active model. |
| `semantic.embeddings.backlog`, `.exhausted`, `.source_errors` | Queue health. Exhausted rows retry only when their content changes. |
| `assistant.search.latency{mode}` | `hybrid` or `fts_fallback` search latency. |

## Known limits

- The schema has no structured salary, location, seniority or modality fields, so those constraints are
  checked as explicit text evidence in the vacancy name/notes, not as typed comparisons.
- `APP_SEMANTIC_SEARCH_MAX_COSINE_DISTANCE` was not calibrated against real data; tune it after enabling.
- Model availability, pricing and `dimensions` support on OpenRouter must be confirmed at deploy time.
