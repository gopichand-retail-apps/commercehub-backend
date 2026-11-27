import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'

const root = process.cwd()
const maven = resolve(root, '../../.tools/apache-maven-3.9.16/bin/mvn.cmd')
const output = resolve(root, 'target/be02-mutations')
mkdirSync(output, { recursive: true })
const java = 'src/main/java/com/commercehub/'
const mutations = [
  ['atomic-outbox', 'order/CommerceService.java', 'events.append(receipt);', '',
    'OutboxWorkflowTest#commitsOneEvent'],
  ['consumer-inbox', 'events/EventConsumer.java', 'if (!seen.isEmpty()) {', 'if (false) {',
    'OutboxWorkflowTest#dedupe'],
  ['projection-version', 'events/EventConsumer.java', 'if (event.aggregateVersion() != previous + 1) return false;', '',
    'OutboxWorkflowTest#ordering'],
  ['broker-confirmation', 'events/OutboxPublisher.java',
    'kafka.send(OrderEvent.TOPIC, claim.aggregateId().toString(), claim.payload()).get(5, TimeUnit.SECONDS);',
    'java.util.concurrent.CompletableFuture.completedFuture(null).get(5, TimeUnit.SECONDS);',
    'PublisherTest#sendOutcomes'],
  ['operator-role', 'events/PipelineController.java', 'if (actor.role() != SyntheticRole.SUPPORT) throw denied();', '',
    'PipelineAdminTest#roles'],
]
const results = []
if (process.argv[2] && !mutations.some(([name]) => name === process.argv[2])) {
  throw new Error('Choose a known guard name')
}
const environment = { ...process.env }
delete environment.DEBUG
for (const [name, relative, original, replacement, test] of mutations) {
  if (process.argv[2] && process.argv[2] !== name) continue
  const path = resolve(root, java + relative)
  const bytes = readFileSync(path)
  const hash = createHash('sha256').update(bytes).digest('hex')
  const text = bytes.toString('utf8')
  if (!text.includes(original)) throw new Error(`Guard not found: ${name}`)
  try {
    writeFileSync(path, text.replace(original, replacement))
    const run = spawnSync('powershell.exe', ['-NoProfile', '-Command',
      `& '${maven.replaceAll("'", "''")}' -B -ntp '-Dtest=${test}' test`], {
      cwd: root, encoding: 'utf8', timeout: 120000, windowsHide: true,
      env: environment, maxBuffer: 8 * 1024 * 1024,
    })
    writeFileSync(resolve(output, `${name}.log`), `${run.stdout ?? ''}\n${run.stderr ?? ''}`)
    const caught = run.status !== 0 && /Failures: [1-9]|Errors: [1-9]/.test(run.stdout ?? '')
    results.push({ name, caught, exitCode: run.status })
  } finally {
    writeFileSync(path, bytes)
    if (createHash('sha256').update(readFileSync(path)).digest('hex') !== hash) throw new Error('Restore mismatch')
  }
}
const report = process.argv[2] ? `results-${process.argv[2]}.json` : 'results.json'
writeFileSync(resolve(output, report), JSON.stringify(results, null, 2) + '\n')
process.stdout.write(JSON.stringify(results, null, 2) + '\n')
if (results.some(result => !result.caught)) process.exitCode = 1
