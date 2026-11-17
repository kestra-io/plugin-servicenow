#!/usr/bin/env bash
set -euo pipefail

./gradlew --no-daemon spotlessCheck check shadowJar
printf '%s\n' 'ALL GREEN'
