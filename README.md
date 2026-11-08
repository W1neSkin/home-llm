# LocAI

LocAI is a local chat on this PC. The model runs in Ollama and stays on the machine. A browser site and an Android app talk to it over the home network. The model itself is not exposed to the internet.

This PC is a Ryzen 9 9950X3D with 96 GB of RAM and an RTX 5070 Ti (16 GB). The active weights are `Huihui-Qwen3.8-27B-abliterated-UD-Q3_K_XL.gguf` (about 13.3 GB, Q3). The chat alias is always `home-qwen`, so swapping the file does not rename old chats. Context length is 16384. One model stays loaded on the GPU.

## Start and stop

The desktop shortcut **LocAI** starts a house icon by the clock. The model does not start when Windows boots. Open the shortcut when you want it.

Right-click the house:

- **Stop** unloads the model and frees the GPU. It stays stopped across reboots until you choose **Start**.
- **Start** loads the model again.
- **Settings** picks the `.gguf` already in `models/`, the context length, and the site port.
- **Open chat** opens the site on this PC.
- **Address for others** copies the site URL, the phone URL, and the phone key.

You can also unload the model from a terminal:

```powershell
ollama stop home-qwen
```

## Website

On this PC: http://127.0.0.1:8787

On another device in the same Wi-Fi: `http://<this-pc>:8787`  
Right now that address is http://192.168.2.36:8787

Each person has their own login and only their own chats. The login looks like an email (`anna@home.local`). No mail is sent. The first account, `admin@localhost`, is the administrator. New signups are normal users. Set `ENABLE_SIGNUP=false` in `.env` and run `docker compose up -d` when you want to close registration.

The site is Open WebUI in Docker. It reaches Ollama through a small local proxy on port 11435. That proxy is what turns thinking on and attaches fresh web snippets before the model answers.

## Android app

The app is not in a store. On a phone in the same Wi-Fi, open:

http://192.168.2.36:8765/LocAI.apk

Install the downloaded `LocAI.apk`. Android will ask permission to install apps from the browser.

The address `http://192.168.2.36:8765` by itself is not a page. It is the door the app uses. A browser that opens it without the key gets `Invalid key`. That is expected.

In the app, under the gear:

- address: `http://192.168.2.36:8765`
- key: the value of `PHONE_TOKEN` in `.env`

One key works on every phone. Extra keys can be added as `PHONE_TOKEN_2`, `PHONE_TOKEN_3`, and so on.

The name and 4-digit code inside the app are only a lock on that phone. They are not the website account, and the chats are not shared with the site. Fingerprint is offered on launch after the code has been used once. Locking the screen does not ask again. The person icon locks the app so someone else can sign in.

Thinking is collapsed under **Thinking**. Tap it to expand. A spinner next to the word means the model is still thinking. The globe turns web lookup on or off. Font size is under the gear.

## What the model receives

The website and the phone prepare a question in `scripts/chat_prep.py`, then call the same Ollama model.

- Thinking is long (`high`, which this model treats as extra-high) unless it is explicitly turned off.
- Unless lookup is off, a few current web snippets are placed in front of the question. The query is sent to DuckDuckGo. The answer is still generated on this GPU.
- `num_predict` is left unlimited so a long thought does not eat the whole reply.

## Change the model

Put a new `.gguf` in `models/`. The script does not download anything unless you pass `-AllowDownload`.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\update-model.ps1 -Base Some-Model.gguf
```

Chats keep calling `home-qwen`.

## Telegram

`telegram/bot.py` stays off until `.env` has `TELEGRAM_BOT_TOKEN` and `TELEGRAM_ALLOWED_IDS`. Create the bot with `/newbot` in @BotFather. Numeric ids come from @userinfobot. Strangers are refused. The bot can be used away from home while this PC is on. No inbound port is required.

## Layout

| Path | What it is |
| --- | --- |
| `.env` | Local settings and secrets. Not in git. |
| `models/` | Weight files. Not in git. |
| `data/` | Site database, pause flag, logs. Not in git. |
| `scripts/tray.ps1` | Clock icon. |
| `scripts/phone.py` | Phone door on port 8765, and the APK link. |
| `scripts/ollama-quiet.py` | Site proxy on port 11435. |
| `scripts/chat_prep.py` | Shared thinking and web lookup. |
| `android/` | The LocAI app. |

Copy `.env.example` to `.env` on a new machine, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install.ps1
```
