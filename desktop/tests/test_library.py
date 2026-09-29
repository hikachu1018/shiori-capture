import copy
import itertools
import json
import ssl
import tempfile
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from unittest import mock

from PIL import Image

from shiori.app import App, acquire_single_instance
from shiori.capture import CaptureSession, CaptureUnavailable, active_kindle_window, build_chapters, clean_text, kindle_window
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

    def test_pairing_dialog_renews_expired_code(self):
        server = SyncServer(self.library, self.path, port=0)
        server.start()
        try:
            server.pair_code = "expired"
            server.pair_failures = 10
            details = server.pairing_text()
            self.assertEqual(server.pair_failures, 0)
            self.assertEqual(len(details.split("|")[-1]), 6)
            context = ssl._create_unverified_context()
            base = f"https://127.0.0.1:{server.port}"
            with urllib.request.urlopen(base + "/v1/pair?code=" + server.pair_code, context=context) as response:
                self.assertTrue(json.load(response)["token"])
        finally:
            server.close()

    def test_capture_stops_after_unchanged_final_page(self):
        book = new_book("末尾の検証")
        self.library.put(book, 0)
        colors = ("white", "gray", "black")
        page = [0]
        turns = []
        def visible(_handle, _process_id=0):
            return Image.new("RGB", (300, 300), colors[page[0]])
        def key(code, _scan, flags, _extra):
            if flags == 0:
                saved = self.library.get(book["uuid"])[1]
                turns.append(len({p["screen"] for p in saved["paragraphs"]}))
                page[0] = min(page[0] + 1, 2)
        messages = []
        session = CaptureSession(self.library, book["uuid"], True, messages.append)
        session.stop.wait = lambda _delay: False
        window = mock.Mock()
        window.keybd_event.side_effect = key
        with mock.patch("shiori.capture.kindle_window", return_value=1), \
             mock.patch("shiori.capture.window_process_id", return_value=123), \
             mock.patch("shiori.capture.active_kindle_window", return_value=1), \
             mock.patch("shiori.capture.visible_page", side_effect=visible), \
             mock.patch("shiori.capture.accessible_text", side_effect=lambda _handle: f"{page[0] + 1}画面目の本文を模擬した、二十文字以上の検証用テキストです。"), \
             mock.patch("shiori.capture.USER32", window), \
             mock.patch("shiori.capture.time.monotonic", side_effect=(x * .5 for x in itertools.count())):
            session.run()
        saved = self.library.get(book["uuid"])[1]
        self.assertEqual(saved["state"], "complete")
        self.assertEqual({row["screen"] for row in saved["paragraphs"]}, {1, 2, 3})
        self.assertEqual(turns[:3], [1, 2, 3])
        self.assertFalse(session.pending_path.exists())
        self.assertTrue(any("末尾" in message for message in messages))

    def test_second_page_ocr_failure_does_not_turn_and_recovers_staged_page(self):
        book = new_book("保存の検証")
        self.library.put(book, 0)
        page = [0]
        turns = []
        window = mock.Mock()
        def key(_code, _scan, flags, _extra):
            if flags == 0:
                turns.append(len({p["screen"] for p in self.library.get(book["uuid"])[1]["paragraphs"]}))
                page[0] = min(page[0] + 1, 1)
        window.keybd_event.side_effect = key
        def visible(_handle, _process_id=0):
            return Image.new("RGB", (300, 300), "white" if page[0] == 0 else "black")
        messages = []
        with mock.patch("shiori.capture.kindle_window", return_value=1), \
             mock.patch("shiori.capture.window_process_id", return_value=123), \
             mock.patch("shiori.capture.active_kindle_window", return_value=1), \
             mock.patch("shiori.capture.visible_page", side_effect=visible), \
             mock.patch("shiori.capture.accessible_text", side_effect=lambda _handle: "最初の画面にある長い文章です。これは保存を検証するための文章です。" if page[0] == 0 else ""), \
             mock.patch("shiori.capture.USER32", window), \
             mock.patch("shiori.capture.time.monotonic", side_effect=(x * .5 for x in itertools.count())), \
             mock.patch("shiori.capture.JapaneseOcr.read", side_effect=RuntimeError("OCR失敗")):
            failed = CaptureSession(self.library, book["uuid"], True, messages.append)
            failed.stop.wait = lambda _delay: False
            failed.run()
        self.assertEqual(turns, [1])
        self.assertEqual({p["screen"] for p in self.library.get(book["uuid"])[1]["paragraphs"]}, {1})
        self.assertTrue(failed.pending_path.exists())
        self.assertTrue(any("OCR失敗" in message for message in messages))
        with mock.patch("shiori.capture.kindle_window", return_value=1), \
             mock.patch("shiori.capture.window_process_id", return_value=123), \
             mock.patch("shiori.capture.active_kindle_window", return_value=1), \
             mock.patch("shiori.capture.visible_page", side_effect=visible), \
             mock.patch("shiori.capture.accessible_text", return_value=""), \
             mock.patch("shiori.capture.USER32", window), \
             mock.patch("shiori.capture.time.monotonic", side_effect=(x * .5 for x in itertools.count())), \
             mock.patch("shiori.capture.JapaneseOcr.read", return_value="二番目の画面は復旧後に保存される本文です。これは十分長い文章です。"):
            resumed = CaptureSession(self.library, book["uuid"], True, messages.append)
            resumed.stop.wait = lambda _delay: False
            resumed.run()
        self.assertEqual({p["screen"] for p in self.library.get(book["uuid"])[1]["paragraphs"]}, {1, 2})
        self.assertEqual(turns[1], 2)
        self.assertFalse(resumed.pending_path.exists())

    def test_refresh_updates_screen_choices_after_capture(self):
        book = new_book("一覧の検証")
        self.library.put(book, 0)
        app = App.__new__(App)
        app.library = self.library
        app.selected = book["uuid"]
        app.items = []
        app.books = mock.Mock()
        app.screen = mock.MagicMock()
        app.screen.get.return_value = "1"
        app.show_screen = mock.Mock()
        self.library.update(book["uuid"], lambda current: current["paragraphs"].extend([
            {"uuid": str(uuid.uuid4()), "seq": i, "screen": i + 1, "text": str(i), "hidden": False}
            for i in range(3)
        ]))
        app.refresh()
        self.assertEqual(app.screen.__setitem__.call_args.args, ("values", [1, 2, 3]))

    def test_reselecting_same_book_does_not_reset_preview(self):
        app = App.__new__(App)
        app.selected = "same-book"
        app.displayed_book_id = "same-book"
        app.books = mock.Mock()
        app.books.curselection.return_value = ()
        app.book = mock.Mock()
        app.title = mock.Mock()
        app.screen = mock.Mock()
        app.select_book()
        app.book.assert_not_called()
        app.screen.set.assert_not_called()

    def test_kindle_with_empty_window_title_is_detected_by_process(self):
        with mock.patch("shiori.capture.USER32") as user, \
             mock.patch("shiori.capture.window_executable", return_value="kindle.exe"):
            user.GetForegroundWindow.return_value = 123
            user.GetWindowTextW.side_effect = lambda _handle, title, _size: setattr(title, "value", "")
            self.assertEqual(kindle_window(), 123)

    def test_kindle_window_change_and_foreign_focus(self):
        with mock.patch("shiori.capture.USER32") as user, \
             mock.patch("shiori.capture.window_process_id", side_effect=lambda handle: {1: 77, 2: 77, 3: 88}[handle]), \
             mock.patch("shiori.capture.window_executable", side_effect=lambda handle: "shiori.exe" if handle == 3 else "kindle.exe"):
            user.GetForegroundWindow.return_value = 2
            self.assertEqual(active_kindle_window(1, 77), 2)
            user.GetForegroundWindow.return_value = 3
            with self.assertRaisesRegex(CaptureUnavailable, "Kindle以外"):
                active_kindle_window(1, 77)

    def test_duplicate_pc_instance_is_rejected(self):
        with mock.patch("shiori.app.ctypes.windll") as system:
            system.kernel32.CreateMutexW.return_value = 321
            system.kernel32.GetLastError.return_value = 183
            self.assertFalse(acquire_single_instance())
            system.kernel32.CloseHandle.assert_called_once_with(321)
            system.user32.MessageBoxW.assert_called_once()

    def test_old_pc_instance_port_conflict_is_reported(self):
        with mock.patch("shiori.app.tk.Tk") as root, \
             mock.patch("shiori.app.Library") as library, \
             mock.patch("shiori.app.SyncServer", side_effect=OSError("port busy")), \
             mock.patch("shiori.app.messagebox.showerror") as show_error:
            with self.assertRaises(SystemExit):
                App()
            self.assertIn("旧版", show_error.call_args.args[1])
            library.return_value.close.assert_called_once()
            root.return_value.destroy.assert_called_once()

    def test_repeated_accessibility_text_uses_ocr_for_new_page(self):
        book = new_book("本文の検証")
        self.library.put(book, 0)
        session = CaptureSession(self.library, book["uuid"], True, lambda _message: None)
        first = "同じアクセシビリティ文字列が残る場合の検証用本文です。"
        image = Image.new("RGB", (300, 300), "white")
        session._process(1, image, first, "first")
        with mock.patch.object(session.ocr, "read", return_value="二画面目には異なる本文があります。これが実際の内容です。") as ocr:
            session._process(2, image, first, "second")
        ocr.assert_called_once()
        saved = self.library.get(book["uuid"])[1]
        self.assertIn("二画面目", "".join(p["text"] for p in saved["paragraphs"] if p["screen"] == 2))

    def test_capture_button_can_retry_after_failed_thread(self):
        app = App.__new__(App)
        app.selected = "book-id"
        app.capture_pending = False
        app.library = self.library
        app.vertical = mock.Mock()
        app.vertical.get.return_value = True
        app.status = mock.Mock()
        app.root = mock.Mock()
        app.notice = lambda _message: None
        app.session = None
        app.capture_thread = None
        sessions = [mock.Mock(), mock.Mock()]
        for session in sessions:
            session.stop.is_set.return_value = False
        thread = mock.Mock()
        thread.is_alive.return_value = False
        with mock.patch("shiori.app.CaptureSession", side_effect=sessions) as session_factory, \
             mock.patch("shiori.app.threading.Thread", return_value=thread):
            app.capture()
            app.root.after.call_args.args[1]()
            app.capture()
            self.assertEqual(session_factory.call_count, 2)


if __name__ == "__main__":
    unittest.main()
