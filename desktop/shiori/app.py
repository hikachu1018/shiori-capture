"""Windows desktop companion. Run with `python -m shiori.app`."""
from __future__ import annotations

import base64
import copy
import ctypes
import io
import os
import threading
import time
import uuid
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, simpledialog, ttk

from PIL import Image, ImageTk

from .capture import CaptureSession, build_chapters
from .library import Library, new_book
from .sync import SyncServer


def app_data() -> Path:
    return Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "ShioriCapture"


_instance_handle = None


def acquire_single_instance() -> bool:
    """Prevent two current-version processes from writing the same library."""
    global _instance_handle
    if not hasattr(ctypes, "windll"):
        return True
    kernel = ctypes.windll.kernel32
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p]
    kernel.CreateMutexW.restype = ctypes.c_void_p
    _instance_handle = kernel.CreateMutexW(None, False, "Local\\ShioriCapture-PC")
    if not _instance_handle:
        raise OSError("単一起動の確認に失敗しました")
    if kernel.GetLastError() == 183:
        kernel.CloseHandle(_instance_handle)
        _instance_handle = None
        ctypes.windll.user32.MessageBoxW(None, "しおり Capture PCはすでに起動しています。", "しおり Capture", 0)
        return False
    return True


def repair_missing_chapters(library: Library) -> int:
    """Finish chapter detection for older captures interrupted before finalization."""
    repaired = 0
    for entry in library.index():
        if entry["deleted_at"]:
            continue
        record = library.get(entry["uuid"])
        if not record:
            continue
        book = record[1]
        if (book["paragraphs"] and not book["chapters"] and not book.get("chapters_edited")
                and any(not paragraph.get("hidden") for paragraph in book["paragraphs"])):
            library.update(entry["uuid"], build_chapters)
            repaired += 1
    return repaired


