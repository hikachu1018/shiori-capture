"""Durable PC library. JSON is the versioned on-wire book representation."""
from __future__ import annotations

import copy
import json
import sqlite3
import threading
import time
import uuid
from pathlib import Path


def new_book(title: str) -> dict:
    now = int(time.time() * 1000)
    return {
        "format": 1,
        "uuid": str(uuid.uuid4()),
        "title": title.strip() or time.strftime("撮影した本 %Y/%m/%d %H:%M"),
        "state": "partial",
        "created_at": now,
        "updated_at": now,
        "read_seq": 0,
        "read_offset": 0,
        "deleted_at": 0,
        "last_fingerprint": "",
        "chapters_edited": False,
        "paragraphs": [],
        "chapters": [],
        "images": [],
    }


def validate_book(book: dict) -> None:
    if book.get("format") != 1:
        raise ValueError("未対応の本棚データ形式です")
    try:
        uuid.UUID(book["uuid"])
    except (ValueError, TypeError, KeyError) as exc:
        raise ValueError("本の識別子が正しくありません") from exc
    if not isinstance(book.get("title"), str) or not book["title"].strip():
        raise ValueError("本の名前がありません")
    if book.get("state") not in ("partial", "complete"):
        raise ValueError("本の状態が正しくありません")
    if len(book.get("paragraphs", [])) > 100000 or len(book.get("images", [])) > 10000:
        raise ValueError("本の項目数が多すぎます")
    for key in ("paragraphs", "chapters", "images"):
        if not isinstance(book.get(key), list):
            raise ValueError(key + "が正しくありません")
    for paragraph in book["paragraphs"]:
        if not isinstance(paragraph.get("text"), str) or len(paragraph["text"]) > 50000:
            raise ValueError("段落が正しくありません")
        uuid.UUID(paragraph["uuid"])
    for chapter in book["chapters"]:
        uuid.UUID(chapter["uuid"])
        if not isinstance(chapter.get("title"), str):
            raise ValueError("章名が正しくありません")
    for image in book["images"]:
        uuid.UUID(image["uuid"])
        if image.get("kind") not in ("cover", "illustration", "title"):
            raise ValueError("画像の種類が正しくありません")


