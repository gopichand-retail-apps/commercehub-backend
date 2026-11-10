# CommerceHub Quality and Standards Status

Scope: APP-02 CommerceHub, reviewed against `AGENTS.md` and the supplied portfolio master playbook on 2026-11-01. This records verified checks and remaining gaps; it is not a security certification or legal opinion.

## Verified in this local demo

- Frontend: `npm run test:unit` passes 93 tests in 14 suites. V8 coverage across every `src/**/*.{js,jsx,ts,tsx}` module and `next.config.ts` is 99.55% statements, 97.68% branches, 98.96% functions, and 99.55% lines. The global 90% gate is enforced in `jest.config.mjs`; auth/session and API-origin modules also have 95% per-file gates. No UI or domain source is excluded.
- Frontend: all 4 `npm run test:identity` tests and all 6 Playwright E2E tests pass in both mock and live API-selection modes. E2E covers model locale/metadata, API catalog and quote, promo errors, mobile layout, API-issued checkout, and expired-session recovery. `npm run format:check`, `npm run lint`, `npm run typecheck`, and a production build with an explicit mock API origin pass. The catalog eagerly loads the first desktop row; later cards remain lazy.
- Backend: `mvn -B verify` passes 45 tests with 0 failures, errors, or skips. JaCoCo reports 99.54% bytecode instructions (statement proxy), 98.81% branches, 100.00% methods, and 99.25% lines. Critical order/session coverage is gated at 95% per class; `CommerceService` reaches 98.76% instructions, 98.28% branches, 100.00% methods, and 98.04% lines. The aggregate 90% gates pass with no production classes excluded.
- The Java critical-path suite now covers promo-code request fingerprints, authenticated session-owned orders, missing idempotency keys, malformed access keys, and denial of guest access to session-owned orders.
- `sh scripts/check-java-lines.sh`, `mvn -B -q -Pquality validate`, and `mvn -B -q -DskipTests package` pass.
- `npm audit --omit=dev` reports zero production dependency vulnerabilities on the frontend lockfile. A Java dependency/SAST scan was not run.
- Checkout totals use integer minor units and are calculated on the server. Stock decrement, order insert, and lines execute in one transaction. Idempotency keys prevent duplicate orders; only a hash of the high-entropy order access key is stored.
- CORS is an explicit allowlist for `http://127.0.0.1:5301` and `http://localhost:5301`. Request logs use route templates, request/trace IDs, status, duration, and safe error codes; unknown paths are logged as `/unmatched`.
- `docs/architecture.md` documents C4 context/containers, trust boundaries, data flow, ERD, workflows, and deviations. `docs/openapi.yaml` defines the nine implemented paths.
- Session issuance returns a random short-lived token once and stores only its hash. Authenticated orders store the owner session ID; guests retain the existing order-access-key flow.
- The identity panel retains its data-mode indicator and API-backed role/session controls; implementation-detail description strings were removed from the UI model.
- No real payment provider, customer identity fields, analytics events, or third-party image requests are used.
- Standalone CI now enforces frontend coverage/typecheck and backend JaCoCo coverage in addition to format, lint, build, tests, line check, Checkstyle, and dependency audit.

## Remaining gaps and deviations

- The local default database is file-backed H2 because this machine has no Docker/PostgreSQL runtime. A PostgreSQL profile, Compose service, and Flyway migrations exist, but that profile has not been run here.
- Sessions are selectable synthetic demo identities, not real customer authentication. Merchant/support order-management flows are not implemented; only the Customer role can create/read its own authenticated orders. The bearer token is held in the current tab's `sessionStorage`. Do not expose this service publicly or use real customer/order data.
- Quote, checkout, receipt, and session operations use an in-process token bucket keyed by the direct socket peer (default burst 30, refilling 30 per 60 seconds). Its bounded 4,096-entry state is process-local, evicts least-recently-used peers when full, resets on restart, and does not distinguish visitors behind the Next.js proxy. Forwarded-IP headers are ignored; catalog, role-list, health, and preflight requests are not throttled. This is not a distributed or public-deployment rate limit.
- Wishlist, returns, merchant admin, and the fulfillment/shipment lifecycle are not implemented. Orders currently end at synthetic `CONFIRMED`; the APP-02 end-to-end fulfillment acceptance remains incomplete.
- Shared client telemetry, PortfolioOps ingestion/dashboard integration, and a client error-boundary event pipeline are not implemented. Server request logs are structured JSON; no visitor analytics are collected.
- The frontend Jest gate reports LCOV, JSON summary, and text under ignored `coverage/`; CSS is not part of JavaScript/TypeScript counters. Playwright remains browser workflow coverage, not source-code coverage. Frontend CI now starts the sibling API on isolated in-memory H2 and runs both API modes.
- The previous Next.js LCP suggestion for `/products/everyday-daypack.jpg` was reproduced in E2E and stopped appearing after the first catalog row was eager-loaded. Remaining below-fold images stay lazy. Core Web Vitals are not measured; a dev warning disappearing is not a p75 LCP measurement.
- A SonarQube quality gate is not configured; the enforced coverage gates are the local Jest and JaCoCo checks.
- Full WCAG 2.2 AA audit, automated contrast/axe checks, screen-reader testing, Core Web Vitals measurement, TLS/HSTS/CSP deployment, backup/restore, secret scanning, SAST, and Java dependency scanning remain outstanding.

## Safe next steps

1. Run the PostgreSQL profile in an environment with Docker or PostgreSQL, then add a Testcontainers integration profile.
2. Keep any future real customer authentication or merchant/support permissions separate from the current local synthetic-session demo.
3. Implement fulfillment state and returns; add coverage gates and shared telemetry/PortfolioOps as separate reviewed slices.
4. Perform accessibility, privacy, security, and performance verification before any non-local deployment.

## 2026-10-31 guest-endpoint rate-limit baseline (before synthetic sessions)

- `mvn -B -Dtest=GuestRequestRateLimiterTest,GuestOrderRateLimitApiTest test`: 3 focused tests passed.
- `mvn -B test`: 10 tests passed with no failures, errors, or skips. Spring MVC tests use the `test` profile and in-memory H2; no request was sent to the active local server.
- `sh scripts/check-java-lines.sh`, `mvn -B -Pquality validate`, and `mvn -B -DskipTests package` passed.
- Quote, checkout, and receipt routes share a per-process token bucket keyed only by the direct socket peer. Defaults are burst capacity 30 and refill 30 tokens per 60 seconds; the in-memory LRU stores at most 4,096 peers. Forwarded-IP headers are ignored. Health, catalog, and preflight requests are not throttled. Requests through Next.js share its peer quota; the limiter is not distributed, identity-aware, or suitable as the only public-deployment control.
