#!/usr/bin/env bash
# Startet echte Paper- und Folia-Server mit dem gebauten OrderHub-Jar.
# Die Minecraft-EULA (https://aka.ms/MinecraftEULA) muss VOM NUTZER akzeptiert sein:
#   ACCEPT_MINECRAFT_EULA=true tools/mcstage/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
[ "${ACCEPT_MINECRAFT_EULA:-}" = "true" ] || { echo "EULA nicht bestaetigt (ACCEPT_MINECRAFT_EULA=true)." >&2; exit 3; }
tools/mcstage/setup.sh
./gradlew -q shadowJar
mkdir -p build/reports
java -jar build/mcstage/mcstage.jar test tools/mcstage/mcstage.yml --accept-eula \
  --work build/mcstage-work --format junit -o build/reports/mcstage-junit.xml \
  --html build/reports/mcstage.html --tail