class Library:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS books(uuid TEXT PRIMARY KEY, revision INTEGER NOT NULL, body TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS revisions(uuid TEXT NOT NULL,revision INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(uuid,revision))")
        self.db.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        self.db.commit()
        self.lock = threading.RLock()
        self.purge_expired()

    def close(self):
        self.db.close()

    def get(self, book_id: str) -> tuple[int, dict] | None:
        with self.lock:
            row = self.db.execute("SELECT revision,body FROM books WHERE uuid=?", (book_id,)).fetchone()
            return (row[0], json.loads(row[1])) if row else None

    def index(self) -> list[dict]:
        with self.lock:
            rows = self.db.execute("SELECT uuid,revision,body FROM books").fetchall()
        return [{"uuid": key, "revision": rev, "title": json.loads(body)["title"],
                 "deleted_at": json.loads(body).get("deleted_at", 0)} for key, rev, body in rows]

    def put(self, book: dict, base_revision: int | None) -> int:
        validate_book(book)
        with self.lock:
            row = self.db.execute("SELECT revision,body FROM books WHERE uuid=?", (book["uuid"],)).fetchone()
            actual = row[0] if row else 0
            if base_revision is not None and actual != base_revision:
                historical = self.db.execute("SELECT body FROM revisions WHERE uuid=? AND revision=?", (book["uuid"], base_revision)).fetchone()
                if not historical or not _progress_only(book, json.loads(historical[0])):
                    raise Conflict(actual)
                current = json.loads(row[1])
                if current.get("deleted_at"):
                    raise Conflict(actual)
                current["read_seq"] = book.get("read_seq", 0)
                current["read_offset"] = book.get("read_offset", 0)
                current["updated_at"] = max(current["updated_at"] + 1, int(time.time() * 1000))
                book = current
            if row and not book.get("last_fingerprint"):
                old = json.loads(row[1])
                book = copy.deepcopy(book)
                book["last_fingerprint"] = old.get("last_fingerprint", "")
            if row:
                previous = {p["uuid"]: p.get("page_kind") for p in json.loads(row[1])["paragraphs"]}
                for paragraph in book["paragraphs"]:
                    if "page_kind" not in paragraph and previous.get(paragraph["uuid"]):
                        paragraph["page_kind"] = previous[paragraph["uuid"]]
            revision = actual + 1
            if row:
                self.db.execute("INSERT OR IGNORE INTO revisions(uuid,revision,body) VALUES(?,?,?)", (book["uuid"], actual, row[1]))
            self.db.execute("INSERT INTO books(uuid,revision,body) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET revision=excluded.revision,body=excluded.body",
                            (book["uuid"], revision, json.dumps(book, ensure_ascii=False, separators=(",", ":"))))
            self.db.execute("INSERT OR IGNORE INTO revisions(uuid,revision,body) VALUES(?,?,?)", (book["uuid"], revision,
                            json.dumps(book, ensure_ascii=False, separators=(",", ":"))))
            self.db.execute("DELETE FROM revisions WHERE uuid=? AND revision<?", (book["uuid"], revision - 20))
            self.db.commit()
            return revision

    def update(self, book_id: str, transform) -> int:
        with self.lock:
            old = self.get(book_id)
            if old is None:
                raise KeyError(book_id)
            rev, book = old
            book = copy.deepcopy(book)
            transform(book)
            book["updated_at"] = max(int(time.time() * 1000), book["updated_at"] + 1)
            return self.put(book, rev)

    def put_progress(self, book_id: str, base_revision: int, seq: int, offset: int) -> tuple[int, bool]:
        if seq < 0 or offset < 0:
            raise ValueError("読書位置が正しくありません")
        with self.lock:
            record = self.get(book_id)
            if record is None or record[1].get("deleted_at"):
                raise KeyError(book_id)
            revision, book = record
            if base_revision > revision:
                raise Conflict(revision)
            book["read_seq"], book["read_offset"] = seq, offset
            book["updated_at"] = max(int(time.time() * 1000), book["updated_at"] + 1)
            return self.put(book, revision), base_revision != revision

    def get_meta(self, key: str) -> str | None:
        with self.lock:
            row = self.db.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
            return row[0] if row else None

    def set_meta(self, key: str, value: str) -> None:
        with self.lock:
            self.db.execute("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))
            self.db.commit()

    def purge_expired(self) -> None:
        cutoff = int(time.time() * 1000) - 30 * 24 * 60 * 60 * 1000
        with self.lock:
            rows = self.db.execute("SELECT uuid,body FROM books").fetchall()
            for book_id, body in rows:
                book = json.loads(body)
                if book.get("deleted_at", 0) and book["deleted_at"] < cutoff and any(book.get(k) for k in ("paragraphs", "chapters", "images")):
                    self.update(book_id, lambda item: item.update(paragraphs=[], chapters=[], images=[]))


class Conflict(Exception):
    def __init__(self, actual: int):
        super().__init__(f"version conflict: {actual}")
        self.actual = actual


def _progress_only(candidate: dict, base: dict) -> bool:
    if (candidate.get("read_seq", 0), candidate.get("read_offset", 0)) == (base.get("read_seq", 0), base.get("read_offset", 0)):
        return False
    ignored = {"read_seq", "read_offset", "updated_at", "last_fingerprint"}
    def structure(book):
        value = {key: item for key, item in book.items() if key not in ignored}
        value["paragraphs"] = [{key: item for key, item in row.items() if key != "page_kind"}
                               for row in book["paragraphs"]]
        return value
    return structure(candidate) == structure(base)
