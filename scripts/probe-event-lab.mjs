import { writeFileSync } from 'node:fs'

const origin = 'http://127.0.0.1:17302'
const checks = []
async function probe(name, path, expected, init = {}) {
  const result = await fetch(origin + path, { ...init, signal: AbortSignal.timeout(12000) })
  const body = await result.json()
  checks.push({ name, expected, actual: result.status, passed: result.status === expected })
  if (result.status !== expected) throw new Error(`${name}: HTTP ${result.status}, expected ${expected}`)
  if (JSON.stringify(body).includes('stackTrace')) throw new Error('Unsafe response')
  return body
}
async function session(role) {
  const result = await probe(`${role} synthetic session`, '/api/v1/sessions', 201, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role }),
  })
  return { Authorization: `Bearer ${result.session.token}` }
}
const operator = await session('SUPPORT')
for (const role of ['CUSTOMER', 'MERCHANT']) {
  const headers = await session(role)
  await probe(`${role} inspection denied`, '/api/v1/admin/outbox', 403, { headers })
  await probe(`${role} replay denied`, `/api/v1/admin/event-pipeline/retry/${crypto.randomUUID()}`, 403, {
    method: 'POST', headers: { ...headers, Origin: origin, 'Idempotency-Key': crypto.randomUUID() },
  })
}
await probe('anonymous inspection denied', '/api/v1/admin/outbox', 401)
await probe('anonymous health denied', '/api/v1/admin/event-pipeline/health', 401)
await probe('Support status', '/api/v1/admin/event-pipeline/health', 200, { headers: operator })
for (const query of ['state=sql%27', 'page=bad', 'page=-1', 'page=1001', 'size=0', 'size=101']) {
  await probe(`invalid ${query}`, `/api/v1/admin/outbox?${query}`, 400, { headers: operator })
}
const catalogue = await probe('actual catalog', '/api/v1/products', 200)
const coffee = catalogue.products.find(product => product.slug === 'daybreak-coffee')
const key = crypto.randomUUID()
const headers = { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-Order-Access-Key': 'x'.repeat(43) }
const body = JSON.stringify({ items: [{ productId: coffee.id, quantity: 1 }] })
const order = await probe('actual order commit', '/api/v1/orders', 201, { method: 'POST', headers, body })
await probe('actual checkout replay', '/api/v1/orders', 200, { method: 'POST', headers, body })
await probe('owned receipt', `/api/v1/orders/${order.order.orderId}`, 200, { headers })
const deadline = Date.now() + 15000
let published
do {
  published = await probe('published metadata', '/api/v1/admin/outbox?state=published', 200, { headers: operator })
  if (published.events.length) break
  if (Date.now() > deadline) throw new Error('Publication deadline exceeded')
  await new Promise(resolve => setTimeout(resolve, 100))
} while (true)
const path = `/api/v1/admin/event-pipeline/retry/${published.events[0].eventId}`
await probe('missing Origin denied', path, 403, { method: 'POST', headers: operator })
await probe('wrong Origin denied', path, 403, { method: 'POST', headers: { ...operator, Origin: 'https://evil.test' } })
const replay = { ...operator, Origin: origin, 'Idempotency-Key': crypto.randomUUID() }
await probe('audited replay', path, 200, { method: 'POST', headers: replay })
const repeated = await probe('idempotent replay', path, 200, { method: 'POST', headers: replay })
if (!repeated.duplicate) throw new Error('Replay was not idempotent')
await probe('malformed UUID', '/api/v1/admin/event-pipeline/retry/bad', 400, { method: 'POST', headers: replay })
await probe('forged bearer denied', '/api/v1/admin/outbox', 401, { headers: { Authorization: 'Bearer forged' } })
writeFileSync('target/be02-live-qa.json', JSON.stringify({ observedAt: new Date().toISOString(), checks }, null, 2) + '\n')
process.stdout.write(`${checks.length} live HTTP checks passed against the actual loopback lab\n`)
