# Root delegation wrapper for dev/flight harness.
# Thin navigation helper — implementation lives in dev/flight/.
param(
    [string]$Scenario = "end-tour",
    [string]$ScenarioFile = "",
    [switch]$CaptureTemplate,
    [int]$TimeoutSeconds = 600,
    [int]$ReadinessTimeoutSeconds = 120,
    [int]$DwellTicks = 0,
    [int]$WaypointCount = 0,
    [switch]$DisableRefinementAdmission,
    [switch]$LaunchProofOnly
)
$ErrorActionPreference = "Stop"
$flightScript = Join-Path $PSScriptRoot "dev/flight/flight.ps1"
if (-not (Test-Path $flightScript)) { throw "Flight harness not found: $flightScript" }
$args = @()
if ($Scenario) { $args += @("-Scenario", $Scenario) }
if ($ScenarioFile) { $args += @("-ScenarioFile", $ScenarioFile) }
if ($CaptureTemplate) { $args += "-CaptureTemplate" }
if ($TimeoutSeconds -ne 600) { $args += @("-TimeoutSeconds", $TimeoutSeconds) }
if ($ReadinessTimeoutSeconds -ne 120) { $args += @("-ReadinessTimeoutSeconds", $ReadinessTimeoutSeconds) }
if ($DwellTicks -ne 0) { $args += @("-DwellTicks", $DwellTicks) }
if ($WaypointCount -ne 0) { $args += @("-WaypointCount", $WaypointCount) }
if ($DisableRefinementAdmission) { $args += "-DisableRefinementAdmission" }
if ($LaunchProofOnly) { $args += "-LaunchProofOnly" }
& $flightScript @args
exit $LASTEXITCODE
