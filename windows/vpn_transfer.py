import logging
import os
import threading
import tkinter as tk
from logging.handlers import RotatingFileHandler
from pathlib import Path

from twisted.internet import reactor

from vpn_protocol import pack
from vpn_transfer_session import Transfer
from vpn_transfer_ui import App


def configure_logging():
    logger = logging.getLogger("vpn_transfer_session")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    try:
        directory = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "VPN Transfer"
        directory.mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(
            directory / "transfer.log", maxBytes=128 * 1024, backupCount=2,
            encoding="utf-8"
        )
    except OSError:
        handler = logging.NullHandler()
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logger.addHandler(handler)


def main():
    configure_logging()
    root = tk.Tk()
    App(root)
    threading.Thread(target=reactor.run, kwargs={"installSignalHandlers": False}, daemon=True).start()
    root.mainloop()


if __name__ == "__main__":
    main()
