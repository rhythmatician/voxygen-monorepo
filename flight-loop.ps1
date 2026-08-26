# Reusable Stage 2 flight-test loop. The template keeps the verification
# corridor virgin, while each run restores a disposable live world.
param(
    [string]$WorktreeJava = (Join-Path $PSScriptRoot "java"),
    [switch]$CaptureTemplate,
    [int]$TimeoutSeconds = 600,
    [int]$ReadinessTimeoutSeconds = 120,
    [int]$DwellTicks = 200,
    [switch]$LaunchProofOnly
)

$ErrorActionPreference = "Stop"

$runDir = Join-Path $WorktreeJava "run"
$savesDir = Join-Path $runDir "saves"
$liveWorld = Join-Path $savesDir "FlightTest"
$templateDir = Join-Path $WorktreeJava "flight-template"

function Get-ContaminatedEndRegions([string]$worldDir) {
    $regions = Join-Path $worldDir "DIM1\region"
    Get-ChildItem $regions -Filter *.mca -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^r\.(-?\d+)\.(-?\d+)\.mca$' -and
            ([int]$Matches[1] -notin -1, 0 -or [int]$Matches[2] -notin -1, 0)
        }
}

function Test-FlightStatusMarker(
    [string]$path,
    [string]$event,
    [string]$detail,
    [string]$runId
) {
    if (-not (Test-Path $path)) {
        return $false
    }
    foreach ($line in Get-Content $path -ErrorAction SilentlyContinue) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        try {
            $record = $line | ConvertFrom-Json
        } catch {
            # The writer may still be appending the newest line. The final parser remains strict.
            continue
        }
        if ($record.event -eq $event -and $record.detail -eq $detail -and
                $record.runId -eq $runId) {
            return $true
        }
    }
    return $false
}

function Get-ObservedReadyRunIds([string]$path) {
    if (-not (Test-Path $path)) {
        return @()
    }
    $ids = @()
    foreach ($line in Get-Content $path -ErrorAction SilentlyContinue) {
        try { $record = $line | ConvertFrom-Json } catch { continue }
        if ($record.event -eq "start" -and $record.detail -eq "ready") {
            $ids += if ($null -eq $record.runId -or $record.runId -eq "") {
                "<missing>"
            } else {
                [string]$record.runId
            }
        }
    }
    return @($ids | Select-Object -Unique)
}

function Stop-ExactProcessTree([Diagnostics.Process]$process) {
    if ($null -eq $process -or $process.HasExited) {
        return
    }
    & taskkill.exe /PID $process.Id /T /F *> $null
    try { $process.WaitForExit(10000) | Out-Null } catch { }
}

if ($CaptureTemplate) {
    if (-not (Test-Path $liveWorld)) {
        throw "No world at $liveWorld. Create FlightTest first (seed 0, creative, structures off), move the player to The End at 0 96 0, save, and quit without flying the corridor."
    }
    $flown = Get-ContaminatedEndRegions $liveWorld
    if ($flown) {
        throw "Template is contaminated: End corridor regions already exist: $(($flown.Name) -join ', '). Create a fresh FlightTest world."
    }
    if (Test-Path $templateDir) { Remove-Item $templateDir -Recurse -Force }
    Copy-Item $liveWorld $templateDir -Recurse
    Write-Host "Captured virgin flight template: $templateDir"
    exit 0
}

if (-not (Test-Path $templateDir)) {
    throw "No flight template at $templateDir. Create FlightTest once, then run .\flight-loop.ps1 -CaptureTemplate."
}
if (Test-Path $liveWorld) { Remove-Item $liveWorld -Recurse -Force }
Copy-Item $templateDir $liveWorld -Recurse

$voxyCache = Join-Path $runDir "voxy-cache"
if (Test-Path $voxyCache) { Remove-Item $voxyCache -Recurse -Force }

$flightStatus = Join-Path $runDir "flight-tour-status.jsonl"
$screenshotsDir = Join-Path $runDir "screenshots"
$expectedScreenshotNames = @()
for ($idx = 1; $idx -le 6; $idx++) {
    $expectedScreenshotNames += "tour-waypoint-$("{0:D2}" -f $idx)-before.png"
    $expectedScreenshotNames += "tour-waypoint-$("{0:D2}" -f $idx)-after.png"
}
$expectedScreenshotPaths = @()
foreach ($name in $expectedScreenshotNames) {
    $expectedScreenshotPaths += Join-Path $screenshotsDir $name
    $expectedScreenshotPaths += Join-Path $runDir $name
}
if (Test-Path $flightStatus) { Remove-Item $flightStatus -Force }
foreach ($path in $expectedScreenshotPaths) {
    if (Test-Path $path) { Remove-Item $path -Force }
}

$stale = Get-ContaminatedEndRegions $liveWorld
if ($stale) {
    throw "Post-reset check failed: End corridor regions present: $(($stale.Name) -join ', ')."
}

