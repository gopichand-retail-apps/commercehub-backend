# CommerceHub Demo Threat Model

## Scope and trust boundaries

This model covers the local APP-02 storefront and API, including optional local synthetic sessions. The browser talks to Next.js on loopback; Next.js proxies API requests to the Spring Boot service on loopback; Spring Boot uses H2 by default or PostgreSQL when explicitly configured. The database and all product/order records are synthetic. No external payment, identity, email, analytics, or shipping service is called.

## Assets

- Server-owned product prices and stock counts.
- Synthetic order totals and order-line snapshots.
- The random order-access key used to retrieve one demo receipt; only its SHA-256 hash is persisted.
- The random synthetic-session bearer token used in server mode; only its SHA-256 hash is persisted.
- Session role, expiry, revocation status, and order-owner link.
- The local database file and structured application logs.

No customer name, address, email, account credential, or payment instrument is accepted or stored.

## Abuse cases and controls

- **Client changes price or total:** the API accepts product IDs and quantities only, recalculates totals server-side, and records the payment method as `SIMULATED`.
- **Checkout replay:** checkout requires a bounded `Idempotency-Key`; the database has a unique constraint, the request payload is hashed, and the same key with changed items conflicts.
- **Session token guessing or replay:** a 256-bit random token is returned once, only its SHA-256 hash is stored, and sessions expire after eight hours by default or can be revoked. Token values are not logged.
- **Cross-owner order lookup or idempotency replay:** an authenticated order stores its issuing session ID. Customer reads and replays require that same owner; another session receives the generic order-not-found response. A different role cannot use customer order routes.
- **Synthetic role selection:** role choices are intentionally selectable local fixtures, not proof of a real person's identity. Only the Customer role can create/read its own orders; merchant/support order-management endpoints do not exist.
- **Inventory race/oversell:** product rows are locked for checkout and stock decrement uses a conditional update inside a database transaction.
- **Order ID guessing:** guest lookup requires a 256-bit random access key; session-mode lookup requires the active owning Customer session. Missing or unauthorized orders return the same not-found response.
- **Guest/session request flooding:** quote, checkout, receipt, and session operations share a bounded in-process token bucket keyed by `getRemoteAddr()`. The default burst is 30 requests with a refill rate of 30 per 60 seconds; excess requests receive HTTP 429 and the normal safe error envelope. Catalog, role-list, preflight, and health requests are excluded.
- **Forwarded-IP spoofing:** rate-limit keys use the socket peer only. Spring forwarded-header processing is disabled; `Forwarded` and `X-Forwarded-For` supplied by a caller are ignored.
- **Input/query injection:** request DTOs are validated and database queries bind values. Search and category filters do not concatenate user input into SQL.
- **Log injection or data leakage:** request IDs are allowlist-validated; routes are logged as templates; query strings and request/response bodies are not logged. Errors return a generic public message and request ID.
- **Cross-origin browser calls:** the API uses an explicit configured origin and narrow methods/headers. Local browser traffic normally uses the same-origin Next.js proxy.

## Residual risks and exclusions

The rate limit is local to one process and is reset on restart. It tracks at most 4,096 peers and evicts the least-recently-used bucket when full. It is not a distributed quota or identity-based control. Requests proxied by the local Next.js server share that server's peer bucket; a deployment proxy would likewise appear as one peer. Do not trust caller-supplied forwarding headers; any future proxy-aware identity must be restricted to a verified proxy that strips and replaces those headers.

This is a local demo with no real end-user authentication, external identity provider, merchant/support order-management features, distributed rate limiting, privacy/legal review, TLS termination, backup/restore, production secret manager, or multi-instance idempotency verification. Server-mode roles are self-selected and only demonstrate ownership checks; the browser keeps the opaque token in tab-scoped `sessionStorage`, so this is not a production session design. H2 is used on the current machine because PostgreSQL and Docker are unavailable; the PostgreSQL profile is provided but was not run here. The demo tax basis points default to zero and do not represent a legal tax rate. Do not expose the services publicly or use real customer data or payments.
