"""공식 Maven/Paper만 내려받는 재실행 가능한 로컬 준비 도구. Python 표준 라이브러리만 사용."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def read(url):
    return OPENER.open(urllib.request.Request(url, headers={"User-Agent": "StockGame-LocalSetup/1.0 (https://papermc.io/)"}), timeout=120).read()

def download(url, path, sha=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and (not sha or hashlib.sha256(path.read_bytes()).hexdigest() == sha):
        return
    data = read(url)
    if sha and hashlib.sha256(data).hexdigest() != sha:
        raise RuntimeError(f"SHA-256 mismatch: {path.name}")
    path.write_bytes(data)
    print(f"Downloaded {path.name}: {len(data)} bytes", flush=True)

def maven():
    version = "3.9.11"
    base = f"https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/{version}/apache-maven-{version}-bin.zip"
    path = ROOT / ".tools" / "maven.zip"
    checksum = read(base + ".sha512").decode().strip().split()[0]
    if not path.exists() or hashlib.sha512(path.read_bytes()).hexdigest() != checksum:
        download(base, path)
    if hashlib.sha512(path.read_bytes()).hexdigest() != checksum:
        raise RuntimeError("Maven SHA-512 mismatch")
    with zipfile.ZipFile(path) as z:
        z.extractall(ROOT / ".tools")

def paper():
    data = json.loads(read("https://fill.papermc.io/v3/projects/paper/versions/1.20.6/builds"))
    build = next(b for b in data if b["id"] == 151)
    info = build["downloads"]["server:default"]
    download(info["url"], ROOT / "server" / "paper.jar", info["checksums"]["sha256"])
    (ROOT / "server" / "paper-build.json").write_text(json.dumps(build, indent=2), encoding="utf-8")

if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        for result in executor.map(lambda f: f(), [maven, paper]):
            pass
    print("Bootstrap ready.", flush=True)
