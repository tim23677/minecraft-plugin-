#!/usr/bin/env python3
"""Richtet die Simulationsumgebung ein (idempotent): Paper (Fill-API, SHA256), Abhaengigkeits-Plugins
(Modrinth, SHA512), mineflayer. Alles unter sim/server und sim/bots, nichts im Repo-Code."""
import hashlib, json, os, shutil, subprocess, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, "server")
MC = os.environ.get("SIM_MC_VERSION", "1.21.11")
PORT = int(os.environ.get("SIM_PORT", "25598"))


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "orderhub-sim/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def sha(data, algo):
    return hashlib.new(algo, data).hexdigest()


def java_ok():
    try:
        out = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
        major = int(out.split('"')[1].split(".")[0])
        return major >= 21
    except Exception:
        return False


def ensure_java():
    if java_ok():
        return
    jdk = os.path.join(HERE, "jdk")
    if not os.path.isdir(jdk):
        os.makedirs(jdk)
        url = "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
        tgz = os.path.join(jdk, "jdk.tar.gz")
        open(tgz, "wb").write(get(url))
        subprocess.check_call(["tar", "-xzf", tgz, "-C", jdk, "--strip-components=1"])
        os.remove(tgz)
    print("JDK in sim/jdk installiert, PATH vor dem Start erweitern: " + os.path.join(jdk, "bin"))


def paper():
    jar = os.path.join(SERVER, "paper.jar")
    meta = os.path.join(SERVER, "paper.version")
    builds = json.loads(get(f"https://fill.papermc.io/v3/projects/paper/versions/{MC}/builds"))
    stable = [b for b in builds if b["channel"] == "STABLE"] or builds
    b = stable[0]
    dl = b["downloads"]["server:default"]
    want = dl["checksums"]["sha256"]
    if os.path.exists(jar) and sha(open(jar, "rb").read(), "sha256") == want:
        print(f"Paper {MC} build {b['id']} vorhanden, SHA256 ok")
    else:
        data = get(dl["url"])
        if sha(data, "sha256") != want:
            sys.exit("Paper: SHA256 stimmt nicht")
        open(jar, "wb").write(data)
        print(f"Paper {MC} build {b['id']} geladen, SHA256 ok")
    open(meta, "w").write(f"{MC} build {b['id']} sha256 {want}\n")


def modrinth(slug, filename, version_hint=None):
    dest = os.path.join(SERVER, "plugins", filename)
    q = f"https://api.modrinth.com/v2/project/{slug}/version?loaders=%5B%22paper%22%5D&game_versions=%5B%22{MC}%22%5D"
    versions = json.loads(get(q))
    v = next((x for x in versions if not version_hint or version_hint in x["version_number"]), versions[0])
    f = v["files"][0]
    want = f["hashes"]["sha512"]
    if os.path.exists(dest) and sha(open(dest, "rb").read(), "sha512") == want:
        print(f"{slug} {v['version_number']} vorhanden, SHA512 ok")
        return v["version_number"]
    data = get(f["url"])
    if sha(data, "sha512") != want:
        sys.exit(f"{slug}: SHA512 stimmt nicht")
    open(dest, "wb").write(data)
    print(f"{slug} {v['version_number']} geladen, SHA512 ok")
    return v["version_number"]


def server_files():
    # Die EULA-Zustimmung kommt von der Person, die die Simulation startet (ausdruecklich beauftragt).
    open(os.path.join(SERVER, "eula.txt"), "w").write("eula=true\n")
    props = {
        "online-mode": "false", "server-port": str(PORT), "level-type": "minecraft\\:flat",
        "spawn-protection": "0", "view-distance": "4", "simulation-distance": "4",
        "motd": "OrderHub simulation", "max-players": "10", "enable-command-block": "false",
        "difficulty": "peaceful", "spawn-monsters": "false", "gamemode": "creative",
        "force-gamemode": "true", "generate-structures": "false", "allow-nether": "false",
    }
    open(os.path.join(SERVER, "server.properties"), "w").write("".join(f"{k}={v}\n" for k, v in props.items()))


def spigot_yml():
    # Vanilla kickt Spieler, die mehr als etwa einen Befehl pro Sekunde senden. Fuer den Test mit
    # schneller Befehlsfolge werden die OrderHub-Befehle ausgenommen (nur in der Simulation).
    f = os.path.join(SERVER, "spigot.yml")
    if not os.path.exists(f):
        open(f, "w").write("commands:\n  spam-exclusions:\n  - /order\n  - /orderadmin\n")


def bots():
    d = os.path.join(HERE, "bots")
    if not os.path.isdir(os.path.join(d, "node_modules", "mineflayer")):
        subprocess.check_call(["npm", "install", "--no-audit", "--no-fund", "mineflayer@4"], cwd=d)
    print("mineflayer bereit")


if __name__ == "__main__":
    os.makedirs(os.path.join(SERVER, "plugins"), exist_ok=True)
    ensure_java()
    paper()
    server_files()
    spigot_yml()
    versions = {
        "VaultUnlocked": modrinth("vaultunlocked", "VaultUnlocked.jar", "2.20"),
        "EssentialsX": modrinth("essentialsx", "EssentialsX.jar"),
        "LuckPerms": modrinth("luckperms", "LuckPerms-Bukkit.jar", "bukkit"),
    }
    json.dump(versions, open(os.path.join(SERVER, "deps.json"), "w"), indent=1)
    bots()