class App:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("しおり Capture PC 0.5.2")
        self.root.geometry("1000x710")
        self.library = Library(app_data() / "books.db")
        repair_missing_chapters(self.library)
        try:
            self.server = SyncServer(self.library, app_data())
        except OSError as exc:
            messagebox.showerror("起動できません", "同期ポートを使用できません。旧版を含む他のしおり Capture PCを終了してください。\n" + str(exc), parent=self.root)
            self.library.close()
            self.root.destroy()
            raise SystemExit(1) from exc
        self.server.start()
        self.session = None
        self.capture_pending = False
        self.selected = None
        self.displayed_book_id = None
        self.photo = None

        bar = ttk.Frame(self.root, padding=10)
        bar.pack(fill="x")
        ttk.Button(bar, text="新しい本", command=self.add_book).pack(side="left")
        ttk.Button(bar, text="撮影・再開", command=self.capture).pack(side="left", padx=5)
        ttk.Button(bar, text="一時停止", command=self.pause).pack(side="left")
        ttk.Button(bar, text="削除", command=self.delete).pack(side="left", padx=5)
        ttk.Button(bar, text="復元", command=self.restore).pack(side="left")
        ttk.Button(bar, text="同じ本を統合", command=self.merge).pack(side="left")
        ttk.Button(bar, text="同期の接続情報", command=self.show_pairing).pack(side="right")
        pane = ttk.Panedwindow(self.root, orient="horizontal")
        pane.pack(fill="both", expand=True, padx=10)
        left = ttk.Frame(pane)
        right = ttk.Frame(pane)
        pane.add(left, weight=1)
        pane.add(right, weight=3)
        self.books = tk.Listbox(left, exportselection=False)
        self.books.pack(fill="both", expand=True)
        self.books.bind("<<ListboxSelect>>", lambda _event: self.select_book())
        self.title = tk.StringVar()
        titlebar = ttk.Frame(right)
        titlebar.pack(fill="x")
        ttk.Entry(titlebar, textvariable=self.title).pack(side="left", fill="x", expand=True)
        ttk.Button(titlebar, text="本名を保存", command=self.save_title).pack(side="left")
        chooser = ttk.Frame(right)
        chooser.pack(fill="x", pady=8)
        ttk.Label(chooser, text="画面").pack(side="left")
        self.screen = ttk.Combobox(chooser, state="readonly", width=15)
        self.screen.pack(side="left", padx=5)
        self.screen.bind("<<ComboboxSelected>>", lambda _event: self.show_screen())
        self.vertical = tk.BooleanVar(value=True)
        ttk.Checkbutton(chooser, text="縦書き", variable=self.vertical).pack(side="left", padx=12)
        ttk.Button(chooser, text="本文を保存", command=self.save_screen).pack(side="right")
        ttk.Label(right, text="OCR本文（画面ごとに修正できます）").pack(anchor="w")
        self.body = tk.Text(right, height=15, wrap="word")
        self.body.pack(fill="both", expand=True)
        chaptersbar = ttk.Frame(right)
        chaptersbar.pack(fill="x", pady=(10, 2))
        ttk.Label(chaptersbar, text="章：開始段落番号 | 章名（1行につき1章）").pack(side="left")
        ttk.Button(chaptersbar, text="章を保存", command=self.save_chapters).pack(side="right")
        self.chapters = tk.Text(right, height=5)
        self.chapters.pack(fill="x")
        self.image = ttk.Label(right, text="表紙・挿絵がある画面では画像を表示します")
        self.image.pack(fill="x", pady=8)
        self.status = tk.StringVar(value="Kindleの本を開いてから撮影を開始してください")
        ttk.Label(self.root, textvariable=self.status, padding=8).pack(fill="x")
        self.refresh()
        self.root.after(5000, self.refresh_periodically)
        self.root.protocol("WM_DELETE_WINDOW", self.close)

    def notice(self, message: str):
        def show():
            self.status.set(message)
            self.refresh()
            if message.startswith("撮影を停止しました:"):
                messagebox.showerror("撮影を停止しました", message.removeprefix("撮影を停止しました: "), parent=self.root)
        self.root.after(0, show)

    def refresh(self):
        selected = self.selected
        self.items = [entry for entry in self.library.index() if not entry["deleted_at"]]
        self.items.sort(key=lambda x: x["title"])
        self.books.delete(0, "end")
        for item in self.items:
            self.books.insert("end", item["title"])
        if selected:
            for i, item in enumerate(self.items):
                if item["uuid"] == selected:
                    self.books.selection_set(i)
                    break
            record = self.library.get(selected)
            if record:
                screens = sorted({p["screen"] for p in record[1]["paragraphs"]})
                current = self.screen.get()
                self.screen["values"] = screens
                if screens and current not in {str(value) for value in screens}:
                    self.screen.set(str(screens[0]))
                    self.show_screen()

    def refresh_periodically(self):
        if not self.root.winfo_exists():
            return
        current = [(item["uuid"], item["revision"], item["deleted_at"]) for item in self.library.index()]
        if current != getattr(self, "last_index", None):
            self.last_index = current
            self.refresh()
        self.root.after(5000, self.refresh_periodically)

    def book(self):
        record = self.library.get(self.selected) if self.selected else None
        return record[1] if record else None

    def add_book(self):
        title = simpledialog.askstring("新しい本", "本の名前（空欄なら自動）", parent=self.root)
        if title is None:
            return
        book = new_book(title)
        self.library.put(book, 0)
        self.selected = book["uuid"]
        self.refresh()
        self.select_book()

    def select_book(self):
        selection = self.books.curselection()
        if selection:
            self.selected = self.items[selection[0]]["uuid"]
        if self.selected == getattr(self, "displayed_book_id", None):
            return
        book = self.book()
        if not book:
            return
        self.displayed_book_id = self.selected
        self.title.set(book["title"])
        screens = sorted({p["screen"] for p in book["paragraphs"]})
        self.screen["values"] = screens
        if screens:
            self.screen.set(str(screens[0]))
        self.chapters.delete("1.0", "end")
        self.chapters.insert("1.0", "\n".join(f'{c["start_seq"]}|{c["title"]}' for c in book["chapters"]))
        self.show_screen()

    def show_screen(self):
        book = self.book()
        if not book:
            return
        try:
            screen = int(self.screen.get())
        except ValueError:
            return
        text = "\n".join(p["text"] for p in book["paragraphs"] if p["screen"] == screen)
        self.body.delete("1.0", "end")
        self.body.insert("1.0", text)
        image = next((item for item in book["images"] if item["screen"] == screen), None)
        if image:
            bitmap = Image.open(io.BytesIO(base64.b64decode(image["data"])))
            bitmap.thumbnail((500, 150))
            self.photo = ImageTk.PhotoImage(bitmap)
            self.image.configure(image=self.photo, text="")
        else:
            self.photo = None
            self.image.configure(image="", text="この画面には保存画像がありません")

    def save_title(self):
        if self.selected and self.title.get().strip():
            self.library.update(self.selected, lambda book: book.update(title=self.title.get().strip()))
            self.refresh()

    def save_screen(self):
        if not self.selected or not self.screen.get():
            return
        screen = int(self.screen.get())
        text = self.body.get("1.0", "end").strip()
        def change(book):
            rows = [p for p in book["paragraphs"] if p["screen"] == screen]
            if not rows:
                return
            rows[0]["text"] = text
            for row in rows[1:]:
                row["text"] = ""
        self.library.update(self.selected, change)
        self.notice("本文を保存しました")

    def save_chapters(self):
        if not self.selected:
            return
        try:
            previous = self.book()["chapters"]
            used = set()
            chapters = []
            for raw in self.chapters.get("1.0", "end").splitlines():
                if not raw.strip():
                    continue
                seq, title = raw.split("|", 1)
                start = int(seq)
                match = next((item for item in previous if item["uuid"] not in used and item["start_seq"] == start), None)
                if match is None:
                    match = next((item for item in previous if item["uuid"] not in used and item["title"] == title.strip()), None)
                identity = match["uuid"] if match else str(uuid.uuid4())
                used.add(identity)
                chapters.append({"uuid": identity, "start_seq": start, "title": title.strip()})
            if any(not c["title"] for c in chapters) or len({c["start_seq"] for c in chapters}) != len(chapters):
                raise ValueError("章名または開始段落が重複しています")
            valid = {p["seq"] for p in self.book()["paragraphs"]}
            if any(c["start_seq"] not in valid for c in chapters):
                raise ValueError("存在しない開始段落があります")
            self.library.update(self.selected, lambda book: book.update(chapters=sorted(chapters, key=lambda c: c["start_seq"]), chapters_edited=True))
            self.notice("章を保存しました")
        except (ValueError, TypeError) as exc:
            messagebox.showerror("章を保存できません", str(exc))

    def capture(self):
        if not self.selected:
            messagebox.showinfo("本を選んでください", "先に『新しい本』を作成してください")
            return
        if self.capture_pending:
            self.status.set("撮影開始まで待機中です。Kindleを前面にしてください")
            return
        if getattr(self, "capture_thread", None) and self.capture_thread.is_alive():
            self.status.set("撮影中です。一時停止してから再開してください")
            return
        self.session = CaptureSession(self.library, self.selected, self.vertical.get(), self.notice)
        self.capture_pending = True
        session = self.session
        self.status.set("5秒後に撮影します。Kindleの読書画面を前面にしてください")
        def launch():
            self.capture_pending = False
            if session.stop.is_set():
                return
            self.capture_thread = threading.Thread(target=session.run, daemon=True, name="shiori-capture")
            self.capture_thread.start()
        self.root.after(5000, launch)

    def pause(self):
        if self.session:
            self.session.request_pause()
            self.status.set("撮影開始を取り消しました" if self.capture_pending else "処理中の画面を保存して停止しています…")

    def delete(self):
        book = self.book()
        if book and messagebox.askyesno("本を削除", f'「{book["title"]}」を両端末から削除しますか？\n30日以内は復元できます。'):
            self.library.update(self.selected, lambda item: item.update(deleted_at=int(time.time() * 1000)))
            self.selected = None
            self.refresh()

    def restore(self):
        cutoff = int(time.time() * 1000) - 30 * 24 * 60 * 60 * 1000
        deleted = [item for item in self.library.index() if item["deleted_at"] > cutoff]
        if not deleted:
            messagebox.showinfo("復元", "復元できる本はありません")
            return
        labels = "\n".join(f'{index + 1}. {item["title"]}' for index, item in enumerate(deleted))
        number = simpledialog.askinteger("本を復元", labels + "\n\n復元する番号", minvalue=1, maxvalue=len(deleted), parent=self.root)
        if number:
            self.library.update(deleted[number - 1]["uuid"], lambda book: book.update(deleted_at=0))
            self.refresh()

    def merge(self):
        book = self.book()
        if not book:
            return
        candidates = [item for item in self.items if item["uuid"] != self.selected and item["title"] == book["title"]]
        if not candidates:
            messagebox.showinfo("統合候補なし", "同じ名前の本はありません。名前を確認してください")
            return
        candidate = candidates[0]
        source = self.library.get(candidate["uuid"])[1]
        preview = "\n".join(p["text"] for p in source["paragraphs"][:12])[:600]
        if not messagebox.askyesno("統合の確認", f'追加元「{candidate["title"]}」の本文冒頭:\n{preview}\n\n重複画面を省き、残りの本文・画像・章を現在の本へ追加しますか？'):
            return
        def append(target):
            seq = max((p["seq"] for p in target["paragraphs"]), default=-1) + 1
            screen = max((p["screen"] for p in target["paragraphs"]), default=0) + 1
            existing = {"\n".join(p["text"] for p in target["paragraphs"] if p["screen"] == n).strip()
                        for n in {p["screen"] for p in target["paragraphs"]}}
            screen_map, seq_map = {}, {}
            for original_screen in sorted({p["screen"] for p in source["paragraphs"]}):
                rows = [p for p in source["paragraphs"] if p["screen"] == original_screen]
                signature = "\n".join(p["text"] for p in rows).strip()
                if signature and signature in existing:
                    continue
                screen_map[original_screen] = screen
                for paragraph in rows:
                    row = copy.deepcopy(paragraph)
                    row["uuid"] = str(uuid.uuid4())
                    row["seq"], row["screen"] = seq, screen
                    seq_map[paragraph["seq"]] = seq
                    target["paragraphs"].append(row)
                    seq += 1
                screen += 1
            for item in source["images"]:
                if item["screen"] in screen_map:
                    row = copy.deepcopy(item)
                    row["uuid"] = str(uuid.uuid4())
                    row["screen"] = screen_map[item["screen"]]
                    row["start_seq"] = seq_map.get(item["start_seq"], row["start_seq"])
                    target["images"].append(row)
            for item in source["chapters"]:
                if item["start_seq"] in seq_map:
                    target["chapters"].append({"uuid": str(uuid.uuid4()), "start_seq": seq_map[item["start_seq"]], "title": item["title"]})
            target["chapters"].sort(key=lambda chapter: chapter["start_seq"])
            target["chapters_edited"] = True
        self.library.update(self.selected, append)
        self.notice("重複を除いた本文・画像・章を追加しました。章境界を確認してください")

    def show_pairing(self):
        value = self.server.pairing_text()
        window = tk.Toplevel(self.root)
        window.title("Galaxyとのペアリング")
        ttk.Label(window, text="Galaxyの『PCと同期』に次の接続情報を貼り付けてください。\n同じWi-Fiに接続し、Windowsファイアウォールではプライベートネットワークを許可します。",
                  padding=15).pack()
        field = ttk.Entry(window, width=105)
        field.insert(0, value)
        field.pack(padx=15, pady=10)
        field.select_range(0, "end")
        try:
            import qrcode
            bitmap = qrcode.make(value)
            self.qr_photo = ImageTk.PhotoImage(bitmap.resize((220, 220)))
            ttk.Label(window, image=self.qr_photo).pack(pady=10)
        except ImportError:
            pass

    def close(self):
        if self.session:
            self.session.request_pause()
        if getattr(self, "capture_thread", None) and self.capture_thread.is_alive():
            self.status.set("保存中の画面を処理しています…")
            self.root.after(500, self.close)
            return
        self.server.close()
        self.library.close()
        self.root.destroy()

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    if acquire_single_instance():
        App().run()
