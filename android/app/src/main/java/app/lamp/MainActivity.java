package app.lamp;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Чат LocAI.
 * Ответ — обычный текст: долгое нажатие выделяет кусок, иконка под ним копирует целиком.
 * Поле ввода на всю ширину. Микрофон, вставка, размышления и отправка — только значки.
 */
public class MainActivity extends Activity {
    private static final int VOICE = 4;

    private ChatStore store;
    private UserStore users;
    private EditText userName;
    private EditText userPin;
    private View gate;
    private EditText serverBox;
    private EditText tokenBox;
    private EditText messageBox;
    private ImageButton sendButton;
    private ImageButton thinkButton;
    private ImageButton webButton;
    private Switch thinkingSwitch;
    private View setup;
    private View hero;
    private View historyPanel;
    private View composer;
    private LinearLayout historyList;
    private ScrollView scroller;
    private LinearLayout transcript;
    private SharedPreferences prefs;
    private TextView replyView;
    private TextView thoughtView;
    private View thoughtLabel;
    private TextView thoughtTitle;
    private ProgressBar thoughtSpin;
    private View replyRow;
    private ImageButton replyCopy;
    private boolean settingsOpen;
    private boolean historyOpen;
    private boolean locked = true;
    private boolean resumed;
    private boolean fingerprintAsked;
    private int fontStep = 1;
    private static final float[] FONT_SCALES = {0.85f, 1f, 1.2f, 1.45f};
    private boolean thinkingOn = true;
    private boolean webOn = true;
    private boolean busy;
    private int bubbleWidth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        bubbleWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        prefs = getSharedPreferences("lamp", MODE_PRIVATE);

        serverBox = findViewById(R.id.server);
        tokenBox = findViewById(R.id.token);
        messageBox = findViewById(R.id.message);
        sendButton = findViewById(R.id.send);
        thinkButton = findViewById(R.id.think);
        webButton = findViewById(R.id.web);
        thinkingSwitch = findViewById(R.id.thinking);
        setup = findViewById(R.id.setup);
        hero = findViewById(R.id.hero);
        historyPanel = findViewById(R.id.historyPanel);
        historyList = findViewById(R.id.historyList);
        composer = findViewById(R.id.composer);
        scroller = findViewById(R.id.scroller);
        transcript = findViewById(R.id.transcript);
        users = new UserStore(new java.io.File(getFilesDir(), "users.json"));
        gate = findViewById(R.id.gate);
        userName = findViewById(R.id.userName);
        userPin = findViewById(R.id.userPin);
        userName.setText(prefs.getString("userName", ""));

        serverBox.setText(prefs.getString("server", "http://192.168.2.36:8765"));
        tokenBox.setText(prefs.getString("token", ""));
        setThinking(prefs.getBoolean("thinking", true), false);
        setWeb(prefs.getBoolean("web", true), false);

