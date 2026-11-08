"""Дверь для приложения LocAI на телефоне.

Ollama слушает только этот компьютер. Телефон ходит сюда и присылает ключ.
Чужой в сети без ключа модель не получит.
"""

import http.client
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from chat_prep import prepare

ROOT = Path(__file__).resolve().parents[1]


def load_env():
    env = {}
    path = ROOT / ".env"
    if not path.exists():
        return env
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip()
    return env


ENV = load_env()
# PHONE_TOKEN — первый телефон, PHONE_TOKEN_2 и дальше — следующие.
TOKENS = {
    value
    for key, value in ENV.items()
    if value and (key == "PHONE_TOKEN" or key.startswith("PHONE_TOKEN_"))
}
MODEL = ENV.get("MODEL") or "home-qwen"
PORT = int(ENV.get("PHONE_PORT") or "8765")
# Файл, который ставят на телефон. Ссылка открывается в браузере без ключа.
APK = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "LocAI.apk"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    def answer(self, code, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def allowed(self):
        header = self.headers.get("Authorization") or ""
        token = header[7:] if header.startswith("Bearer ") else ""
        return bool(token) and token in TOKENS

    def do_GET(self):
        # Установка приложения. Ключ здесь не нужен: это просто файл.
        if self.path.split("?", 1)[0] == "/LocAI.apk":
            if not APK.exists():
                self.answer(404, {"error": "Файл приложения не собран"})
                return
            data = APK.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", "attachment; filename=LocAI.apk")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if not self.allowed():
            self.answer(401, {"error": "Неверный ключ"})
            return
        self.answer(200, {"ok": True, "name": "LocAI"})

    def do_POST(self):
        if self.path != "/api/chat":
            self.answer(404, {"error": "Нет такого адреса"})
            return
        if not self.allowed():
            self.answer(401, {"error": "Неверный ключ"})
            return
        size = int(self.headers.get("Content-Length") or 0)
        incoming = json.loads(self.rfile.read(size) or b"{}")
        # Та же подготовка, что у сайта: мысль и поиск не отличаются.
        incoming["model"] = MODEL
        incoming["stream"] = True
        incoming["keep_alive"] = -1
        payload = prepare(incoming)
        # urllib Ollama обрывает, поэтому обычный HTTP-клиент.
        conn = http.client.HTTPConnection("127.0.0.1", 11434, timeout=600)
        started = False
        try:
            conn.request(
                "POST",
                "/api/chat",
                body=json.dumps(payload).encode("utf-8"),
                headers={"User-Agent": "locai", "Content-Type": "application/json", "Accept": "*/*"},
            )
            upstream = conn.getresponse()
            if upstream.status >= 400:
                self.answer(upstream.status, {"error": "Модель на паузе или ещё запускается."})
                return
            # Без chunked: телефон читает строки по мере появления, а не одним куском в конце.
            self.close_connection = True
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
            self.send_header("Connection", "close")
            self.end_headers()
            started = True
            while True:
                line = upstream.readline()
                if not line:
                    break
                if not line.endswith(b"\n"):
                    line += b"\n"
                self.wfile.write(line)
                self.wfile.flush()
        except (OSError, json.JSONDecodeError, TimeoutError):
            # Заголовки уже ушли — второй ответ слать нельзя, клиент увидит обрыв.
            if not started:
                self.answer(503, {"error": "Модель на паузе или ещё запускается."})
        finally:
            conn.close()

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    if not TOKENS:
        raise SystemExit("В .env нет PHONE_TOKEN")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
