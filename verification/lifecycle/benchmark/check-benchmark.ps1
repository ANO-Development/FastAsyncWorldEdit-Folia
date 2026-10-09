param(
    [Parameter(Mandatory = $true)][string]$Log,
    [Parameter(Mandatory = $true)][int]$Cycles
)

$ErrorActionPreference = 'Stop'
$content = Get-Content -LiteralPath $Log
if (-not ($content -match 'BENCHMARK_PASS')) { throw 'Benchmark completion marker missing' }
if (@($content | Select-String 'CYCLE_PASS').Count -ne $Cycles) { throw 'Incorrect completed cycle count' }
if (@($content | Select-String 'AUDIT_PASS rows=316806 ').Count -ne $Cycles) { throw 'Missing exact paste audit counts' }
if (@($content | Select-String 'AUDIT_PASS rows=633612 ').Count -ne $Cycles) { throw 'Missing exact undo audit counts' }
if (@($content | Select-String 'chunks=240 ').Count -ne $Cycles) { throw 'Missing unique destination chunk counts' }
if ($content -match 'BENCHMARK_FAIL|Recovery capture is delayed|backlog exceeded|Chunk write failed|OutOfMemoryError|Cannot access world asynchronously') {
    throw 'Native correctness or recovery-pressure failure; preserve the log for investigation'
}
if (-not ($content -match 'Shutdown recovery: 0 captured records not yet crash-safe, 0 tracked durable records')) {
    throw 'Recovery shutdown did not verify zero unsafe captures and tracked records'
}
"ACCEPTED: $Cycles paste/undo cycles with exact block-audit counts and no recovery-pressure warning"
