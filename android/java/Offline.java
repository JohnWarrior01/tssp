package app.vpntransfer.mobile;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;

final class Offline {
    private static final byte[] CONTEXT = "tssp-offline-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    static String send(String server, String token, String kind, String name, byte[] data)
            throws IOException, GeneralSecurityException, JSONException {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32], nonce = new byte[12];
        random.nextBytes(key);
        random.nextBytes(nonce);
        JSONObject payload = new JSONObject().put("kind", kind).put("name", name)
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP));
        byte[] plaintext = new JSONObject().put("offer", new JSONObject().put("message", payload.toString()))
            .toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext);
        byte[] wire = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, wire, nonce.length, encrypted.length);
        request(server, "drop", key, token, wire, 201);
        return "T1-" + Base64.encodeToString(key, FLAGS);
    }

    static void receive(String server, String code, bridge.Observer observer)
            throws IOException, GeneralSecurityException, JSONException {
        if (!code.matches("T1-[A-Za-z0-9_-]{43}")) throw new IOException("Invalid code");
        byte[] key = Base64.decode(code.substring(3), FLAGS);
        if (!code.equals("T1-" + Base64.encodeToString(key, FLAGS))) throw new IOException("Invalid code");
        byte[] wire = request(server, "take", key, "", new byte[0], 200);
        if (wire.length < 29) throw new IOException("Invalid payload");
        byte[] plain = cipher(Cipher.DECRYPT_MODE, key, Arrays.copyOf(wire, 12)).doFinal(wire, 12, wire.length - 12);
        JSONObject payload = new JSONObject(new JSONObject(new String(plain, StandardCharsets.UTF_8))
            .getJSONObject("offer").getString("message"));
        String kind = payload.getString("kind");
        byte[] data = Base64.decode(payload.getString("data"), Base64.DEFAULT);
        if ((!kind.equals("file") && !kind.equals("text")) || data.length == 0 || data.length > 32768)
            throw new IOException("Invalid payload");
        observer.received(kind, payload.getString("name"), data);
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(CONTEXT);
        return cipher;
    }

    private static byte[] request(String server, String action, byte[] key, String token, byte[] body, int status)
            throws IOException, GeneralSecurityException {
        URL base = new URL(server);
        if (!base.getProtocol().equals("https") || base.getHost().isEmpty() ||
            base.getUserInfo() != null || base.getQuery() != null || base.getRef() != null)
            throw new IOException("Expected HTTPS");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(CONTEXT);
        StringBuilder locator = new StringBuilder();
        for (byte value : hash.digest(key)) locator.append(String.format(Locale.ROOT, "%02x", value & 255));
        URL url = new URL(server.replaceFirst("/+$", "") + "/v1/" + action + "/" + locator);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(40000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            if (connection.getResponseCode() != status) throw new IOException("Broker rejected request");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (result.size() + count > 65536) throw new IOException("Response too large");
                    result.write(buffer, 0, count);
                }
                return result.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
