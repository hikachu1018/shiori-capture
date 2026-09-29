"""Capture visible Kindle for Windows pages without accessing Kindle book files."""
from __future__ import annotations

import base64
import ctypes
import hashlib
import io
import os
import shutil
import sys
import re
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageGrab, ImageOps

from .library import Library

USER32 = ctypes.windll.user32 if hasattr(ctypes, "windll") else None
if USER32 is not None:
    USER32.GetForegroundWindow.restype = ctypes.c_void_p
    USER32.GetWindowTextW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
    USER32.keybd_event.argtypes = [ctypes.c_ubyte, ctypes.c_ubyte, ctypes.c_ulong, ctypes.c_ulong]
VK_NEXT = 0x22  # Page Down, documented by Kindle for Windows.
KEYEVENTF_KEYUP = 0x0002
FOOTER = re.compile(r"(?:章を読み終えるまで|本を読み終えるまで|時間.*本に残っています|\d+\s*%)")
HEADING = re.compile(r"^(?:第[一二三四五六七八九十百0-9０-９]+章|(?:序章|終章|プロローグ|エピローグ))(?:[\s　].*)?$")


class CaptureUnavailable(RuntimeError):
    pass


class _Rect(ctypes.Structure):
    _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long), ("right", ctypes.c_long), ("bottom", ctypes.c_long)]


if USER32 is not None:
    USER32.GetWindowRect.argtypes = [ctypes.c_void_p, ctypes.POINTER(_Rect)]
    USER32.GetWindowThreadProcessId.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]


def window_process_id(handle: int) -> int:
    if USER32 is None:
        return 0
    process_id = ctypes.c_ulong()
    USER32.GetWindowThreadProcessId(handle, ctypes.byref(process_id))
    return process_id.value


def window_executable(handle: int) -> str:
    """Resolve the foreground app even when its window has no caption."""
    process_id = window_process_id(handle)
    if not process_id:
        return ""
    kernel = ctypes.windll.kernel32
    kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.QueryFullProcessImageNameW.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_ulong)]
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    process = kernel.OpenProcess(0x1000, 0, process_id)
    if not process:
        return ""
    try:
        filename = ctypes.create_unicode_buffer(32768)
        size = ctypes.c_ulong(len(filename))
        if kernel.QueryFullProcessImageNameW(process, 0, filename, ctypes.byref(size)):
            return filename.value.rsplit("\\", 1)[-1].lower()
    finally:
        kernel.CloseHandle(process)
    return ""


def kindle_window() -> int:
    if USER32 is None:
        raise CaptureUnavailable("Windows 11で実行してください")
    handle = USER32.GetForegroundWindow()
    if not handle:
        raise CaptureUnavailable("Kindleの読書画面を前面に開いてください")
    title = ctypes.create_unicode_buffer(512)
    USER32.GetWindowTextW(handle, title, len(title))
    if "kindle" not in title.value.lower() and window_executable(handle) != "kindle.exe":
        raise CaptureUnavailable("Kindleの読書画面を前面に開いてください（検出した画面: " + (title.value or "無題") + "）")
    return handle


def active_kindle_window(handle: int, process_id: int = 0) -> int:
    current = USER32.GetForegroundWindow()
    current_process = window_process_id(current) if current else 0
    if (current == handle and (not process_id or current_process == process_id)) or (process_id and current_process == process_id):
        return current
    executable = window_executable(current)
    if executable == "kindle.exe":
        return current
    title = ctypes.create_unicode_buffer(512)
    if current:
        USER32.GetWindowTextW(current, title, len(title))
    found = executable or title.value or "無題"
    raise CaptureUnavailable(f"Kindle以外の画面が前面になりました（{found}）")


def visible_page(handle: int, process_id: int = 0) -> Image.Image:
    handle = active_kindle_window(handle, process_id)
    rect = _Rect()
    if not USER32.GetWindowRect(handle, ctypes.byref(rect)):
        raise CaptureUnavailable("Kindle画面の範囲を取得できません")
    if rect.right - rect.left < 200 or rect.bottom - rect.top < 200:
        raise CaptureUnavailable("Kindle画面が小さすぎます")
    image = ImageGrab.grab(bbox=(rect.left, rect.top, rect.right, rect.bottom), all_screens=True)
    if image.convert("L").getextrema() == (0, 0):
        raise CaptureUnavailable("この画面は撮影できません。Kindleの表示と撮影制限を確認してください")
    return image


