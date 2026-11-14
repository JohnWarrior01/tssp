import hashlib
import http.client
import json
import os
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path
from urllib.request import urlopen

from vpn_settings import RELEASE_API, VERSION

REPOSITORY = "https://github.com/JohnWarrior01/tssp"


@dataclass(frozen=True)
class Update:
    tag: str
    name: str
    digest: str
    size: int

    @property
    def url(self):
        return f"{REPOSITORY}/releases/download/{self.tag}/{self.name}"


def find_update():
    with urlopen(RELEASE_API, timeout=15) as response:
        release = json.loads(response.read(262144))
    tag = release["tag_name"]
    if not re.fullmatch(r"v[0-9]{1,8}\.[0-9]{1,8}\.[0-9]{1,8}", tag):
        raise ValueError("invalid release tag")
    if tuple(map(int, tag[1:].split("."))) <= VERSION:
        return None
    for asset in release["assets"]:
        name = asset["name"]
        if name not in ("TSSP-Windows-x64.zip", f"TSSP-Windows-x64-{tag[1:]}.zip"):
            continue
        digest, size = asset.get("digest") or "", asset["size"]
        if asset["state"] == "uploaded" and re.fullmatch(r"sha256:[a-f0-9]{64}", digest) and 0 < size <= 256 * 1024**2:
            return Update(tag, name, digest[7:], size)
    raise ValueError("missing verified asset")


def download(update, destination, progress):
    digest = hashlib.sha256()
    received = 0
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
        temporary = Path(output.name)
    try:
        with urlopen(update.url, timeout=20) as response, temporary.open("wb") as output:
            while chunk := response.read(128 * 1024):
                received += len(chunk)
                if received > update.size:
                    raise ValueError("invalid download size")
                output.write(chunk)
                digest.update(chunk)
                progress(received * 100 // update.size)
        if received != update.size or digest.hexdigest() != update.digest:
            raise ValueError("invalid download hash")
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


UPDATE_ERRORS = (OSError, http.client.HTTPException, ValueError, TypeError, KeyError)