$runId = [Guid]::NewGuid().ToString("N")

Write-Host "Starting AFK tour run: runId=$runId world=FlightTest auto-start enabled (no manual command)"
Write-Host "AFK preflight: autoStart=true timeout=24000 dwell=$DwellTicks ticks (Gradle JVM properties)"
Push-Location $WorktreeJava
try {
    $arguments = @(
        "--no-daemon",
        "clean",
        "runClient",
        "--console=plain",
        "-PflightWorld=FlightTest",
        "-PflightTourAutoStart=true",
        "-PflightTourTimeoutTicks=24000",
        "-PflightTourDwellTicks=$DwellTicks",
        "-PflightTourRunId=$runId"
    )
    $process = Start-Process -FilePath .\gradlew.bat -ArgumentList $arguments -WorkingDirectory $WorktreeJava -NoNewWindow -PassThru
    $elapsed = [Diagnostics.Stopwatch]::StartNew()
    $readyDeadline = [DateTime]::UtcNow.AddSeconds($ReadinessTimeoutSeconds)
    $hasStarted = $false
    while (-not $process.HasExited -and [DateTime]::UtcNow -lt $readyDeadline) {
        if (Test-FlightStatusMarker $flightStatus "start" "ready" $runId) {
            $hasStarted = $true
            break
        }
        Start-Sleep -Milliseconds 250
        $process.Refresh()
    }
    if (-not $hasStarted) {
        $hasStarted = Test-FlightStatusMarker $flightStatus "start" "ready" $runId
    }
    if (-not $hasStarted) {
        if (-not $process.HasExited) {
            Stop-ExactProcessTree $process
        }
        $observed = @(Get-ObservedReadyRunIds $flightStatus)
        $observedText = if ($observed.Count -eq 0) { "<none>" } else { $observed -join ", " }
        throw "AFK tour readiness mismatch/timeout after ${ReadinessTimeoutSeconds}s. Expected runId=$runId; observed runIds=$observedText. Exact process tree was stopped."
    }
    if ($LaunchProofOnly) {
        Stop-ExactProcessTree $process
        Write-Host "AFK launch proof matched runId=$runId; exact process tree stopped after readiness."
        return
    }

    $remainingMilliseconds = [Math]::Max(0, ($TimeoutSeconds * 1000) - [int]$elapsed.ElapsedMilliseconds)
    if ($remainingMilliseconds -eq 0 -or -not $process.WaitForExit($remainingMilliseconds)) {
        Stop-ExactProcessTree $process
        throw "Flight loop timeout (${TimeoutSeconds}s): process did not exit. Exact process tree was killed. runId=$runId"
    }
    if ($process.ExitCode -ne 0) {
        throw "runClient failed with exit code $($process.ExitCode)."
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $flightStatus)) {
    throw "AFK tour status log not found: $flightStatus"
}

$hasCompleted = $false
$hasTimedOut = $false
Get-Content $flightStatus | ForEach-Object {
    if ([string]::IsNullOrWhiteSpace($_)) {
        return
    }
    try {
        $record = $_ | ConvertFrom-Json
    } catch {
        throw "Failed to parse AFK tour status line as JSON: $($_)"
    }

    if ($record.runId -ne $runId) {
        return
    }
    if ($record.event -eq 'complete' -and $record.detail -eq 'all_waypoints') {
        $hasCompleted = $true
    }
    if ($record.event -eq 'failed' -and $record.detail -eq 'timeout') {
        $hasTimedOut = $true
    }
}

if (-not $hasCompleted) {
    throw "AFK tour did not emit completion marker: complete/all_waypoints not found in $flightStatus."
}
if ($hasTimedOut) {
    throw "AFK tour completed with timeout."
}

function Find-ExistingScreenshot([string]$name) {
    $run = Join-Path $runDir $name
    $screenshots = Join-Path $screenshotsDir $name
    return (Test-Path $run) -or (Test-Path $screenshots)
}

$missing = @()
foreach ($name in $expectedScreenshotNames) {
    if (-not (Find-ExistingScreenshot $name)) {
        $missing += $name
    }
}
if ($missing.Count -gt 0) {
    throw "Missing expected screenshots: $($missing -join ', ')"
}

Write-Host "AFK tour completed successfully. Saved screenshots:"
foreach ($name in $expectedScreenshotNames) {
    Write-Host "  - $name"
}

$verdictHelper = Join-Path $PSScriptRoot "flight-screenshot-verdict.ps1"
$verdictPath = Join-Path $runDir "flight-tour-verdict.json"
& $verdictHelper -ScreenshotDirectory $screenshotsDir -OutputPath $verdictPath
if ($LASTEXITCODE -ne 0) {
    throw "Screenshot verdict helper failed with exit code $LASTEXITCODE."
}
Write-Host "Advisory screenshot verdict: $verdictPath"