def page_fingerprint(image: Image.Image) -> str:
    w, h = image.size
    center = image.crop((int(w * .08), int(h * .10), int(w * .92), int(h * .88)))
    return hashlib.sha256(center.resize((64, 64)).convert("L").tobytes()).hexdigest()


def accessible_text(handle: int) -> str:
    """Prefer text exposed for assistive technology; unavailable titles fall back to OCR."""
    try:
        import uiautomation as uia
        root = uia.ControlFromHandle(handle)
        lines = []
        queue = [(root, 0)]
        deadline = time.monotonic() + 1.5
        scanned = 0
        while queue and scanned < 500 and time.monotonic() < deadline:
            node, depth = queue.pop(0)
            scanned += 1
            if depth > 8:
                continue
            if node.ControlTypeName in ("DocumentControl", "TextControl"):
                try:
                    pattern = node.GetTextPattern()
                    value = pattern.DocumentRange.GetText(-1) if pattern else node.Name
                    if value and len(value.strip()) > 12:
                        lines.append(value.strip())
                except Exception:
                    if node.Name and len(node.Name.strip()) > 12:
                        lines.append(node.Name.strip())
            queue.extend((child, depth + 1) for child in node.GetChildren())
        # A single document is preferable to duplicated child controls.
        return max(lines, key=len) if lines else ""
    except Exception:
        return ""


class JapaneseOcr:
    def __init__(self):
        self.engine = None

    def read(self, image: Image.Image, vertical: bool) -> str:
        cache = Path(os.environ.get("PADDLE_PDX_CACHE_HOME", str(Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "ShioriCapture" / "ocr-models")))
        if getattr(sys, "frozen", False):
            bundled = Path(sys._MEIPASS) / "models" / "official_models"
            if bundled.is_dir():
                for model in bundled.iterdir():
                    target = cache / "official_models" / model.name
                    if not target.is_dir():
                        shutil.copytree(model, target)
            os.environ.setdefault("DISABLE_MODEL_SOURCE_CHECK", "True")
            os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")
        os.environ.setdefault("PADDLE_PDX_CACHE_HOME", str(cache))
        try:
            from paddleocr import PaddleOCR
            import numpy as np
        except ImportError as exc:
            raise CaptureUnavailable("PaddleOCRが未導入です。PC版の依存関係をインストールしてください") from exc
        if self.engine is None:
            self.engine = PaddleOCR(lang="japan", use_textline_orientation=True,
                                    use_doc_orientation_classify=False, use_doc_unwarping=False)
        w, h = image.size
        image = image.crop((int(w * .04), int(h * .08), int(w * .96), int(h * .90)))
        grayscale = image.convert("L")
        if sum(grayscale.resize((8, 8)).getdata()) / 64 < 105:
            image = ImageOps.invert(grayscale).convert("RGB")
        output = self.engine.predict(np.array(image))
        lines = []
        for page in output:
            payload = page.json.get("res", page.json) if hasattr(page, "json") else page
            for text, box in zip(payload.get("rec_texts", []), payload.get("rec_boxes", [])):
                if text and len(box) >= 4:
                    x1, y1, x2, y2 = map(float, box[:4])
                    if y1 >= image.height * .90 or FOOTER.search(text):
                        continue
                    lines.append((x1, y1, x2, text))
        lines.sort(key=(lambda row: (-row[2], row[1])) if vertical else (lambda row: (row[1], row[0])))
        return "\n".join(line[3] for line in lines)


def clean_text(value: str) -> str:
    lines = [line.strip() for line in value.splitlines()]
    return "\n".join(line for line in lines if line and not FOOTER.search(line))


