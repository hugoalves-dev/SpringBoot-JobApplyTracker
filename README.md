# SpringBoot Job Apply Tracker API

[Frontend - React-JobApplyTracker](https://github.com/vitorhugo-java/React-JobApplyTracker) 

A production-ready Spring Boot REST API for tracking job applications, built with Java 21, Spring Security JWT authentication, MariaDB, and comprehensive test coverage. Exposes all domain services via a **Model Context Protocol (MCP) server** so AI assistants (Claude Desktop, Claude.ai, and any MCP-compatible client) can manage applications on behalf of authenticated users.

> **Alterações no MCP podem exigir atualização manual dos clientes.**
> 
> Ao adicionar, remover ou modificar **tools**, **resources**, **prompts**, instruções ou schemas do MCP, valide os clientes integrados após o deploy.
> 
> Alguns clientes mantêm cache das capacidades do servidor e podem continuar usando definições antigas. Quando necessário:
> 
> - desconecte e conecte novamente o servidor MCP;
> - recrie ou atualize a integração;
> - reinicie o cliente;
> - valide novamente `tools/list`, `resources/list` e `prompts/list`;
> - confirme que descrições, argumentos, schemas e instruções atualizados estão visíveis.
> 
> Não considere uma alteração do MCP concluída apenas porque o backend foi publicado. Confirme também que os clientes estão consumindo a versão mais recente.

## Tech Stack

- **Java 21**
- **Spring Boot 4.1** (Web MVC, Data JPA, Security, Validation)
- **Spring AI 2.0** — MCP server (`spring-ai-starter-mcp-server-webmvc`, Streamable HTTP transport)
- **Spring OAuth2 Authorization Server** (JDBC-backed, issues tokens for both GPT Actions and MCP clients)
- **Spring Security** with stateless JWT authentication + role-based authorization (`USER`, `BETA`, `ADMIN`)
- **JWT + Refresh Tokens** (access: 15 min, refresh: 7 days with rotation)
- **Resilience4j Rate Limiting** on auth endpoints
- **MariaDB** (production) / **Testcontainers** (tests)
- **Flyway** for DB migrations
- **JUnit 5 + Mockito** (unit tests)
- **Testcontainers + MockMvc** (integration tests)
- **RestAssured** (E2E tests)
- **Maven**

## Project Structure

```
.
├── src/
│   ├── main/java/com/jobtracker/
│   │   ├── config/          # Security, JWT, CORS, OAuth2 AS, filters
│   │   ├── controller/      # REST controllers
│   │   ├── dto/             # Request/Response DTOs
│   │   ├── entity/          # JPA entities
│   │   ├── exception/       # Global exception handling
│   │   ├── mapper/          # Entity-DTO mappers
│   │   ├── mcp/             # MCP server — tools and prompts
│   │   │   ├── tools/       # @Tool-annotated service wrappers
│   │   │   ├── McpToolsConfig.java
│   │   │   └── McpPromptsConfig.java
│   │   ├── repository/      # Spring Data JPA repositories
│   │   ├── service/         # Business logic
│   │   └── util/            # Utilities
│   ├── main/resources/
│   │   ├── application.yml
│   │   └── db/migration/    # Flyway migrations
│   └── test/java/com/jobtracker/
│       ├── unit/            # Mockito unit tests
│       │   └── mcp/         # MCP tool unit tests
│       ├── integration/     # SpringBootTest + Testcontainers + MockMvc
│       │   └── mcp/         # MCP auth and tool integration tests
│       └── e2e/             # RestAssured end-to-end tests
├── pom.xml
├── Dockerfile
├── docker-compose.yml
└── .github/workflows/ci.yml
```

## API Endpoints

### Auth

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/register` | Register a new user |
| POST | `/api/v1/auth/login` | Login and receive tokens |
| POST | `/api/v1/auth/refresh` | Refresh access token |
| POST | `/api/v1/auth/logout` | Logout and revoke refresh token |
| POST | `/api/v1/auth/forgot-password` | Request password reset |
| POST | `/api/v1/auth/reset-password` | Reset password with token |
| GET | `/api/v1/auth/me` | Get current user info |

### Applications

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/applications` | Create application |
| GET | `/api/v1/applications` | List all (paginated + filterable) |
| GET | `/api/v1/applications/{id}` | Get by ID |
| PUT | `/api/v1/applications/{id}` | Full update |
| PATCH | `/api/v1/applications/{id}` | Partial update (omitted fields unchanged, incl. `archived`) |
| PATCH | `/api/v1/applications/{id}/status` | Update status |
| PATCH | `/api/v1/applications/{id}/archive` | Archive |
| PATCH | `/api/v1/applications/{id}/reminder` | Toggle reminder |
| DELETE | `/api/v1/applications/{id}` | Delete |
| GET | `/api/v1/applications/upcoming` | Upcoming next steps |
| GET | `/api/v1/applications/overdue` | Overdue next steps |

### Exports

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/exports/applications` | Download the authenticated user's applications as CSV or XLSX |
| GET | `/api/v1/exports/columns` | List every exportable column key and its file header |
| GET | `/api/v1/exports/history` | Paginated history of export attempts |
| GET | `/api/v1/exports/history/{id}` | Detail of a single export attempt |
| POST | `/api/v1/export-schedules` | Create a recurring export |
| GET | `/api/v1/export-schedules` | List the user's recurring exports |
| GET | `/api/v1/export-schedules/{id}` | Get one recurring export |
| PUT | `/api/v1/export-schedules/{id}` | Replace a recurring export |
| PATCH | `/api/v1/export-schedules/{id}/enabled` | Enable or disable a recurring export |
| DELETE | `/api/v1/export-schedules/{id}` | Delete a recurring export (history is kept) |
| POST | `/api/v1/export-schedules/{id}/run-now` | Run a recurring export immediately (202, async) |

Manual export request:

```json
{
  "format": "CSV",
  "filters": {
    "status": ["RH"],
    "applicationDateFrom": "2026-07-01",
    "applicationDateTo": "2026-07-31",
    "archived": null,
    "organization": "ACME"
  },
  "columns": []
}
```

The response is an attachment named `applywell-applications-<yyyy-MM-dd>.<csv|xlsx>` plus the
`X-Export-Record-Count` and `X-Export-Truncated` headers. An empty `columns` array exports every
column; unknown keys are rejected with 400.

Recurring export request:

```json
{
  "name": "Backup diário das candidaturas",
  "format": "XLSX",
  "frequency": "DAILY",
  "time": "20:00",
  "timezone": "America/Sao_Paulo",
  "enabled": true,
  "filters": { "archived": null },
  "destination": "GOOGLE_DRIVE"
}
```

Notes:

- **No cron from the client.** The recurrence is stored as a validated domain configuration
  (`DAILY` / `WEEKLY` + `dayOfWeek` / `MONTHLY` + `dayOfMonth`, plus `time` and `timezone`) and the
  next execution instant is derived internally and persisted in UTC.
- **Filters** reuse the same predicates as the applications listing, always anchored on the
  authenticated user. The one deliberate difference: `archived: null` exports active *and* archived
  applications, because an export is a backup.
- **Destination.** `GOOGLE_DRIVE` uploads the file to an exports folder inside the user's configured
  Drive root folder. `EMAIL`, `OBJECT_STORAGE` and `WEBHOOK` are modelled but not implemented yet and
  are rejected with 400. The container filesystem is never used as storage.
- **Safety.** Cells starting with `=`, `+`, `-`, `@`, tab or CR are neutralized against CSV/formula
  injection (plain numbers are left alone), CSV is written RFC 4180 with a UTF-8 BOM so Excel keeps
  accents, and XLSX is streamed with `SXSSFWorkbook`.
- **Concurrency.** Each run claims the schedule with a conditional UPDATE, so a schedule never runs
  twice at the same time even across instances; a stale lock is reclaimed after
  `app.export.lock-timeout-minutes`.
- **Limits.** `app.export.max-records` caps a single export; the file is still produced and flagged
  as truncated. Manual exports are rate-limited (`exportManual`).

Configuration (`app.export.*`): `max-records`, `page-size`, `default-timezone`,
`max-schedules-per-user`, `scheduler-enabled`, `scheduler-interval-ms`, `lock-timeout-minutes`,
`drive-folder-name`.

## Authorization Model

- JWT access tokens now include a `roles` claim (e.g., `ROLE_USER`, `ROLE_ADMIN`).
- Existing users are backfilled with `ROLE_USER` during migration.
- A default `ROLE_USER` is assigned on registration.

Flyway seeds the roles catalog (`USER`, `BETA`, `ADMIN`) and then assigns `ROLE_USER` to all existing users.

### Endpoints currently requiring `ROLE_USER`

- `GET /api/v1/auth/me`
- `PUT /api/v1/auth/me`
- `PUT /api/v1/auth/me/password`
- `POST|GET|PUT|PATCH|DELETE /api/v1/applications/**`
- `POST|GET /api/v1/exports/**`
- `POST|GET|PUT|PATCH|DELETE /api/v1/export-schedules/**`
- `GET|POST /api/v1/gamification/**`
- `GET /api/v1/dashboard/summary`
- `POST /api/v1/account/test-email`

### Gamification

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/gamification/profile` | Get current XP, level, rank title and streak snapshot |
| GET | `/api/v1/gamification/achievements` | List achievement catalog with unlocked state |
| POST | `/api/v1/gamification/events` | Apply a tracked XP event and return updated profile |

### Google Drive

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/google-drive/oauth/start` | Generate the Google OAuth authorization URL for the authenticated user |
| GET | `/api/v1/google-drive/oauth/callback` | Google OAuth callback endpoint used by Google Cloud |
| GET | `/api/v1/google-drive/status` | Return current Google Drive connection status, configured root folder, and base resumes |
| DELETE | `/api/v1/google-drive/connection` | Disconnect the current user's Google account and remove stored Drive preferences |
| PUT | `/api/v1/google-drive/root-folder` | Validate and save the user's base Drive folder |
| POST | `/api/v1/google-drive/base-resumes` | Register a Google Docs base resume by Google Docs URL or file ID |
| DELETE | `/api/v1/google-drive/base-resumes/{baseResumeId}` | Remove a configured base resume |
| POST | `/api/v1/google-drive/applications/{applicationId}/resume-copies` | Copy a configured base resume into the application's Drive subfolder |
| POST | `/api/v1/google-drive/base-information` | Register a base information document (Google Docs, PDF, DOCX, or Markdown) by Drive URL or file ID |
| GET | `/api/v1/google-drive/base-information` | List the user's configured base information documents |
| DELETE | `/api/v1/google-drive/base-information/{baseInformationId}` | Remove a configured base information document |
| GET | `/api/v1/google-drive/base-information/{baseInformationId}/content` | Extract the plain-text content of a base information document |

### Base information about the candidate

Base information documents (Google Docs, PDF, DOCX, or Markdown hosted on the user's Drive)
describe the candidate — real experience, projects, skills, and background. They are the
**authoritative, highest-priority source of truth about the candidate**: AIs MUST read them
(via `List-Base-Information` → `Get-Base-Information-Content`, or the
`resource://job-apply-tracker/base-information` resources) **before generating any CV content**.
A prior generated resume is only a secondary supplement. This is enforced in the MCP
`Intake-Vacancy` prompt, the `resume-workflow-rules` resource, and the `Generate-Resume` tool.

## Application Status Values

- `RH`
- `Fiz a RH - Aguardando Atualização`
- `Fiz a Hiring Manager - Aguardando Atualização`
- `Teste Técnico`
- `Fiz teste Técnico - aguardando atualização`
- `RH (Negociação)`

## Gamification System

The backend tracks gamification in `user_gamification`, `achievements`, and `user_achievements`. XP is awarded from application lifecycle events and stored per user, while each application keeps one-time award flags so the same action is not counted twice. The service also derives the current streak and unlocks achievements from the user's non-archived applications.

### XP rules

| Action | Backend event | XP |
|--------|---------------|----|
| New application | `APPLICATION_CREATED` | +10 |
| Recruiter DM sent | `RECRUITER_DM_SENT` | +15 |
| Interview progress | `INTERVIEW_PROGRESS` | +50 |
| Note added | `NOTE_ADDED` | +5 |
| Offer / win | `OFFER_WON` | +500 |

### Level formula

- `level = floor(sqrt(totalXp / 100)) + 1`
- `XP required for level N = 100 * (N - 1)^2`

Examples:

| Level | Total XP required |
|-------|-------------------|
| 1 | 0 |
| 2 | 100 |
| 3 | 400 |
| 4 | 900 |
| 5 | 1600 |

### Rank milestones

| Milestone level | XP threshold | Rank title |
|-----------------|--------------|------------|
| 1 | 0 | Desempregado de Aluguel |
| 6 | 2500 | Job Hunter Iniciante |
| 16 | 22500 | Sobrevivente do LinkedIn |
| 31 | 90000 | Mestre das Soft Skills |
| 51 | 250000 | Lenda das Contratacoes |

### Achievements

| Code | Name | Unlock condition in the backend today |
|------|------|----------------------------------------|
| `EARLY_BIRD` | Early Bird | Have 5 non-archived applications with `applicationDate` set and `createdAt` before `09:00` |
| `NETWORKING_PRO` | Networking Pro | Have 10 recruiter DMs sent inside any rolling 7-day window |
| `PERSISTENT` | Persistent | Reach a 5-day longest streak based on distinct `applicationDate` values |
| `GHOSTBUSTER` | Ghostbuster | Have any non-archived application currently in `GHOSTING` status |

### Current backend status mapping

The backend does not currently have literal `INTERVIEW` or `HIRED` statuses.

- `INTERVIEW_PROGRESS` is awarded when `interviewScheduled = true` or when the application enters one of these statuses: `Fiz a RH - Aguardando Atualização`, `Fiz a Hiring Manager - Aguardando Atualização`, `Teste Técnico`, `Fiz teste Técnico - aguardando atualização`, or `RH (Negociação)`.
- `OFFER_WON` is currently mapped to `RH (Negociação)` (`RH_NEGOCIACAO` in code), which is the backend's current closing-stage proxy until dedicated offer/hired statuses exist.
- `GHOSTBUSTER` currently unlocks from `GHOSTING` status itself; although the seeded achievement description mentions "30 days", the implemented unlock rule is status-based today.

## Running Locally

### With Docker Compose

Development (build locally):

```bash
docker-compose up -d
```

Production (use pre-built image from GitHub Container Registry):

1. Log in to GHCR (requires a Personal Access Token with `read:packages`):

```bash
# POSIX / macOS / WSL
echo $CR_PAT | docker login ghcr.io -u vitorhugo-java --password-stdin

# Windows PowerShell
# $env:CR_PAT | docker login ghcr.io -u vitorhugo-java --password-stdin
```

2. Pull the production compose file image(s):

```bash
docker compose -f docker-compose.prod.yml pull
```

3. Start the services from the production compose file:

```bash
docker compose -f docker-compose.prod.yml up -d
```

By default this compose file pulls image: `ghcr.io/vitorhugo-java/springboot-jobapplytracker:latest`. Change the image name in `docker-compose.prod.yml` if you publish with a different tag or repository.

The API will be available at `http://localhost:8080`.

### With Maven (requires a running MariaDB)

```bash
export DB_URL=jdbc:mariadb://localhost:3306/jobtracker?createDatabaseIfNotExist=true
export DB_USERNAME=jobtracker
export DB_PASSWORD=jobtracker
export JWT_SECRET=your-secret-key-at-least-256-bits-long
export GOOGLE_DRIVE_CLIENT_ID=your-google-client-id
export GOOGLE_DRIVE_CLIENT_SECRET=your-google-client-secret
export GOOGLE_DRIVE_REDIRECT_URI=http://localhost:8080/api/v1/google-drive/oauth/callback
export GOOGLE_DRIVE_OAUTH_COMPLETE_URL=http://localhost:5173/settings/google-drive/callback
export OPENAI_GPT_CLIENT_ID=your-openai-gpt-client-id
export OPENAI_GPT_CLIENT_SECRET=your-openai-gpt-client-secret
export OPENAI_GPT_REDIRECT_URIS=https://chat.openai.com/aip/default/callback
export OPENAI_GPT_SCOPES=read:profile,read:applications,write:applications,read:resume,read:google-drive,read:metrics
# MCP client (optional — omit to disable MCP OAuth2 registration)
export MCP_CLIENT_ID=your-mcp-client-id
export MCP_REDIRECT_URIS=http://localhost:3000/mcp/callback
mvn spring-boot:run
```

## Google Drive integration

This backend supports per-user Google Drive OAuth2 for resume-copy automation. It does **not** replace the app's JWT auth flow; users stay authenticated with the existing bearer token, and Google is connected separately with `POST /api/v1/google-drive/oauth/start`.

### Required Google Cloud setup

1. Create a Google Cloud OAuth client for a web application.
2. Enable the **Google Drive API**.
3. Add the backend callback URL as an authorized redirect URI. Example local value:
   - `http://localhost:8080/api/v1/google-drive/oauth/callback`
4. Configure these environment variables:

| Variable | Required | Description |
|----------|----------|-------------|
| `GOOGLE_DRIVE_CLIENT_ID` | Yes | Google OAuth client ID |
| `GOOGLE_DRIVE_CLIENT_SECRET` | Yes | Google OAuth client secret |
| `GOOGLE_DRIVE_REDIRECT_URI` | Yes | Backend callback URL registered in Google Cloud |
| `GOOGLE_DRIVE_OAUTH_COMPLETE_URL` | Yes | Frontend page that receives the final `status` and `message` query params after OAuth finishes |
| `GOOGLE_DRIVE_AUTHORIZATION_URI` | No | Override Google authorization endpoint |
| `GOOGLE_DRIVE_TOKEN_URI` | No | Override Google token endpoint |

### OAuth flow expectations

1. Frontend calls `POST /api/v1/google-drive/oauth/start` with the user's JWT bearer token.
2. Backend creates a short-lived OAuth state tied to that authenticated user and returns:
   - `authorizationUrl`
   - `state`
   - `redirectUri`
   - `scopes`
3. Frontend opens `authorizationUrl` in a new tab or popup.
4. Google redirects back to `GET /api/v1/google-drive/oauth/callback`.
5. Backend exchanges the authorization code for user-scoped Drive credentials, stores them, and redirects the browser to `GOOGLE_DRIVE_OAUTH_COMPLETE_URL` with:
   - `status=success|error`
   - `message=<url-encoded message>`

### Scope used

- `https://www.googleapis.com/auth/drive`

This scope is used so the backend can validate user-selected Drive folders, read chosen Google Docs metadata, create vacancy subfolders, and copy Google Docs files on behalf of the authenticated user.

### Supported files

- Base resumes must be **Google Docs** (`application/vnd.google-apps.document`).
- The root folder must be a **Google Drive folder**.
- The frontend Gemini button that opens `https://gemini.google.com/gem/f8ed7c14b062` is frontend-only and does not require a backend endpoint.

### Resume copy behavior

When the frontend later calls `POST /api/v1/google-drive/applications/{applicationId}/resume-copies`:

1. Backend verifies the current user owns the application.
2. Backend refreshes the user's Google access token if needed.
3. Backend verifies the configured root folder still exists and is a folder.
4. Backend finds or creates a vacancy subfolder under that root folder using the application identity.
5. Backend copies the selected base Google Doc into that subfolder.
6. Backend renames the copy with an `APP-<application-uuid>` prefix plus vacancy context.
7. Backend returns a Google Docs web URL for the copied file.

### Google Drive request/response shapes

`POST /api/v1/google-drive/oauth/start`

```json
{}
```

Response:

```json
{
  "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?...",
  "state": "generated-state",
  "redirectUri": "http://localhost:8080/api/v1/google-drive/oauth/callback",
  "scopes": [
    "https://www.googleapis.com/auth/drive"
  ]
}
```

## MCP Server (Model Context Protocol)

The backend exposes all core domain services as MCP tools via a **Streamable HTTP** endpoint at `POST /mcp/messages`. Any MCP-compatible AI client (Claude Desktop, Claude.ai, `mcp-cli`, etc.) can call these tools on behalf of an authenticated user.

### How authentication works

MCP requests use the **same OAuth2 Authorization Server** already used by GPT Actions. There are no static tokens, no synthetic service accounts, and no parallel auth system. The flow is:

1. MCP client completes OAuth2 **Authorization Code + PKCE** against the existing AS.
2. The AS issues a JWT carrying the user's `id`, `roles` (`ROLE_USER`, `ROLE_BETA`, etc.), and scopes.
3. MCP client presents the JWT as `Authorization: Bearer <token>` on every `POST /mcp/messages` request.
4. The existing `BearerTokenAuthenticationFilter` validates the JWT, populates the `SecurityContext` with the real user, and all domain service ownership checks (`SecurityUtils.getCurrentUser()`) and role guards (`@PreAuthorize`) work exactly as they do for REST requests.

### Required environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `MCP_CLIENT_ID` | Yes | OAuth2 client ID for your MCP client registration |
| `MCP_CLIENT_SECRET` | No | If set, enables `CLIENT_SECRET_BASIC` / `CLIENT_SECRET_POST` auth methods (omit for public PKCE-only clients) |
| `MCP_REDIRECT_URIS` | Yes | Comma-separated list of allowed redirect URIs for your MCP client |

> If `MCP_CLIENT_ID` is not set, the MCP OAuth2 client is not registered and MCP authentication will fail. The MCP server endpoint itself always starts.

### Supported scopes

| Scope | Grants access to |
|-------|-----------------|
| `openid` | OIDC identity |
| `read:profile` | User profile |
| `read:applications` | List / read applications |
| `write:applications` | Create / update / delete applications |
| `read:resume` | Base resume metadata |
| `read:google-drive` | Google Drive status (BETA role also required) |
| `read:metrics` | Dashboard and gamification data |

### MCP endpoint

```
POST /mcp/messages
Authorization: Bearer <access_token>
Content-Type: application/json
```

The endpoint implements JSON-RPC 2.0 over HTTP (Streamable HTTP transport). All MCP protocol messages (`initialize`, `tools/list`, `tools/call`, `prompts/list`, `prompts/get`) are sent as POST requests to this single URL.

### Available tools

#### Application tools

| Tool | Description |
|------|-------------|
| `listApplications` | List paginated applications with optional filters (status, recruiter, date range, archived, etc.) |
| `getApplication` | Get a single application by UUID |
| `getUpcomingApplications` | Applications with a next-step date/time in the near future |
| `getOverdueApplications` | Applications whose next-step date/time has passed |
| `createApplication` | Create a new application |
| `updateApplication` | Full update of an existing application |
| `updateApplicationStatus` | Change status only |
| `updateApplicationReminder` | Enable or disable the recruiter DM reminder |
| `markRecruiterDmSent` | Record that a recruiter DM was sent |
| `archiveApplication` | Archive an application |
| `deleteApplication` | Permanently delete an application |

#### Dashboard & gamification tools

| Tool | Description |
|------|-------------|
| `getPipelineSummary` | Aggregate pipeline statistics |
| `getGamificationProfile` | Current XP, level, rank, and streak |
| `getAchievements` | Achievement catalog with unlocked state |

#### Google Drive tools (ROLE_BETA required)

| Tool | Description |
|------|-------------|
| `getDriveStatus` | Google Drive connection status and configured base resumes |
| `listBaseResumes` | List available base resume templates |
| `copyResumeToApplication` | Copy a base resume into the application's Drive folder |
| `List-Base-Information` | List the candidate's base information documents (authoritative source of truth) |
| `Get-Base-Information-Content` | Extract the plain-text content of a base information document |

> Calling any Google Drive tool without `ROLE_BETA` returns 403 — the same restriction enforced on the REST controller.

### Available prompts

Prompts are pre-built guided workflows. Call `prompts/get` with the prompt name and arguments to receive an instruction message that tells the AI which tools to call and in what order.

| Prompt | Required args | Description |
|--------|--------------|-------------|
| `Prepare-New-Application` | `vacancyName`, `recruiterName`, `organization` (all optional) | Guides the AI to gather any missing fields and call `createApplication` |
| `Tailor-Resume` | `applicationId` | Fetches the application, lists base resumes, asks which to use, calls `copyResumeToApplication`, and returns the Google Docs link |
| `Summarize-Pipeline` | *(none)* | Calls `getPipelineSummary`, `listApplications` (recent 10), `getOverdueApplications`, and `getGamificationProfile` to produce a full pipeline report |

### Quick smoke-test with curl

```bash
# 1. Get an access token (exchange auth code from OAuth2 flow — or use a test user's JWT from /api/v1/auth/login)
TOKEN="<your-access-token>"

# 2. List available tools
curl -X POST http://localhost:8080/mcp/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

# 3. Call a tool
curl -X POST http://localhost:8080/mcp/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0","id":2,
    "method":"tools/call",
    "params":{
      "name":"listApplications",
      "arguments":{"page":0,"size":5}
    }
  }'

# 4. Get a prompt
curl -X POST http://localhost:8080/mcp/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0","id":3,
    "method":"prompts/get",
    "params":{
      "name":"Summarize-Pipeline",
      "arguments":{}
    }
  }'
```

### Connecting Claude Desktop

Add the following to your Claude Desktop `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "job-tracker": {
      "command": "mcp-remote",
      "args": [
        "http://localhost:8080/mcp/messages",
        "--header",
        "Authorization: Bearer <your-access-token>"
      ]
    }
  }
}
```

> Replace `<your-access-token>` with a valid JWT obtained from the OAuth2 Authorization Server or from `POST /api/v1/auth/login`. For production deployments, replace `http://localhost:8080` with your API base URL and use tokens from the full OAuth2 PKCE flow.

### OAuth2 endpoints (same AS used by GPT Actions)

- Authorization: `GET/POST /oauth2/authorize`
- Token: `POST /oauth2/token`
- JWKS: `GET /oauth2/jwks`
- Discovery: `GET /.well-known/openid-configuration`

---

## GPT Actions OAuth integration

This backend now exposes a dedicated OAuth 2.0 Authorization Code + PKCE flow for GPT Actions without changing the existing JWT login flow for human users. GPT-issued access tokens are scoped, bearer-only, and isolated to `/api/v1/gpt/**`.

### Required environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `OPENAI_GPT_CLIENT_ID` | Yes | OAuth client ID configured for the GPT Action |
| `OPENAI_GPT_CLIENT_SECRET` | Yes | OAuth client secret configured for the GPT Action |
| `OPENAI_GPT_REDIRECT_URIS` | Yes | Comma-separated list of allowed GPT Action redirect URIs |
| `OPENAI_GPT_SCOPES` | No | Comma-separated allowed GPT scopes; defaults to the built-in GPT scopes |

### Supported GPT scopes

- `read:profile`
- `read:applications`
- `write:applications`
- `read:resume`
- `read:google-drive`
- `read:metrics`

### OAuth endpoints

- Authorization endpoint: `GET/POST /oauth2/authorize`
- Token endpoint: `POST /oauth2/token`

### GPT Action setup steps

1. Create or update your GPT Action OAuth client with the backend base URL.
2. Register the same callback URL in `OPENAI_GPT_REDIRECT_URIS`.
3. Configure the client ID and client secret with `OPENAI_GPT_CLIENT_ID` and `OPENAI_GPT_CLIENT_SECRET`.
4. Set the action scopes to the minimum required set from `OPENAI_GPT_SCOPES`.
5. In the GPT Action OAuth settings, use:
   - Authorization URL: `https://<your-api-host>/oauth2/authorize`
   - Token URL: `https://<your-api-host>/oauth2/token`
6. After OAuth succeeds, call the GPT-friendly endpoints under `/api/v1/gpt/**`.

### GPT-friendly endpoints

- `GET /api/v1/gpt/profile`
- `GET /api/v1/gpt/applications`
- `GET /api/v1/gpt/applications/{id}`
- `POST /api/v1/gpt/applications`
- `PATCH /api/v1/gpt/applications/{id}/status`
- `GET /api/v1/gpt/resumes/base`
- `GET /api/v1/gpt/resumes/base/{resumeId}/content`
- `GET /api/v1/gpt/resumes/generated/{applicationId}/content`
- `GET /api/v1/gpt/google-drive/status`
- `GET /api/v1/gpt/metrics/summary`

Google Drive and resume GPT endpoints still enforce the user's existing `BETA` role in addition to the new OAuth scopes, so the GPT flow does not bypass the repository's current authorization rules.

`GET /api/v1/google-drive/status`

```json
{
  "configured": true,
  "connected": true,
  "googleEmail": "user@gmail.com",
  "googleDisplayName": "User Name",
  "googleAccountId": "permission-id",
  "rootFolderId": "drive-folder-id",
  "rootFolderName": "Job Tracker Root",
  "connectedAt": "2026-05-05T12:00:00",
  "baseResumes": [
    {
      "id": "uuid",
      "googleFileId": "google-doc-id",
      "documentName": "Resume Base",
      "webViewLink": "https://docs.google.com/document/d/google-doc-id/edit",
      "createdAt": "2026-05-05T12:05:00"
    }
  ]
}
```

`PUT /api/v1/google-drive/root-folder`

```json
{
  "folderIdOrUrl": "https://drive.google.com/drive/folders/drive-folder-id"
}
```

`POST /api/v1/google-drive/base-resumes`

```json
{
  "documentIdOrUrl": "https://docs.google.com/document/d/google-doc-id/edit"
}
```

`POST /api/v1/google-drive/applications/{applicationId}/resume-copies`

```json
{
  "baseResumeId": "base-resume-uuid"
}
```

Response:

```json
{
  "applicationId": "application-uuid",
  "baseResumeId": "base-resume-uuid",
  "copiedFileId": "copied-google-doc-id",
  "copiedFileName": "APP-application-uuid - Backend Engineer - Resume Base",
  "documentWebViewLink": "https://docs.google.com/document/d/copied-google-doc-id/edit",
  "vacancyFolderId": "vacancy-folder-id",
  "vacancyFolderName": "Backend Engineer - APP-application-uuid",
  "vacancyFolderWebViewLink": "https://drive.google.com/drive/folders/vacancy-folder-id"
}
```

## Running Tests

```bash
# All tests
mvn verify

# Unit tests only
mvn test -Dtest="com.jobtracker.unit.*"

# Integration tests only
mvn test -Dtest="com.jobtracker.integration.*"

# E2E tests only
mvn test -Dtest="com.jobtracker.e2e.*"
```

> **Note:** Integration and E2E tests require Docker to be running (Testcontainers pulls a MariaDB image automatically).

## Seed Fake Data

This project includes a startup seeder that can generate fake job applications using the Java library `net.datafaker:datafaker`.

The seeder is disabled by default and only runs when explicitly enabled.

Required parameters:

- `APP_SEED_ENABLED=true`
- `APP_SEED_USER_EMAIL=<existing user email>`

Optional:

- `APP_SEED_COUNT=1000` (default is `1000`)

Example with Maven:

```bash
export APP_SEED_ENABLED=true
export APP_SEED_USER_EMAIL=user@example.com
export APP_SEED_COUNT=1000
mvn spring-boot:run
```

Example with `java -jar`:

```bash
APP_SEED_ENABLED=true APP_SEED_USER_EMAIL=user@example.com APP_SEED_COUNT=1500 java -jar target/job-tracker-1.0.0.jar
```

If `APP_SEED_ENABLED=true` and `APP_SEED_USER_EMAIL` is not provided (or the user does not exist), the application startup fails with a clear error.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | `jdbc:mariadb://localhost:3306/jobtracker` | JDBC URL |
| `DB_USERNAME` | `jobtracker` | DB username |
| `DB_PASSWORD` | `jobtracker` | DB password |
| `JWT_SECRET` | *(dev default)* | JWT signing secret (min 256 bits) |
| `JWT_ACCESS_TOKEN_EXPIRATION_MS` | `900000` | Access token TTL (15 min) |
| `JWT_REFRESH_TOKEN_EXPIRATION_MS` | `604800000` | Refresh token TTL (7 days) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Allowed CORS origins |
| `GOOGLE_DRIVE_CLIENT_ID` | *(empty)* | Google OAuth client ID for Drive integration |
| `GOOGLE_DRIVE_CLIENT_SECRET` | *(empty)* | Google OAuth client secret for Drive integration |
| `GOOGLE_DRIVE_REDIRECT_URI` | `http://localhost:8080/api/v1/google-drive/oauth/callback` | OAuth callback URL registered in Google Cloud |
| `GOOGLE_DRIVE_OAUTH_COMPLETE_URL` | *(empty)* | Frontend URL that receives OAuth completion redirects |
| `GITHUB_USER_ID` | `65777252` | Stable GitHub numeric user ID of the linked account (canonical identity of the integration) |
| `GITHUB_LOGIN` | *(empty)* | Legacy username. Resolved once to a numeric ID when no `GITHUB_USER_ID` is set; also used as a fallback label while GitHub is unreachable |
| `GITHUB_API_BASE_URL` | `https://api.github.com` | GitHub REST API base URL |
| `GITHUB_TOKEN` | *(empty)* | Optional PAT; only raises the GitHub API rate limit |
| `GITHUB_CACHE_TTL_SECONDS` | `3600` | How long a resolved GitHub profile is cached |
| `OPENAI_GPT_CLIENT_ID` | *(empty)* | OAuth client ID for GPT Actions |
| `OPENAI_GPT_CLIENT_SECRET` | *(empty)* | OAuth client secret for GPT Actions |
| `OPENAI_GPT_REDIRECT_URIS` | *(empty)* | Comma-separated GPT Action redirect URIs |
| `OPENAI_GPT_SCOPES` | `read:profile,read:applications,write:applications,read:resume,read:google-drive,read:metrics` | Allowed GPT Action scopes |
| `GPT_FALLBACK_AUTH_ENABLED` | `false` | Enables the temporary static bearer fallback |
| `APP_GPT_FALLBACK_AUTH_ENABLED` | No | Enables the GPT fallback auth filter |
| `APP_GPT_FALLBACK_AUTH_TOKEN` | No | Static bearer token accepted by the fallback flow |
| `APP_GPT_FALLBACK_AUTH_ACCOUNT_EMAIL` | No | Email of the account used by the fallback flow |
| `APP_GPT_FALLBACK_AUTH_ACCOUNT_NAME` | No | Display name used when the fallback user is created |
| `GPT_FALLBACK_AUTH_TOKEN` | *(empty)* | Static bearer token used when fallback auth is enabled |
| `MCP_CLIENT_ID` | *(empty)* | OAuth2 client ID for MCP clients; if not set, no MCP client is registered |
| `MCP_CLIENT_SECRET` | *(empty)* | OAuth2 client secret for MCP clients (omit for public PKCE-only clients) |
| `MCP_REDIRECT_URIS` | *(empty)* | Comma-separated redirect URIs allowed for the MCP OAuth2 client |
| `RATE_LIMIT_AUTH_LOGIN_LIMIT_FOR_PERIOD` | `10` | Max login requests allowed per refresh period |
| `RATE_LIMIT_AUTH_LOGIN_REFRESH_PERIOD` | `1m` | Window used by the login rate limiter |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP gRPC endpoint (Jaeger/OpenTelemetry collector) |
| `PROMETHEUS_URL` | `http://localhost:9090` | Prometheus base URL for observability integrations |
| `SERVER_PORT` | `8080` | Server port |

## Monitoring

Spring Boot Actuator runs on a dedicated management port **8081**, completely separate from the main API port (8080). This port is **never exposed to the host machine** in Docker Compose — it is only reachable within the internal `infra_network` Docker network.

Prometheus scrapes metrics directly from the container over the internal network:

```yaml
scrape_configs:
  - job_name: job-tracker
    static_configs:
      - targets: ['app:8081']
    metrics_path: /actuator/prometheus
    scheme: http
    scrape_interval: 15s
```

No authentication token is required — network-level isolation (Docker bridge network) is the security boundary. The Actuator is unreachable from outside the Docker network.

## Rate Limiting

Auth endpoints are protected with Resilience4j rate limiters. When a limit is exceeded, the API returns `429 Too Many Requests` with the standard error payload used by the application.

## CI/CD

GitHub Actions workflow (`.github/workflows/ci.yml`) triggers on push/PR to `main`:

1. Checkout
2. Setup Java 21
3. Build project
4. Run unit tests
5. Run integration tests (Testcontainers)
6. Run E2E tests (Testcontainers + RestAssured)
7. Full `mvn verify`

## API Documentation

Swagger UI is available at `http://localhost:8080/swagger-ui.html` when the app is running.


### Completed interview counts

`interviewCount` belongs to each application and stores the confirmed number of completed interviews.
Status changes and `interviewScheduled` do not increment it. Existing totals are preserved; notes and
legacy status events are not interpreted as proof of attendance. Scheduled, cancelled or missed
interviews and asynchronous technical tests must not be included.

Set an absolute total through the authenticated API:

```http
PATCH /api/v1/applications/{id}
Content-Type: application/json

{"interviewCount": 2}
```

The MCP `Patch-Application` tool accepts the same optional `interviewCount` parameter. It must be a
non-negative integer. Omit it to keep the total, or set it to zero to correct it. Repeating the same
total does not add another interview. Updating this number does not award interview XP; gamification
remains independent of the business count. A status can be patched together with the total without
adding an implicit increment.

The React form asks whether an interview was actually completed when saving a change to an interview
funnel status, including moving between interview stages. Yes adds one to the form total; No saves
without adding; closing the popup cancels saving. The total can also be corrected manually in the form.
Dashboard and MCP analytics sum stored completed counts. Analytics `interviewRate` is the percentage
of applications with at least one completed interview, rather than interviews divided by applications.
