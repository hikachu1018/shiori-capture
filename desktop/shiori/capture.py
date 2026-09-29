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
from concurrent.futures import ThreadPoolExecutor
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


def kindle_window() -> int:
    if USER32 is None:
        raise CaptureUnavailable("Windows 11で実行してください")
    handle = USER32.GetForegroundWindow()
    title = ctypes.create_unicode_buffer(512)
    USER32.GetWindowTextW(handle, title, len(title))
    if "kindle" not in title.value.lower():
        raise CaptureUnavailable("Kindleの読書画面を前面に開いてください")
    return handle


def visible_page(handle: int) -> Image.Image:
    if USER32.GetForegroundWindow() != handle:
        raise CaptureUnavailable("Kindle以外の画面が前面になりました")
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
    pending: int = 0
    stopped: str = ""


class CaptureSession:
    def __init__(self, library: Library, book_id: str, vertical: bool, notice):
        self.library, self.book_id, self.vertical, self.notice = library, book_id, vertical, notice
        self.stop = threading.Event()
        self.state = CaptureState()
        self.ocr = JapaneseOcr()
        self.worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="shiori-ocr")
        self.jobs = []

    def request_pause(self):
        self.stop.set()

    def _process(self, screen: int, image: Image.Image, ui_text: str, fingerprint: str) -> None:
        text = clean_text(ui_text)
        if len(text) < 20:
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
        self.notice(f"{screen}画面を保存しました")

    def run(self):
        try:
            self.handle = kindle_window()
            previous = None
            repeated = 0
            old = self.library.get(self.book_id)
            screen = max((p["screen"] for p in old[1]["paragraphs"]), default=0) if old else 0
            resume_hash = old[1].get("last_fingerprint", "") if old else ""
            while not self.stop.is_set():
                image = visible_page(self.handle)
                fingerprint = page_fingerprint(image)
                if resume_hash:
                    if fingerprint == resume_hash:
                        self.notice("保存済みの最後の画面を読み飛ばしました")
                        USER32.keybd_event(VK_NEXT, 0, 0, 0)
                        USER32.keybd_event(VK_NEXT, 0, KEYEVENTF_KEYUP, 0)
                        time.sleep(.5)
                        previous = resume_hash
                        resume_hash = ""
                        continue
                    resume_hash = ""
                if fingerprint == previous:
                    repeated += 1
                    if repeated >= 2:
                        self.state.stopped = "本の末尾で停止しました"
                        break
                    time.sleep(.6)
                    continue
                repeated = 0
                previous = fingerprint
                screen += 1
                self.state.pages = screen
                ui_text = accessible_text(self.handle)
                self.jobs.append(self.worker.submit(self._process, screen, image, ui_text, fingerprint))
                while len([job for job in self.jobs if not job.done()]) >= 4:
                    if self.stop.wait(.1):
                        break
                if self.stop.is_set():
                    break
                if USER32.GetForegroundWindow() != self.handle:
                    raise CaptureUnavailable("Kindle以外の画面が前面になりました")
                USER32.keybd_event(VK_NEXT, 0, 0, 0)
                USER32.keybd_event(VK_NEXT, 0, KEYEVENTF_KEYUP, 0)
                for _ in range(30):
                    if self.stop.wait(.12):
                        break
                    if page_fingerprint(visible_page(self.handle)) != previous:
                        break
            for job in self.jobs:
                job.result()
            self.library.update(self.book_id, build_chapters)
            if self.state.stopped:
                self.library.update(self.book_id, lambda book: book.update(state="complete"))
            self.notice(self.state.stopped or "一時停止しました。保存済みの画面から再開できます")
        except Exception as exc:
            self.state.stopped = str(exc)
            self.notice("撮影を停止しました: " + str(exc))
        finally:
            self.worker.shutdown(wait=True)
