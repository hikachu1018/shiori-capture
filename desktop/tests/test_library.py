import copy
import json
import ssl
import tempfile
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path

from shiori.capture import build_chapters, clean_text
from shiori.library import Conflict, Library, new_book
from shiori.sync import SyncServer


class LibraryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.library = Library(self.path / "books.db")

    def tearDown(self):
        self.library.close()
        self.temp.cleanup()

    def test_revision_prevents_silent_overwrite(self):
        book = new_book("検証")
        self.assertEqual(self.library.put(book, 0), 1)
        changed = copy.deepcopy(book)
        changed["title"] = "編集後"
        self.assertEqual(self.library.put(changed, 1), 2)
        with self.assertRaises(Conflict):
            self.library.put(book, 1)
        self.assertEqual(self.library.get(book["uuid"])[1]["title"], "編集後")

    def test_stale_reading_progress_merges_with_pc_edit(self):
        book = new_book("本")
        self.library.put(book, 0)
        self.library.update(book["uuid"], lambda current: current.update(title="PCで修正"))
        stale = copy.deepcopy(book)
        stale["read_seq"] = 9
        revision = self.library.put(stale, 1)
        result = self.library.get(book["uuid"])[1]
        self.assertEqual(revision, 3)
        self.assertEqual(result["title"], "PCで修正")
        self.assertEqual(result["read_seq"], 9)

    def test_small_progress_update_does_not_replace_pc_content(self):
        book = new_book("元の題名")
        self.library.put(book, 0)
        self.library.update(book["uuid"], lambda item: item.update(title="PCで変更"))
        revision, changed = self.library.put_progress(book["uuid"], 1, 7, 2)
        self.assertEqual(revision, 3)
        self.assertTrue(changed)
        result = self.library.get(book["uuid"])[1]
        self.assertEqual((result["title"], result["read_seq"], result["read_offset"]), ("PCで変更", 7, 2))

    def test_toc_title_maps_to_body_not_navigation(self):
        book = new_book("本")
        book["paragraphs"] = [
            {"uuid": str(uuid.uuid4()), "seq": 0, "screen": 1, "text": "目次", "hidden": True, "page_kind": "toc"},
            {"uuid": str(uuid.uuid4()), "seq": 1, "screen": 1, "text": "第一章 はじまり", "hidden": True, "page_kind": "toc"},
            {"uuid": str(uuid.uuid4()), "seq": 2, "screen": 2, "text": "第一章 はじまり", "hidden": False},
            {"uuid": str(uuid.uuid4()), "seq": 3, "screen": 2, "text": "本文", "hidden": False},
        ]
        build_chapters(book)
        self.assertEqual(book["chapters"][0]["start_seq"], 2)
        self.assertEqual(book["chapters"][0]["title"], "第一章 はじまり")

    def test_footer_removed(self):
        self.assertEqual(clean_text("本文\n章を読み終えるまで: 5分 42%\n続き"), "本文\n続き")

    def test_pair_and_conflict_over_pinned_tls(self):
        server = SyncServer(self.library, self.path, port=0)
        server.start()
        context = ssl._create_unverified_context()
        base = f"https://127.0.0.1:{server.port}"
        try:
            with urllib.request.urlopen(base + "/v1/pair?code=" + server.pair_code, context=context) as response:
                token = json.load(response)["token"]
            book = new_book("同期")
            path = base + "/v1/books/" + book["uuid"]
            payload = json.dumps({"base_revision": 0, "book": book}).encode()
            request = urllib.request.Request(path, payload, {"Authorization": "Bearer " + token}, method="PUT")
            with urllib.request.urlopen(request, context=context) as response:
                self.assertEqual(json.load(response)["revision"], 1)
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request, context=context)
            self.assertEqual(error.exception.code, 409)
            index = urllib.request.Request(base + "/v1/books", headers={"Authorization": "Bearer " + token})
            with urllib.request.urlopen(index, context=context) as response:
                self.assertEqual(json.load(response)["books"][0]["uuid"], book["uuid"])
        finally:
            server.close()


if __name__ == "__main__":
    unittest.main()
