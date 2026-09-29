"""Paired, certificate-pinned local network sync server."""
from __future__ import annotations

import hashlib
import ipaddress
import json
import secrets
import socket
import ssl
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from .library import Conflict, Library, validate_book

MAX_BODY = 32 * 1024 * 1024


def certificate(paths: Path) -> tuple[Path, Path, str]:
    paths.mkdir(parents=True, exist_ok=True)
    cert_file, key_file = paths / "sync-cert.pem", paths / "sync-key.pem"
    if not cert_file.exists() or not key_file.exists():
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Shiori Capture PC")])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
                .public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=3650))
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                .sign(key, hashes.SHA256()))
        key_file.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
                                              serialization.NoEncryption()))
        cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    cert = x509.load_pem_x509_certificate(cert_file.read_bytes())
    return cert_file, key_file, hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()


def lan_address() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.connect(("192.0.2.1", 9))  # Route lookup only; no packet is sent.
            address = sock.getsockname()[0]
            if ipaddress.ip_address(address).is_private:
                return address
        except OSError:
            pass
    return "127.0.0.1"


class SyncServer:
    def __init__(self, library: Library, data_dir: Path, port: int = 8765):
        self.library = library
        self.cert_file, self.key_file, self.fingerprint = certificate(data_dir)
        self.port = port
        self.pair_code = f"{secrets.randbelow(1000000):06d}"
        self.pair_deadline = datetime.now(timezone.utc) + timedelta(minutes=5)
        self.pair_failures = 0
        self.httpd = ThreadingHTTPServer(("0.0.0.0", port), self._handler())
        self.port = self.httpd.server_address[1]
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.cert_file, self.key_file)
        self.httpd.socket = context.wrap_socket(self.httpd.socket, server_side=True)
        self.thread = None

    def pairing_text(self) -> str:
        # Showing the dialog is the explicit start of a new five-minute pairing window.
        self.pair_code = f"{secrets.randbelow(1000000):06d}"
        self.pair_deadline = datetime.now(timezone.utc) + timedelta(minutes=5)
        self.pair_failures = 0
        return f"{lan_address()}:{self.port}|{self.fingerprint}|{self.pair_code}"

    def start(self):
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True, name="shiori-sync")
        self.thread.start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def _handler(self):
        parent = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def reply(self, code: int, data: dict):
                body = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def authorized(self) -> bool:
                token = parent.library.get_meta("pair_token")
                if token and secrets.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
                    return True
                self.reply(401, {"error": "ペアリングが必要です"})
                return False

            def do_GET(self):
                path = urlsplit(self.path)
                if path.path == "/v1/pair":
                    code = parse_qs(path.query).get("code", [""])[0]
                    if (parent.pair_failures >= 10 or datetime.now(timezone.utc) > parent.pair_deadline
                            or not parent.pair_code or not secrets.compare_digest(code, parent.pair_code)):
                        parent.pair_failures += 1
                        self.reply(403, {"error": "コードが正しくないか、有効期限を過ぎました"})
                        return
                    token = secrets.token_urlsafe(32)
                    parent.library.set_meta("pair_token", token)
                    parent.pair_code = ""
                    self.reply(200, {"token": token, "format": 1})
                    return
                if not self.authorized():
                    return
                if path.path == "/v1/books":
                    self.reply(200, {"format": 1, "books": parent.library.index()})
                elif path.path.startswith("/v1/books/"):
                    record = parent.library.get(path.path.removeprefix("/v1/books/"))
                    self.reply(200, {"revision": record[0], "book": record[1]}) if record else self.reply(404, {"error": "本がありません"})
                else:
                    self.reply(404, {"error": "見つかりません"})

            def do_PUT(self):
                if not self.authorized():
                    return
                path = urlsplit(self.path).path
                if not path.startswith("/v1/books/") and not path.startswith("/v1/progress/"):
                    self.reply(404, {"error": "見つかりません"})
                    return
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if size < 1 or size > MAX_BODY:
                        raise ValueError("本のデータが大きすぎます")
                    data = json.loads(self.rfile.read(size))
                    if path.startswith("/v1/progress/"):
                        revision, changed = parent.library.put_progress(path.removeprefix("/v1/progress/"),
                            data["base_revision"], data["read_seq"], data["read_offset"])
                        self.reply(200, {"revision": revision, "content_changed": changed})
                        return
                    book = data["book"]
                    validate_book(book)
                    if path.removeprefix("/v1/books/") != book["uuid"]:
                        raise ValueError("本の識別子が一致しません")
                    base = data.get("base_revision")
                    if not isinstance(base, int) or base < 0:
                        raise ValueError("同期位置が正しくありません")
                    revision = parent.library.put(book, base)
                    self.reply(200, {"revision": revision})
                except Conflict as exc:
                    self.reply(409, {"error": "同じ本がPCで更新されています", "revision": exc.actual})
                except KeyError as exc:
                    self.reply(404, {"error": "本が見つかりません"})
                except (ValueError, TypeError, json.JSONDecodeError) as exc:
                    self.reply(400, {"error": str(exc)})

        return Handler
