#!/usr/bin/env bash
# Baut die beiden Abgabepakete nach dist/:
#   OrderHub-<version>.zip         Kaeuferpaket (Jar, SHA256SUMS, README, CONFIGURATION, COMPATIBILITY, CHANGELOG, LICENSE)
#   OrderHub-<version>-source.zip  vollstaendiger Quellstand (git archive, ohne build und Caches)
# Voraussetzung: ./gradlew build ist gelaufen, der Stand ist committet.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
JAR="build/libs/OrderHub-${VERSION}.jar"
[ -f "$JAR" ] || { echo "Jar fehlt: $JAR (erst ./gradlew build)" >&2; exit 2; }
if [ -n "$(git status --porcelain)" ]; then
  echo "Warnung: Arbeitsverzeichnis nicht sauber, das Quellpaket enthaelt nur den committeten Stand." >&2
fi
OUT="$ROOT/dist"
rm -rf "$OUT"
mkdir -p "$OUT/OrderHub-${VERSION}"
cp "$JAR" README.md CONFIGURATION.md COMPATIBILITY.md CHANGELOG.md LICENSE.txt "$OUT/OrderHub-${VERSION}/"
( cd "$OUT/OrderHub-${VERSION}" && sha256sum "OrderHub-${VERSION}.jar" > SHA256SUMS )
( cd "$OUT" && zip -qr "OrderHub-${VERSION}.zip" "OrderHub-${VERSION}" )
git archive --prefix="OrderHub-${VERSION}-source/" -o "$OUT/OrderHub-${VERSION}-source.zip" HEAD
cp "$OUT/OrderHub-${VERSION}/SHA256SUMS" "$OUT/SHA256SUMS"
echo "gebaut in $OUT:"
ls -la "$OUT"/*.zip "$OUT/SHA256SUMS"
