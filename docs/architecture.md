# CommerceHub Backend Architecture

Maintained in this independent Git repository at **docs/architecture.md**. Updated **2026-11-09**. The guide covers the implemented stack, interfaces, data model, operational limits, and release requirements.

## Contents

1. [Scope, users, and repository boundary](#1-scope-users-and-repository-boundary)
2. [Context, containers, and trust boundaries](#2-context-containers-and-trust-boundaries)
3. [Source ownership and module map](#3-source-ownership-and-module-map)
4. [Screens, navigation, and lifecycle](#4-screens-navigation-and-lifecycle)
5. [Primary workflows and recovery](#5-primary-workflows-and-recovery)
6. [Domain, persistence, and data ownership](#6-domain-persistence-and-data-ownership)
7. [API and internal contracts](#7-api-and-internal-contracts)
8. [Security, privacy, and egress](#8-security-privacy-and-egress)
9. [Design, accessibility, and platform adapters](#9-design-accessibility-and-platform-adapters)
10. [Build, checks, and deployment](#10-build-checks-and-deployment)
11. [Architecture decisions and tradeoffs](#11-architecture-decisions-and-tradeoffs)
12. [Requirements traceability](#12-requirements-traceability)
13. [Dated verification and limitations](#13-dated-verification-and-limitations)
14. [Extension rules and companion repository](#14-extension-rules-and-companion-repository)

## 1. Scope, users, and repository boundary

A synthetic storefront for customers, with merchant and support profiles.

Catalog search, category filters, sorting, local cart, server quotes, simulated checkout, and session-scoped order lookup.

This repository owns the Spring Boot / Java 17 API and H2 state. UI rendering belongs to the separate frontend. The service validates and executes the domain operations described below.

There are two independent Git roots for this application, each with its own README and architecture guide. A parent app/workspace folder is a checkout convenience. Use synthetic records; current local services remain on loopback.

### Product and roles

CommerceHub is a fictional customer storefront. The local vertical slice supports browsing/search/filtering a seeded catalog, a browser-local bag, server-calculated quotes, the synthetic `HELLO10` promotion, simulated checkout, and receipt lookup. Both mock and live-selection modes obtain role choices and short-lived synthetic Customer, merchant, and support sessions from the selected API. The implemented order workflow is Customer-only. There are no external accounts, merchant/support order-management screens, wishlist, returns, real payment provider, shipment service, or live fulfillment.

## 2. Context, containers, and trust boundaries

```mermaid
flowchart LR
  U["CommerceHub user"] --> F["Next.js frontend - port 5301"]
  F -->|"API requests across an untrusted browser boundary"| A["Spring Boot / Java 17 API - port 5302"]
  A --> D["H2 - app-owned local persistence"]
  M["Validated public content model"] --> F
```

The API is the data authority, but only the identity/ownership controls actually implemented below can be relied on. CORS is not authentication.

### C4 Context

```mermaid
flowchart LR
  shopper["Guest shopper or synthetic customer"] -->|"HTTP loopback locally"| web["CommerceHub storefront\nNext.js :5301"]
  web -->|"same-origin /api rewrite"| api["CommerceHub API\nSpring Boot :5302"]
  api -->|"JDBC + Flyway"| db[("H2 local file\nPostgreSQL profile")]
  api -.->|"no external PSP is called"| sandbox["Payment status: SIMULATED"]
```

Trust boundaries: browser storage and browser inputs are untrusted; the Next.js server is a local proxy; Spring Boot owns role issuance, session validation, price, tax, shipping, promo validation, order state, and stock; the database persists synthetic records. The API does not contact an identity provider. Guest order-access keys and synthetic-session bearer tokens are local demo credentials, not production authentication.

### C4 Containers

```mermaid
flowchart LR
  subgraph Browser["Browser trust boundary"]
    ui["Next.js React UI\nsearch, bag, dialogs"]
    storage[("localStorage\nproduct IDs + quantities")]
    tabStorage[("sessionStorage\nAPI-issued synthetic session token")]
    ui <--> storage
    ui <--> tabStorage
  end
  subgraph Server["Local application trust boundary"]
    next["Next.js server\nport 5301\nrewrite /api/*"]
    spring["Spring Boot REST API\nport 5302\nsessions, validation, pricing, orders"]
    db[("H2 file or PostgreSQL\nFlyway migrations")]
    next --> spring
    spring --> db
  end
  ui -->|"same origin /api/v1/*"| next
```

## 3. Source ownership and module map

| Owner/path                                                                                                                                                                                                                                                                                                                                                                                                                                        | Responsibility                                                               |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| [src/main/java/com/commercehub/CommerceHubApplication.java](../src/main/java/com/commercehub/CommerceHubApplication.java)                                                                                                                                                                                                                                                                                                                         | Spring Boot process entry                                                    |
| [src/main/java/com/commercehub/catalog/ProductController.java](../src/main/java/com/commercehub/catalog/ProductController.java)<br>[src/main/java/com/commercehub/health/HealthController.java](../src/main/java/com/commercehub/health/HealthController.java)<br>[src/main/java/com/commercehub/order/OrderController.java](../src/main/java/com/commercehub/order/OrderController.java)                                                         | HTTP adapters and public request handling                                    |
| [src/main/java/com/commercehub/catalog/ProductCatalogService.java](../src/main/java/com/commercehub/catalog/ProductCatalogService.java)<br>[src/main/java/com/commercehub/order/CommerceService.java](../src/main/java/com/commercehub/order/CommerceService.java)                                                                                                                                                                                | Application/domain services; verify individual transaction/permission owners |
| [src/main/java/com/commercehub/order/OrderRepository.java](../src/main/java/com/commercehub/order/OrderRepository.java)<br>[src/main/java/com/commercehub/security/SyntheticSessionRepository.java](../src/main/java/com/commercehub/security/SyntheticSessionRepository.java)                                                                                                                                                                    | Persistence boundary and queries                                             |
| [src/main/java/com/commercehub/security/SyntheticSessionController.java](../src/main/java/com/commercehub/security/SyntheticSessionController.java)<br>[src/main/java/com/commercehub/security/SyntheticSessionService.java](../src/main/java/com/commercehub/security/SyntheticSessionService.java)                                                                                                                                              | Security/session configuration and synthetic identity                        |
| [src/main/java/com/commercehub/api/ApiExceptionHandler.java](../src/main/java/com/commercehub/api/ApiExceptionHandler.java)<br>[src/main/java/com/commercehub/config/RequestIdFilter.java](../src/main/java/com/commercehub/config/RequestIdFilter.java)                                                                                                                                                                                          | Safe error handling and request logging                                      |
| [src/main/resources/db/migration/V1__commercehub_schema.sql](../src/main/resources/db/migration/V1__commercehub_schema.sql)<br>[src/main/resources/db/migration/V2__correct_demo_daypack_catalog_item.sql](../src/main/resources/db/migration/V2__correct_demo_daypack_catalog_item.sql)<br>[src/main/resources/db/migration/V3__add_synthetic_session_ownership.sql](../src/main/resources/db/migration/V3__add_synthetic_session_ownership.sql) | Ordered Flyway schema/seed migrations                                        |
| [src/main/resources/application-postgres.yml](../src/main/resources/application-postgres.yml)<br>[src/main/resources/application.yml](../src/main/resources/application.yml)                                                                                                                                                                                                                                                                      | Local/postgres profile configuration                                         |
| [pom.xml](../pom.xml)                                                                                                                                                                                                                                                                                                                                                                                                                             | Java/dependency/build/quality gate authority                                 |
| [docs/openapi.yaml](../docs/openapi.yaml)                                                                                                                                                                                                                                                                                                                                                                                                         | Published request/response contract source                                   |
| [src/test](../src/test)                                                                                                                                                                                                                                                                                                                                                                                                                           | Existing test/support directory; configured does not mean rerun              |
| [.github/workflows](../.github/workflows)                                                                                                                                                                                                                                                                                                                                                                                                         | Configured CI; hosted outcomes require separate verification                 |

The source map follows the existing framework directories and test placement.

## 4. Screens, navigation, and lifecycle

This repository has no rendered screens. It supports the frontend through the HTTP/domain contracts in sections 5–7. Its lifecycle entry, response/error mapping, session behavior where present, and storage ownership are listed in section 3. Startup health is http://127.0.0.1:5302/health; that configured address is not evidence of a running service. Persistent state is owned by this backend; preserve existing database files during changes.

## 5. Primary workflows and recovery

### Main workflow and failures

```mermaid
sequenceDiagram
  actor Shopper
  participant UI as Next.js storefront
  participant API as Spring Boot API
  participant DB as H2/PostgreSQL
  Shopper->>UI: Browse and add items
  UI->>API: GET /api/v1/products
  API-->>UI: Synthetic catalog
  UI->>API: POST /api/v1/orders/quote
  API->>DB: Read current price and stock
  API-->>UI: Server-calculated quote
  UI->>API: GET /api/v1/sessions/roles
  API-->>UI: Customer, merchant, support
  UI->>API: POST /api/v1/sessions
  API->>DB: Store role and token hash
  API-->>UI: Short-lived bearer token
  Shopper->>UI: Place demo order
  UI->>API: POST /api/v1/orders + idempotency key + bearer token
  API->>DB: Begin, verify owner, lock products, recalculate, decrement, insert order/lines
  DB-->>API: Commit
  API-->>UI: Synthetic CONFIRMED receipt
  UI-->>Shopper: Confirmation, no real charge
```

Expected recovery behavior: malformed request or unknown promo returns a safe 400 envelope; insufficient or changed stock returns 409 and preserves the bag; a repeated key with the same request returns the prior order; the same key with changed request returns 409; an expired or incorrect session cannot read an order; a missing/incorrect order access key returns 404 for the retained guest API compatibility path; an exhausted token bucket returns a safe 429 envelope. The UI displays server messages for quote errors and keeps checkout disabled until a current quote is available.

## 6. Domain, persistence, and data ownership

The API owns the current persistence boundary. Keep database engines, schema/migration order, exact-money rules where applicable, and transaction invariants consistent with executable source.

### Data flow and ownership

1. The browser requests `GET /api/v1/products`; Next.js rewrites it to Spring Boot. Product values are synthetic seed data.
2. The browser sends product IDs, quantities, and an optional promo code to `POST /api/v1/orders/quote`. The API reloads current products and calculates all totals in integer minor units in both modes.
3. `isMock` selects the API origin only. In both modes, the browser loads `GET /api/v1/sessions/roles`, creates a synthetic role session, and keeps its short-lived API-issued bearer token in this tab's `sessionStorage`.
4. The API stores only a SHA-256 hash of each random 256-bit session token and returns the session ID, role, token, and expiry once. No name, address, email, password, or card data is requested or stored.
5. Checkout sends the same item request, a random idempotency key, and the API-issued bearer token. The API binds the order to the session ID. The backend retains its guest access-key endpoint for API compatibility, but the current storefront UI does not use it.
6. The API validates the request, checks promo and stock, locks product rows, recalculates the total, conditionally decrements stock, and inserts the order and item snapshots in one database transaction. A repeated key by the same owner returns the existing order; an owner mismatch is denied without returning another owner's receipt.
7. Order creation and receipt reads require the Customer role and matching owner session in the storefront. Merchant/support identities cannot use this customer order workflow.
8. Payment is stored only as `SIMULATED`; no payment network is called. Shipping and tax are illustrative configuration.

The localStorage bag contains only product IDs and quantities. It may be cleared by the user. Demo orders and inventory are local development data and are not personal data.

### Data model

```mermaid
erDiagram
  SYNTHETIC_SESSION ||--o{ CUSTOMER_ORDER : owns
  PRODUCT ||--o{ ORDER_LINE : "snapshotted by"
  CUSTOMER_ORDER ||--|{ ORDER_LINE : "contains"
  SYNTHETIC_SESSION {
    uuid id PK
    string role
    char token_hash UK
    timestamp created_at
    timestamp expires_at
    timestamp revoked_at
  }
  PRODUCT {
    uuid id PK
    string slug UK
    string name
    string category
    bigint price_minor
    int stock_quantity
    boolean available
    boolean featured
  }
  CUSTOMER_ORDER {
    uuid id PK
    string access_token_hash
    uuid owner_session_id FK
    string idempotency_key UK
    string request_hash
    string status
    bigint subtotal_minor
    bigint discount_minor
    bigint shipping_minor
    bigint tax_minor
    bigint total_minor
    smallint tax_basis_points
    string promo_code
    string payment_method
    timestamp created_at
  }
  ORDER_LINE {
    uuid id PK
    uuid order_id FK
    uuid product_id FK
    string product_name
    string product_image_url
    int quantity
    bigint unit_price_minor
    bigint discount_minor
    bigint tax_minor
    bigint line_total_minor
  }
```

Schema is versioned by Flyway. The local profile uses file-backed H2 in PostgreSQL compatibility mode because Docker/PostgreSQL are not installed on the current machine. A PostgreSQL profile and Compose file exist but have not been exercised here.

Guest orders keep `owner_session_id` null and store a hash of the order-access key. Authenticated orders store their owner session ID and leave `access_token_hash` null. The existing unique idempotency key remains unchanged; the service checks the owner before returning a replayed receipt.

## 7. API and internal contracts

The repository contract is [docs/openapi.yaml](../docs/openapi.yaml). Keep public paths, status/error envelopes, headers, payload validation, and compatibility tied to that source.

### API and logging

The versioned API contract is in [`openapi.yaml`](openapi.yaml). Every request receives validated `requestId` and `traceId` correlation values and response headers. JSON request-completion logs include route template, method, status, duration, outcome, and safe error code; query strings and request/response bodies are not logged. Session events include a hash of the session ID and the synthetic role, never the bearer token. Unknown URLs are logged as `/unmatched`, not with attacker-controlled path text. Order-created events contain a hash of the order ID, item count, total minor units, and `SIMULATED` status, not a raw order identifier or customer fields.

This correlation implementation is not an OpenTelemetry SDK or a PortfolioOps collector integration. No client analytics are sent.

## 8. Security, privacy, and egress

Current release limitation: **The published baseline uses synthetic sessions. Verified-authentication/PostgreSQL candidate work exists separately in IT and is not included in these repositories. Formatting, development dependency, and deployment gaps remain.**

### Security and privacy decisions

- Exact local CORS allowlist: `http://127.0.0.1:5301` and `http://localhost:5301`; no wildcard.
- Server-side runtime validation, parameterized JDBC queries, integer minor-unit money, transaction-scoped stock updates, and idempotency constraint.
- Loopback binding by default. Local sessions use a random 256-bit bearer token, store only its hash, expire after eight hours by default, and can be revoked. Authenticated order records bind to a session ID; only Customer sessions may create/read their own orders. The guest order-key API remains for compatibility but is not used by the current UI.
- No external identity provider, real customer identity fields, analytics, or external image requests. The browser stores the API-issued synthetic token in tab-scoped `sessionStorage`; do not treat these selectable synthetic roles as real authentication.
- Quote, checkout, receipt, and session operations use a per-process token bucket keyed by the direct socket peer (default burst 30, refill 30 per 60 seconds); catalog, role-list, preflight, and health routes are excluded. Forwarded-IP headers are not trusted. Requests through Next.js share its peer quota, the 4,096-entry LRU state resets on restart, and quotas are not coordinated across instances.
- Demo tax is illustrative; the default is 0 basis points. The built-in promo is synthetic.
- Next.js `robots.txt` disallows indexing; the privacy route describes actual local demo behavior and is not a production privacy policy.

Keep private credentials out of public models, browser bundles, logs, URLs, and source archives. No additional runtime network destination or external service is configured. Keep logs limited to the source-defined request/trace and safe aggregate fields. Backups, retention, deletion, and real-user ownership need explicit product/operations decisions before external exposure.

## 9. Design, accessibility, and platform adapters

There is no visual rendering or native package in this API repository. Preserve safe errors and stable contracts so the frontend can present accessible recovery states. Hardware scanning, banking/payment rails, shipping providers, and native integrations remain limited to what the current source actually implements; simulator behavior is not device/provider integration.

## 10. Build, checks, and deployment

Use Java **17** and Maven **3.9.16** with [pom.xml](../pom.xml). `mvn.cmd -B -ntp -Dspring.profiles.active=test verify` uses the configured isolated test profile. Run local startup through the existing README command; inherited DEBUG must be removed only in newly launched Java processes. Java verification was not rerun for the documentation updates. Maven dependency installation needs registry access.

The API remains loopback-only. Direct-start storage is `.local/commercehub` relative to this repository.

CI configuration is listed in section 3. Run the configured checks before merging or releasing. Deployment requires separate verification of the selected hosting environment.

## 11. Architecture decisions and tradeoffs

### Implemented Design Patterns and Invariants

- **Modular monolith and layered API:** Spring Boot route/controller validation delegates to application/domain services and JDBC repositories; Flyway owns schema evolution. Next.js rewrites selected API traffic but does not calculate authoritative commerce state.
- **Server-authoritative quote and checkout:** the browser sends product identifiers, quantities, and an optional promo. The API reloads current catalog/stock, calculates integer-minor-unit totals, validates owner/session, and persists the order and item snapshots transactionally.
- **Synthetic identity boundary:** role choices and short-lived role sessions come from the selected API in both modes. API-issued tokens are tab-scoped; the frontend never invents role, ownership, or receipt authorization.
- **Idempotent order command:** a unique key and request hash prevent duplicate orders. A replay returns the prior receipt only to the same owner; conflicting payload/owner reuse is rejected.
- **Capability boundary:** order creation and lookup are customer-only; merchant/support role sessions do not imply permissions for unimplemented management workflows. Payment remains `SIMULATED`.

The two Git repositories remain separate. Each owns one maintained README and this repository-local architecture guide. Shared application behavior may be described here for contract context, but implementation changes belong to the owning source module.

## 12. Requirements traceability

| Capability                       | Acceptance                                                        | Owner/path                                                             | Current status                                                | Evidence                               | Risk                                                                                                                                                                                                                                 |
| -------------------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Repository-local architecture    | Guide available in this checkout and linked from README           | docs/architecture.md; README.md                                        | Documented and validated                                      | File, link, and diagram checks         | Keep documentation consistent with source changes                                                                                                                                                                                    |
| API composition and domain state | Preserve existing workflows and failure states                    | src/main/java/com/commercehub/api/ApiErrorResponse.java and source map | Implemented; application verification limits are listed below | Current source and existing app design | The published baseline uses synthetic sessions. Verified-authentication/PostgreSQL candidate work exists separately in IT and is not included in these repositories. Formatting, development dependency, and deployment gaps remain. |
| Public deployment                | Verified identity, data durability, recovery, and hosted behavior | Existing deployment/API boundaries                                     | Needs separate hardening/verification                         | No new hosted evidence                 | Local checks do not establish production readiness                                                                                                                                                                                   |

## 13. Dated verification and limitations

Last documentation review: **2026-11-09**. The checks below distinguish documentation validation from application and deployment testing.

- Repository-local guide, README link, source-path references, section/contents anchors, and Mermaid syntax: passed documentation validation.
- New application tests/builds, coverage, dependency audits, browser/device accessibility/performance, hosted CI, and Vercel deployment: **not rerun for the documentation updates**.
- Frontend production builds passed on 2026-11-09 with Node 24.15.0. Build success does not establish that all application quality gates pass.
- Outstanding baseline limit: The published baseline uses synthetic sessions. Verified-authentication/PostgreSQL candidate work exists separately in IT and is not included in these repositories. Formatting, development dependency, and deployment gaps remain.

The baseline results below record earlier validation. Re-run the listed commands against the current revision before release.

Previously recorded application verification:

### Local Verification and Coverage

Run the frontend from `commercehub-frontend` and the backend from
`commercehub-backend`:

```sh
npm run format:check
npm run test:identity
npm run test:unit
npm run test:e2e
NEXT_PUBLIC_IS_MOCK=false npm run test:e2e
npm run typecheck
npm run lint
COMMERCEHUB_MOCK_API_URL=http://127.0.0.1:5302 npm run build
npm audit --omit=dev

mvn -B verify
mvn -B -Pquality validate
sh scripts/check-java-lines.sh
```

The latest local frontend run passed 93 Jest tests with 99.55% statements,
97.68% branches, 98.96% functions, and 99.55% lines. Its global coverage gate
is 90%; `useSyntheticIdentity.ts`, `commerce-api.ts`, and `config.ts` each have
95% per-file gates. The 45-test Java run passed with 99.54% instructions,
98.81% branches, 100% methods, and 99.25% lines; JaCoCo also enforces 95% for
the order package, product catalog service, and synthetic session service.
`CommerceService` reached 98.76% instructions, 98.28% branches, 100% methods,
and 98.04% lines.

Playwright passed six browser workflows in each API mode using an isolated
in-memory H2 database. Those E2E runs prove selected-API routing and UI
workflows, not production PostgreSQL integration; they are not source-code
coverage. Individual noncritical frontend files such as `CommerceStore.tsx`
remain below 95% branches, while the aggregate application and critical backend
gates pass.

### Standards status and deviations

See [`quality-status.md`](quality-status.md) for executable checks, verified results, and remaining gaps. Local synthetic sessions and Customer owner/role checks cover the implemented order workflow only; there are no real customer accounts or merchant/support management features. PostgreSQL execution, fulfillment, return flows, distributed/app-wide rate limiting, PortfolioOps telemetry, full WCAG/axe, secret/SAST/Java dependency scanning, and production deployment controls remain incomplete. Do not expose this prototype or use real customer/order/payment data.

## 14. Extension rules and companion repository

Start with [README.md](../README.md) and this guide. The companion repository is [commercehub-frontend](https://github.com/gopichand-retail-apps/commercehub-frontend); clone the pair as siblings when using the existing local integration/model contract. This guide is inside the current repository and does not depend on a parent-folder architecture file to exist.

Update this guide when owned source, routes, persistence, identity, UI states, egress, or deployment behavior changes. Coordinate public contract changes with the companion repository and tests. Preserve existing data, run isolated checks with synthetic records, record failures, and review release and infrastructure changes before publication.
