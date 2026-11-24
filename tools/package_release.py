"""소스와 바로 실행할 서버를 함께 ZIP으로 묶습니다. 운영자 권한/플레이어 자산/로그는 제외합니다."""
from pathlib import Path
import argparse
import copy
import shutil
import zipfile
import re

from prepare_npc import FILE_NAME as NPC_FILE_NAME, SHA256 as NPC_SHA256, verified as npc_verified

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "dist" / "StockGame-1.0.0-server.zip"
PLUGIN_JAR = ROOT / "dist" / "StockGame-1.0.0.jar"
PLUGIN_ENTRY = "StockGame/server/plugins/StockGame-1.0.0.jar"
NPC_JAR = ROOT / "dist" / NPC_FILE_NAME
NPC_ENTRY = "StockGame/server/plugins/Citizens.jar"
ASSET_BACKUPS = ROOT / "server" / "plugins" / "StockGame" / "backups"
EXCLUDE = {"state.yml", "state.yml.bak", "state.yml.tmp", "resource-round.yml", "eula.txt", "server.pid", "session.lock", "admin-status.json", "admin-panel.pid", "config.yml.bak"}

def exchange_world_name():
    map_config = (ROOT / "server" / "plugins" / "StockGame" / "map.yml").read_text(encoding="utf-8")
    match = re.search(r'(?m)^world-name:\s*[\x22\x27]?([A-Za-z0-9_-]+)', map_config)
    if not match:
        raise ValueError("Cannot read exchange world-name")
    return match.group(1)


def paths(world_name, include_world=True):
    for pattern in ("*.ps1", "*.bat", "*.md", "admin-panel.json", "pom.xml", ".gitignore"):
        yield from ROOT.glob(pattern)
    for name in ("src", "docs", "tools"):
        for path in (ROOT / name).rglob("*"):
            if path.is_file() and "__pycache__" not in path.parts:
                yield path
    for name in ("paper.jar", "paper-build.json", "server.properties"):
        yield ROOT / "server" / name
    names = ["plugins", "cache", "libraries", "versions"]
    if include_world:
        names.append(world_name)
    for name in names:
        for path in (ROOT / "server" / name).rglob("*"):
            if path.is_file() and ASSET_BACKUPS not in path.parents and "config-backups" not in path.parts and path.name not in EXCLUDE and path.suffix not in (".log", ".bak", ".tmp") and ".paper-remapped" not in path.parts:
                yield path


def package(reuse_map_from_existing=False):
    if not PLUGIN_JAR.is_file():
        raise FileNotFoundError(f"Latest built plugin is missing: {PLUGIN_JAR}. Run build.ps1 first.")
    if not npc_verified(NPC_JAR):
        raise FileNotFoundError(f"Verified Citizens build is missing: {NPC_JAR}. Run build.ps1 first. Required SHA256: {NPC_SHA256}")
    if reuse_map_from_existing and not DEST.is_file():
        raise FileNotFoundError(f"--reuse-map-from-existing requires an existing release ZIP: {DEST}")
    world_name = exchange_world_name()
    map_prefix = f"StockGame/server/{world_name}/"
    DEST.parent.mkdir(exist_ok=True)
    temporary = DEST.with_name(DEST.name + ".tmp")
    previous = zipfile.ZipFile(DEST) if reuse_map_from_existing else None
    try:
        map_entries = [] if previous is None else [
            entry for entry in previous.infolist()
            if entry.filename.startswith(map_prefix) and not entry.is_dir()
            and Path(entry.filename).name not in EXCLUDE and Path(entry.filename).suffix not in (".log", ".bak", ".tmp")
        ]
        if previous is not None and not map_entries:
            raise ValueError(f"Existing release ZIP contains no reusable map files under {map_prefix}")
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=3) as z:
            for path in sorted(set(paths(world_name, include_world=not reuse_map_from_existing))):
                entry_name = "StockGame/" + path.relative_to(ROOT).as_posix()
                # 실행 중 빌드는 서버 설치를 건너뛸 수 있으므로 서버의 오래된 JAR를 읽지 않습니다.
                if entry_name not in (PLUGIN_ENTRY, NPC_ENTRY) and path.exists():
                    z.write(path, entry_name)
            z.write(PLUGIN_JAR, PLUGIN_ENTRY)
            z.write(NPC_JAR, NPC_ENTRY)
            # 이 모드에서는 실행 중인 월드 디렉터리를 열거하거나 읽지 않습니다.
            for entry in map_entries:
                with previous.open(entry) as source, z.open(copy.copy(entry), "w") as target:
                    shutil.copyfileobj(source, target)
        with zipfile.ZipFile(temporary) as z:
            bad = z.testzip()
            if bad:
                raise RuntimeError(f"Broken archive entry: {bad}")
        if previous is not None:
            previous.close()
            previous = None
        temporary.replace(DEST)
    finally:
        if previous is not None:
            previous.close()
        temporary.unlink(missing_ok=True)
    with zipfile.ZipFile(DEST) as z:
        print(f"{DEST.name}: {len(z.namelist())} files, {DEST.stat().st_size:,} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reuse-map-from-existing", action="store_true",
                        help="Reuse the configured exchange map from the existing release ZIP without reading the live world")
    arguments = parser.parse_args()
    try:
        package(reuse_map_from_existing=arguments.reuse_map_from_existing)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        parser.exit(1, f"Packaging failed: {error}\n")
