import base64
import binascii
import json
import os
import queue
import re
import secrets
import threading
import tkinter as tk
import urllib.request
import webbrowser
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog, ttk

import wormhole
from twisted.internet import defer, reactor
from wormhole.cli.public_relay import MAILBOX_RELAY
from wormhole.errors import WrongPasswordError

APP_ID = "6c880aab-d491-4554-b906-645ca0a89d0d/vpn-transfer-v2"
VERSION = (1, 0, 0)
RELEASES = "https://github.com/JohnWarrior01/vpn-transfer/releases/latest"
RELEASE_API = "https://api.github.com/repos/JohnWarrior01/vpn-transfer/releases/latest"
MAX_BYTES = 32 * 1024
TIMEOUT = 300
ACK = b'{"answer":{"message_ack":"ok"}}'
WORDS = r"[a-zа-яё0-9]{2,24}"


def normalize_code(value):
    return re.sub(r"[-\s]+", "-", value.strip().lower())


def valid_phrase(value):
    return re.fullmatch(rf"{WORDS}(?:-{WORDS}){{3,7}}", value) is not None


def pack(kind, data, name=""):
    if kind not in ("text", "file") or not 0 < len(data) <= MAX_BYTES:
        raise ValueError("Размер файла или текста должен быть от 1 байта до 32 КБ.")
    contents = {"kind": kind, "name": name, "data": base64.b64encode(data).decode("ascii")}
    return json.dumps({"offer": {"message": json.dumps(contents)}}).encode("utf-8")


def unpack(message):
    try:
        contents = json.loads(json.loads(message)["offer"]["message"])
        kind, name = contents["kind"], contents["name"]
        data = base64.b64decode(contents["data"], validate=True)
        if kind not in ("text", "file") or not isinstance(name, str) or not 0 < len(data) <= MAX_BYTES:
            raise ValueError
        if kind == "text":
            data = data.decode("utf-8")
        return kind, name, data
    except (ValueError, TypeError, KeyError, binascii.Error):
        raise ValueError("Получены данные в неподдерживаемом формате.") from None


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


class Transfer:
    def __init__(self, events, relay=MAILBOX_RELAY):
        self.events = events
        self.relay = relay
        self.pending = None

    @defer.inlineCallbacks
    def exchange(self, data, code, target):
        channel = wormhole.create(APP_ID, self.relay, reactor)
        try:
            if data is None:
                channel.set_code(code)
            else:
                if code is None:
                    channel.allocate_code(4)
                else:
                    channel.set_code(code)
                self.events.put(("code", (yield channel.get_code())))
            yield channel.get_verifier()
            if data is None:
                payload = unpack((yield channel.get_message()))
                if target is None:
                    self.events.put(("payload", payload))
                else:
                    kind, _, content = payload
                    if kind != "file":
                        raise ValueError("Ожидался файл.")
                    save_config(target, content)
                channel.send_message(ACK)
            else:
                channel.send_message(data)
                answer = json.loads((yield channel.get_message()))
                if answer != {"answer": {"message_ack": "ok"}}:
                    raise ValueError("Получено неверное подтверждение.")
        finally:
            try:
                yield channel.close().addTimeout(4, reactor)
            except Exception:
                pass

    def start(self, data, code, target):
        self.pending = self.exchange(data, code, target)
        self.pending.addTimeout(TIMEOUT, reactor)
        self.pending.addCallbacks(self.done, self.failed)

    def done(self, _):
        self.events.put(("done", None))

    def failed(self, failure):
        if failure.check(defer.CancelledError):
            message = "Передача остановлена."
        elif failure.check(defer.TimeoutError):
            message = "Время ожидания истекло. Начните новую передачу."
        elif failure.check(WrongPasswordError):
            message = "Коды не совпали. Начните новую передачу."
        elif failure.check(FileExistsError):
            message = "Файл уже существует. Выберите другое имя."
        elif failure.check(OSError):
            message = "Не удалось записать файл или подключиться к серверу."
        elif failure.check(ValueError):
            message = str(failure.value)
        else:
            message = "Передача прервалась. Проверьте интернет и повторите с новым кодом."
        self.events.put(("error", message))

    def cancel(self):
        if self.pending is not None:
            self.pending.cancel()


