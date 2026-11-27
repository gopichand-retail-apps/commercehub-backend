# CommerceHub order-event lab: understand, run and test

## What this adds

A synthetic checkout still confirms an order and reserves stock. With events enabled, the same database
transaction also writes a durable message to an **outbox**. The publisher sends it to Kafka later.
Two independent consumers keep a reporting copy and a simulated notification log. No email is sent.
This feature is off by default; the ordinary H2 storefront works without Kafka.

The published baseline uses selectable synthetic roles. Its separate IT cousin has Supabase Auth;
BE-02 neither changes that copy nor connects to its hosted database. Do not use real customer data.

## Ideas needed for the tour

- A **transaction** commits every local change together or rolls all of them back.
- The **outbox** is a durable to-send list. Crashing after checkout cannot erase the committed event.
- A Kafka **partition** is an ordered log. An order ID always chooses the same partition.
- An **offset** is a position in that log. Reporting and notification have independent consumer groups.
- Each consumer's **inbox** remembers event IDs in the same transaction as its effect. A redelivery has
  no second effect. Kafka delivery can repeat; the business effect is idempotent.
- A **quarantine** records a safe reason/location/hash for an unusable record. It stores no raw poison body.

## Run locally on Windows

Use PowerShell from this backend checkout. The workspace already supplies Maven and Node; set `JAVA_HOME`
to the installed JDK 17. The commands below are specific to the Retail Apps workspace layout.
No Docker, WSL, administrator install, cloud credentials or paid service is needed.

```powershell
$env:JAVA_HOME='C:\Users\chand\Documents\Codex\tools\jdk\jdk-17.0.19+10'
$maven='..\..\.tools\apache-maven-3.9.16\bin\mvn.cmd'
$node='..\..\.tools\node-v24.15.0-win-x64\node.exe'
& $node '..\..\scripts\check-ports.mjs' 17302 17432 17992 17993
```

An occupied port means stop here; it never authorizes killing that listener.