        findViewById(R.id.paste).setOnClickListener(v -> pasteKey());
        findViewById(R.id.pasteMessage).setOnClickListener(v -> pasteIntoMessage());
        findViewById(R.id.voice).setOnClickListener(v -> voice());
        thinkButton.setOnClickListener(v -> setThinking(!thinkingOn, true));
        webButton.setOnClickListener(v -> setWeb(!webOn, true));
        findViewById(R.id.settings).setOnClickListener(v -> {
            settingsOpen = !settingsOpen;
            historyOpen = false;
            refreshChrome();
        });
        findViewById(R.id.newChat).setOnClickListener(v -> startNewChat());
        findViewById(R.id.history).setOnClickListener(v -> toggleHistory());
        findViewById(R.id.user).setOnClickListener(v -> lock());
        findViewById(R.id.enter).setOnClickListener(v -> unlock());
        sendButton.setOnClickListener(v -> send());
        messageBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            public void afterTextChanged(Editable s) {
                sendButton.setAlpha(s.toString().trim().isEmpty() ? 0.35f : 1f);
            }
        });
        sendButton.setAlpha(0.35f);
        fontStep = prefs.getInt("fontStep", 1);
        findViewById(R.id.fontDown).setOnClickListener(v -> changeFont(-1));
        findViewById(R.id.fontUp).setOnClickListener(v -> changeFont(1));
        paint(messageBox, 16);
        lock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Окно уже на экране, иначе системный отпечаток сразу закрывается и остаётся код.
        if (locked) {
            offerFingerprint();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    /** Без верного кода чаты этого имени не показываются. Новое имя заводит своего человека. */
    private void unlock() {
        String name = userName.getText().toString().trim();
        String pin = userPin.getText().toString().trim();
        if (name.isEmpty() || pin.length() < 4) {
            Toast.makeText(this, "Имя и код из 4 цифр", Toast.LENGTH_SHORT).show();
            return;
        }
        String id = users.unlock(name, pin);
        if (id == null) {
            Toast.makeText(this, "Неверный код", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString("userName", name).putString("userId", id).apply();
        userPin.setText("");
        openUser(id);
    }

    /** Код спрашиваем при запуске и по значку человека, не после каждой блокировки экрана. */
    private void lock() {
        locked = true;
        store = null;
        historyOpen = false;
        if (transcript != null) {
            transcript.removeAllViews();
        }
        refreshChrome();
        fingerprintAsked = false;
        // Код остаётся на экране. Отпечаток — дополнительный способ, не единственный.
        findViewById(R.id.pinForm).setVisibility(View.VISIBLE);
        View finger = findViewById(R.id.fingerprint);
        finger.setVisibility(canFingerprint() ? View.VISIBLE : View.GONE);
        finger.setOnClickListener(v -> startFingerprint());
        if (resumed) {
            offerFingerprint();
        }
    }

    private void offerFingerprint() {
        if (!locked || fingerprintAsked || !canFingerprint()) {
            return;
        }
        fingerprintAsked = true;
        // Системное окно нельзя показать, пока активность ещё не на экране.
        getWindow().getDecorView().post(this::startFingerprint);
    }

    private void startFingerprint() {
        if (!locked || !canFingerprint()) {
            return;
        }
        FingerGate.start(this, prefs.getString("userName", ""), new FingerGate.Done() {
            public void ok() {
                openUser(prefs.getString("userId", ""));
            }

            public void useCode() {
                findViewById(R.id.pinForm).setVisibility(View.VISIBLE);
            }
        });
    }

    private boolean canFingerprint() {
        if (Build.VERSION.SDK_INT < 30 || prefs.getString("userId", "").isEmpty()) {
            return false;
        }
        return FingerGate.available(this);
    }

    private void openUser(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        store = new ChatStore(new java.io.File(getFilesDir(), "chats-" + id + ".json"));
        locked = false;
        showChat();
    }

    /** Плюс в шапке. Текущий разговор уже лежит в истории, если в нём что-то написано. */
    private void startNewChat() {
        if (locked || store == null) {
            return;
        }
        if (busy) {
            Toast.makeText(this, "Дождитесь ответа", Toast.LENGTH_SHORT).show();
            return;
        }
        store.newChat();
        showChat();
    }

    private void toggleHistory() {
        if (locked || store == null) {
            return;
        }
        if (busy) {
            Toast.makeText(this, "Дождитесь ответа", Toast.LENGTH_SHORT).show();
            return;
        }
        historyOpen = !historyOpen;
        if (historyOpen) {
            fillHistory();
        }
        refreshChrome();
    }

    private void fillHistory() {
        historyList.removeAllViews();
        JSONArray items = store.list();
        if (items.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("Пока нет чатов");
            empty.setTextColor(0xFF8A8A8A);
            empty.setTextSize(16);
            empty.setPadding(0, dp(24), 0, 0);
            historyList.addView(empty);
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id");
            TextView row = new TextView(this);
            row.setText(item.optString("title", "Чат"));
            row.setTextColor(0xFFF2F2F2);
            row.setTextSize(16);
            row.setPadding(0, dp(16), 0, dp(16));
            row.setOnClickListener(v -> {
                store.open(id);
                showChat();
            });
            historyList.addView(row);
        }
    }

    /** Рисует сохранённый чат заново. После закрытия приложения он остаётся. */
    private void showChat() {
        historyOpen = false;
        if (!busy) {
            store.dropInterrupted();
        }
        transcript.removeAllViews();
        replyView = null;
        thoughtView = null;
        thoughtLabel = null;
        thoughtTitle = null;
        thoughtSpin = null;
        replyRow = null;
        JSONArray messages = store.messages();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            boolean mine = "user".equals(message.optString("role"));
            String content = message.optString("content");
            addBubble(mine, content, false);
            String thinking = message.optString("thinking", "");
            if (!mine && !thinking.isEmpty() && thoughtView != null) {
                attachThought(thoughtLabel, thoughtTitle, thoughtSpin, thoughtView, thinking, false);
            }
        }
        refreshChrome();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void changeFont(int delta) {
        fontStep = Math.max(0, Math.min(FONT_SCALES.length - 1, fontStep + delta));
        prefs.edit().putInt("fontStep", fontStep).apply();
        paint(messageBox, 16);
        for (int i = 0; i < transcript.getChildCount(); i++) {
            View row = transcript.getChildAt(i);
            paint(row.findViewById(R.id.bubble), 16);
            paint(row.findViewById(R.id.thought), 16);
            paint(row.findViewById(R.id.thoughtTitle), 13);
        }
    }

    private void paint(TextView view, float base) {
        if (view == null) {
            return;
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, base * FONT_SCALES[fontStep]);
    }

    private void refreshChrome() {
        boolean ready = !tokenBox.getText().toString().trim().isEmpty();
        boolean chatting = transcript.getChildCount() > 0;
        gate.setVisibility(locked ? View.VISIBLE : View.GONE);
        setup.setVisibility(!locked && !historyOpen && (!ready || settingsOpen) ? View.VISIBLE : View.GONE);
        historyPanel.setVisibility(!locked && historyOpen ? View.VISIBLE : View.GONE);
        hero.setVisibility(!locked && !historyOpen && !chatting ? View.VISIBLE : View.GONE);
        scroller.setVisibility(!locked && !historyOpen && chatting ? View.VISIBLE : View.GONE);
        composer.setVisibility(!locked && !historyOpen ? View.VISIBLE : View.GONE);
    }

    /** Размышления: значок в поле и переключатель в настройках смотрят в одно место. */
    private void setThinking(boolean on, boolean announce) {
        thinkingOn = on;
        prefs.edit().putBoolean("thinking", on).apply();
        thinkButton.setImageResource(on ? R.drawable.ic_think : R.drawable.ic_think_off);
        thinkButton.setAlpha(1f);
        thinkingSwitch.setOnCheckedChangeListener(null);
        thinkingSwitch.setChecked(on);
        thinkingSwitch.setOnCheckedChangeListener((button, checked) -> setThinking(checked, true));
        if (announce) {
            Toast.makeText(this, on ? "Размышления включены" : "Размышления выключены", Toast.LENGTH_SHORT).show();
        }
    }

    /** Глобус: перед ответом ищем свежие страницы и отдаём их модели. */
    private void setWeb(boolean on, boolean announce) {
        webOn = on;
        prefs.edit().putBoolean("web", on).apply();
        webButton.setImageResource(on ? R.drawable.ic_web : R.drawable.ic_web_off);
        if (announce) {
            Toast.makeText(this, on ? "Поиск в интернете включён" : "Поиск в интернете выключен", Toast.LENGTH_SHORT).show();
        }
    }

    private void pasteKey() {
        CharSequence raw = clipText();
        if (raw == null) {
            return;
        }
        String key = null;
        String server = null;
        for (String line : raw.toString().split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Ключ:")) {
                key = trimmed.substring("Ключ:".length()).trim();
            } else if (trimmed.startsWith("Приложение:")) {
                server = trimmed.substring("Приложение:".length()).trim();
            }
        }
        if (key == null && server == null) {
            key = raw.toString().trim();
        }
        if (server != null && !server.isEmpty()) {
            serverBox.setText(server);
        }
        if (key != null && !key.isEmpty()) {
            tokenBox.setText(key);
        }
        prefs.edit()
                .putString("server", serverBox.getText().toString().trim())
                .putString("token", tokenBox.getText().toString().trim())
                .apply();
        settingsOpen = false;
        refreshChrome();
    }

    /** Вставка в поле сообщения, в то место, где стоит курсор. */
    private void pasteIntoMessage() {
        CharSequence raw = clipText();
        if (raw == null) {
            return;
        }
        int start = Math.max(messageBox.getSelectionStart(), 0);
        messageBox.getText().insert(start, raw);
        messageBox.setSelection(start + raw.length());
    }

    private CharSequence clipText() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "Буфер пуст", Toast.LENGTH_SHORT).show();
            return null;
        }
        CharSequence raw = clipboard.getPrimaryClip().getItemAt(0).getText();
        if (raw == null || raw.length() == 0) {
            Toast.makeText(this, "В буфере нет текста", Toast.LENGTH_SHORT).show();
            return null;
        }
        return raw;
    }

    private void copyText(String text) {
        if (text == null || text.isEmpty() || "…".equals(text)) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("LocAI", text));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    private void voice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, VOICE);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите");
        try {
            startActivityForResult(intent, VOICE);
        } catch (Exception error) {
            Toast.makeText(this, "Голосовой ввод недоступен", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == VOICE && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            voice();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE || result != RESULT_OK || data == null) {
            return;
        }
        ArrayList<String> words = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (words == null || words.isEmpty()) {
            return;
        }
        pasteIntoMessage(words.get(0));
    }

    private void pasteIntoMessage(String text) {
        int start = Math.max(messageBox.getSelectionStart(), 0);
        String current = messageBox.getText().toString();
        String piece = current.isEmpty() || start == 0 || Character.isWhitespace(current.charAt(start - 1))
                ? text
                : " " + text;
        messageBox.getText().insert(start, piece);
        messageBox.setSelection(start + piece.length());
    }

    private void send() {
        final String text = messageBox.getText().toString().trim();
        final String server = serverBox.getText().toString().trim().replaceAll("/+$", "");
        final String token = tokenBox.getText().toString().trim();
        if (text.isEmpty() || busy || locked || store == null) {
            return;
        }
        if (server.isEmpty() || token.isEmpty()) {
            settingsOpen = true;
            refreshChrome();
            return;
        }
        prefs.edit().putString("server", server).putString("token", token).apply();
        messageBox.setText("");
        settingsOpen = false;
        final boolean think = thinkingOn;
        final boolean web = webOn;
        final ChatStore chats = store;
        chats.add("user", text, "");
        addBubble(true, text, false);
        addBubble(false, "…", think);
        refreshChrome();
        busy = true;
        new Thread(() -> ask(server, token, think, web, chats)).start();
    }

    /**
     * Свои слова — серый пузырь справа.
     * Ответ модели — текст на всю ширину: его можно выделить, иконка копирует всё.
     */
    private void addBubble(boolean mine, String text, boolean expectThought) {
        View row = getLayoutInflater().inflate(R.layout.item_message, transcript, false);
        TextView body = row.findViewById(R.id.bubble);
        View label = row.findViewById(R.id.thoughtLabel);
        TextView title = row.findViewById(R.id.thoughtTitle);
        ProgressBar spin = row.findViewById(R.id.thoughtSpin);
        TextView thought = row.findViewById(R.id.thought);
        ImageButton copy = row.findViewById(R.id.copy);
        label.setVisibility(View.GONE);
        thought.setVisibility(View.GONE);
        body.setText(mine ? text : Markdown.format(text), TextView.BufferType.SPANNABLE);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setLinkTextColor(0xFF9ECAFF);
        paint(body, 16);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                mine ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = mine ? Gravity.END : Gravity.START;
        body.setLayoutParams(params);
        if (mine) {
            body.setMaxWidth(bubbleWidth);
            body.setBackgroundResource(R.drawable.bg_mine);
            body.setPadding(dp(14), dp(10), dp(14), dp(10));
            copy.setVisibility(View.GONE);
        } else {
            body.setPadding(dp(2), dp(2), dp(2), dp(2));
            copy.setOnClickListener(v -> copyText(body.getText().toString()));
            replyRow = row;
            replyView = body;
            thoughtLabel = label;
            thoughtTitle = title;
            thoughtSpin = spin;
            thoughtView = thought;
            replyCopy = copy;
            // Подпись видна сразу. Текст мысли свёрнут, пока по ней не нажмут.
            if (expectThought) {
                attachThought(label, title, spin, thought, "…", true);
                body.setText("");
            }
            copy.setVisibility(text.isEmpty() || "…".equals(text) ? View.INVISIBLE : View.VISIBLE);
        }
        transcript.addView(row);
        reveal();
    }

    private void ask(String server, String token, boolean think, boolean web, ChatStore chats) {
        StringBuilder reply = new StringBuilder();
        StringBuilder thought = new StringBuilder();
        String error = null;
        try {
            JSONObject body = new JSONObject();
            body.put("messages", chats.apiMessages());
            body.put("think", think);
            body.put("web", web);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            // Свой сокет, а не HttpURLConnection: тот копит весь ответ и мысль не успевает появиться.
            URI uri = URI.create(server);
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 10000);
                socket.setSoTimeout(600000);
                OutputStream rawOut = socket.getOutputStream();
                String head = "POST /api/chat HTTP/1.0\r\nHost: " + host
                        + "\r\nAuthorization: Bearer " + token
                        + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length
                        + "\r\nConnection: close\r\n\r\n";
                rawOut.write(head.getBytes(StandardCharsets.ISO_8859_1));
                rawOut.write(bytes);
                rawOut.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                String headerLine;
                while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                    // Заголовки ответа не нужны, тело идёт следом.
                }
                if (status == null || !status.contains(" 200 ")) {
                    error = "Модель на паузе или ещё запускается.";
                } else {
                String row;
                while ((row = reader.readLine()) != null) {
                    if (row.isEmpty()) {
                        continue;
                    }
                    JSONObject event = new JSONObject(row);
                    if (event.has("reply")) {
                        reply.setLength(0);
                        reply.append(event.getString("reply"));
                        show(reply.toString());
                    } else if (event.has("error") && reply.length() == 0) {
                        error = event.getString("error");
                    } else {
                        JSONObject message = event.optJSONObject("message");
                        if (message == null) {
                            continue;
                        }
                        // Мысль приходит отдельным полем. Раньше приложение его выбрасывало,
                        // поэтому на телефоне казалось, что размышление не включается.
                        String thinking = message.optString("thinking", "");
                        if (!thinking.isEmpty()) {
                            thought.append(thinking);
                            showThought(thought.toString());
                        }
                        String piece = message.optString("content", "");
                        if (piece.isEmpty()) {
                            continue;
                        }
                        reply.append(piece);
                        show(reply.toString());
                    }
                }
                reader.close();
                }
            }
        } catch (Exception failure) {
            error = "Нет связи с компьютером. Проверьте Wi-Fi и что LocAI запущен.";
        }
        String shown = reply.length() > 0 ? reply.toString() : (error == null ? "Пустой ответ" : error);
        if (reply.length() == 0) {
            show(shown);
        }
        chats.add("assistant", shown, thought.toString());
        runOnUiThread(() -> {
            busy = false;
            if (thoughtSpin != null) {
                thoughtSpin.setVisibility(View.GONE);
            }
        });
    }

    /** Серый текст над ответом. Пока строка свёрнута, видна только подпись. */
    private void showThought(String text) {
        runOnUiThread(() -> {
            if (locked || thoughtView == null || thoughtLabel == null) {
                return;
            }
            attachThought(thoughtLabel, thoughtTitle, thoughtSpin, thoughtView, text, true);
            reveal();
        });
    }

    /** Первое появление прячет текст. Повторные куски мысли не закрывают уже открытый блок. */
    private void attachThought(View label, TextView title, ProgressBar spin, TextView thought, String text, boolean running) {
        label.setVisibility(View.VISIBLE);
        thought.setText(Markdown.format(text), TextView.BufferType.SPANNABLE);
        thought.setMovementMethod(LinkMovementMethod.getInstance());
        thought.setLinkTextColor(0xFF9ECAFF);
        paint(thought, 16);
        paint(title, 13);
        if (label.getTag() == null) {
            label.setTag(Boolean.FALSE);
            thought.setVisibility(View.GONE);
            title.setText("▸  Размышление");
        }
        // Кружок только пока мысль ещё пишется и строка свёрнута.
        boolean collapsed = !Boolean.TRUE.equals(label.getTag());
        spin.setVisibility(running && collapsed ? View.VISIBLE : View.GONE);
        label.setOnClickListener(v -> {
            boolean open = !Boolean.TRUE.equals(v.getTag());
            v.setTag(open);
            thought.setVisibility(open ? View.VISIBLE : View.GONE);
            title.setText(open ? "▾  Размышление" : "▸  Размышление");
            spin.setVisibility(!open && running ? View.VISIBLE : View.GONE);
        });
    }

    /** Ответ держим внизу экрана. Открытую мысль не утаскиваем прокруткой. */
    private void reveal() {
        scroller.post(() -> {
            if (thoughtView != null && thoughtView.getVisibility() == View.VISIBLE) {
                return;
            }
            scroller.fullScroll(View.FOCUS_DOWN);
        });
    }

    /** Последний ответ дописывается, пока модель говорит. Иконка копирования появляется вместе с текстом. */
    private void show(String text) {
        runOnUiThread(() -> {
            if (locked || replyView == null) {
                return;
            }
            replyView.setText(Markdown.format(text), TextView.BufferType.SPANNABLE);
            replyView.setMovementMethod(LinkMovementMethod.getInstance());
            paint(replyView, 16);
            if (replyCopy != null) {
                replyCopy.setVisibility(text.isEmpty() || "…".equals(text) ? View.INVISIBLE : View.VISIBLE);
            }
            reveal();
        });
    }
}