class App:
    def __init__(self, root):
        self.root = root
        self.events = queue.Queue()
        self.transfer = Transfer(self.events)
        self.mode = None
        self.received = None
        self.code_mode = tk.StringVar(value="auto")
        self.code = tk.StringVar()
        self.status = tk.StringVar(value="Оба компьютера должны быть онлайн.")
        root.title("Передача VPN-ключей")
        root.resizable(False, False)
        root.protocol("WM_DELETE_WINDOW", self.close)
        panel = ttk.Frame(root, padding=24)
        panel.grid(sticky="nsew")
        ttk.Label(panel, text="Передача VPN-ключей", font=("Segoe UI", 21, "bold")).grid(
            row=0, columnspan=2, sticky="w"
        )
        ttk.Label(panel, text="Сквозное шифрование · файл или текст до 32 КБ").grid(
            row=1, columnspan=2, sticky="w", pady=(4, 20)
        )
        self.auto_option = ttk.Radiobutton(
            panel, text="Сгенерировать код", variable=self.code_mode, value="auto"
        )
        self.auto_option.grid(row=2, column=0, sticky="w", pady=(0, 8))
        self.custom_option = ttk.Radiobutton(
            panel, text="Задать свою фразу", variable=self.code_mode, value="custom"
        )
        self.custom_option.grid(row=2, column=1, sticky="w", pady=(0, 8))
        self.send_button = ttk.Button(panel, text="Отправить файл", command=self.send)
        self.send_button.grid(row=3, column=0, sticky="ew", padx=(0, 5))
        self.text_button = ttk.Button(panel, text="Отправить текст", command=self.send_text)
        self.text_button.grid(row=3, column=1, sticky="ew", padx=(5, 0))
        ttk.Separator(panel).grid(row=4, columnspan=2, sticky="ew", pady=16)
        ttk.Label(panel, text="Код передачи").grid(row=5, columnspan=2, sticky="w")
        self.entry = ttk.Entry(panel, textvariable=self.code, width=51)
        self.entry.grid(row=6, columnspan=2, sticky="ew", pady=(5, 8))
        self.receive_button = ttk.Button(panel, text="Получить", command=self.receive)
        self.receive_button.grid(row=7, column=0, sticky="ew", padx=(0, 5))
        self.cancel_button = ttk.Button(panel, text="Остановить", command=self.cancel, state="disabled")
        self.cancel_button.grid(row=7, column=1, sticky="ew", padx=(5, 0))
        ttk.Label(panel, textvariable=self.status, wraplength=350).grid(
            row=8, columnspan=2, sticky="w", pady=(16, 0)
        )
        self.update_button = ttk.Button(panel, text="Проверить обновления", command=self.check_updates)
        self.update_button.grid(row=9, column=0, sticky="w", pady=(12, 0))
        ttk.Label(panel, text="Версия " + ".".join(map(str, VERSION))).grid(row=9, column=1, sticky="e", pady=(12, 0))
        panel.columnconfigure((0, 1), weight=1)
        root.after(80, self.poll)

    def check_updates(self):
        self.update_button.state(["disabled"])

        def fetch():
            try:
                with urllib.request.urlopen(RELEASE_API, timeout=8) as response:
                    tag = json.loads(response.read(262144))["tag_name"]
                if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
                    raise ValueError
                version = tuple(map(int, tag[1:].split(".")))
                self.events.put(("update", tag if version > VERSION else ""))
            except (OSError, ValueError, TypeError, KeyError):
                self.events.put(("update", None))

        threading.Thread(target=fetch, daemon=True).start()

    def show_update(self, tag):
        self.update_button.state(["!disabled"])
        if tag is None:
            messagebox.showerror("Обновления", "Не удалось проверить GitHub. Повторите позже.", parent=self.root)
        elif not tag:
            messagebox.showinfo("Обновления", "Установлена актуальная версия.", parent=self.root)
        elif messagebox.askyesno("Обновления", f"Доступна версия {tag}. Открыть страницу загрузки?", parent=self.root):
            webbrowser.open(RELEASES)

    def send(self):
        name = filedialog.askopenfilename(parent=self.root, title="Выберите VPN-конфиг или ключ")
        if not name:
            return
        try:
            data = read_config(Path(name))
        except (OSError, ValueError) as exc:
            message = str(exc) if isinstance(exc, ValueError) else "Не удалось прочитать файл."
            messagebox.showerror("Отправка", message, parent=self.root)
            return
        self.send_payload(pack("file", data, Path(name).name))

    def send_text(self):
        self.text_dialog("Отправить текст", None)

    def text_dialog(self, title, received):
        dialog = tk.Toplevel(self.root)
        dialog.title(title)
        dialog.transient(self.root)
        panel = ttk.Frame(dialog, padding=16)
        panel.pack(fill="both", expand=True)
        editor = tk.Text(panel, width=54, height=10, wrap="word", undo=False)
        editor.pack(fill="both", expand=True, pady=(0, 10))
        if received is None:
            def submit():
                try:
                    payload = pack("text", editor.get("1.0", "end-1c").encode("utf-8"))
                except ValueError as exc:
                    messagebox.showerror(title, str(exc), parent=dialog)
                    return
                dialog.destroy()
                self.send_payload(payload)
            ttk.Button(panel, text="Отправить", command=submit).pack(side="left")
        else:
            editor.insert("1.0", received)
            editor.configure(state="disabled")

            def copy():
                self.root.clipboard_clear()
                self.root.clipboard_append(received)
                copy_button.configure(text="Скопировано")

            copy_button = ttk.Button(panel, text="Копировать", command=copy)
            copy_button.pack(side="left")
        ttk.Button(panel, text="Закрыть", command=dialog.destroy).pack(side="right")
        dialog.grab_set()
        editor.focus_set()

    def send_payload(self, data):
        code = None
        if self.code_mode.get() == "custom":
            phrase = simpledialog.askstring(
                "Своя фраза",
                "Введите 4–8 случайных слов через пробел.\n"
                "Можно использовать русский или английский.\n"
                "Для этого сеанса программа добавит номер.",
                parent=self.root,
            )
            if phrase is None:
                return
            phrase = normalize_code(phrase)
            if not valid_phrase(phrase):
                self.status.set("Нужно 4–8 слов по 2–24 буквы или цифры.")
                return
            number = 10_000_000 + secrets.randbelow(90_000_000)
            code = f"{number}-{phrase}"
        self.code.set("")
        self.start("send", data, code, None)

    def receive(self):
        code = normalize_code(self.code.get())
        number, _, phrase = code.partition("-")
        if not re.fullmatch(r"[0-9]{1,12}", number) or not valid_phrase(phrase):
            self.status.set("Введите код целиком, включая число и все слова.")
            return
        self.start("receive", None, code, None)

    def show_payload(self, payload):
        kind, name, data = payload
        if kind == "text":
            self.text_dialog("Полученный текст", data)
            return
        while True:
            destination = filedialog.asksaveasfilename(
                parent=self.root, title="Сохранить полученный файл",
                initialfile=safe_name(name), confirmoverwrite=False,
            )
            if not destination:
                return
            try:
                save_config(Path(destination), data)
            except OSError:
                messagebox.showerror(
                    "Сохранение", "Выберите новое имя файла и доступную папку.", parent=self.root
                )
            else:
                self.status.set("Файл сохранён.")
                return

    def start(self, mode, data, code, target):
        self.mode = mode
        self.send_button.state(["disabled"])
        self.text_button.state(["disabled"])
        self.auto_option.state(["disabled"])
        self.custom_option.state(["disabled"])
        self.receive_button.state(["disabled"])
        self.cancel_button.state(["!disabled"])
        self.entry.state(["readonly"])
        self.status.set("Подключение к серверу…")
        reactor.callFromThread(self.transfer.start, data, code, target)

    def cancel(self):
        self.code.set("")
        self.cancel_button.state(["disabled"])
        self.status.set("Остановка передачи…")
        reactor.callFromThread(self.transfer.cancel)

    def finish(self, message):
        self.mode = None
        self.code.set("")
        self.entry.state(["!readonly"])
        self.cancel_button.state(["disabled"])
        self.send_button.state(["!disabled"])
        self.text_button.state(["!disabled"])
        self.auto_option.state(["!disabled"])
        self.custom_option.state(["!disabled"])
        self.receive_button.state(["!disabled"])
        self.status.set(message)

    def poll(self):
        while not self.events.empty():
            kind, value = self.events.get_nowait()
            if kind == "code" and not self.cancel_button.instate(["disabled"]):
                self.code.set(value)
                self.entry.selection_range(0, tk.END)
                self.entry.focus_set()
                self.status.set("Продиктуйте получателю число и все слова или скопируйте код (Ctrl+C). Сеанс длится до 5 минут.")
            elif kind == "update":
                self.show_update(value)
            elif kind == "payload":
                self.received = value
            elif kind == "done":
                received = self.received
                self.received = None
                self.finish("Данные получены." if received else "Данные доставлены.")
                if received:
                    self.show_payload(received)
            elif kind == "error":
                self.received = None
                self.finish(value)
        self.root.after(80, self.poll)

    def close(self):
        if self.mode and not messagebox.askyesno(
            "Передача VPN-ключей", "Остановить передачу и закрыть окно?", parent=self.root
        ):
            return
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    App(root)
    threading.Thread(target=reactor.run, kwargs={"installSignalHandlers": False}, daemon=True).start()
    root.mainloop()
