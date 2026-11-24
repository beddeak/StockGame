"""Citizens 다운로드/스테이징의 고정 해시와 오프라인 실패 경로를 확인합니다."""
import hashlib
from io import BytesIO
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_npc


class NpcPreparationTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="stock-npc-test-")
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.content = b"fixed test Citizens artifact"
        digest = hashlib.sha256(self.content).hexdigest()
        patcher = patch.object(prepare_npc, "SHA256", digest)
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, relative, content=None):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(self.content if content is None else content)
        return target

    def artifact(self):
        return self.root / ".tools/m2/net/citizensnpcs/citizens-main" / prepare_npc.VERSION / f"citizens-main-{prepare_npc.VERSION}.jar"

    def test_offline_stages_fixed_cache_and_minimal_pom_without_network(self):
        cache = self.write(f".tools/npc/{prepare_npc.FILE_NAME}")
        with patch.object(prepare_npc, "urlopen") as download:
            staged = prepare_npc.prepare(self.root, offline=True)
        download.assert_not_called()
        self.assertEqual(self.content, staged.read_bytes())
        self.assertEqual(self.content, self.artifact().read_bytes())
        self.assertEqual(self.content, cache.read_bytes())
        pom = self.artifact().with_suffix(".pom").read_text(encoding="utf-8")
        self.assertIn(prepare_npc.VERSION, pom)
        self.assertNotIn("<dependencies>", pom)

    def test_offline_missing_or_wrong_hash_fails_without_overwriting_server(self):
        installed = self.write("server/plugins/Citizens.jar", b"unknown installed plugin")
        self.write(f".tools/npc/{prepare_npc.FILE_NAME}", b"corrupt cache")
        with patch.object(prepare_npc, "urlopen") as download:
            with self.assertRaisesRegex(FileNotFoundError, "without -Offline"):
                prepare_npc.prepare(self.root, offline=True)
        download.assert_not_called()
        self.assertEqual(b"unknown installed plugin", installed.read_bytes())
        self.assertFalse(self.artifact().exists())
        self.assertFalse((self.root / "dist").exists())

    def test_distribution_installed_jar_is_read_only_offline_source(self):
        installed = self.write("server/plugins/Citizens.jar")
        with patch.object(prepare_npc, "urlopen") as download:
            staged = prepare_npc.prepare(self.root, offline=True)
        download.assert_not_called()
        self.assertEqual(self.content, staged.read_bytes())
        self.assertEqual(self.content, installed.read_bytes())

    def test_online_request_has_user_agent_and_checks_hash_before_staging(self):
        with patch.object(prepare_npc, "urlopen", return_value=BytesIO(self.content)) as download:
            staged = prepare_npc.prepare(self.root)
        request = download.call_args.args[0]
        self.assertEqual(prepare_npc.URL, request.full_url)
        self.assertIn("Mozilla/5.0", request.get_header("User-agent"))
        self.assertEqual(self.content, staged.read_bytes())
        self.assertEqual(self.content, self.artifact().read_bytes())

    def test_bad_download_preserves_existing_cache_and_never_stages(self):
        cache = self.write(f".tools/npc/{prepare_npc.FILE_NAME}", b"old invalid cache")
        with patch.object(prepare_npc, "urlopen", return_value=BytesIO(b"bad download")):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                prepare_npc.prepare(self.root)
        self.assertEqual(b"old invalid cache", cache.read_bytes())
        self.assertFalse(self.artifact().exists())
        self.assertFalse((self.root / "dist").exists())
        self.assertEqual([cache], list(cache.parent.iterdir()))


if __name__ == "__main__":
    unittest.main()