def classify_page(screen: int, text: str, image: Image.Image) -> str | None:
    lines = text.splitlines()
    if screen <= 3 and len(lines) <= 8 and sum(len(x) for x in lines) < 95:
        return "cover" if screen == 1 else "title"
    gray = image.convert("L").resize((64, 64))
    dark = sum(value < 220 for value in gray.getdata()) / (64 * 64)
    if dark > .48 and sum(len(x) for x in lines) < 100:
        return "illustration"
    return None


def make_image(image: Image.Image) -> str:
    thumb = image.copy()
    thumb.thumbnail((1280, 1280))
    buffer = io.BytesIO()
    thumb.save(buffer, "WEBP", quality=80)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def build_chapters(book: dict) -> None:
    """Use TOC wording for confirmed body headings; keep unmatched text for review."""
    if book.get("chapters_edited"):
        return
    paragraphs = book["paragraphs"]
    toc_titles = [p["text"].strip("・.． 　0123456789０１２３４５６７８９") for p in paragraphs
                  if p.get("page_kind") == "toc" and p["text"] not in ("目次", "もくじ")]
    chapters = []
    for paragraph in paragraphs:
        if paragraph.get("hidden"):
            continue
        for line in paragraph["text"].splitlines():
            matched = next((title for title in toc_titles if len(title) >= 3 and (title == line or title in line or line in title)), None)
            if HEADING.match(line) or matched:
                matched = matched or line
                chapters.append({"uuid": str(uuid.uuid4()), "start_seq": paragraph["seq"], "title": matched})
                break
    if not chapters and paragraphs:
        first = next((p for p in paragraphs if not p.get("hidden")), paragraphs[0])
        chapters.append({"uuid": str(uuid.uuid4()), "start_seq": first["seq"], "title": "冒頭"})
    book["chapters"] = chapters


@dataclass
class CaptureState:
    pages: int = 0
    stopped: str = ""


