"""Offline release checks: temporary local git repositories, no credentials/API."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("prepare_release.py").resolve()


class PrepareReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bluedeck-release-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "app").mkdir()
        (self.root / "app/build.gradle.kts").write_text('versionName = "1.14.0"\n')
        (self.root / "CHANGELOG.md").write_text("## [1.14.0] - 2026-09-17\n\n### Added\n\n- Test release.\n")
        self.git("init", "-q")
        self.git("add", "app", "CHANGELOG.md")
        self.git("commit", "-qm", "Initial fixture")
        self.commit = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.run(
            ["git", "-c", "user.name=Release Test", "-c", "user.email=test@example.invalid", *args],
            cwd=self.root, text=True, capture_output=True, check=True,
        ).stdout.strip()

    def run_release(self, *, tag="v1.14.0", event="workflow_dispatch", ref_type="branch", ref_name="master"):
        env = dict(os.environ, GITHUB_EVENT_NAME=event, INPUT_TAG=tag,
                   GITHUB_REF_TYPE=ref_type, GITHUB_REF_NAME=ref_name,
                   GITHUB_OUTPUT=str(self.root / "output"))
        return subprocess.run([sys.executable, str(SCRIPT)], cwd=self.root, env=env,
                              text=True, capture_output=True)

    def assert_rejected(self, result, message):
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message, result.stderr)
        self.assertFalse((self.root / "output").exists())

    def test_manual_run_outputs_exact_commit_without_creating_tag(self):
        result = self.run_release()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "output").read_text(),
                         f"tag=v1.14.0\nversion=1.14.0\ncommit={self.commit}\n")
        self.assertEqual(self.git("tag", "--list"), "")

    def test_tag_push_uses_ref_not_manual_input(self):
        self.git("tag", "v1.14.0")
        result = self.run_release(event="push", ref_type="tag", ref_name="v1.14.0", tag="ignored")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_manual_existing_lightweight_tag_at_head_is_allowed(self):
        self.git("tag", "v1.14.0")
        result = self.run_release()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_manual_existing_annotated_tag_at_head_is_allowed(self):
        self.git("tag", "-a", "v1.14.0", "-m", "Release")
        result = self.run_release()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_tag_at_another_commit_is_rejected_and_preserved(self):
        self.git("tag", "v1.14.0")
        self.git("commit", "--allow-empty", "-qm", "Later commit")
        self.assert_rejected(self.run_release(), "another commit")
        self.assertEqual(self.git("rev-parse", "v1.14.0"), self.commit)

    def test_invalid_tags_are_rejected(self):
        for tag in ["", "1.14.0", "v1.14", "v01.14.0", "v1.14.0-rc.1", "v1.14.0\ncommit=evil",
                    "v1.14.0; touch injected", "$(touch injected)"]:
            with self.subTest(tag=tag):
                self.assert_rejected(self.run_release(tag=tag), "vMAJOR.MINOR.PATCH")
                self.assertFalse((self.root / "injected").exists())

    def test_version_mismatch_is_rejected(self):
        self.assert_rejected(self.run_release(tag="v1.13.0"), "versionName")

    def test_duplicate_version_declarations_are_rejected(self):
        (self.root / "app/build.gradle.kts").write_text('versionName = "1.14.0"\n' * 2)
        self.assert_rejected(self.run_release(), "single versionName")

    def test_missing_version_declaration_is_rejected(self):
        (self.root / "app/build.gradle.kts").write_text("// No version\n")
        self.assert_rejected(self.run_release(), "versionName")

    def test_missing_changelog_version_is_rejected(self):
        (self.root / "CHANGELOG.md").write_text("## [1.13.0] - 2026-09-17\n")
        self.assert_rejected(self.run_release(), "dated section")

    def test_branch_push_is_not_a_release(self):
        self.assert_rejected(self.run_release(event="push"), "tag push or a manual")

    def test_pull_request_is_not_a_release(self):
        self.assert_rejected(self.run_release(event="pull_request"), "tag push or a manual")

    def test_tag_push_requires_tag_in_checkout(self):
        self.assert_rejected(self.run_release(event="push", ref_type="tag", ref_name="v1.14.0"),
                             "missing from the checkout")


if __name__ == "__main__":
    unittest.main()
