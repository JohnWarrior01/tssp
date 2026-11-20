import base64
import hmac
import json
import os
import re
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

MAX_BODY = 65536
TTL = 86400
ROUTE = re.compile(r"/v1/(drop|take)/([a-f0-9]{64})")


class Broker(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def setup(self):
        self.request.settimeout(15)
        super().setup()

    def reply(self, status, body=b""):
        self.send_response(status)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_POST(self):
        route = ROUTE.fullmatch(self.path)
        if not route:
            return self.reply(404)
        action, locator = route.groups()
        try:
            if action == "drop":
                if not hmac.compare_digest(
                    self.headers.get("Authorization", "").encode(), self.server.upload_auth
                ):
                    return self.reply(401)
                size = int(self.headers.get("Content-Length", "0"))
                if not 29 <= size <= MAX_BODY:
                    return self.reply(413)
                data = self.rfile.read(size)
                if len(data) != size:
                    return self.reply(400)
                record = str(int(time.time()) + TTL).encode() + b"\n" + data
                self.github("PUT", locator, {
                    "message": "Store encrypted transfer",
                    "content": base64.b64encode(record).decode(),
                })
                return self.reply(201)

            entry = self.github("GET", locator)
            expiry, data = base64.b64decode(entry["content"]).split(b"\n", 1)
            self.github("DELETE", locator, {"message": "Consume encrypted transfer", "sha": entry["sha"]})
            if int(expiry) <= time.time():
                return self.reply(410)
            self.reply(200, data)
        except HTTPError as error:
            self.reply(error.code if action == "take" and error.code == 404 else 502)
        except (URLError, ValueError, KeyError, TimeoutError):
            self.reply(502)

    def github(self, method, locator, payload=None):
        request = Request(
            f"{self.server.repo_url}/contents/transfers/{locator[:2]}/{locator}.bin",
            method=method, data=None if payload is None else json.dumps(payload).encode(),
            headers={
                "Authorization": self.server.github_auth,
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
                "User-Agent": "tssp-broker",
            },
        )
        with urlopen(request, timeout=15) as response:
            return json.load(response)


def main():
    repo = os.environ["TSSP_GITHUB_REPO"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Expected OWNER/REPO")
    github_token = os.environ["TSSP_GITHUB_TOKEN"]
    upload_token = os.environ["TSSP_UPLOAD_TOKEN"]
    if not github_token or len(upload_token) < 24:
        raise ValueError("Configure GitHub and upload credentials")
    server = HTTPServer(("127.0.0.1", 8765), Broker)
    server.repo_url = f"https://api.github.com/repos/{repo}"
    server.github_auth = "Bearer " + github_token
    server.upload_auth = ("Bearer " + upload_token).encode()
    with urlopen(Request(server.repo_url, headers={
        "Authorization": server.github_auth, "User-Agent": "tssp-broker"
    }), timeout=15) as response:
        if json.load(response).get("private") is not True:
            raise ValueError("Storage repository must be private")
    server.serve_forever()


if __name__ == "__main__":
    main()
