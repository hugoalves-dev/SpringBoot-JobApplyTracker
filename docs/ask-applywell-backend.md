# Ask ApplyWell backend

The authenticated endpoint is `POST /api/v1/assistant/chat` and produces
`text/event-stream`. A POST SSE stream is intentional: the browser client should use
`fetch()` and consume its `ReadableStream`; native `EventSource` cannot send a JSON body.

Events:

- `token`: `{"content":"..."}`
- `complete`: `{"sources":["APPLICATION_STATS"]}`
- `error`: a sanitized provider failure

Per-user daily quota, rate limit, single in-flight generation and global provider limits are
enforced before the provider is called. Rejections return HTTP 429 before the stream opens.
`GET /api/v1/assistant/usage` returns the caller's daily usage. See
[assistant-quota.md](assistant-quota.md).

Search can combine full-text and pgvector similarity, and `matchApplicationsToProfile` ranks
applications against the user's profile documents. Both are opt-in; see
[semantic-search.md](semantic-search.md).

Enable with `APP_ASSISTANT_ENABLED=true`, set `OPENAI_API_KEY`, and optionally set
`OPENAI_CHAT_MODEL`. The feature is disabled by default. Every tool is read-only and binds
the user ID captured from the authenticated request; the model never receives a user ID.

MariaDB FULLTEXT handles ordinary terms. The bounded LIKE fallback is used only for explicitly
recognized short technology tokens (`Go`, `AI`, `C#`, `C++`, and `.NET`) and remains
user-scoped, filtered, escaped, ordered, and capped. Redis, RabbitMQ, embeddings, persistent
chat memory, model routing, and mutation tools remain outside this MVP.
