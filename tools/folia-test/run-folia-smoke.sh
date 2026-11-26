#!/usr/bin/env bash
# Startet einen echten Folia-Server mit dem gebauten OrderHub-Jar und prueft,
# dass das Plugin ohne Fehler aktiviert wird. Beendet den Server danach.
#
# Voraussetzung: Java 21, curl, sha256sum, ein gebautes Jar (./gradlew build).
# Die Minecraft-EULA (https://aka.ms/MinecraftEULA) muss VOM NUTZER akzeptiert werden:
#   ACCEPT_MINECRAFT_EULA=true tools/folia-test/run-folia-smoke.sh
set -euo pipefail

FOLIA_VERSION="${FOLIA_VERSION:-1.21.11}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN_DIR="${RUN_DIR:-$ROOT/build/folia-server}"
API="https://fill.papermc.io/v3/projects/folia/versions/${FOLIA_VERSION}/builds/latest"

mkdir -p "$RUN_DIR/plugins"
cd "$RUN_DIR"

meta="$(curl -fsS "$API")"
url="$(printf '%s' "$meta" | python3 -c 'import sys,json;print(json.load(sys.stdin)["downloads"]["server:default"]["url"])')"
sha="$(printf '%s' "$meta" | python3 -c 'import sys,json;print(json.load(sys.stdin)["downloads"]["server:default"]["checksums"]["sha256"])')"
jar="folia-${FOLIA_VERSION}.jar"

if [ ! -f "$jar" ] || ! echo "$sha  $jar" | sha256sum -c --quiet -; then
  curl -fsSL -o "$jar" "$url"
  echo "$sha  $jar" | sha256sum -c -
fi

plugin_jar="$(ls "$ROOT"/build/libs/OrderHub-*-SNAPSHOT.jar "$ROOT"/build/libs/OrderHub-*.jar 2>/dev/null | grep -v -- '-plain' | head -1)"
[ -n "$plugin_jar" ] || { echo "Kein Plugin-Jar gefunden. Zuerst ./gradlew build ausfuehren." >&2; exit 2; }
cp "$plugin_jar" plugins/OrderHub.jar

if [ "${ACCEPT_MINECRAFT_EULA:-}" != "true" ]; then
  echo "EULA nicht bestaetigt. Lies https://aka.ms/MinecraftEULA und starte mit ACCEPT_MINECRAFT_EULA=true." >&2
  exit 3
fi
echo "eula=true" > eula.txt

log="$RUN_DIR/smoke.log"
# Eingabe 'stop' sorgt fuer den Shutdown, sobald der Server bereit ist.
( sleep 1; while ! grep -q 'Done (' "$log" 2>/dev/null; do sleep 2; done; echo "orderhub"; sleep 2; echo "stop" ) \
  | timeout 300 java -Xmx1G -jar "$jar" --nogui > "$log" 2>&1 || true

grep -q 'Enabling OrderHub' "$log" || { echo "FAIL: OrderHub wurde nicht aktiviert"; tail -40 "$log"; exit 1; }
if grep -E 'ERROR|Exception' "$log" | grep -qi orderhub; then echo "FAIL: Fehler im Log"; grep -iE 'ERROR|Exception' "$log" | head; exit 1; fi
echo "OK: OrderHub wurde auf Folia ${FOLIA_VERSION} aktiviert."
