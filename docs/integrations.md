# Integrations: TinyFish Fetch and Gmail drafts

Issue #113. Applywell's own MCP server can read a job post from a URL and create a Gmail draft
with the generated resume attached, so AI clients no longer need the TinyFish or Draft Email Hub
plugins. The AI client still writes the resume content and the email body; Applywell only
fetches pages, stores and renders documents, and creates drafts.

Each integration is optional per user and works on its own. Nothing here sends email or changes
an application's status.

## Typical flow

1. The AI client calls `Fetch-Url` with the shared URL and gets the page as Markdown.
2. The AI client reads the job post and runs the usual application and resume tools
   (`Create-Application`, `Generate-Application-Resume`, and so on).
3. The AI client calls `List-Email-Accounts`, then `Create-Email-Draft` with the recipient,
   subject, HTML body, account or profile, and the `applicationId` whose PDF should be attached.
4. The user reviews the draft in Gmail and sends it.

## Server configuration

| Variable | Purpose |
| --- | --- |
| `APP_INTEGRATIONS_ENCRYPTION_KEY` | **Required.** Base64 of 32 random bytes (`openssl rand -base64 32`). AES-256-GCM key for user API keys and OAuth tokens at rest. If it is missing, users cannot save integration secrets. If it is rotated, stored secrets become unreadable: users must re-enter their TinyFish key and reconnect Gmail. |
| `TINYFETCH_BASE_URL` | Default `https://api.fetch.tinyfish.ai`. |
| `TINYFETCH_MAX_PER_MINUTE_LIMIT` / `TINYFETCH_MAX_DAILY_LIMIT` | Ceilings that users cannot exceed. Defaults 150/min and 1000/day (TinyFish free tier as of 2026-11-23). Lower them if your account has a smaller quota. |
| `TINYFETCH_READ_TIMEOUT`, `TINYFETCH_MAX_ATTEMPTS`, `TINYFETCH_RETRY_BASE_DELAY`, `TINYFETCH_MAX_RETRY_DELAY` | Timeout and bounded retry with exponential backoff on 429/5xx. `Retry-After` is honoured up to the max delay. |
| `TINYFETCH_CACHE_TTL`, `TINYFETCH_MAX_CONTENT_CHARS` | Per-user in-memory cache lifetime (default 15m) and the content truncation limit. |
| `GMAIL_CLIENT_ID` / `GMAIL_CLIENT_SECRET` | Default to the Google Drive OAuth client. |
| `GMAIL_REDIRECT_URI` | Default `${APP_API_BASE_URL}/api/v1/integrations/gmail/oauth/callback`. It must be registered on the OAuth client. |
| `GMAIL_OAUTH_COMPLETE_URL` | The frontend page the callback redirects to, e.g. `https://applywell.hugojava.dev/account`. The redirect adds `integration=gmail&status=success|error&message=...`. |
| `GMAIL_MAX_ACCOUNTS_PER_USER`, `GMAIL_MAX_ATTACHMENT_BYTES`, `GMAIL_DRAFT_PENDING_TIMEOUT` | Limits. |

### Google Cloud / OAuth requirements

- Enable the **Gmail API** in the same Google Cloud project as the Drive OAuth client, or create
  a dedicated client and set `GMAIL_CLIENT_ID`/`GMAIL_CLIENT_SECRET`.