For the normal application, leave `COMMERCEHUB_EVENTS_ENABLED` unset/false and use the README's existing
startup. That default uses its existing `.local/commercehub` H2 database; preserve it. For a disposable
Kafka/PostgreSQL lab, use:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Run-EventLab.ps1
```

The script compiles the test-only runner, resolves the test classpath, probes ports, and starts a real
PostgreSQL 17.11 engine, a real Kafka 3.9.1 KRaft broker, and the compiled application as child processes.
Maven may download dependencies. Each run creates a unique `target/be02-process/<run-id>` directory.
Postgres: 17432; Kafka client/controller: 17992/17993; API: 17302, all loopback. Wait for the explicit ready
message. Press Enter in that terminal to stop the lab. Never kill arbitrary Java/Postgres processes.
The runner records owned Java PIDs; native PostgreSQL lifecycle is owned by the embedded-postgres library.

The runner is not packaged into the app jar. Its PostgreSQL administrator has access only to this new
disposable loopback cluster; it is not a hosted credential or a production least-privilege demonstration.
Kafka single-replica plaintext is deliberately local. Windows broker restart/file-lock behavior is tested
only on this machine; native Windows Kafka is not an officially supported production target.

## Guided tour (API; no new frontend page)

In another PowerShell terminal:

```powershell
$api='http://127.0.0.1:17302'
$support=Invoke-RestMethod "$api/api/v1/sessions" -Method Post -ContentType 'application/json' -Body '{"role":"SUPPORT"}'
$headers=@{Authorization=('Bearer '+$support.session.token)}
Invoke-RestMethod "$api/api/v1/admin/outbox?state=pending&size=25" -Headers $headers
Invoke-RestMethod "$api/api/v1/admin/event-pipeline/health" -Headers $headers
```

Do not copy or log the returned synthetic token. Role selection is a local fixture, not real identity.
For `OUT-01`, create a synthetic guest order using a random 43-character access key and request key:

```powershell
$catalog=Invoke-RestMethod "$api/api/v1/products"
$coffee=$catalog.products | Where-Object slug -eq 'daybreak-coffee'
$body=@{items=@(@{productId=$coffee.id;quantity=1})} | ConvertTo-Json -Depth 4
$key=[guid]::NewGuid().ToString()
$orderHeaders=@{'Idempotency-Key'=$key;'X-Order-Access-Key'=('x'*43)}
$order=Invoke-RestMethod "$api/api/v1/orders" -Method Post -ContentType 'application/json' -Headers $orderHeaders -Body $body
```

Repeat exactly that POST to see `duplicate=true` with the same order. No second stock reservation or
outbox event is created. A changed body with the same key conflicts (`CMP-01`).
Inspect `state=published`. The event contains identifiers, attempts and timestamps, without payload.
Health distinguishes database backlog/quarantine from broker readiness and each group's lag. Empty/null
timestamps mean no observation yet. An unavailable broker gives an error and no invented zero-lag map (`OBS-01`).
There is no aggregate "production healthy" badge.

For `POI-02`, choose a published event ID from that page and request a deliberate duplicate delivery:

```powershell
$event=(Invoke-RestMethod "$api/api/v1/admin/outbox?state=published&size=1" -Headers $headers).events[0].eventId
$retryHeaders=@{Authorization=$headers.Authorization;Origin=$api;'Idempotency-Key'=[guid]::NewGuid().ToString()}
Invoke-RestMethod "$api/api/v1/admin/event-pipeline/retry/$event" -Method Post -Headers $retryHeaders
```

Repeat with the same replay key: `duplicate=true`, with no rescheduling. Consumer duplicate counters
increase after delivery but projections/notifications do not duplicate. An active lease conflicts.
Only five replay requests per synthetic session per minute are allowed. Customer/merchant/anonymous,
wrong Host, non-loopback peer and missing/wrong Origin cannot perform this operation (`ADM-01/02`).
Poison cannot be edited through this API. A known event can be replayed only from its validated original
outbox row; unknown/corrupt sources require operator investigation. Unresolved quarantine reasons and
record positions are exposed as a bounded metadata list in health. The recovery suite injects real poison
on Kafka, observes quarantine and verifies safe replay without asking you to damage your normal data.

## Run tests

Testing starts with the [scenario catalogue](test-scenarios.md), then checks coverage.

```powershell
& $node '..\..\scripts\check-ports.mjs' 17302 17432 17992 17993
& $maven -B -ntp -Pquality '-Dspring.profiles.active=test' verify
& $maven -B -ntp '-Dtest=OutboxWorkflowTest#rollback' test
& $maven -B -ntp '-Dtest=ProcessPipelineTest' test
& $node scripts\check-guards.mjs
```

Full verification includes isolated H2 and PostgreSQL scenarios plus actual app/broker child processes,
database-commit crash recovery, broker outage/catch-up, duplicate/poison records, and three seeded fault
cycles. The process harness records HTTP checks, PIDs, latency samples and invariants under `target`.
The guard script temporarily breaks five precise controls, requires a test failure and restores exact bytes.
Run it alone in an idle checkout; do not edit concurrently. Follow it with full verification so compiled
mutant classes cannot be mistaken for a final artifact. Java instructions, branches, methods and lines
are reported separately; these are not JavaScript statement/function metrics.

## Troubleshooting and manual checklist

- Refused startup: check the port probe and task-owned records. Never stop somebody else's listener.
- Broker unavailable: inspect the owned `process-*.log`, then backlog/attempts. Ten attempts exhaust
  automatic delivery; retry needs the local Support boundary. A broker timeout can still mean delivery.
- After a hard app kill: Kafka can wait for the dead group member's session timeout before reassignment.
  A bounded wait is part of the recovery check, not evidence of lost data. No timeouts are hidden.
- Poison remains quarantined: inspect reason/offset. A future version may need missing earlier versions;
  schema changes need reviewed code. Do not discard records or mark effects complete manually.
- File locks on Windows: stop only the owned lab before inspecting its files. Keep unique evidence
  directories; do not overwrite another run or change retention to conceal a failure.
- Full recovery suite takes several minutes and is intentionally more than a mocked broker test.
- Read the fresh dependency findings before release. Boot 3.5.6's fixed dependency line has known
  advisories; no "zero vulnerabilities" or public readiness claim applies.

Owner checks still open: read [learning.md](learning.md), try this tour, inspect the dated evidence in
[architecture.md](architecture.md), and record your own teach-back. Narrated demo is not produced.
There are no new screens to screenshot or certify; baseline storefront browser issues remain separate.
