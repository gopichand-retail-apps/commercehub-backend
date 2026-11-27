# CommerceHub Quality and Standards Status

## BE-02 feature verification — November 18, 2026

This is a deliberate feature on local `feature/be02-order-events`; older results below remain historical.
The event writer/publisher/consumers/admin boundary have been added to the existing 95% critical-class
JaCoCo gate. Overall 90% counters, original migration bytes, original checkout behavior and the frontend
content contract are preserved. No production class is excluded, and no gate or assertion was weakened.

The prior complete feature run passed 79 tests, 0 skipped, with 99.68% instructions, 99.01% branches,
100% methods and 99.51% lines. Later real HTTP QA found a P2 Origin error-envelope defect, fixed test-first;
the rebuilt real Kafka/PostgreSQL lab passed 30 HTTP probes. There are now 19 scenario rows and additional
concurrent claim/consumer tests. Final 12-cycle verification under four CPU workers is running; its results
will be appended to architecture and the program ledger. The first contention attempt failed the existing
redacted-log test because its runner retained DEBUG as an empty environment value; the corrected runner
removes DEBUG from child environments and preserves the failure evidence. It does not alter the caller.

Five guard mutations are caught by behavioral tests; the initial confirmation mutant's compilation
failure was corrected before counting that guard. Current source/history secret scans report no findings;
a synthetic github-pat control is detected. YAML, PowerShell helper syntax and 8 Mermaid diagrams parse.
Local helper startup/Enter shutdown was exercised. Hosted CI remains NOT RUN, since nothing is pushed.

**Release blocker:** OSV 2.7.1 scanned 146 resolved Maven package versions (including test tools), finding
94 package/advisory pairs across 25 versions. The earlier direct-POM scan was incomplete and superseded.
Affected dependency lines include Jackson, Spring, Tomcat, Kafka, PostgreSQL JDBC and test dependencies.
Findings are not suppressed, and this is not an exploitability assessment. The BE-02 prompt fixes the
Boot 3.5.6 BOM line; a separately reviewed patch/BOM and dependency maintenance increment is needed.
No public/production or REVIEW_READY claim follows from the passing functional checks.

See [scenario QA and defects](test-scenarios.md), [owner guide](guide.md), and the
[dated acceptance/experiment tables](architecture.md#be-02-dated-evidence-and-release-boundary).
Idle-machine comparative tuning, operated retention/backups, production identity/ACL, public hosting,
developer teach-backs and narrated demo remain open. Separate IT recovery work is not completed by BE-02.

**Final local rerun:** `node scripts/verify-under-load.mjs 12` passed in 518,232ms: 82 tests,
0 failures/errors/skips, Checkstyle and all coverage gates. JaCoCo instructions **99.6851%**, branches
**99.0385%**, methods **100%**, lines **99.5255%**. Twelve seeded crash cycles and the additional
four-client load profile finished with 82 orders/effects consistent and zero remaining lag/backlog/quarantine.
All task-owned processes stopped and four ports were free afterward. This supersedes the running status
above. Detailed source-safe results: [evidence/be02-local-2026-11-18.json](evidence/be02-local-2026-11-18.json).
The dependency gate still fails; status remains IN_PROGRESS, M5. The existing rules are unchanged.

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
