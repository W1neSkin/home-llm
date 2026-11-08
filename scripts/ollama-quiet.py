"""Прокси между сайтом и Ollama.

Сайт и телефон готовят вопрос одним модулем chat_prep:
длинная мысль и свежие страницы из интернета.
Явный think: false с сайта мысль выключает.
"""

import http.client
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from chat_prep import prepare

PORT = 11435


class Handler(BaseHTTPRequestHandler):
    def _forward(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        path = self.path.split("?", 1)[0]
        if body and path in ("/api/chat", "/api/generate"):
            try:
                # Сайт не присылает свой флаг поиска: ищем так же, как телефон с включённым глобусом.
                body = json.dumps(prepare(json.loads(body)), ensure_ascii=False).encode("utf-8")
            except json.JSONDecodeError:
                pass
        # urllib Ollama обрывает. Обычный HTTP-клиент с коротким User-Agent проходит.
        conn = http.client.HTTPConnection("127.0.0.1", 11434, timeout=600)
        headers = {"User-Agent": "lamp", "Accept": "*/*"}
        if body is not None:
            headers["Content-Type"] = self.headers.get("Content-Type", "application/json")
        conn.request(self.command, self.path, body=body, headers=headers)
        upstream = conn.getresponse()
        payload = upstream.read()
        self.send_response(upstream.status)
        for key, value in upstream.getheaders():
            if key.lower() in ("transfer-encoding", "content-length", "connection"):
                continue
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        conn.close()

    def do_GET(self):
        self._forward()

    def do_POST(self):
        self._forward()

    def do_DELETE(self):
        self._forward()

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
