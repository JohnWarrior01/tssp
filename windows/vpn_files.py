import os
import re

from vpn_settings import MAX_BYTES


def safe_name(name):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")[:120]
    return name or "vpn.conf"


def read_config(path):
    with path.open("rb") as source:
        data = source.read(MAX_BYTES + 1)
    if not 0 < len(data) <= MAX_BYTES:
        raise ValueError("Выберите непустой файл размером до 32 КБ.")
    return data


def save_config(path, data):
    if not 0 < len(data) <= MAX_BYTES:
        raise ValueError("Получен файл недопустимого размера.")
    output = path.open("xb")
    try:
        with output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
    except OSError:
        path.unlink(missing_ok=True)
        raise