- Add the redirect URI above to the client's *Authorized redirect URIs*.
- The only scope requested is `https://www.googleapis.com/auth/gmail.compose`. It is the
  narrowest scope that allows `users.drafts.create`, and it also covers `users.getProfile`, which
  is used to identify the mailbox. Google classifies `gmail.compose` as a **restricted** scope:
  - While the consent screen is in *Testing*, only listed test users can connect, and refresh
    tokens expire after 7 days.
  - Public availability requires OAuth app verification, which includes a security assessment for
    restricted scopes. See Google's
    [restricted scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification)
    and [Gmail API scopes](https://developers.google.com/workspace/gmail/api/auth/scopes) pages.
- The flow uses `access_type=offline`, `prompt=consent` and PKCE (S256), plus a single-use
  `state` that expires after 10 minutes. Signing in to Applywell with Google is **not** treated
  as Gmail access: each mailbox goes through its own consent.
- Disconnecting an account calls Google's revoke endpoint (best effort) and deletes the stored
  tokens. A refresh that fails with `invalid_grant` flags the account as needing reconnection.

## REST API

All endpoints require authentication. Endpoints marked *session only* reject OAuth tokens issued
to MCP/GPT clients, so an AI client can use the integrations but cannot replace credentials or
connect and disconnect mailboxes.

### TinyFish Fetch: `/api/v1/integrations/tinyfetch`

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/` | Settings. The key is never returned, only a hint such as `••••1234`. Includes `usedToday`. |
| PUT | `/` | *Session only.* `{ apiKey?, enabled?, perMinuteLimit?, dailyLimit? }`. `apiKey` is required on first setup. |
| DELETE | `/` | *Session only.* Removes the settings and the stored key. |
| POST | `/test` | Fetches `TINYFETCH_TEST_URL` once (counts toward quota) and records the result. |
| POST | `/fetch` | `{ url, bypassCache? }` → same payload as the `Fetch-Url` tool. |

### Gmail: `/api/v1/integrations/gmail`

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/` | `{ serverConfigured, maxAccounts, scopes, accounts[] }` |
| POST | `/oauth/start` | *Session only.* `{ profileName? }` → `{ authorizationUrl }`. Reconnecting an already connected mailbox updates it in place. |
| GET | `/oauth/callback` | Public; called by Google. |
| PATCH | `/accounts/{id}` | *Session only.* `{ profileName }` |
| POST | `/accounts/{id}/test` | Calls `users.getProfile` with the stored grant. |
| DELETE | `/accounts/{id}` | *Session only.* Revokes the grant and deletes the account. |
| POST | `/drafts` | Same contract as `Create-Email-Draft`. Returns 201 for a new draft, 200 for a deduplicated one. |

## MCP tools

### `Fetch-Url`

Input: `url` (required), `bypassCache` (optional).

Output:

```json
{
  "status": "OK | PARTIAL | BLOCKED | FAILED",
  "url": "https://...", "finalUrl": "https://...", "title": "...",
  "markdown": "...", "metadata": { "...": "..." },
  "contentLength": 1234, "truncated": false, "cached": false,
  "manualInputRequired": false, "message": null,
  "contentNotice": "Untrusted third-party page content...",
  "fetchedAt": "2026-11-24T10:00:00"
}
```

- `PARTIAL` means fewer than 200 characters came back, usually a login wall. `BLOCKED` means
  TinyFish reported bot or login blocking. `FAILED` covers timeouts and upstream errors. In all
  three cases `manualInputRequired=true`, and the client should ask the user to paste the job
  description.
- Errors: not configured or disabled (400), invalid or private URL (400, SSRF guard: only http(s)
  on default ports, no credentials in the URL, every resolved address must be public), API key
  rejected (400), user or provider quota reached (429 with `Retry-After` and codes
  `TINYFETCH_MINUTE_LIMIT`, `TINYFETCH_DAILY_LIMIT`, `TINYFETCH_PROVIDER_RATE_LIMIT`,
  `TINYFETCH_PROVIDER_QUOTA`).
- Concurrent identical requests from the same user share one provider call. The cache is
  per user. Cache hits do not count toward quota.
- Page content is data. Nothing in Applywell acts on it, and the tool description tells the AI
  client not to either.

### `List-Email-Accounts`

Returns `[{ id, email, profileName, connectedAt, lastTestedAt, reauthorizationRequired }]`.

### `Create-Email-Draft`

Input: `to[]`, `subject`, `htmlBody` (required); `accountId` or `profileName` (required when
more than one account is connected); `applicationId`, `attachResume` (default true when
`applicationId` is set), `cc[]`, `bcc[]`, `idempotencyKey` (all optional).

Output: `{ status: CREATED | ALREADY_CREATED, draftId, messageId, accountId, accountEmail,
profileName, applicationId, attachmentFileName, idempotencyKey, deduplicated, gmailDraftsUrl, message }`.

- The resume PDF is exported internally from the application's generated Google Doc through the
  user's Drive connection. No public URL is used, and application ownership is checked.
- The message is `multipart/mixed`: an HTML part with a plain-text alternative, plus the PDF.
  Header values containing CR/LF are rejected.
- **Idempotency.** Without `idempotencyKey`, the key is a SHA-256 of the normalized request.
  A ledger row is committed before Gmail is called:
  - Repeating a request that succeeded returns the same draft (`ALREADY_CREATED`).
  - A definitive failure (validation, 4xx, missing resume) can be retried.
  - A timeout, connection reset or Gmail 5xx returns 409 `EMAIL_DRAFT_OUTCOME_UNKNOWN`, because
    the draft may already exist. It is never retried automatically. The user should check Gmail
    drafts, and a new `idempotencyKey` is needed to create another copy.
  - A concurrent identical request returns 409 `EMAIL_DRAFT_IN_PROGRESS`.
  - Reusing a key with different content returns 409 `EMAIL_DRAFT_KEY_REUSED`.
- A failed draft never deletes or modifies the application or its resume.

## Observability

Metrics:

- `integrations.tinyfetch.requests{outcome}` and `integrations.tinyfetch.latency{outcome}`
- `integrations.gmail.drafts{outcome}` and `integrations.gmail.drafts.latency{outcome}`

Daily fetch and failure counts per user are stored in `tinyfetch_daily_usage`.

Logs record user id, host (never the full URL), outcome and latency. Recipients, subjects, bodies,
keys and tokens are never logged. MCP calls are also audited by the existing `@AuditMcpOperation`
aspect.
