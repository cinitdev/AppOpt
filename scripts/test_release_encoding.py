"""使用本机 Bash/curl 和本地 HTTP 服务验证发布说明；不访问发布平台。"""

import http.server
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import threading
import unittest
import urllib.parse


ROOT = Path(__file__).resolve().parents[1]
BASH = os.environ.get("QIXIA_TEST_BASH") or (
    "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash")
)
NOTES = "新版及历史更新日志：[查看完整日志](https://gitee.com/cinitdev/AppOpt/blob/modules-update/modules_update/changelog.md)"
SCRIPT = (ROOT / "build_module.sh").read_text(encoding="utf-8")


def function(name):
    # 只加载待测发布函数，避免触发编译、真实凭据读取或远端更新。
    marker = name + "() {\n"
    return marker + SCRIPT.split(marker, 1)[1].split("\n}\n", 1)[0] + "\n}\n"


class Capture(http.server.BaseHTTPRequestHandler):
    def handle_release(self):
        payload = self.rfile.read(int(self.headers["Content-Length"]))
        self.server.received.append((self.command, self.path, payload, self.headers))
        failure = self.server.fail_release and not self.path.endswith("/attach_files")
        self.send_response(500 if failure else 200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.end_headers()
        self.wfile.write(b'{"id":41}')

    do_POST = do_PATCH = handle_release

    def log_message(self, *args):
        pass


class ReleaseEncodingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="release-test-", dir=ROOT / "build")
        self.root = Path(self.temporary.name)
        (self.root / "build").mkdir()
        (self.root / "modules_update").mkdir()
        (self.root / "modules_update/changelog.md").write_text("# 更新日志\n", encoding="utf-8")
        (self.root / "module.zip").write_bytes(b"test asset")
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Capture)
        self.server.received = []
        self.server.fail_release = False
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temporary.cleanup()

    def run_publish(self, target, exists, locale):
        environment = os.environ.copy()
        for key in ("LANG", "LC_ALL", "LC_CTYPE", "GITEE_TOKEN", "GH_TOKEN", "GITHUB_TOKEN"):
            environment.pop(key, None)
        if locale:
            environment.update(LANG=locale, LC_ALL=locale)
        environment["NO_PROXY"] = "127.0.0.1"
        prelude = f"""set -euo pipefail
ROOT={shlex.quote(self.root.as_posix())}
ZIP="$ROOT/module.zip"
UPDATE_BRANCH=modules-update
PUBLISH_CHANGELOG_PAGE={shlex.quote(NOTES.split('](', 1)[1][:-1])}
PUBLISH_DRY_RUN=0
GITEE_TOKEN=test-only-token
GITEE_API_BASE=http://127.0.0.1:{self.server.server_port}
read_release_tag() {{ printf '%s' v0.0.0-test; }}
path_for_cargo() {{ printf '%s' "$1"; }}
load_gitee_token() {{ :; }}
validate_update_json() {{ :; }}
prepare_publish_update_json() {{ :; }}
publish_update_json() {{ printf '%s\\n' UPDATE_CALLED; }}
gitee_release_id() {{ printf '%s' {'41' if exists else "''"}; }}
find_github_cli() {{ printf '%s' gh_stub; }}
gh_stub() {{
    case "$1/$2" in
        auth/status|release/upload) return 0 ;;
        release/view) return {0 if exists else 1} ;;
        release/edit|release/create)
            [ "${{@: -2:1}}" = --notes-file ] && [ "${{@: -1}}" = - ] || return 64
            printf 'NOTES_BEGIN\\n'; cat; printf '\\nNOTES_END\\n' ;;
        *) return 65 ;;
    esac
}}
"""
        content = prelude + function("publish_" + target + "_release") + "\npublish_" + target + "_release\n"
        return subprocess.run(
            [BASH, "--noprofile", "--norc", "-s"],
            input=content.encode("utf-8"), capture_output=True, env=environment, timeout=20,
        )

    def test_gitee_create_and_edit_preserve_utf8_for_different_locales(self):
        for locale in (None, "C", "zh_CN.GBK", "C.UTF-8"):
            for exists in (False, True):
                with self.subTest(locale=locale, exists=exists):
                    self.server.received.clear()
                    result = self.run_publish("gitee", exists, locale)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    request, asset = self.server.received
                    method, path, data, headers = request
                    self.assertEqual(method, "PATCH" if exists else "POST")
                    self.assertEqual(path, "/releases/41" if exists else "/releases")
                    form = urllib.parse.parse_qs(data.decode("ascii"), errors="strict")
                    self.assertEqual(form["body"], [NOTES])
                    self.assertIn("charset=UTF-8", headers["Content-Type"])
                    self.assertEqual(asset[1], "/releases/41/attach_files")
                    self.assertIn(b"UPDATE_CALLED", result.stdout)

    def test_gitee_rejected_description_stops_before_asset_and_update(self):
        self.server.fail_release = True
        for exists in (False, True):
            with self.subTest(exists=exists):
                self.server.received.clear()
                result = self.run_publish("gitee", exists, None)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(len(self.server.received), 1)
                self.assertNotIn(b"UPDATE_CALLED", result.stdout)

    def test_github_create_and_edit_use_utf8_stdin(self):
        for locale in (None, "C", "zh_CN.GBK", "C.UTF-8"):
            for exists in (False, True):
                with self.subTest(locale=locale, exists=exists):
                    result = self.run_publish("github", exists, locale)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    notes = result.stdout.split(b"NOTES_BEGIN\n", 1)[1].split(b"\nNOTES_END", 1)[0]
                    self.assertEqual(notes.decode("utf-8"), NOTES)

    def test_update_commit_message_keeps_utf8_with_no_locale(self):
        environment = os.environ.copy()
        for key in ("LANG", "LC_ALL", "LC_CTYPE"):
            environment.pop(key, None)
        environment.update(
            GIT_AUTHOR_NAME="Encoding test", GIT_AUTHOR_EMAIL="test@example.invalid",
            GIT_COMMITTER_NAME="Encoding test", GIT_COMMITTER_EMAIL="test@example.invalid",
        )
        # 执行生产脚本中的提交命令，用本地仓库读取实际保存的提交说明。
        command = "    printf '发布：更新 "
        command = command + SCRIPT.split(command, 1)[1].split("\n\n", 1)[0]
        content = f"""set -euo pipefail
worktree={shlex.quote(self.root.as_posix())}
tag=v0.0.0-test
git -C "$worktree" init -q
git -C "$worktree" config core.hooksPath "$worktree/no-hooks"
git -C "$worktree" add modules_update/changelog.md
{command}
git -C "$worktree" -c i18n.logOutputEncoding=UTF-8 log -1 --format=%B
"""
        result = subprocess.run(
            [BASH, "--noprofile", "--norc", "-s"], input=content.encode("utf-8"),
            capture_output=True, env=environment, timeout=20,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.decode("utf-8").strip(), "发布：更新 v0.0.0-test 远程更新信息与日志")


if __name__ == "__main__":
    (ROOT / "build").mkdir(exist_ok=True)
    unittest.main()
