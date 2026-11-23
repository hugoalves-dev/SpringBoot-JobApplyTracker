# Ask ApplyWell: quotas and anti-flood

Every `POST /api/v1/assistant/chat` request goes through `AssistantQuotaService` **before** a
`ChatModel` is called. The checks live above Spring AI's `ChatModel` abstraction, so they apply
to any configured provider (OpenRouter, Gemini or a future one) without provider-specific code.

## Limits

Configure them in the server `.env` (or deployment environment) and restart the backend.
A value of `0` disables that single check.

| Variable | Default | Meaning |
| --- | --- | --- |
| `APP_ASSISTANT_QUOTA_ENABLED` | `true` | Master switch for all checks below. |
| `APP_ASSISTANT_QUOTA_DAILY_LIMIT` | `30` | Messages per user per day. |
| `APP_ASSISTANT_QUOTA_RESET_ZONE` | `America/Sao_Paulo` | Daily reset happens at local midnight in this IANA zone. It is unrelated to the provider's own reset (Gemini resets at midnight Pacific time). |
| `APP_ASSISTANT_QUOTA_RATE_LIMIT` | `5` | Messages per user in the short window. |
| `APP_ASSISTANT_QUOTA_RATE_WINDOW` | `1m` | Short (sliding) window length. |
| `APP_ASSISTANT_QUOTA_MAX_CONCURRENT` | `1` | Generations in progress per user, across all tabs, devices and instances. |
| `APP_ASSISTANT_QUOTA_GLOBAL_DAILY_LIMIT` | `500` | Provider calls per day for all users together. |
| `APP_ASSISTANT_QUOTA_GLOBAL_RATE_LIMIT` | `20` | Provider calls in the global window for all users together. |
| `APP_ASSISTANT_QUOTA_GLOBAL_RATE_WINDOW` | `1m` | Global window length. |
| `APP_ASSISTANT_QUOTA_RETENTION` | `7d` | How long request rows are kept (minimum 2 days). |
| `APP_ASSISTANT_MAX_OUTPUT_TOKENS` | `1024` | Output cap per provider call, sent as Spring AI's portable `maxTokens`. `0` keeps the provider default. |
| `APP_ASSISTANT_MAX_TOOL_CALLS` | `8` | Tool calls per message. After the budget, tools return an error and the model must answer with what it has. `0` means unlimited. |
| `APP_ASSISTANT_MAX_MESSAGE_LENGTH` | `4000` | Prompt size cap (existing). Conversation memory is capped at 20 messages. |

Set the global limits **below** your provider plan (OpenRouter key/model limits or Gemini
project RPM/RPD). A single message can trigger several provider round-trips when the model
uses tools, so the real provider request count can be up to `1 + APP_ASSISTANT_MAX_TOOL_CALLS`
per message. Keep a margin.

## How it stays correct

- Each authorized request inserts a row in `assistant_request_reservations`. Checks and the
  insert run in one transaction that first locks the `assistant_quota_locks` row
  (`SELECT ... FOR UPDATE`, `READ COMMITTED`). Parallel requests, tabs and application instances
  are serialized there, so limits cannot be overshot. No Redis is needed; the lock is held for a
  few milliseconds and the expected traffic is low.
- An `IN_PROGRESS` row only blocks new requests (concurrency check) for
  `APP_ASSISTANT_STREAM_TIMEOUT + 30s`. If an instance crashes mid-stream, the user is unblocked
  after that lease, but the message stays charged.
- Timestamps are stored in UTC; the reset zone is only used to compute day boundaries.

## Accounting policy

| Outcome | Daily user quota | Short window / global limits |
| --- | --- | --- |
| Blocked by a local limit, validation error, assistant disabled | Not charged (no row) | Not counted |
| Local failure after reserving but before the provider call | Not charged (`RELEASED`) | Not counted |
| Completed | Charged (`COMPLETED`) | Counted |
| Provider error before any token was streamed (e.g. provider 429) | Refunded (`REFUNDED`) | Counted, because the provider was called |
| Provider error after tokens were streamed | Charged (`FAILED`) | Counted |
| Client disconnect or SSE timeout | Charged (`CANCELLED`) | Counted |

Refunded failures still count toward the short window, so retry loops are still throttled.

## API contract

Rejected requests get an HTTP `429` JSON response before the SSE stream opens. A generation
that was already authorized is never interrupted by a later rejection.

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "code": "ASSISTANT_DAILY_QUOTA_EXCEEDED",
  "message": "Daily assistant message limit reached",
  "retryAfterSeconds": 43200,
  "retryAt": "2026-11-23T03:00:00Z"
}
```

The `Retry-After` header carries the same seconds when known. Codes:

- `ASSISTANT_DAILY_QUOTA_EXCEEDED`: retry at the next local reset.
- `ASSISTANT_RATE_LIMITED`: retry when the oldest request leaves the short window.
- `ASSISTANT_REQUEST_IN_PROGRESS`: another generation from this user is still running (no `Retry-After`).
- `ASSISTANT_GLOBAL_LIMIT_REACHED`: the shared capacity is exhausted.

Provider rate limits keep the existing SSE `error` event with code `RATE_LIMITED`
(see `AssistantProviderErrorMapper`), which separates local 429s from provider 429s.

`GET /api/v1/assistant/usage` returns only the authenticated user's usage:

```json
{
  "quotaEnabled": true,
  "dailyLimit": 30,
  "used": 4,
  "remaining": 26,
  "resetAt": "2026-11-23T03:00:00Z",
  "resetTimezone": "America/Sao_Paulo"
}
```

`dailyLimit` and `remaining` are `null` when the daily limit is disabled.

## Observability

Prompts are never logged. Metrics (Micrometer, exposed via Prometheus):

- `assistant.quota.decisions{result=allowed|daily_quota|rate_limited|concurrent_request|global_limit}`
- `assistant.requests.in_flight` (per instance gauge)
- `assistant.requests.completed{result=success|failure|cancelled}`
- `assistant.quota.reservations.finished{status=...}`
- `assistant.provider.errors{code=RATE_LIMITED|PROVIDER_UNAVAILABLE}` (provider-side 429 vs other failures)
- `assistant.tokens{type=prompt|completion}` and `assistant.tool.calls`

Token usage comes from Spring AI's `ChatResponse` metadata. It is cumulative across tool-call
rounds for OpenRouter (OpenAI adapter, `stream_options.include_usage` is on by default) and
Gemini. It is also stored per request in `prompt_tokens` / `completion_tokens`. Providers that
report no usage leave those columns `NULL`.

The log event `ASSISTANT_QUOTA_REJECTED` records the reason, user ID and retry delay.
