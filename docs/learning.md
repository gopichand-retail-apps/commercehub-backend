# CommerceHub BE-02 learning notes

These notes explain the mechanism; every developer teach-back is **PENDING** until the developer answers.

## Follow one order

`CommerceService` uses its existing `TransactionTemplate`. After writing stock, order and line snapshots,
`OrderEventWriter` inserts a compact `ORDER_PLACED` event before that transaction returns. If any write
fails, neither order nor event commits. A unique `(aggregate_id, aggregate_version)` also constrains duplicates.
There is no database/broker distributed transaction and no direct network call inside checkout.

`OutboxStore` uses a short transaction to lock a due row with `SKIP LOCKED` and give it a random lease.
`OutboxPublisher` sends outside that transaction. Only a broker confirmation marks it published. If the
app dies after Kafka accepted but before that mark, the lease expires and the event is sent again.
The previous lease owner cannot overwrite a newer owner's result. This is why consumers still need inboxes
even when the producer uses Kafka's idempotence and `acks=all`.

Each consumer locks its local group state, validates the bounded schema/key, and writes its inbox plus
projection/notification together. Kafka acknowledgment comes after that service call committed. Database
failure causes redelivery, while malformed records commit safe quarantine metadata before acknowledgment.
The group-state lock deliberately serializes this small local lab. It trades throughput for a clear,
tested concurrency boundary; independent scaling needs measured partition-aware coordination first.

## Kafka and RabbitMQ

A Kafka broker keeps ordered partition logs. Consumers track positions (offsets); independent groups
can read the same history at their own pace. Partitioning by order ID preserves per-order broker order,
but source version checks still detect stale/gapped or manually reordered records.
RabbitMQ is a good fit when workers compete for individual jobs and acknowledgment removes completed
work from that queue. Kafka is chosen here for replay and independent reporting/notification readers.
No connection to DispatchForge or another retail app is made.

## Study the failure

The first real harness started Kafka successfully but could not create its Admin client. Its default API
timeout was shorter than Kafka's request timeout. The readiness check correctly failed. The harness now
sets compatible bounded timeouts and records the failure; this was a harness configuration correction.

The next run killed the app after an order/outbox commit with workers disabled. After restart, the event
was published and both consumers caught up. Five more orders committed while Kafka was down and were
delivered after restart. A repeated record increased duplicate counters without adding business effects.
Three seeded process-fault cycles retained all 49 committed orders and the stock invariant. This is a
short crash rehearsal, not a prolonged soak, multi-broker failover or production capacity result.

The measured first run had 12 sequential orders per sample after three warm-ups, repeated three times:
p50 publication latency 192.8–225.8ms; p95 482.5–504.8ms, with a fixed 500ms poll/25-row batch.
These figures describe one Windows machine and workload. They do not establish an idle-machine,
independently tuned/held-out workload or hosted SLO.
The measured clock starts at the database event timestamp (transaction start) and ends at the confirmed
publication mark. It includes checkout time; exact commit-to-broker-ACK instrumentation is still open.

## Teach-back questions — all PENDING

1. Why can a database commit followed by a direct Kafka send lose an event? Show the transaction boundary.
2. What happens if the app dies after Kafka accepted a send but before `published_at_utc` is saved?
3. Why are Kafka producer idempotence and committed offsets insufficient for idempotent database effects?
4. Why does each independent consumer need its own inbox key and group offset?
5. What does `SKIP LOCKED` do, and why must the broker call happen after releasing that transaction?
6. What would a stale lease confirmation do without `WHERE claim_id=?`?
7. Why use order ID as the partition key and still verify aggregate versions?
8. Why quarantine unknown schemas instead of silently skipping them? What data is safe to retain?
9. Why is replay authorized, audited, rate-limited and based on the trusted original event?
10. What would be lost or changed by replacing this event log with a competing-worker queue?
11. Which measurements and security/release gates are still missing? Why is this not production ready?
