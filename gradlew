#!/bin/sh
# Monorepo delegation wrapper -- forwards to java/gradlew with -p java
# This keeps projectDir == java/ so all relative file(...) paths in java/build.gradle stay correct.
# Preferred invocation from repo root: ./gradlew :compileJava  or  rtk proxy ./gradlew :compileJava
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/java/gradlew" -p "$SCRIPT_DIR/java" "$@"
