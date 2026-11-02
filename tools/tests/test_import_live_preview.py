"""Integration checks for the read-only mytv importer; uses only a local fixture server."""
import importlib.util
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.parse import parse_qs

SCRIPT = Path(__file__).resolve().parents[1] / "import-live-preview.py"


class ImportLivePreviewTest(unittest.TestCase):
    def setUp(self):
        self.requests = []
        self.index = 0
        test = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                test.requests.append(("GET", self.path, None))
                if self.path == "/api/configs":
                    data = json.dumps({"iptvSourceList": {"value": [{"url": test.base + "/fixture.m3u", "sourceType": 0}]},
                                       "iptvSourceCurrentIdx": test.index}).encode()
                else:
                    data = b"#EXTM3U\n#EXTINF:-1,Fixture News\nhttp://192.0.2.2/live.ts\n"
                self.send_response(200); self.end_headers(); self.wfile.write(data)

            def do_POST(self):
                data = parse_qs(self.rfile.read(int(self.headers["Content-Length"])).decode())
                test.requests.append(("POST", self.path, data))
                self.send_response(200); self.end_headers(); self.wfile.write(b'{"saved":true}')

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.base = "http://127.0.0.1:" + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.temp = tempfile.TemporaryDirectory()
        self.url_file = Path(self.temp.name) / "phone-url.txt"
        self.url_file.write_text(self.base + "/private-fixture-token/")

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.temp.cleanup()

    def invoke(self):
        return subprocess.run([sys.executable, str(SCRIPT), self.base, "--phone-url-file", str(self.url_file)],
                              capture_output=True, text=True, timeout=8)

    def test_reads_existing_source_and_posts_only_to_aerial(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        posts = [(path, data) for method, path, data in self.requests if method == "POST"]
        self.assertEqual(["/private-fixture-token/api/content"], [p for p, _ in posts])
        self.assertEqual(["com.github.mytv.android"], posts[0][1]["app"])
        self.assertIn("Fixture News", posts[0][1]["snapshot"][0])
        self.assertNotIn("192.0.2.2", result.stdout)
        self.assertFalse(any(method == "POST" and path == "/api/configs" for method, path, _ in self.requests))

    def test_missing_selected_source_never_overwrites_aerial(self):
        self.index = 99
        result = self.invoke()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(method == "POST" for method, _, _ in self.requests))


if __name__ == "__main__":
    unittest.main()
