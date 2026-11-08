package app.lamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Имя и код человека. Чаты лежат в отдельном файле на его id.
 * Чужой код существующее имя не открывает.
 */
public class UserStore {
    private final File file;
    private JSONArray users = new JSONArray();

    public UserStore(File file) {
        this.file = file;
        try {
            if (file.exists()) {
                users = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            users = new JSONArray();
        }
    }

    /**
     * Верный код — id человека. Новое имя создаётся.
     * Имя уже есть, а код не тот — null, чаты не открываются.
     */
    public synchronized String unlock(String name, String pin) {
        String clean = name.trim();
        JSONObject found = find(clean);
        try {
            if (found == null) {
                JSONObject user = new JSONObject();
                String id = Long.toString(System.currentTimeMillis(), 36);
                user.put("id", id);
                user.put("name", clean);
                String salt = salt();
                user.put("salt", salt);
                user.put("pin", hash(salt, pin));
                users.put(user);
                save();
                return id;
            }
            if (hash(found.optString("salt"), pin).equals(found.optString("pin"))) {
                return found.optString("id");
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private JSONObject find(String name) {
        for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i);
            if (user != null && name.equalsIgnoreCase(user.optString("name"))) {
                return user;
            }
        }
        return null;
    }

    private static String salt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return hex(bytes);
    }

    private static String hash(String salt, String pin) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(pin.getBytes(StandardCharsets.UTF_8));
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private void save() throws Exception {
        Files.write(file.toPath(), users.toString().getBytes(StandardCharsets.UTF_8));
    }
}
