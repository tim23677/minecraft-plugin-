#!/usr/bin/env bash
# Builds the delivery packages into dist/ with the Gradle tasks (sales ZIP with content check, source ZIP).
# Prerequisite: the state is committed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
if [ -n "$(git status --porcelain)" ]; then
  echo "Warning: working tree is not clean, the source package contains only the committed state." >&2
fi
./gradlew build -x dependencyCheckAnalyze --max-workers=1
OUT="$ROOT/dist"
rm -rf "$OUT"
mkdir -p "$OUT"
cp build/distributions/OrderHub-"${VERSION}"*.zip "$OUT"/ 2>/dev/null || cp build/sales/*.zip "$OUT"/
git archive --prefix="OrderHub-${VERSION}-source/" -o "$OUT/OrderHub-${VERSION}-source.zip" HEAD
( cd "$OUT" && sha256sum *.zip > SHA256SUMS )
ls -la "$OUT"
