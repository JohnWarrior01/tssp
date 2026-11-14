package app.vpntransfer.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int OPEN_FILE = 1;
    private static final int SAVE_FILE = 2;
    private static final int LIMIT = 32768;
    private static final String PHRASE = "[a-zа-яё0-9]{2,24}(?:-[a-zа-яё0-9]{2,24}){3,7}";

    private static final String RELEASES = "https://github.com/JohnWarrior01/vpn-transfer/releases/latest";
    private static final String RELEASE_API = "https://api.github.com/repos/JohnWarrior01/vpn-transfer/releases/latest";

    private EditText code;
    private TextView status;
    private Switch custom;
    private Button sendFile, sendText, receive, cancel, copyCode;
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
        title.setText("Передача VPN-ключей");
        title.setTextSize(26);
        content.addView(title);
        TextView description = new TextView(this);
        description.setText("Сквозное шифрование · файл или текст до 32 КБ");
        content.addView(description);

        custom = new Switch(this);
        custom.setText("Задать свою фразу");
        content.addView(custom);
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
        copyCode = button(content, "Скопировать код", () -> copy(code.getText().toString()));
        copyCode.setVisibility(View.GONE);
        receive = button(content, "Получить", this::receive);
        cancel = button(content, "Остановить", () -> {
            cancel.setEnabled(false);
            if (session != null) session.cancel();
        });
        status = new TextView(this);
        status.setText("Оба устройства должны быть онлайн.");
        content.addView(status);
        button(content, "Проверить обновления", this::checkUpdates);
        TextView version = new TextView(this);
        version.setText("Версия 2.0.0");
        content.addView(version);
        setBusy(false);
    }

    private void checkUpdates() {
        new Thread(() -> {
            String result;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (body.size() + count > 262144) throw new IOException();
                        body.write(buffer, 0, count);
                    }
                    String tag = new JSONObject(body.toString("UTF-8")).getString("tag_name");
                    if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException();
                    String[] parts = tag.substring(1).split("\\.");
                    int major = Integer.parseInt(parts[0]);
                    result = major > 2 || major == 2 && (Integer.parseInt(parts[1]) > 0 ||
                        Integer.parseInt(parts[2]) > 0) ? tag : "";
                }
            } catch (Exception error) {
                result = null;
            } finally {
                if (connection != null) connection.disconnect();
            }
            final String tag = result;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Обновления");
                if (tag == null) {
                    dialog.setMessage("Не удалось проверить GitHub. Повторите позже.").setPositiveButton("Закрыть", null);
                } else if (tag.isEmpty()) {
                    dialog.setMessage("Установлена актуальная версия.").setPositiveButton("Закрыть", null);
                } else {
                    dialog.setMessage("Доступна версия " + tag + ".")
                        .setNegativeButton("Закрыть", null).setPositiveButton("Открыть загрузку",
                            (d, which) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))));
                }
                dialog.show();
            });
        }).start();
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
        if (!custom.isChecked()) {
            beginSend(kind, name, data, "");
            return;
        }
        EditText editor = secretEditor(false);
        editor.setHint("4–8 случайных слов");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Своя фраза")
            .setMessage("Введите слова через пробелы. Программа добавит номер сеанса.")
            .setView(editor).setNegativeButton("Отмена", null).setPositiveButton("Продолжить", null)
            .create();
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String phrase = normalize(editor.getText().toString());
            if (!phrase.matches(PHRASE)) {
                editor.setError("Нужно 4–8 слов по 2–24 буквы или цифры.");
                return;
            }
            dialog.dismiss();
            int number = 10000000 + new SecureRandom().nextInt(90000000);
            beginSend(kind, name, data, number + "-" + phrase);
        });
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
        String entered = normalize(code.getText().toString());
        if (!entered.matches("[0-9]{1,12}-" + PHRASE)) {
            code.setError("Введите номер и все слова.");
            return;
        }
        setBusy(true);
        status.setText("Ожидание данных…");
        session = Bridge.receive(entered, observer(false));
    }

    private Observer observer(boolean outbound) {
        return new Observer() {
            @Override public void code(String value) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    code.setText(value);
                    copyCode.setVisibility(View.VISIBLE);
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
                    copyCode.setVisibility(View.GONE);
                    setBusy(false);
                    status.setText(error.isEmpty() ? (outbound ? "Данные доставлены." : "Данные получены.") : error);
                });
            }
        };
    }

    private void setBusy(boolean value) {
        busy = value;
        custom.setEnabled(!value);
        sendFile.setEnabled(!value);
        sendText.setEnabled(!value);
        receive.setEnabled(!value);
        code.setEnabled(!value);
        cancel.setEnabled(value);
    }

    private void showText(String text) {
        EditText viewer = secretEditor(true);
        viewer.setText(text);
        viewer.setKeyListener(null);
        viewer.setTextIsSelectable(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Полученный текст")
            .setView(viewer).setNegativeButton("Закрыть", null)
            .setPositiveButton("Копировать", (d, which) -> copy(text)).create();
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void copy(String value) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText("VPN Transfer", value);
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
        super.onDestroy();
    }
}
