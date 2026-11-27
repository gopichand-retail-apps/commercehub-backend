import { readFileSync, writeFileSync } from 'node:fs'

// Input comes from Maven's resolved dependency:list (includeScope=test), not unresolved POM declarations.
const lines = readFileSync('target/be02-dependencies.txt', 'utf8').split(/\r?\n/)
const components = new Map()
for (const line of lines) {
  const match = /^\s+([\w.-]+):([\w.-]+):(?:jar|pom):(?:[\w.-]+:)?([^:\s]+):(compile|runtime|test|provided)/.exec(line)
  if (!match) continue
  const [, group, name, version] = match
  const purl = `pkg:maven/${group}/${name}@${version}`
  components.set(purl, { type: 'library', group, name, version, purl })
}
if (components.size < 100) throw new Error('Unexpectedly incomplete resolved Maven inventory')
writeFileSync('target/be02-bom.cdx.json', JSON.stringify({
  bomFormat: 'CycloneDX', specVersion: '1.5', version: 1, components: [...components.values()],
}, null, 2) + '\n')
process.stdout.write(`${components.size} resolved Maven component versions, including test tools\n`)
