import os
import math
import queue
import re
import secrets
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog, ttk

from twisted.internet import reactor

from vpn_files import read_config, safe_name, save_config
from vpn_protocol import pack
from vpn_settings import TIMEOUT, VERSION
from vpn_updates import UPDATE_ERRORS, download, find_update
from vpn_transfer_session import Transfer
from vpn_text_dialog import text_dialog

WORDS = r"[a-zа-яё0-9]{2,24}"


def normalize_code(value):
    return re.sub(r"[-\s]+", "-", value.strip().lower())


def valid_phrase(value):
    return re.fullmatch(rf"{WORDS}(?:-{WORDS}){{3,7}}", value) is not None


class App:
    def __init__(self, root):
        self.root = root
        self.events = queue.Queue()
        self.transfer = Transfer(self.events)
        self.mode = None
        self.deadline = None
        self.timer = tk.StringVar()
        self.received = None
        self.code_mode = tk.StringVar(value="auto")
        self.code = tk.StringVar()
        self.status = tk.StringVar(value="Оба компьютера должны быть онлайн.")
        root.title("ТССП")
        root.resizable(False, False)
        root.protocol("WM_DELETE_WINDOW", self.close)
        panel = ttk.Frame(root, padding=24)
        panel.grid(sticky="nsew")
        ttk.Label(panel, text="ТССП", font=("Segoe UI", 21, "bold")).grid(
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
        self.code_button = ttk.Button(panel, text="Вставить код", command=self.use_clipboard)
        self.code_button.grid(row=10, column=0, sticky="ew", pady=(8, 0))
        ttk.Label(panel, textvariable=self.timer).grid(row=10, column=1, sticky="e")
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

    def use_clipboard(self):
        if self.mode == "send":
            self.root.clipboard_clear()
            self.root.clipboard_append(self.code.get())
            self.code_button.configure(text="Код скопирован")
            return
        try:
            value = self.root.clipboard_get()
        except tk.TclError:
            self.status.set("В буфере обмена нет текста.")
            return
        self.code.set(normalize_code(value))
        self.entry.focus_set()

    def check_updates(self):
        self.update_button.state(["disabled"])

        def fetch():
            try:
                self.events.put(("update", find_update()))
            except UPDATE_ERRORS:
                self.events.put(("update_error", "Не удалось проверить обновления."))

        threading.Thread(target=fetch, daemon=True).start()

    def show_update(self, update):
        self.update_button.state(["!disabled"])
        if update is None:
            messagebox.showinfo("Обновления", "Установлена актуальная версия.", parent=self.root)
            return
        if not messagebox.askyesno("Обновления", f"Скачать версию {update.tag}?", parent=self.root):
            return
        name = filedialog.asksaveasfilename(parent=self.root, initialfile=update.name, defaultextension=".zip")
        if not name:
            return
        target = Path(name)
        self.update_button.state(["disabled"])
        self.update_button.configure(text="Загрузка… 0%")

        def fetch():
            try:
                download(update, target, lambda percent: self.events.put(("update_progress", percent)))
            except UPDATE_ERRORS:
                self.events.put(("update_error", "Не удалось скачать или проверить архив."))
            else:
                self.events.put(("update_saved", target))

        threading.Thread(target=fetch, daemon=True).start()

    def finish_update(self):
        self.update_button.configure(text="Проверить обновления")
        self.update_button.state(["!disabled"])

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
        text_dialog(self.root, "Отправить текст", None, self.send_payload)

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
            text_dialog(self.root, "Полученный текст", data, self.send_payload)
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
        self.deadline = time.monotonic() + TIMEOUT
        self.code_button.state(["disabled"])
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
        self.code_button.state(["disabled"])
        self.code.set("")
        self.cancel_button.state(["disabled"])
        self.status.set("Остановка передачи…")
        reactor.callFromThread(self.transfer.cancel)

    def finish(self, message):
        self.mode = None
        self.deadline = None
        self.timer.set("")
        self.code_button.configure(text="Вставить код")
        self.code_button.state(["!disabled"])
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
        if self.deadline is not None:
            remaining = max(0, math.ceil(self.deadline - time.monotonic()))
            self.timer.set(f"Осталось {remaining // 60}:{remaining % 60:02d}")
        while not self.events.empty():
            kind, value = self.events.get_nowait()
            if kind == "code" and not self.cancel_button.instate(["disabled"]):
                self.code.set(value)
                self.code_button.configure(text="Скопировать код")
                self.code_button.state(["!disabled"])
                self.entry.selection_range(0, tk.END)
                self.entry.focus_set()
                self.status.set("Продиктуйте получателю число и все слова или скопируйте код (Ctrl+C). Сеанс длится до 5 минут.")
            elif kind == "update":
                self.show_update(value)
            elif kind == "update_progress":
                self.update_button.configure(text=f"Загрузка… {value}%")
            elif kind == "update_error":
                self.finish_update()
                messagebox.showerror("Обновления", value, parent=self.root)
            elif kind == "update_saved":
                self.finish_update()
                if messagebox.askyesno("Обновления", "Архив проверен и сохранён. Открыть папку?", parent=self.root):
                    try:
                        os.startfile(value.parent)
                    except OSError:
                        messagebox.showinfo("Обновления", str(value), parent=self.root)
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
        if self.update_button.cget("text").startswith("Загрузка"):
            messagebox.showinfo("Обновления", "Дождитесь завершения загрузки.", parent=self.root)
            return
        if self.mode and not messagebox.askyesno(
            "ТССП", "Остановить передачу и закрыть окно?", parent=self.root
        ):
            return
        self.root.destroy()
