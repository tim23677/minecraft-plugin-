#!/usr/bin/env bash
# Baut mcstage (Apache 2.0) aus einem festen Commit selbst, statt ein Binary zu laden.
# Ergebnis: build/mcstage/mcstage.jar
set -euo pipefail
COMMIT="9df11b0787c5990ba0fcabbf22561e43e5ee3d29"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/build/mcstage-src"
OUT="$ROOT/build/mcstage/mcstage.jar"
[ -f "$OUT" ] && { echo "$OUT existiert"; exit 0; }
rm -rf "$SRC"; mkdir -p "$SRC" "$(dirname "$OUT")"
git clone -q https://github.com/SalyyS1/mcstage.git "$SRC"
git -C "$SRC" checkout -q "$COMMIT"
# Maven Central antwortet gelegentlich mit HTTP 429, daher Wiederholungen.
for i in 1 2 3 4; do
  ( cd "$SRC" && ./gradlew --console=plain -q --max-workers=2 shadowJar -x test ) && break
  [ "$i" = 4 ] && { echo "Build fehlgeschlagen" >&2; exit 1; }
  sleep 30
done
cp "$(ls "$SRC"/build/libs/mcstage-*.jar | grep -v plain | head -1)" "$OUT"
echo "gebaut: $OUT"
