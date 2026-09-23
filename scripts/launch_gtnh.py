"""Launch the GT New Horizons pack straight from the TLauncher install directory.

Used to run SkinPort's in-game test inside the real pack (Angelica, RenderPlayerAPI and the
rest of it) rather than the bare development client. Extra JVM arguments are passed through:

    python scripts/launch_gtnh.py -Dskinport.autotest=true -Dskinport.debug=true \
        -Dskinport.autotest.menuWait=300 -Dskinport.autotest.skipReload=true

The pack loads whatever SkinPort jar is in its mods folder: install the build first
(../_install_skinport.sh), with the game closed.
"""
import hashlib
import io
import json
import os
import subprocess
import sys
import uuid

GAME = r"C:\Users\newbie\AppData\Roaming\.tlauncher\legacy\Minecraft\game"
VERSION = "Forge 1.7.10"
JAVA = (r"C:\Users\newbie\AppData\Roaming\.tlauncher\legacy\Minecraft\jre"
        r"\jre-legacy\windows-x64\jre-legacy\bin\java.exe")
USERNAME = "reil20"

vdir = os.path.join(GAME, "versions", VERSION)
with io.open(os.path.join(vdir, VERSION + ".json"), encoding="utf-8") as fh:
    meta = json.load(fh)

cp = []
for lib in meta["libraries"]:
    if "natives" in lib:
        continue
    group, artifact, version = lib["name"].split(":")[0:3]
    path = os.path.join(GAME, "libraries", group.replace(".", "/"), artifact, version,
                        "%s-%s.jar" % (artifact, version))
    if os.path.exists(path):
        cp.append(path)
    else:
        print("missing library:", path)
cp.append(os.path.join(vdir, VERSION + ".jar"))


def offline_uuid(name):
    """What Minecraft itself derives in offline mode: UUID.nameUUIDFromBytes("OfflinePlayer:name").

    Python's uuid.uuid3 hashes with a namespace and gives a different id; with it the client and
    the integrated server disagree about who the player is.
    """
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


jvm = [
    JAVA,
    "-Xmx6G", "-Xms2G",
    "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50",
    "-XX:+UnlockExperimentalVMOptions", "-XX:G1NewSizePercent=20",
    "-XX:G1ReservePercent=20", "-XX:G1HeapRegionSize=32M",
    "-Dfml.readTimeout=180",
    "-Djava.library.path=" + os.path.join(vdir, "natives"),
    "-Dminecraft.launcher.brand=skinport-test",
]
jvm += sys.argv[1:]
jvm += ["-cp", os.pathsep.join(cp), meta["mainClass"]]

args = [
    "--username", USERNAME,
    "--version", VERSION,
    "--gameDir", GAME,
    "--assetsDir", os.path.join(GAME, "assets"),
    "--assetIndex", meta.get("assets", "1.7.10"),
    "--uuid", offline_uuid(USERNAME).hex,
    "--accessToken", "0",
    "--userProperties", "{}",
    "--userType", "legacy",
    "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker",
]

print("launching with", len(cp), "libraries")
sys.stdout.flush()
sys.exit(subprocess.call(jvm + args, cwd=GAME))
