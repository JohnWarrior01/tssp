import tkinter as tk
from tkinter import messagebox, ttk

from vpn_protocol import pack


def text_dialog(root, title, received, send_payload):
    dialog = tk.Toplevel(root)
    dialog.title(title)
    dialog.transient(root)
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
            send_payload(payload)
        ttk.Button(panel, text="Отправить", command=submit).pack(side="left")
    else:
        editor.pack_forget()
        hint = ttk.Label(panel, text="Текст скрыт. Его можно скопировать или показать.")
        hint.pack(pady=(0, 12))

        def toggle():
            if editor.winfo_manager():
                editor.pack_forget()
                editor.configure(state="normal")
                editor.delete("1.0", "end")
                hint.pack(pady=(0, 12), before=copy_button)
                reveal.configure(text="Показать")
            else:
                hint.pack_forget()
                editor.insert("1.0", received)
                editor.configure(state="disabled")
                editor.pack(fill="both", expand=True, pady=(0, 10), before=copy_button)
                reveal.configure(text="Скрыть")

        def copy():
            root.clipboard_clear()
            root.clipboard_append(received)
            copy_button.configure(text="Скопировано")

        copy_button = ttk.Button(panel, text="Копировать", command=copy)
        copy_button.pack(side="left")
        reveal = ttk.Button(panel, text="Показать", command=toggle)
        reveal.pack(side="left", padx=8)
    ttk.Button(panel, text="Закрыть", command=dialog.destroy).pack(side="right")
    dialog.grab_set()
    editor.focus_set()
