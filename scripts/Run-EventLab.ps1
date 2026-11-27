param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent (Split-Path -Parent $repo)
$node = Join-Path $workspace '.tools/node-v24.15.0-win-x64/node.exe'
$maven = Join-Path $workspace '.tools/apache-maven-3.9.16/bin/mvn.cmd'
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to the documented JDK 17 first.' }
& $node (Join-Path $workspace 'scripts/check-ports.mjs') 17302 17432 17992 17993
if ($LASTEXITCODE -ne 0) { throw 'Event-lab ports occupied. No listener was stopped.' }
Push-Location $repo
try {
  & $maven -B -ntp test-compile dependency:build-classpath '-Dmdep.outputFile=target/event-lab-classpath.txt'
  if ($LASTEXITCODE -ne 0) { throw 'Event-lab compilation/classpath failed.' }
  $classpath = (Join-Path $repo 'target/test-classes') + ';' + (Join-Path $repo 'target/classes') + ';' +
    (Get-Content -LiteralPath 'target/event-lab-classpath.txt' -Raw).Trim()
  $argumentFile = Join-Path $repo 'target/event-lab-java-args.txt'
  $lines = @('-Xmx512m', '-cp', ('"' + $classpath.Replace('\', '/') + '"'),
    'com.commercehub.events.LocalEventLab')
  [IO.File]::WriteAllLines($argumentFile, $lines, [Text.UTF8Encoding]::new($false))
  $previousDebug = $env:DEBUG
  try {
    $env:DEBUG = $null
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') ('@' + $argumentFile)
    if ($LASTEXITCODE -ne 0) { throw 'Event lab failed. Inspect its task-owned target/be02-process directory.' }
  } finally { $env:DEBUG = $previousDebug }
} finally { Pop-Location }
