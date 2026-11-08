package app.lamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Чаты приложения лежат в одном файле на телефоне.
 * Пустой новый чат в список не попадает, пока не отправлено первое сообщение.
 */
public class ChatStore {
    private final File file;
    private JSONObject root = new JSONObject();

    public ChatStore(File file) {
        this.file = file;
        load();
    }

    public synchronized void load() {
        try {
            if (file.exists()) {
                root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            root = new JSONObject();
        }
        if (!root.has("chats")) {
            try {
                root.put("chats", new JSONArray());
            } catch (Exception ignored) {
                root = new JSONObject();
            }
        }
        if (root.optString("current").isEmpty()) {
            newChat();
        }
    }

    /** Текущий разговор сбрасывается. Предыдущий уже записан, если в нём были сообщения. */
    public synchronized void newChat() {
        try {
            root.put("current", String.valueOf(System.currentTimeMillis()));
            save();
        } catch (Exception ignored) {
            // Файл починится при следующем сообщении.
        }
    }

    public synchronized void open(String id) {
        try {
            root.put("current", id);
            save();
        } catch (Exception ignored) {
        }
    }

    public synchronized JSONArray messages() {
        JSONObject chat = find(root.optString("current"));
        if (chat == null) {
            return new JSONArray();
        }
        JSONArray messages = chat.optJSONArray("messages");
        return messages == null ? new JSONArray() : messages;
    }

    /** В модель уходят только реплики, без текста размышления. */
    public synchronized JSONArray apiMessages() {
        JSONArray clean = new JSONArray();
        JSONArray messages = messages();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            try {
                JSONObject copy = new JSONObject();
                copy.put("role", message.optString("role"));
                copy.put("content", message.optString("content"));
                clean.put(copy);
            } catch (Exception ignored) {
            }
        }
        return clean;
    }

    public synchronized void add(String role, String content, String thinking) {
        try {
            String id = root.optString("current");
            if (id.isEmpty()) {
                id = String.valueOf(System.currentTimeMillis());
                root.put("current", id);
            }
            JSONObject chat = find(id);
            JSONArray chats = root.getJSONArray("chats");
            if (chat == null) {
                chat = new JSONObject();
                chat.put("id", id);
                chat.put("title", "");
                chat.put("messages", new JSONArray());
                JSONArray next = new JSONArray();
                next.put(chat);
                for (int i = 0; i < chats.length(); i++) {
                    next.put(chats.get(i));
                }
                root.put("chats", next);
            }
            JSONObject message = new JSONObject();
            message.put("role", role);
            message.put("content", content);
            if (thinking != null && !thinking.isEmpty()) {
                message.put("thinking", thinking);
            }
            chat.getJSONArray("messages").put(message);
            if (chat.optString("title").isEmpty() && "user".equals(role)) {
                String title = content.replace('\n', ' ').trim();
                if (title.length() > 42) {
                    title = title.substring(0, 42) + "…";
                }
                chat.put("title", title);
            }
            save();
        } catch (Exception ignored) {
        }
    }

    /** Новые чаты сверху. Пустые не показываем. */
    public synchronized JSONArray list() {
        JSONArray result = new JSONArray();
        JSONArray chats = root.optJSONArray("chats");
        if (chats == null) {
            return result;
        }
        for (int i = 0; i < chats.length(); i++) {
            JSONObject chat = chats.optJSONObject(i);
            if (chat == null || chat.optJSONArray("messages") == null || chat.optJSONArray("messages").length() == 0) {
                continue;
            }
            try {
                JSONObject row = new JSONObject();
                row.put("id", chat.optString("id"));
                row.put("title", chat.optString("title", "Чат"));
                result.put(row);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /** Вопрос без ответа остаётся, если приложение закрыли на середине мысли. Такой хвост убираем. */
    public synchronized void dropInterrupted() {
        JSONObject chat = find(root.optString("current"));
        if (chat == null) {
            return;
        }
        JSONArray messages = chat.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            return;
        }
        JSONObject last = messages.optJSONObject(messages.length() - 1);
        if (last == null || !"user".equals(last.optString("role"))) {
            return;
        }
        messages.remove(messages.length() - 1);
        if (messages.length() == 0) {
            try {
                chat.put("title", "");
            } catch (Exception ignored) {
            }
        }
        save();
    }

    private JSONObject find(String id) {
        JSONArray chats = root.optJSONArray("chats");
        if (chats == null) {
            return null;
        }
        for (int i = 0; i < chats.length(); i++) {
            JSONObject chat = chats.optJSONObject(i);
            if (chat != null && id.equals(chat.optString("id"))) {
                return chat;
            }
        }
        return null;
    }

    private void save() {
        try {
            Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
