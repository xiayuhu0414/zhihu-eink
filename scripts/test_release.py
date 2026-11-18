import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        self.code = 4
        self.commit = "a" * 40
        self.run_id = "1234"
        files = release.names(self.code)
        (self.output / files[0]).write_bytes(b"signed-apk-fixture")
        (self.output / files[-1]).write_bytes(b"exact-source-fixture")
        (self.output / "LICENSE").write_text("AGPL-3.0")
        hashes = {name: hashlib.sha256((self.output / name).read_bytes()).hexdigest() for name in [files[0], files[-1]]}
        (self.output / "SHA256SUMS.txt").write_text("".join(f"{value}  {name}\n" for name, value in hashes.items()))
        info = dict(applicationId=release.APPLICATION_ID, versionName="0.1.4", versionCode=4,
                    certificateSha256=release.CERTIFICATE_SHA256, sourceCommit=self.commit,
                    workflowRunId=self.run_id, workflowRunNumber=4, sha256=hashes)
        (self.output / "build-info.json").write_text(json.dumps(info))
        self.apk_check = patch.object(release, "check_apk")
        self.apk_check.start()
        self.addCleanup(self.apk_check.stop)

    def verify(self):
        return release.verify(self.output, self.code, self.commit, self.run_id)

    def test_complete_release_is_verified(self):
        self.assertEqual(5, len(self.verify()))
        release.check_apk.assert_called_once()

    def test_modified_apk_and_source_are_rejected(self):
        for name in [release.names(4)[0], release.names(4)[-1]]:
            path = self.output / name
            original = path.read_bytes()
            path.write_bytes(original + b"tampered")
            with self.assertRaisesRegex(ValueError, "摘要"):
                self.verify()
            path.write_bytes(original)

    def test_wrong_source_run_code_or_certificate_are_rejected(self):
        path = self.output / "build-info.json"
        original = json.loads(path.read_text())
        for field, wrong in [("sourceCommit", "b" * 40), ("workflowRunId", "wrong"),
                             ("versionCode", 5), ("certificateSha256", "c" * 64)]:
            path.write_text(json.dumps(dict(original, **{field: wrong})))
            with self.assertRaisesRegex(ValueError, "不匹配"):
                self.verify()
        path.write_text(json.dumps(original))

    def test_missing_asset_cannot_publish(self):
        (self.output / "SHA256SUMS.txt").unlink()
        with patch.object(release, "gh") as github:
            with self.assertRaises(ValueError):
                release.publish(self.output, 4, self.commit, self.run_id)
            github.assert_not_called()

    def test_older_build_is_not_republished(self):
        payload = json.dumps([dict(tag_name="test-v0.1.5", draft=False)])
        with patch.object(release, "gh", return_value=payload) as github:
            with self.assertRaisesRegex(ValueError, "旧版本"):
                release.publish(self.output, 4, self.commit, self.run_id)
            self.assertEqual(1, github.call_count)

    def github(self, fail_upload=False, missing_asset=False):
        calls = []

        def fake(*args):
            calls.append(args)
            if args[:2] == ("release", "upload") and fail_upload:
                raise RuntimeError("模拟上传中断")
            if args[0] == "api" and "/releases?" in args[1]:
                if not any(c[:2] == ("release", "upload") for c in calls):
                    return "[]"
                files = release.names(4)
                if missing_asset:
                    files = files[:-1]
                return json.dumps([dict(tag_name="test-v0.1.4", draft=True, assets=[dict(name=p, size=(self.output / p).stat().st_size) for p in files])])
            return ""
        return calls, fake

    def test_upload_failure_keeps_release_in_draft(self):
        calls, github = self.github(fail_upload=True)
        with patch.object(release, "gh", side_effect=github), patch.object(release, "run", return_value="测试变更"):
            with self.assertRaises(RuntimeError):
                release.publish(self.output, 4, self.commit, self.run_id)
        self.assertTrue(any(c[:2] == ("release", "create") and "--draft" in c for c in calls))
        self.assertFalse(any(c[:2] == ("release", "edit") for c in calls))

    def test_incomplete_upload_does_not_become_public(self):
        calls, github = self.github(missing_asset=True)
        with patch.object(release, "gh", side_effect=github), patch.object(release, "run", return_value="测试变更"):
            with self.assertRaisesRegex(ValueError, "保持草稿"):
                release.publish(self.output, 4, self.commit, self.run_id)
        self.assertFalse(any(c[:2] == ("release", "edit") for c in calls))

    def test_complete_upload_becomes_prerelease_after_upload(self):
        calls, github = self.github()
        with patch.object(release, "gh", side_effect=github), patch.object(release, "run", return_value="测试变更"):
            release.publish(self.output, 4, self.commit, self.run_id)
        self.assertEqual(("release", "edit"), calls[-1][:2])
        self.assertIn("--draft=false", calls[-1])
        self.assertIn("--latest=false", calls[-1])

    def test_wrong_apk_identity_and_signing_certificate_are_rejected(self):
        # 独立覆盖真实工具输出的检查，不依赖 verify 中模拟的 check_apk。
        self.apk_check.stop()
        badging = "package: name='com.github.xiayuhu0414.zhihu.eink' versionCode='4' versionName='0.1.4'\nminSdkVersion:'27'"
        for wrong_badging, cert in [(badging.replace("versionCode='4'", "versionCode='5'"), ""),
                                    (badging, "Signer #1 certificate SHA-256 digest: " + "b" * 64)]:
            with patch.object(release, "run", side_effect=[wrong_badging, cert]):
                with self.assertRaises(ValueError):
                    release.check_apk(self.output / release.names(4)[0], 4, Path("/tools"))


if __name__ == "__main__":
    unittest.main()
