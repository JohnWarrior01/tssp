import base64
import hashlib
import re
import secrets
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CONTEXT = b"tssp-offline-v1"
MAX_BODY = 65536


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(server, action, key, data=b"", token=""):
    url = urlsplit(server)
    if url.scheme != "https" or not url.hostname or url.username or url.query or url.fragment:
        raise ValueError("Expected an HTTPS broker URL")
    locator = hashlib.sha256(CONTEXT + key).hexdigest()
    headers = {"Content-Type": "application/octet-stream"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = Request(f"{server.rstrip('/')}/v1/{action}/{locator}", data=data, headers=headers)
    with build_opener(NoRedirect()).open(req, timeout=40) as response:
        expected = 201 if action == "drop" else 200
        if response.status != expected:
            raise ValueError("Unexpected broker response")
        return response.read(MAX_BODY + 1)


def send(server, token, payload):
    key, nonce = secrets.token_bytes(32), secrets.token_bytes(12)
    request(server, "drop", key, nonce + AESGCM(key).encrypt(nonce, payload, CONTEXT), token)
    return "T1-" + base64.urlsafe_b64encode(key).decode().rstrip("=")


def receive(server, code):
    if not re.fullmatch(r"T1-[A-Za-z0-9_-]{43}", code):
        raise ValueError("Invalid offline code")
    key = base64.urlsafe_b64decode(code[3:] + "=")
    if "T1-" + base64.urlsafe_b64encode(key).decode().rstrip("=") != code:
        raise ValueError("Invalid offline code")
    data = request(server, "take", key)
    if not 29 <= len(data) <= MAX_BODY:
        raise ValueError("Invalid encrypted payload")
    return AESGCM(key).decrypt(data[:12], data[12:], CONTEXT)
