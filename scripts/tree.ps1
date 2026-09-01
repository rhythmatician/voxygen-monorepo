<#
.SYNOPSIS
  Generates a clean `tree /F` style listing without build/cache/binary noise.

.DESCRIPTION
  Preferred path: uses `git ls-files --cached --others --exclude-standard` as
  the source of truth.  That means anything covered by .gitignore is
  automatically excluded - worktrees, caches, venvs, build outputs, etc.

  * Python noise: training/__pycache__, training/.venv, training/.pytest_cache,
    training/.mypy_cache, training/*.egg-info, training/runs, training/artifacts,
    .ruff_cache, .mypy_cache, .pytest_cache, __pycache__  -> all in .gitignore
  * Java/Gradle noise: mod/.gradle, mod/build, mod/run/mods|logs|...,
    *.class, *.jar, build/, out/, .gradle/  -> covered by root + mod/.gitignore
  * Worktrees: .sandcastle/worktrees/*, external/* (external/.gitignore = *),
    graphify-out/, node_modules/, tmp/, .tmp/.log, .vscode/prompt-diagnostics
    -> all in .gitignore
  * Binary/compiled strays: extra extension denylist catches stray
    .class/.jar/.pyc etc even if not yet gitignored.

  Fallback (no git): runs `tree /F /A` per top-level dir with a tiny
  Python+Java denylist.

.OUTPUTS
  tree_output.txt in the repo root
#>

param(
  [string]$OutputFile = "tree_output.txt"
)

# --- helpers ---
function Test-BinaryNoise {
  param([string]$Path)
  $binaryExt = @(
    '\.class$','\.jar$','\.war$','\.ear$','\.zip$','\.tar\.gz$',
    '\.pyc$','\.pyo$','\.pyd$','\.so$','\.dylib$','\.dll$','\.exe$',
    '\.o$','\.a$','\.lib$','\.bin$','\.out$'
  )
  foreach ($pat in $binaryExt) {
    if ($Path -match $pat) { return $true }
  }
  return $false
}

function Write-Tree {
  param([hashtable]$Nodes, [string]$Prefix, [System.IO.StreamWriter]$Writer)

  # dirs first, then files, both alpha-sorted (matches tree behaviour)
  $dirs  = $Nodes.Keys | Where-Object { -not $Nodes[$_].IsFile } | Sort-Object
  $files = $Nodes.Keys | Where-Object { $Nodes[$_].IsFile } | Sort-Object
  $ordered = @($dirs) + @($files)

  for ($i = 0; $i -lt $ordered.Count; $i++) {
    $name   = $ordered[$i]
    $node   = $Nodes[$name]
    $isLast = ($i -eq $ordered.Count - 1)
    $connector = if ($isLast) { "\---" } else { "+---" }
    $Writer.WriteLine("$Prefix$connector$name")
    if (-not $node.IsFile -and $node.Children.Count -gt 0) {
      $childPrefix = if ($isLast) { "$Prefix    " } else { "$Prefix|   " }
      Write-Tree -Nodes $node.Children -Prefix $childPrefix -Writer $Writer
    }
  }
}

# --- try git path ---
$files = @()
$useGit = $false

if (Get-Command git -ErrorAction SilentlyContinue) {
  # --cached = tracked (respects .gitignore negations like !gradle-wrapper.jar and !*.onnx)
  # --others --exclude-standard = untracked but NOT ignored
  # Keep tracked files verbatim; filter only untracked strays for binary/cache noise.
  $cached = @(& git ls-files --cached 2>$null)
  $others = @(& git ls-files --others --exclude-standard 2>$null)
  if ($LASTEXITCODE -eq 0 -and ($cached -or $others)) {
    $cached = @($cached | Where-Object { $_ -ne "" -and $_ -notmatch '^\s*$' })
    $others = @($others | Where-Object { $_ -ne "" -and $_ -notmatch '^\s*$' })

    # Only filter untracked files for stray compiled/binary + root cache noise
    # (tracked .jar/.onnx like gradle-wrapper.jar, voxy_l*.onnx must stay).
    $others = @($others | Where-Object { -not (Test-BinaryNoise $_) })
    $extraIgnore = @('^\.ruff_cache/','^\.mypy_cache/','\.ruff_cache','\.mypy_cache','__pycache__')
    $others = @($others | Where-Object {
      $p = $_
      -not ($extraIgnore | Where-Object { $p -match $_ })
    })

    $files = @($cached + $others | Where-Object { $_ -ne $OutputFile } | Sort-Object -Unique)
    $useGit = $true
  }
}

if ($useGit) {
  $files = @($files | Sort-Object)

  # Build nested hashtable tree from flat file list
  $root = @{}
  foreach ($f in $files) {
    # normalize to forward slashes
    $norm = $f -replace '\\','/'
    if (-not $norm) { continue }
    $parts = $norm -split '/'
    $cur = $root
    for ($idx = 0; $idx -lt $parts.Count; $idx++) {
      $part   = $parts[$idx]
      $isFile = ($idx -eq $parts.Count - 1)
      if (-not $cur.ContainsKey($part)) {
        $cur[$part] = @{ Children = @{}; IsFile = $isFile }
      } else {
        if (-not $isFile) { $cur[$part].IsFile = $false }
      }
      if (-not $isFile) {
        $cur = $cur[$part].Children
      }
    }
  }

  # Write output
  $writer = [System.IO.StreamWriter]::new($OutputFile, $false, [System.Text.Encoding]::UTF8)
  try {
    $repoRoot = (Get-Location).Path
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $gitHash = ""
    if (Get-Command git -ErrorAction SilentlyContinue) {
      $gitHash = & git rev-parse --short HEAD 2>$null
      if ($LASTEXITCODE -ne 0) { $gitHash = "" }
    }
    $writer.WriteLine("Folder PATH listing for volume $([System.IO.Path]::GetPathRoot($repoRoot))")
    $writer.WriteLine("Volume serial number is 0000-0000")
    $writer.WriteLine($repoRoot)
    $writer.WriteLine("Generated: $timestamp")
    if ($gitHash) { $writer.WriteLine("Git commit: $gitHash") }
    $writer.WriteLine("")
    Write-Tree -Nodes $root -Prefix "" -Writer $writer
  } finally {
    $writer.Close()
  }

  Write-Host "Done! (git-aware) Output written to $OutputFile" -ForegroundColor Green
  Write-Host "Files: $($files.Count)  Lines: $((Get-Content $OutputFile | Measure-Object -Line).Lines)" -ForegroundColor Cyan
  return
}

# --- fallback: no git available -> tree /F /A per dir with minimal denylist ---
Write-Host "git not available, falling back to tree /F /A filtering" -ForegroundColor Yellow

$directories = @(
  ".agents",".codex",".github",".claude",".vscode","dev","docs","git","java","python","scripts"
)

# Minimal fallback patterns: cover Python venv/cache + Java/Gradle + generic binary
$excludePatterns = @(
  '__pycache__','\.pyc$','\.pyo$','\.pyd$','\.venv','\.pytest_cache','\.mypy_cache','\.ruff_cache','\.egg-info',
  'python[\\/]runs','python[\\/]artifacts','\.coverage','pyvenv\.cfg','CACHEDIR\.TAG',
  '\.gradle','[\\/]build[\\/]','loom-cache','remapped','\.class$','\.jar$','\.log$','\.tmp$','\.bin$','\.exe$','\.dll$','\.so$',
  'graphify-out','node_modules','\.sandcastle[\\/]worktrees','external[\\/]voxy','external[\\/]fabric','\.vscode[\\/]prompt-diagnostics'
)

"" | Out-File -FilePath $OutputFile -Encoding utf8
  $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
  $gitHash = ""
  if (Get-Command git -ErrorAction SilentlyContinue) {
    $gitHash = & git rev-parse --short HEAD 2>$null
    if ($LASTEXITCODE -ne 0) { $gitHash = "" }
  }
  $repoRoot = (Get-Location).Path
  "Folder PATH listing for volume $([System.IO.Path]::GetPathRoot($repoRoot))" | Out-File -FilePath $OutputFile -Encoding utf8 -Append
  "Volume serial number is 0000-0000" | Out-File -FilePath $OutputFile -Encoding utf8 -Append
  $repoRoot | Out-File -FilePath $OutputFile -Encoding utf8 -Append
  "Generated: $timestamp" | Out-File -FilePath $OutputFile -Encoding utf8 -Append
  if ($gitHash) { "Git commit: $gitHash" | Out-File -FilePath $OutputFile -Encoding utf8 -Append }
  "" | Out-File -FilePath $OutputFile -Encoding utf8 -Append
  Write-Host "Generating tree output..." -ForegroundColor Cyan
foreach ($dir in $directories) {
  if (Test-Path $dir) {
    Write-Host "Scanning $dir..." -ForegroundColor Gray
    cmd /c "tree $dir /F /A" 2>$null | ForEach-Object {
      $line = $_
      $skip = $false
      foreach ($pat in $excludePatterns) {
        if ($line -match $pat) { $skip = $true; break }
      }
      if (-not $skip) { $line | Out-File -FilePath $OutputFile -Encoding utf8 -Append }
    }
  }
}
Write-Host "Done! Output written to $OutputFile" -ForegroundColor Green
Write-Host "Lines: $((Get-Content $OutputFile | Measure-Object -Line).Lines)" -ForegroundColor Cyan
