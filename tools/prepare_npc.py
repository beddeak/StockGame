"""고정 Citizens JAR를 검증하고 Maven/배포용으로 준비합니다. 서버 파일은 변경하지 않습니다."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
VERSION = "2.0.35-b3581"
FILE_NAME = f"Citizens-{VERSION}.jar"
SHA256 = "863cc01168d4241d07530815ffd320e9a62a376741f7fc9144043a0c5f1c8289"
URL = f"https://ci.citizensnpcs.co/job/Citizens2/3581/artifact/dist/target/{FILE_NAME}"
POM = f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>net.citizensnpcs</groupId><artifactId>citizens-main</artifactId><version>{VERSION}</version>
  <packaging>jar</packaging><name>Citizens fixed Paper 1.20.6 dependency</name>
</project>
"""


def verified(path: Path) -> bool:
    if not path.is_file():
        return False
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest() == SHA256


def copy_verified(source: Path, destination: Path) -> None:
    """같은 파일 재준비를 허용하고, 부분 파일 대신 검증 완료한 파일만 교체합니다."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    if verified(destination):
        return
    temporary = destination.with_name(destination.name + ".tmp")
    try:
        shutil.copyfile(source, temporary)
        if not verified(temporary):
            raise ValueError(f"Citizens checksum mismatch while staging: {source}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def prepare(root: Path = ROOT, *, offline: bool = False) -> Path:
    cache = root / ".tools" / "npc" / FILE_NAME
    staged = root / "dist" / FILE_NAME
    repository = root / ".tools" / "m2" / "net" / "citizensnpcs" / "citizens-main" / VERSION
    artifact = repository / f"citizens-main-{VERSION}.jar"
    # 배포 ZIP의 검증된 기존 설치 JAR도 로컬 원본으로 쓸 수 있습니다. 읽기만 합니다.
    candidates = (cache, staged, artifact, root / "server" / "plugins" / "Citizens.jar")
    source = next((path for path in candidates if verified(path)), None)
    if source is None:
        if offline:
            raise FileNotFoundError(
                f"Verified Citizens {VERSION} is not available locally. "
                "Run build.ps1 without -Offline once, or tools/prepare_npc.py --online. "
                f"Required SHA256: {SHA256}"
            )
        cache.parent.mkdir(parents=True, exist_ok=True)
        # 서버·다른 정상 파일을 변경하기 전에 다운로드 파일 자체를 검증합니다.
        with tempfile.TemporaryDirectory(prefix="citizens-", dir=cache.parent) as folder:
            downloaded = Path(folder) / FILE_NAME
            request = Request(URL, headers={"User-Agent": "Mozilla/5.0 StockGame/1.0"})
            with urlopen(request, timeout=60) as response, downloaded.open("wb") as target:
                shutil.copyfileobj(response, target)
            if not verified(downloaded):
                raise ValueError(f"Downloaded Citizens checksum mismatch; expected {SHA256}")
            copy_verified(downloaded, cache)
        source = cache
    copy_verified(source, artifact)
    repository.mkdir(parents=True, exist_ok=True)
    (repository / f"citizens-main-{VERSION}.pom").write_text(POM, encoding="utf-8")
    copy_verified(source, staged)
    return staged


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--offline", action="store_true", help="Use only SHA256-verified local files")
    mode.add_argument("--online", action="store_true", help="Download the fixed official build if needed")
    arguments = parser.parse_args()
    try:
        staged = prepare(offline=arguments.offline)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Citizens preparation failed: {error}\n")
    print(f"Citizens {VERSION} ready: {staged} (SHA256 {SHA256})")


if __name__ == "__main__":
    main()
