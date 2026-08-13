<#
.SYNOPSIS
    Automates symbolic link creation for external reference repositories in VoxyGen.
.DESCRIPTION
    Checks for local reference code paths and links them to ./external/.
#>

$ErrorActionPreference = "Stop"

# Monorepo root check (java/ and python/ are the two subprojects)
if (-not (Test-Path ".\java\build.gradle") -or -not (Test-Path ".\python\pyproject.toml")) {
    Write-Warning "Please run this script from the root of the voxygen-monorepo directory."
    exit 1
}

# Define your default reference directory path
$ReferenceBase = "C:\Users\$env:USERNAME\git\MC\reference-code"

if (-not (Test-Path $ReferenceBase)) {
    Write-Warning "Reference code folder not found at: $ReferenceBase"
    $ReferenceBase = Read-Host "Enter the absolute path to your local reference-code directory"
}

# Ensure ./external exists
New-Item -ItemType Directory -Path ".\external" -Force | Out-Null

$Links = @{
    "voxy"          = "voxy"
    "minecraft-src" = "26.1-snapshot-11"
    "fabric-api"    = "fabric-api"
    "ogn"           = "ogn"
}

Write-Host "Setting up symbolic links in ./external..." -ForegroundColor Cyan

foreach ($Link in $Links.GetEnumerator()) {
    $TargetPath = Join-Path $ReferenceBase $Link.Value
    $LinkPath   = Join-Path ".\external" $Link.Key

    if (Test-Path $TargetPath) {
        if (Test-Path $LinkPath) {
            Write-Host "  [EXISTS] $LinkPath already exists. Skipping." -ForegroundColor Yellow
        } else {
            New-Item -ItemType SymbolicLink -Path $LinkPath -Target $TargetPath | Out-Null
            Write-Host "  [LINKED] $LinkPath -> $TargetPath" -ForegroundColor Green
        }
    } else {
        Write-Host "  [MISSING] Target not found: $TargetPath. Skipping $Link.Key." -ForegroundColor DarkGray
    }
}

Write-Host "`nExternal setup complete!" -ForegroundColor Cyan