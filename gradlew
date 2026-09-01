#!/bin/sh
# Monorepo delegation wrapper -- forwards to mod/gradlew with -p mod
# This keeps projectDir == mod/ so all relative file(...) paths in mod/build.gradle stay correct.
# Preferred invocation from repo root: ./gradlew :compileJava  or  rtk proxy ./gradlew :compileJava
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/mod/gradlew" -p "$SCRIPT_DIR/mod" "$@"
