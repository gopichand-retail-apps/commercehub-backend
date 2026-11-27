import { Worker } from 'node:worker_threads'
import { spawn } from 'node:child_process'
import { openSync, closeSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Controlled CPU contention, not an idle-machine performance benchmark.
const cycles = Number(process.argv[2] ?? 3)
if (!Number.isInteger(cycles) || cycles < 3 || cycles > 30) throw new Error('Choose 3–30 crash cycles')
const workers = Array.from({ length: 4 }, () => new Worker(`
  const end=Date.now()+600000;
  let value=0;
  while(Date.now()<end) for(let i=0;i<100000;i++) value+=Math.sqrt(i);
`, { eval: true }))
const output = openSync('target/be02-under-load.log', 'w')
const maven = resolve('../../.tools/apache-maven-3.9.16/bin/mvn.cmd').replaceAll("'", "''")
const started = Date.now()
const environment = { ...process.env }
delete environment.DEBUG
try {
  const child = spawn('powershell.exe', ['-NoProfile', '-Command',
    `& '${maven}' -B -ntp -Pquality '-Dspring.profiles.active=test' '-Dbe02.soak.cycles=${cycles}' verify`], {
    stdio: ['ignore', output, output], windowsHide: true, env: environment,
  })
  const result = await new Promise((resolve, reject) => {
    child.once('error', reject)
    child.once('exit', code => resolve(code))
  })
  writeFileSync('target/be02-under-load-result.json', JSON.stringify({
    workers: 4, crashCycles: cycles, mavenPid: child.pid, elapsedMs: Date.now() - started, exitCode: result,
  }, null, 2) + '\n')
  process.stdout.write(`Full verification under four CPU workers exited ${result}\n`)
  process.exitCode = result ?? 1
} finally {
  await Promise.all(workers.map(worker => worker.terminate()))
  closeSync(output)
}
