param(
    [string]$ScreenshotDirectory = (Join-Path $PSScriptRoot "java\run\screenshots"),
    [string]$OutputPath = (Join-Path $PSScriptRoot "java\run\flight-tour-verdict.json"),
    [int]$MaxImages = 0,
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

function Get-Hsv([System.Drawing.Color]$Color) {
    $r = $Color.R / 255.0
    $g = $Color.G / 255.0
    $b = $Color.B / 255.0
    $maximum = [Math]::Max($r, [Math]::Max($g, $b))
    $minimum = [Math]::Min($r, [Math]::Min($g, $b))
    $delta = $maximum - $minimum
    $hue = 0.0
    if ($delta -gt 0.0001) {
        if ($maximum -eq $r) { $hue = 60.0 * ((($g - $b) / $delta) % 6.0) }
        elseif ($maximum -eq $g) { $hue = 60.0 * ((($b - $r) / $delta) + 2.0) }
        else { $hue = 60.0 * ((($r - $g) / $delta) + 4.0) }
        if ($hue -lt 0) { $hue += 360.0 }
    }
    [pscustomobject]@{
        Hue = $hue
        Saturation = if ($maximum -eq 0) { 0.0 } else { $delta / $maximum }
        Value = $maximum
    }
}

function Get-OverlayClass([System.Drawing.Color]$Color) {
    $hsv = Get-Hsv $Color
    # The overlay is translucent, so these deliberately broad hue bands use
    # saturation/value floors rather than matching its exact source RGB values.
    if ($hsv.Saturation -lt 0.22 -or $hsv.Value -lt 0.25) { return $null }
    $hue = $hsv.Hue
    if ($hue -le 18 -or $hue -ge 340) { return "L0_red" }
    if ($hue -le 45) { return "L1_orange" }
    if ($hue -le 72) { return "L2_yellow" }
    if ($hue -ge 165 -and $hue -le 205) { return "L3_cyan" }
    if ($hue -ge 260 -and $hue -le 320) { return "L4_violet" }
    return $null
}

function Test-ExcludedUiPixel([int]$x, [int]$y, [int]$width, [int]$height) {
    # Fixed Voxy legend, chat/status text near the horizon, and hotbar are UI,
    # not world overlay evidence.
    if ($x -lt [Math]::Min(384, $width) -and $y -lt [Math]::Min(152, $height)) { return $true }
    if ($x -lt [Math]::Min(520, $width) -and $y -gt ($height * 0.26) -and $y -lt ($height * 0.68)) { return $true }
    return $y -gt ($height * 0.86)
}

function Get-ImageEvidence([System.Drawing.Bitmap]$Bitmap, [bool]$ExcludeUi = $true) {
    $counts = [ordered]@{ L0_red = 0; L1_orange = 0; L2_yellow = 0; L3_cyan = 0; L4_violet = 0 }
    $analyzed = 0
    $voidPixels = 0
    $voidSamples = 0
    $width = $Bitmap.Width
    $height = $Bitmap.Height
    # Four-pixel sampling keeps the full twelve-image AFK report bounded while
    # retaining enough pixels from the broad translucent terrain overlay.
    $rectangle = [System.Drawing.Rectangle]::new(0, 0, $width, $height)
    $locked = $Bitmap.LockBits($rectangle, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $bytes = [byte[]]::new([Math]::Abs($locked.Stride) * $height)
        [Runtime.InteropServices.Marshal]::Copy($locked.Scan0, $bytes, 0, $bytes.Length)
        for ($y = 0; $y -lt $height; $y += 4) {
            for ($x = 0; $x -lt $width; $x += 4) {
                if ($ExcludeUi -and (Test-ExcludedUiPixel $x $y $width $height)) { continue }
                $offset = ($y * $locked.Stride) + ($x * 4)
                $blue = $bytes[$offset]
                $green = $bytes[$offset + 1]
                $red = $bytes[$offset + 2]
                $analyzed++

                # Inline broad-HSV classifier: LockBits avoids the otherwise very
                # costly Bitmap.GetPixel call for every sampled screenshot pixel.
                $maximum = [Math]::Max($red, [Math]::Max($green, $blue))
                $minimum = [Math]::Min($red, [Math]::Min($green, $blue))
                $delta = $maximum - $minimum
                if ($maximum -ge 64 -and $delta -ge ($maximum * 0.22)) {
                    if ($delta -eq 0) { $hue = 0.0 }
                    elseif ($maximum -eq $red) { $hue = 60.0 * ((($green - $blue) / $delta) % 6.0) }
                    elseif ($maximum -eq $green) { $hue = 60.0 * ((($blue - $red) / $delta) + 2.0) }
                    else { $hue = 60.0 * ((($red - $green) / $delta) + 4.0) }
                    if ($hue -lt 0) { $hue += 360.0 }
                    if ($hue -le 18 -or $hue -ge 340) { $counts.L0_red++ }
                    elseif ($hue -le 45) { $counts.L1_orange++ }
                    elseif ($hue -le 72) { $counts.L2_yellow++ }
                    elseif ($hue -ge 165 -and $hue -le 205) { $counts.L3_cyan++ }
                    elseif ($hue -ge 260 -and $hue -le 320) { $counts.L4_violet++ }
                }

                # No horizon line is available from a screenshot alone. This is a
                # deliberately labelled lower-screen approximation, not geometry truth.
                if ($y -ge ($height * 0.38) -and $y -le ($height * 0.80)) {
                    $voidSamples++
                    if ($red -le 20 -and $green -le 20 -and $blue -le 24) { $voidPixels++ }
                }
            }
        }
    } finally {
        $Bitmap.UnlockBits($locked)
    }
    $colors = [ordered]@{}
    foreach ($name in $counts.Keys) {
        $colors[$name] = [ordered]@{
            pixels = $counts[$name]
            fractionOfAnalyzed = if ($analyzed -eq 0) { 0.0 } else { [Math]::Round($counts[$name] / $analyzed, 6) }
            observed = ($counts[$name] -gt 0)
        }
    }
    return [ordered]@{
        analyzedPixels = $analyzed
        overlayColors = $colors
        heuristicNearBlackVoidFractionBelowHorizon = if ($voidSamples -eq 0) { 0.0 } else { [Math]::Round($voidPixels / $voidSamples, 6) }
        voidHeuristicRegion = "lower-screen 38%-80% height, UI-excluded; not a horizon detector"
    }
}

function Invoke-SelfTest {
    $bitmap = [System.Drawing.Bitmap]::new(40, 40)
    try {
        $palette = @(
            [System.Drawing.Color]::FromArgb(255, 40, 40),
            [System.Drawing.Color]::FromArgb(255, 145, 35),
            [System.Drawing.Color]::FromArgb(245, 225, 45),
            [System.Drawing.Color]::FromArgb(35, 220, 230),
            [System.Drawing.Color]::FromArgb(185, 75, 235)
        )
        for ($index = 0; $index -lt $palette.Count; $index++) {
            for ($y = 0; $y -lt 8; $y++) {
                for ($x = 0; $x -lt 8; $x++) { $bitmap.SetPixel(($index * 8) + $x, $y, $palette[$index]) }
            }
        }
        for ($y = 20; $y -lt 40; $y++) {
            for ($x = 0; $x -lt 40; $x++) { $bitmap.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(8, 8, 10)) }
        }
        $evidence = Get-ImageEvidence $bitmap $false
        foreach ($name in @("L0_red", "L1_orange", "L2_yellow", "L3_cyan", "L4_violet")) {
            if (-not $evidence.overlayColors[$name].observed) { throw "Self-test did not classify $name" }
        }
        if ($evidence.heuristicNearBlackVoidFractionBelowHorizon -le 0) { throw "Self-test did not report near-black void" }
        Write-Host "flight screenshot verdict self-test passed"
    } finally {
        $bitmap.Dispose()
    }
}

if ($SelfTest) {
    Invoke-SelfTest
    exit 0
}

$files = Get-ChildItem $ScreenshotDirectory -Filter "tour-waypoint-*.png" -File -ErrorAction SilentlyContinue |
    Sort-Object Name
if ($MaxImages -gt 0) { $files = @($files | Select-Object -First $MaxImages) }
$images = @()
foreach ($file in $files) {
    $bitmap = [System.Drawing.Bitmap]::new($file.FullName)
    try {
        $evidence = Get-ImageEvidence $bitmap
        $images += [ordered]@{
            name = $file.Name
            width = $bitmap.Width
            height = $bitmap.Height
            evidence = $evidence
        }
    } finally {
        $bitmap.Dispose()
    }
}

$result = [ordered]@{
    schemaVersion = 1
    classifier = "broad HSV bands calibrated for translucent Voxy overlay; fixed top-left legend and UI excluded"
    advisoryOnly = $true
    images = $images
}
$parent = Split-Path -Parent $OutputPath
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $OutputPath
$result | ConvertTo-Json -Depth 8
