#!/usr/bin/env bash
# Startet die komplette Simulation mit einem Befehl: ./sim/run.sh
# Richtet Paper und die Abhaengigkeits-Plugins ein, baut OrderHub, setzt den Server zurueck,
# fuehrt bots/test.js aus. Ergebnis: sim/out/result.json und sim/out/result.log
# SIM_DB=mariadb startet zusaetzlich einen MariaDB-Container (Docker) und nutzt ihn statt SQLite.
# Ergebnis: sim/out/<sqlite|mariadb>/result.json und server.log
# Mit SIM_KEEP=1 bleiben Welt und Plugin-Daten des letzten Laufs erhalten.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$HERE"
[ -d jdk/bin ] && export PATH="$HERE/jdk/bin:$PATH"
mkdir -p out
python3 setup.py
(cd "$ROOT" && ./gradlew -q shadowJar --max-workers=1)
JAR="$(ls "$ROOT"/build/libs/OrderHub-*.jar | grep -v -e plain -e bundled | head -1)"
rm -f server/plugins/OrderHub*.jar
cp "$JAR" server/plugins/OrderHub.jar
if [ "${SIM_KEEP:-0}" != "1" ]; then
  find server/plugins -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} +
  rm -rf server/world server/world_nether server/world_the_end server/logs server/ops.json \
         server/usercache.json server/cache 2>/dev/null || true
fi
if [ "${SIM_DB:-sqlite}" = "mariadb" ]; then
  if ! docker info >/dev/null 2>&1 && command -v dockerd >/dev/null; then
    (nohup dockerd > out/dockerd.log 2>&1 &)
    for i in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 1; done
  fi
  docker rm -f orderhub-sim-mariadb >/dev/null 2>&1 || true
  docker run -d --name orderhub-sim-mariadb -e MARIADB_ROOT_PASSWORD=sim -e MARIADB_DATABASE=orderhub \
    -p 3307:3306 mariadb:11.4 >/dev/null
  for i in $(seq 1 60); do
    docker exec orderhub-sim-mariadb mariadb-admin -uroot -psim ping >/dev/null 2>&1 && break
    sleep 2
  done
  trap 'docker rm -f orderhub-sim-mariadb >/dev/null 2>&1 || true' EXIT
fi
OUT="out/${SIM_DB:-sqlite}"
mkdir -p "$OUT"
rm -f "$OUT/server.log" "$OUT/result.json"
node bots/test.js server "$OUT/result.json"
# Absturz-Szenario (SIGKILL waehrend einer Lieferung, Neustart, Recovery) nur mit SQLite
if [ "${SIM_DB:-sqlite}" = "sqlite" ] && [ "${SIM_CRASH:-1}" = "1" ]; then
  find server/plugins -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} +
  rm -rf server/world server/world_nether server/world_the_end server/logs server/ops.json server/usercache.json
  SIM_PORT="${SIM_PORT:-25598}" NODE_PATH="$HERE/bots/node_modules" node "$ROOT/tools/e2e/e2e.js" server "$OUT/crash-result.json" | tee "$OUT/crash.log"
fi
