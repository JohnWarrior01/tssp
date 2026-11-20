package app.vpntransfer.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PersistableBundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import bridge.Bridge;
import bridge.Observer;
import bridge.Session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int OPEN_FILE = 1;
    private static final int SAVE_FILE = 2;
    private static final int LIMIT = 32768;
    private static final String PHRASE = "[a-zа-яё0-9]{2,24}(?:-[a-zа-яё0-9]{2,24}){1,7}";


    private EditText code;
    private TextView status;
    private Switch custom, offline;
    private String customDisplay;
    private String broker = "";
    private Button updateButton;
    private Button sendFile, sendText, receive, cancel, copyCode;
    private TextView timer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long deadline;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long seconds = Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
            timer.setText(String.format(Locale.ROOT, "Осталось %d:%02d", seconds / 60, seconds % 60));
            if (seconds > 0) handler.postDelayed(this, 250);
        }
    };
    private Session session;
    private boolean busy;
    private byte[] pendingFile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("ТССП");
        title.setTextSize(26);
        content.addView(title);
        TextView description = new TextView(this);
        description.setText("Сквозное шифрование · файл или текст до 32 КБ");
        content.addView(description);

        custom = new Switch(this);
        custom.setText("Задать свою фразу");
        content.addView(custom);
        offline = new Switch(this);
        offline.setText("Отложенная отправка");
        content.addView(offline);
        sendFile = button(content, "Отправить файл", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, OPEN_FILE);
        });
        sendText = button(content, "Отправить текст", this::enterText);
        code = new EditText(this);
        code.setHint("Код передачи");
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setSaveEnabled(false);
        content.addView(code);
        copyCode = button(content, "Вставить код", () -> {
            if (busy) {
                copy(code.getText().toString());
                copyCode.setText("Код скопирован");
                return;
            }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            ClipData clip = clipboard.getPrimaryClip();
            CharSequence value = clip == null ? null : clip.getItemAt(0).getText();
            if (value == null) status.setText("В буфере обмена нет текста.");
            else code.setText(value.toString());
        });
        receive = button(content, "Получить", this::receive);
        cancel = button(content, "Остановить", () -> {
            cancel.setEnabled(false);
            copyCode.setEnabled(false);
            if (session != null) session.cancel();
        });
        status = new TextView(this);
        status.setText("Оба устройства должны быть онлайн.");
        content.addView(status);
        timer = new TextView(this);
        content.addView(timer);
        updateButton = button(content, "Проверить обновления", this::checkUpdates);
        TextView version = new TextView(this);
        version.setText("Версия " + Updates.VERSION);
        content.addView(version);
        setBusy(false);
    }

    private void checkUpdates() {
        updateButton.setEnabled(false);
        new Thread(() -> {
            try {
                Updates.Asset asset = Updates.find();
                runOnUiThread(() -> offerUpdate(asset));
            } catch (IOException | org.json.JSONException error) {
                runOnUiThread(() -> finishUpdate("Не удалось проверить обновления."));
            }
        }).start();
    }

    private void offerUpdate(Updates.Asset asset) {
        if (isDestroyed() || isFinishing()) return;
        updateButton.setEnabled(true);
        if (asset == null) {
            finishUpdate("Установлена актуальная версия.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Обновления")
            .setMessage("Скачать версию " + asset.tag + "?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Скачать", (dialog, which) -> downloadUpdate(asset)).show();
    }

    private void downloadUpdate(Updates.Asset asset) {
        updateButton.setEnabled(false);
        updateButton.setText("Загрузка… 0%");
        new Thread(() -> {
            try {
                Updates.download(asset, getCacheDir(), percent -> runOnUiThread(() ->
                    updateButton.setText("Загрузка… " + percent + "%")));
                runOnUiThread(this::offerInstall);
            } catch (IOException | java.security.NoSuchAlgorithmException error) {
                runOnUiThread(() -> finishUpdate("Не удалось скачать или проверить APK."));
            }
        }).start();
    }

    private void finishUpdate(String message) {
        updateButton.setText("Проверить обновления");
        updateButton.setEnabled(true);
        if (message != null && !isDestroyed() && !isFinishing()) {
            new AlertDialog.Builder(this).setTitle("Обновления").setMessage(message)
                .setPositiveButton("Закрыть", null).show();
        }
    }

    private void offerInstall() {
        finishUpdate(null);
        if (isDestroyed() || isFinishing()) return;
        new AlertDialog.Builder(this).setTitle("Обновление скачано")
            .setMessage("Откроется системное окно установки.")
            .setNegativeButton("Позже", null)
            .setPositiveButton("Установить", (dialog, which) -> installUpdate()).show();
    }

    private void installUpdate() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(settings, 3);
                return;
            }
            Intent installer = new Intent(Intent.ACTION_VIEW);
            installer.setDataAndType(UpdateProvider.APK, UpdateProvider.TYPE);
            installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(installer);
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            finishUpdate("Не удалось открыть системный установщик.");
        }
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }

    private EditText secretEditor(boolean multiline) {
        EditText editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        editor.setSaveEnabled(false);
        if (multiline) editor.setMinLines(4);
        return editor;
    }

    private void enterText() {
        EditText editor = secretEditor(true);
        editor.setHint("Вставьте текстовый ключ");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Отправить текст")
            .setView(editor).setNegativeButton("Отмена", null).setPositiveButton("Отправить", null)
            .create();
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            byte[] data = editor.getText().toString().getBytes(StandardCharsets.UTF_8);
            if (data.length == 0 || data.length > LIMIT) {
                editor.setError("Нужен непустой текст до 32 КБ.");
                return;
            }
            dialog.dismiss();
            send("text", "", data);
        });
    }

    private void send(String kind, String name, byte[] data) {
        if (offline.isChecked()) {
            askBroker(kind, name, data, null);
            return;
        }
        customDisplay = null;
        if (!custom.isChecked()) {
            beginSend(kind, name, data, "");
            return;
        }
        EditText editor = secretEditor(false);
        editor.setHint("Свой код от одного символа");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Своя фраза")
            .setMessage("Регистр и пробелы учитываются. Короткий код легко угадать. Для ключей лучше автоматический код.")
            .setView(editor).setNegativeButton("Отмена", null).setPositiveButton("Продолжить", null)
            .create();
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String phrase = editor.getText().toString();
            if (!validCustom(phrase)) {
                editor.setError("Введите от 1 до 256 байт текста в одну строку.");
                return;
            }
            dialog.dismiss();
            int number = 10000000 + new SecureRandom().nextInt(90000000);
            customDisplay = number + ":" + phrase;
            beginSend(kind, name, data, customWire(Integer.toString(number), phrase));
        });
    }

    private static boolean validCustom(String value) {
        return !value.isEmpty() && value.getBytes(StandardCharsets.UTF_8).length <= 256
            && !value.contains("\n") && !value.contains("\r");
    }

    private static String customWire(String number, String value) {
        StringBuilder result = new StringBuilder(number).append("-~");
        for (byte part : value.getBytes(StandardCharsets.UTF_8)) {
            result.append(String.format(Locale.ROOT, "%02x", part & 255));
        }
        return result.toString();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[-\\s]+", "-");
    }

    private void beginSend(String kind, String name, byte[] data, String chosenCode) {
        code.setText("");
        setBusy(true);
        status.setText("Подключение к серверу…");
        session = Bridge.send(kind, name, data, chosenCode, observer(true));
    }

    private void receive() {
        String rawCode = code.getText().toString().trim();
        if (rawCode.startsWith("T1-")) {
            askBroker(null, null, null, rawCode);
            return;
        }
        String entered = code.getText().toString();
        int separator = entered.indexOf(':');
        if (separator >= 0) {
            String number = entered.substring(0, separator);
            String phrase = entered.substring(separator + 1);
            if (!number.matches("[0-9]{8}") || !validCustom(phrase)) {
                code.setError("Введите номер сеанса, двоеточие и свой код.");
                return;
            }
            entered = customWire(number, phrase);
        } else {
            entered = normalize(entered);
            if (!entered.matches("[0-9]{1,12}-" + PHRASE)) {
                code.setError("Введите номер и все слова.");
                return;
            }
        }
        setBusy(true);
        status.setText("Ожидание данных…");
        session = Bridge.receive(entered, observer(false));
    }

    private void askBroker(String kind, String name, byte[] data, String receivedCode) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText address = secretEditor(false);
        address.setHint("HTTPS-адрес посредника");
        address.setText(broker);
        fields.addView(address);
        EditText password = secretEditor(false);
        if (data != null) {
            password.setHint("Пароль загрузки");
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            fields.addView(password);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Отложенная передача")
            .setView(fields).setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить", null).create();
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String server = address.getText().toString().trim();
            String token = password.getText().toString();
            if (!server.startsWith("https://")) { address.setError("Введите HTTPS-адрес."); return; }
            if (data != null && token.isEmpty()) { password.setError("Введите пароль."); return; }
            broker = server;
            dialog.dismiss();
            runOffline(server, token, kind, name, data, receivedCode);
        });
    }

    private void runOffline(String server, String token, String kind, String name, byte[] data, String receivedCode) {
        setBusy(true);
        cancel.setEnabled(false);
        handler.removeCallbacks(tick);
        timer.setText("");
        status.setText("Обращение к хранилищу…");
        new Thread(() -> {
            try {
                if (data == null) {
                    Offline.receive(server, receivedCode, observer(false));
                    runOnUiThread(() -> finishOffline("Данные получены."));
                    return;
                }
                String issued = Offline.send(server, token, kind, name, data);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    finishOffline("Код скопирован. Получатель может забрать данные в течение 24 часов.");
                    code.setText(issued);
                    copy(issued);
                });
            } catch (IOException | java.security.GeneralSecurityException | org.json.JSONException | IllegalArgumentException error) {
                runOnUiThread(() -> finishOffline("Проверьте адрес, пароль или код. Передача могла истечь или уже быть получена."));
            }
        }).start();
    }

    private void finishOffline(String message) {
        if (isDestroyed() || isFinishing()) return;
        setBusy(false);
        status.setText(message);
    }

    private Observer observer(boolean outbound) {
        return new Observer() {
            @Override public void code(String value) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    code.setText(customDisplay == null ? value : customDisplay);
                    if (cancel.isEnabled()) {
                        copyCode.setText("Скопировать код");
                        copyCode.setEnabled(true);
                    }
                    status.setText("Продиктуйте номер и слова получателю. Сеанс длится до 5 минут.");
                });
            }

            @Override public void received(String kind, String name, byte[] data) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (kind.equals("text")) {
                        showText(new String(data, StandardCharsets.UTF_8));
                    } else {
                        pendingFile = data;
                        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        save.addCategory(Intent.CATEGORY_OPENABLE);
                        save.setType("application/octet-stream");
                        String safeName = name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_").trim();
                        if (safeName.length() > 120) safeName = safeName.substring(0, 120);
                        if (safeName.isEmpty() || safeName.equals(".") || safeName.equals("..")) safeName = "vpn.conf";
                        save.putExtra(Intent.EXTRA_TITLE, safeName);
                        startActivityForResult(save, SAVE_FILE);
                    }
                });
            }

            @Override public void finished(String error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    session = null;
                    code.setText("");
                    copyCode.setText("Вставить код");
                    setBusy(false);
                    status.setText(error.isEmpty() ? (outbound ? "Данные доставлены." : "Данные получены.") : error);
                });
            }
        };
    }

    private void setBusy(boolean value) {
        busy = value;
        copyCode.setEnabled(!value);
        handler.removeCallbacks(tick);
        if (value) {
            deadline = SystemClock.elapsedRealtime() + 300000;
            tick.run();
        } else {
            timer.setText("");
        }
        custom.setEnabled(!value);
        offline.setEnabled(!value);
        sendFile.setEnabled(!value);
        sendText.setEnabled(!value);
        receive.setEnabled(!value);
        code.setEnabled(!value);
        cancel.setEnabled(value);
    }

    private void showText(String text) {
        EditText viewer = secretEditor(true);
        viewer.setHint("Текст скрыт");
        viewer.setKeyListener(null);
        viewer.setTextIsSelectable(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Полученный текст")
            .setView(viewer).setNegativeButton("Закрыть", null)
            .setNeutralButton("Показать", null)
            .setPositiveButton("Копировать", (d, which) -> copy(text)).create();
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        Button toggle = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        toggle.setOnClickListener(view -> {
            boolean reveal = viewer.length() == 0;
            viewer.setText(reveal ? text : "");
            toggle.setText(reveal ? "Скрыть" : "Показать");
        });
    }

    private void copy(String value) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText("ТССП", value);
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            ClipDescription description = clip.getDescription();
            description.setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
    }

    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == 3) {
            if (android.os.Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) installUpdate();
            return;
        }
        Uri uri = result == RESULT_OK && intent != null ? intent.getData() : null;
        if (request == SAVE_FILE) {
            byte[] data = pendingFile;
            pendingFile = null;
            if (uri == null || data == null) return;
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException();
                output.write(data);
                status.setText("Файл сохранён.");
            } catch (IOException | RuntimeException error) {
                status.setText("Не удалось сохранить файл.");
            }
        } else if (request == OPEN_FILE && uri != null && !busy) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException();
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (data.size() + count > LIMIT) {
                        status.setText("Выберите непустой файл до 32 КБ.");
                        return;
                    }
                    data.write(buffer, 0, count);
                }
                if (data.size() == 0) {
                    status.setText("Выберите непустой файл до 32 КБ.");
                    return;
                }
                String name = "vpn.conf";
                try (android.database.Cursor cursor = getContentResolver().query(
                        uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
                }
                send("file", name, data.toByteArray());
            } catch (IOException | RuntimeException error) {
                status.setText("Не удалось прочитать файл.");
            }
        }
    }

    @Override protected void onDestroy() {
        if (session != null) session.cancel();
        pendingFile = null;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
