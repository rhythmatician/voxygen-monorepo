# Reconstruct tools/server-harness/runtime/ from pinned manifests (PowerShell)
$ErrorActionPreference = "Stop"
$Harness = Join-Path $PSScriptRoot ".."
$Runtime = Join-Path $Harness "runtime"
$ManifestMods = Join-Path $Harness "mods.manifest.json"
$ManifestServer = Join-Path $Harness "server.manifest.json"

New-Item -ItemType Directory -Force -Path (Join-Path $Runtime "mods"), (Join-Path $Runtime "versions/1.21.11"), (Join-Path $Runtime "config") | Out-Null
Write-Host "[server-harness] Copying tracked config -> runtime/config/"
Copy-Item -Recurse -Force (Join-Path $Harness "config/*") (Join-Path $Runtime "config")

function Verify-Hash($file, $expected) {
    $actual = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $expected.ToLower()) { throw "SHA256 mismatch for $file : expected $expected, got $actual" }
    Write-Host "[server-harness] verified $file"
}

function Fetch-And-Verify($url, $dest, $sha) {
    if (Test-Path $dest) { Verify-Hash $dest $sha; return }
    Write-Host "[server-harness] downloading $url -> $dest"
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    try { Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing } catch {
        Write-Warning "[server-harness] download failed for $url : $_ (offline? manual fetch required)"
        Write-Warning "[server-harness] expected sha256 $sha for $dest"
        if (Test-Path $dest) { Remove-Item $dest -Force }
        return
    }
    Verify-Hash $dest $sha
}

$server = Get-Content $ManifestServer -Raw | ConvertFrom-Json
$mods = Get-Content $ManifestMods -Raw | ConvertFrom-Json

$launcherFile = $server.launcherJar.file
$launcherSha = $server.launcherJar.sha256
$launcherPath = Join-Path $Harness $launcherFile
if (-not (Test-Path $launcherPath)) {
    Write-Warning "[server-harness] launcher jar not found at $launcherPath"
    Write-Warning "[server-harness] Generate via: java -jar fabric-installer.jar server -mcversion 1.21.11 -loader 0.18.4 -downloadMinecraft"
} else { Verify-Hash $launcherPath $launcherSha }

$vanillaUrl = $server.vanillaServerJar.url
$vanillaSha = $server.vanillaServerJar.sha256
$vanillaDest = Join-Path $Runtime "versions/1.21.11/server-1.21.11.jar"
Fetch-And-Verify $vanillaUrl $vanillaDest $vanillaSha

foreach ($m in $mods.mods) {
    $dest = Join-Path $Runtime "mods/$($m.file)"
    Fetch-And-Verify $m.url $dest $m.sha256
}

Write-Host "[server-harness] runtime reconstructed at $Runtime (built jars still need local gradle builds)"
Write-Host "[server-harness] built artifacts (if needed):"
Write-Host "  ./tools/data-harvester/gradlew -p tools/data-harvester build  -> $Runtime/mods/"
Write-Host "  ./java/gradlew -p java build  -> $Runtime/mods/"