class CaptureSession:
    def __init__(self, library: Library, book_id: str, vertical: bool, notice):
        self.library, self.book_id, self.vertical, self.notice = library, book_id, vertical, notice
        self.stop = threading.Event()
        self.state = CaptureState()
        self.ocr = JapaneseOcr()
        self.pending_path = library.path.parent / "pending" / f"{book_id}.png"

    def request_pause(self):
        self.stop.set()

    def _process(self, screen: int, image: Image.Image, ui_text: str, fingerprint: str) -> None:
        text = clean_text(ui_text)
        previous = self.library.get(self.book_id)[1]
        last_screen = max((p["screen"] for p in previous["paragraphs"]), default=0)
        last_text = "\n".join(p["text"] for p in previous["paragraphs"] if p["screen"] == last_screen)
        if len(text) < 20 or (last_screen and text == last_text):
            text = clean_text(self.ocr.read(image, self.vertical))
        kind = classify_page(screen, text, image)
        lines = [part.strip() for part in text.splitlines() if part.strip()]
        toc = any(line in ("目次", "もくじ") for line in lines[:2])
        def append(book):
            next_seq = max((p["seq"] for p in book["paragraphs"]), default=-1) + 1
            if not lines:
                lines.append("［文字を認識できませんでした］")
            for line in lines:
                book["paragraphs"].append({"uuid": str(uuid.uuid4()), "seq": next_seq,
                    "screen": screen, "text": line, "hidden": bool(kind or toc),
                    "page_kind": "toc" if toc else kind or "body"})
                next_seq += 1
            if kind:
                book["images"].append({"uuid": str(uuid.uuid4()), "screen": screen,
                    "start_seq": next_seq - len(lines), "kind": kind, "data": make_image(image)})
            if screen == 1 and kind == "cover" and lines and book["title"].startswith("撮影した本"):
                book["title"] = lines[0]
            book["state"] = "partial"
            book["last_fingerprint"] = fingerprint
        self.library.update(self.book_id, append)
        saved = self.library.get(self.book_id)[1]
        if saved.get("last_fingerprint") != fingerprint or screen not in {p["screen"] for p in saved["paragraphs"]}:
            raise CaptureUnavailable(f"{screen}画面の保存を確認できませんでした")
        self.state.pages = len({p["screen"] for p in saved["paragraphs"]})
        self.notice(f"保存済み {self.state.pages}画面・処理待ち 0画面")

    def _stage(self, image: Image.Image) -> None:
        self.pending_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.pending_path.with_suffix(".tmp")
        with temporary.open("wb") as output:
            image.save(output, "PNG")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, self.pending_path)

    def _save_page(self, screen: int, image: Image.Image, fingerprint: str, recovered: bool = False) -> None:
        self._stage(image)
        self.notice(f"{screen}画面を処理中・保存済み {self.state.pages}画面・処理待ち 1画面")
        self.handle = active_kindle_window(self.handle, self.process_id)
        self._process(screen, image, "" if recovered else accessible_text(self.handle), fingerprint)
        self.pending_path.unlink()

    def _turn_page(self, previous: str) -> Image.Image | None:
        """Return a settled new page, or None after two unchanged turns."""
        for _attempt in range(2):
            self.handle = active_kindle_window(self.handle, self.process_id)
            USER32.keybd_event(VK_NEXT, 0, 0, 0)
            USER32.keybd_event(VK_NEXT, 0, KEYEVENTF_KEYUP, 0)
            deadline = time.monotonic() + 4
            candidate = None
            while time.monotonic() < deadline and not self.stop.is_set():
                if self.stop.wait(.18):
                    return None
                image = visible_page(self.handle, self.process_id)
                fingerprint = page_fingerprint(image)
                if fingerprint != previous:
                    if candidate == fingerprint:
                        return image
                    candidate = fingerprint
                else:
                    candidate = None
            if candidate is not None:
                raise CaptureUnavailable("ページ切り替え中の表示が安定しませんでした。保存済み画面から再開してください")
        return None

    def run(self):
        try:
            self.handle = kindle_window()
            self.process_id = window_process_id(self.handle)
            old = self.library.get(self.book_id)
            screen = max((p["screen"] for p in old[1]["paragraphs"]), default=0) if old else 0
            self.state.pages = len({p["screen"] for p in old[1]["paragraphs"]}) if old else 0
            resume_hash = old[1].get("last_fingerprint", "") if old else ""
            if self.pending_path.exists():
                with Image.open(self.pending_path) as staged:
                    image = staged.copy()
                fingerprint = page_fingerprint(image)
                if fingerprint != resume_hash:
                    self._save_page(screen + 1, image, fingerprint, recovered=True)
                    screen += 1
                    resume_hash = fingerprint
                    self.handle = active_kindle_window(self.handle, self.process_id)
                    if page_fingerprint(visible_page(self.handle, self.process_id)) != fingerprint:
                        raise CaptureUnavailable("処理待ちの画面は保存しました。Kindleが別のページを表示しているため、位置を確認して再開してください")
                else:
                    self.pending_path.unlink()
            next_image = None
            while not self.stop.is_set():
                self.handle = active_kindle_window(self.handle, self.process_id)
                image = next_image if next_image is not None else visible_page(self.handle, self.process_id)
                next_image = None
                fingerprint = page_fingerprint(image)
                if resume_hash and fingerprint == resume_hash:
                    next_image = self._turn_page(resume_hash)
                    if next_image is None:
                        if not self.stop.is_set():
                            self.state.stopped = "本の末尾とみなして停止しました（ページが変わりません）"
                        break
                    resume_hash = ""
                    continue
                screen += 1
                self._save_page(screen, image, fingerprint)
                resume_hash = fingerprint
                if self.stop.is_set():
                    break
                next_image = self._turn_page(fingerprint)
                if next_image is None:
                    if not self.stop.is_set():
                        self.state.stopped = "本の末尾とみなして停止しました（ページが変わりません）"
                    break
            self.library.update(self.book_id, build_chapters)
            if self.state.stopped:
                self.library.update(self.book_id, lambda book: book.update(state="complete"))
            self.notice(self.state.stopped or "一時停止しました。保存済みの画面から再開できます")
        except Exception as exc:
            self.state.stopped = str(exc)
            self.notice(f"撮影を停止しました: {exc}（保存済み {self.state.pages}画面・処理待ち {int(self.pending_path.exists())}画面）")
