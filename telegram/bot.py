"""Телеграм-бот для домашней Qwen.

Бот сам опрашивает Telegram, поэтому роутер пробрасывать не нужно.
Телефон может быть где угодно. Нужен включенный этот компьютер и интернет.

Запуск отдельно:
    py -3 telegram/bot.py

Обычный путь — scripts/start.ps1, он поднимает бота, если в .env есть токен
и хотя бы один разрешённый id. Чужие аккаунты бот отшивает.
"""

import json
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OLLAMA_URL = "http://127.0.0.1:11434"
# Память диалога только в процессе. Перезапуск бота начинает разговор заново.
history = {}


def load_env():
    """Читает .env, не затирая переменные, которые уже заданы снаружи."""
    path = ROOT / ".env"
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def telegram(method, payload=None, timeout=70):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{TOKEN}/{method}",
        data=data,
        headers=headers,
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def send_text(chat_id, text):
    # У Telegram лимит 4096 символов на сообщение.
    chunks = [text[i : i + 4000] for i in range(0, max(len(text), 1), 4000)]
    for chunk in chunks:
        telegram("sendMessage", {"chat_id": chat_id, "text": chunk or "(пустой ответ)"})


def answer(chat_id, text):
    """Один ход диалога. think=false — Qwen3 не уходит в длинные рассуждения."""
    dialog = history.setdefault(chat_id, [])
    dialog.append({"role": "user", "content": text})
    # Длинный хвост всё равно обрежет num_ctx. Здесь режем раньше, чтобы запрос был короче.
    body = {
        "model": MODEL,
        "messages": dialog[-20:],
        "stream": False,
        "think": False,
        "keep_alive": -1,
    }
    request = urllib.request.Request(
        f"{OLLAMA_URL}/api/chat",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        message = json.load(response).get("message") or {}
    reply = message.get("content") or ""
    reply = re.sub(r"<think>.*?</think>", "", reply, flags=re.DOTALL).strip()
    if not reply:
        reply = "(пустой ответ)"
    dialog.append({"role": "assistant", "content": reply})
    return reply


def main():
    offset = 0
    while True:
        try:
            updates = telegram("getUpdates", {"offset": offset, "timeout": 50})
        except Exception as error:
            print(f"telegram: {error}", flush=True)
            time.sleep(3)
            continue

        for update in updates.get("result", []):
            offset = update["update_id"] + 1
            message = update.get("message") or {}
            chat = message.get("chat") or {}
            user = message.get("from") or {}
            chat_id = chat.get("id")
            user_id = user.get("id")
            text = (message.get("text") or "").strip()
            if chat_id is None or not text:
                continue
            if user_id not in ALLOWED:
                send_text(chat_id, "Нет доступа.")
                continue
            if text in ("/start", "/reset"):
                history[chat_id] = []
                send_text(chat_id, f"Готов. Модель {MODEL}. Напишите сообщение. /reset очищает диалог.")
                continue
            try:
                send_text(chat_id, answer(chat_id, text))
            except Exception as error:
                print(f"model: {error}", flush=True)
                send_text(chat_id, "Модель сейчас не ответила. Проверьте, что компьютер не спит.")


load_env()
TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
MODEL = os.environ.get("MODEL", "home-qwen").strip() or "home-qwen"
ALLOWED = set()
for item in os.environ.get("TELEGRAM_ALLOWED_IDS", "").replace(" ", "").split(","):
    if item.isdigit():
        ALLOWED.add(int(item))

if __name__ == "__main__":
    if not TOKEN or not ALLOWED:
        raise SystemExit("Заполните TELEGRAM_BOT_TOKEN и TELEGRAM_ALLOWED_IDS в .env")
    main()
