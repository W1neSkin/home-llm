"""Одна подготовка вопроса для сайта и телефона.

Оба входа зовут этот модуль, затем одну и ту же модель.
Мысль и поиск в интернете поэтому не разъезжаются.
"""

from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
THINK_FLAG = ROOT / "data" / "thinking"


def switched_off(value):
    return value is False or (
        isinstance(value, str) and value.strip().lower() in ("false", "off", "none", "0")
    )


def resolve_think(value):
    """Выключено только явным false. Иначе длинная мысль, как high у модели."""
    if value is None:
        enabled = True
        if THINK_FLAG.exists() and THINK_FLAG.read_text(encoding="utf-8").strip() == "off":
            enabled = False
        return "high" if enabled else False
    if switched_off(value):
        return False
    return "high"


def search_web(query):
    """Несколько свежих страниц. Сама модель в интернет не выходит."""
    text = (query or "").strip()
    if not text:
        return ""
    try:
        from ddgs import DDGS

        with DDGS() as ddgs:
            rows = ddgs.text(text, safesearch="moderate", max_results=5, backend="auto")
    except Exception:
        return ""
    lines = []
    for row in rows or []:
        title = (row.get("title") or "").strip()
        body = (row.get("body") or "").strip()
        link = (row.get("href") or "").strip()
        if not (title or body):
            continue
        lines.append(f"- {title}\n  {body}\n  {link}")
    return "\n".join(lines)


def prepare(data):
    """Возвращает тело запроса к Ollama: мысль, лимит и при необходимости выдержки из сети."""
    payload = dict(data)
    messages = list(payload.get("messages") or [])
    payload["think"] = resolve_think(payload.get("think", None) if "think" in payload else None)
    web = payload.pop("web", True)
    if not switched_off(web) and messages:
        found = search_web((messages[-1] or {}).get("content") or "")
        if found:
            today = date.today().strftime("%d.%m.%Y")
            messages.insert(0, {
                "role": "system",
                "content": (
                    f"Сегодня {today}. Ниже свежие выдержки из интернета. "
                    "Опирайся на них в ответе и укажи ссылку, если она есть.\n\n"
                    + found
                ),
            })
    if messages:
        payload["messages"] = messages
    options = dict(payload.get("options") or {})
    # Без потолка: длинная мысль иначе съедает лимит, и ответа не остаётся.
    options["num_predict"] = -1
    payload["options"] = options
    return payload
