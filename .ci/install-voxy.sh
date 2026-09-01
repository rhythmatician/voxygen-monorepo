#!/usr/bin/env bash
set -euo pipefail

manifest=.ci/voxy-artifact.json
expected_sha=$(jq -r .sha256 "$manifest")
filename=$(jq -r '.filename // "voxy-0.2.11-alpha.jar"' "$manifest")
url=$(jq -r '.url // empty' "$manifest")
legacy_path=$(jq -r '.path // empty' "$manifest")

# Resolve source: prefer cached download, fallback to legacy committed path, else download
cache_dir=".ci/.voxy-cache"
mkdir -p "$cache_dir" mod/mods
cached_path="$cache_dir/$filename"
legacy_exists=false
if [[ -n "$legacy_path" && -f "$legacy_path" ]]; then
  legacy_exists=true
fi

source_path=""
if [[ -f "$cached_path" ]]; then
  source_path="$cached_path"
elif [[ "$legacy_exists" == true ]]; then
  source_path="$legacy_path"
elif [[ -n "$url" ]]; then
  echo "[voxy] downloading $url -> $cached_path"
  if ! curl -L --fail --retry 3 -o "$cached_path" "$url"; then
    echo "[voxy] WARNING: download failed for $url (offline?)" >&2
    # If mod/mods already has the jar, verify that instead
    if [[ -f "mod/mods/$filename" ]]; then
      echo "[voxy] verifying existing mod/mods/$filename"
      printf '%s  %s\n' "$expected_sha" "mod/mods/$filename" | sha256sum --check --strict
      exit 0
    fi
    echo "[voxy] no cached artifact available; verification skipped (offline)" >&2
    # Still try to verify legacy if exists, else fail
    if [[ "$legacy_exists" == true ]]; then
      printf '%s  %s\n' "$expected_sha" "$legacy_path" | sha256sum --check --strict
      source_path="$legacy_path"
    else
      echo "[voxy] ERROR: no artifact found and download failed" >&2
      exit 1
    fi
  else
    source_path="$cached_path"
  fi
else
  echo "[voxy] ERROR: no url or legacy path in manifest" >&2
  exit 1
fi

if [[ -n "$source_path" ]]; then
  printf '%s  %s\n' "$expected_sha" "$source_path" | sha256sum --check --strict
fi

if [[ ${1:-verify} == install ]]; then
  mkdir -p mod/mods
  cp "$source_path" "mod/mods/$filename"
  # Also verify the installed copy
  printf '%s  %s\n' "$expected_sha" "mod/mods/$filename" | sha256sum --check --strict
fi
