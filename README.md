# CommerceHub API

![CI](https://github.com/gopichand-retail-apps/commercehub-backend/actions/workflows/ci.yml/badge.svg)

[Architecture guide](docs/architecture.md) — source map, workflows, contracts, data, security, and checks.

## Repository layout and deployment boundary

This independent repository owns CommerceHub's Spring Boot / Java 17 API and authoritative H2 state. The UI is the separate [commercehub-frontend](https://github.com/gopichand-retail-apps/commercehub-frontend) repository. Keep the current directory names, Git histories, origins, and independent builds.

The Vercel configuration belongs to the frontend only. This API has no verified Vercel deployment adapter. Run the Java service on a suitable separate host; no Java runtime configuration is invented here. The local file database is not shared durable storage for stateless function instances. Do not deploy the local simulator as a public API or point it at real records.

Use Java 17 and Maven; the POM is the dependency authority. From the full Retail Apps workspace, `./scripts/Start-Apps.ps1 -App CommerceHub` uses the correct built/native entry after setup. From this repository alone, the documented Maven command below starts the service.

Health: http://127.0.0.1:5302/health. Direct startup stores data under `.local/commercehub`; the workspace launcher can select a separate app-owned path. The [API contract](docs/openapi.yaml) and [canonical architecture](docs/architecture.md) describe the existing routes and trust boundaries. The architecture guide is inside this repository.

Known release gaps: The published baseline uses synthetic sessions. Verified-authentication/PostgreSQL candidate work exists separately in IT and is not included in these repositories. Formatting, development dependency, and deployment gaps remain.

Spring Boot 3.5 API for the APP-02 CommerceHub storefront. It binds to `127.0.0.1:5302` by default and allows the explicit loopback origins `http://127.0.0.1:5301` and `http://localhost:5301`. Override them with a comma-separated `FRONTEND_ORIGINS` allowlist; do not use a wildcard.

Shared architecture: [repository architecture](docs/architecture.md). API contract: [`docs/openapi.yaml`](docs/openapi.yaml). Quality/gap status: [`docs/quality-status.md`](docs/quality-status.md).

## Start locally

```sh
mvn verify
mvn spring-boot:run
```

`mvn verify` runs the in-memory H2 tests, writes `target/site/jacoco/index.html`,
and enforces at least 90% instruction, branch, method, and line coverage across
all compiled production classes. It also enforces 95% per-class coverage for
the order domain package, product catalog service, and synthetic session
service. No production class packages are excluded.
`mvn test` remains available for a quick test-only run; use `mvn verify` for the
coverage gate.

Run the local Java line-length check with `sh scripts/check-java-lines.sh`. The optional Maven Checkstyle profile is `mvn -Pquality validate` and needs Maven Central access.

The default profile uses a file-backed H2 database under `.local/commercehub`. Flyway applies the versioned schema migrations; eight fictional products are seeded on first startup. No Docker or PostgreSQL installation is needed for the local demo.

## PostgreSQL profile

`compose.yaml` starts PostgreSQL 17 when Docker Compose is available. Create a local `.env` from `.env.example`, replace its placeholder password with a random local-only value, then run:

```sh
docker compose up -d database
SPRING_PROFILES_ACTIVE=postgres DB_PASSWORD='your-local-password' mvn spring-boot:run
```

The app profile reads database credentials from environment variables. Never commit `.env` or use the example placeholder outside a disposable local environment.

## API

- `GET /health`
- `GET /api/v1/products?q=&category=`
- `GET /api/v1/products/{slug}`
- `GET /api/v1/sessions/roles`
- `POST /api/v1/sessions`
- `GET /api/v1/sessions/current`
- `DELETE /api/v1/sessions/current`
- `POST /api/v1/orders/quote`
- `POST /api/v1/orders` with `Idempotency-Key` and either a synthetic bearer session or guest `X-Order-Access-Key`
- `GET /api/v1/orders/{orderId}` with an owning synthetic bearer session or guest `X-Order-Access-Key`

Synthetic sessions are local test identities for the APP-02 roles Customer, merchant, and support. Session tokens
are random bearer values that expire after eight hours by default; the API stores only their SHA-256 hashes.
Authenticated orders are bound to the issuing session, and this storefront's implemented order reads/creation are
limited to that Customer session. The API still supports its guest order-key endpoint for compatibility, but the
current frontend uses API-issued sessions in both data modes. There is no external identity provider, customer
account, or real personal data.

Quote, checkout, receipt, and synthetic-session requests share a per-process token bucket keyed by the
direct TCP peer. The default burst capacity is 30 requests, refilling at 30 tokens per
60 seconds; configure it with `GUEST_RATE_LIMIT_MAX_REQUESTS` and
`GUEST_RATE_LIMIT_WINDOW_SECONDS`. Exhaustion returns HTTP 429 with the standard
`{code,message,requestId}` envelope and code `RATE_LIMITED`. Catalog routes, CORS preflights,
the synthetic-role list, and `/health` are not throttled.

The API uses the socket peer address only; it does not trust `Forwarded`,
`X-Forwarded-For`, or other client-supplied forwarding headers. Because the local Next.js
server proxies calls, browser visitors share the proxy's backend quota. The limiter
keeps at most 4,096 peer buckets in process memory, evicts the least-recently-used entry
when full, and resets on restart. It is not shared across instances or sufficient as the
only control for public or multi-instance deployment.

Order creation recalculates prices on the server, locks products, decrements inventory transactionally, and binds
current storefront orders to the issuing synthetic session in both modes. The opaque bearer token is held in the
current tab's `sessionStorage` and is never logged. The API continues to hash guest order-access keys for its
compatibility path, but the current UI does not use it. The only payment method stored is `SIMULATED`. Request
logs omit query strings and bodies; correlation IDs are validated before use.

Read `docs/threat-model.md` before exposing or adapting the API. Synthetic sessions are not real user
authentication, and merchant/support order-management flows are not implemented. This local portfolio service
is not production-ready and has no payment processor, shipping integration, or jurisdiction-specific tax
calculation.
