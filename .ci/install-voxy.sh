#!/usr/bin/env bash
set -euo pipefail

manifest=.ci/voxy-artifact.json
source_path=$(jq -r .path "$manifest")
expected_sha=$(jq -r .sha256 "$manifest")
printf '%s  %s\n' "$expected_sha" "$source_path" | sha256sum --check --strict

if [[ ${1:-verify} == install ]]; then
  mkdir -p java/mods
  cp "$source_path" "java/mods/${source_path##*/}"
fi
