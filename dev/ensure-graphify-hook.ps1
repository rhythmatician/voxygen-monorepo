#!/usr/bin/env pwsh
# Fix: make git-lfs preamble non-fatal so graphify rebuild always runs (WSL has no git-lfs)
# Run after `git lfs install` or `graphify hook install` which re-adds the fatal preamble
# Idempotent: re-runnable
$ErrorActionPreference = "Stop"
foreach ($hook in @(".git/hooks/post-checkout",".git/hooks/post-commit")) {
  if (!(Test-Path $hook)) { continue }
  $raw = Get-Content -Raw $hook -ErrorAction SilentlyContinue
  if ($null -eq $raw) { continue }
  if ($raw -match "exit 2") {
    $lines = Get-Content $hook
    $new = @()
    $skip = 0
    foreach ($line in $lines) {
      if ($skip -gt 0) { $skip--; continue }
      if ($line -match "command -v git-lfs.*exit 2") {
        $cmd = if ($hook -like "*post-checkout*") { "post-checkout" } else { "post-commit" }
        $new += 'if command -v git-lfs >/dev/null 2>&1; then'
        $new += "  git lfs $cmd `"`$@`""
        $new += 'fi'
        $skip = 1
        continue
      }
      if ($line -match "^git lfs post-") { continue }
      $new += $line
    }
    $new | Set-Content -Path $hook -Encoding utf8NoBOM
    Write-Host "Patched $hook to make git-lfs non-fatal"
  } else {
    Write-Host "$hook already resilient"
  }
}
