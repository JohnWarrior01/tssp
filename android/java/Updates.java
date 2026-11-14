package app.vpntransfer.mobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Updates {
    static final String VERSION = "1.2.0";
    private static final String REPO = "JohnWarrior01/tssp";

    static final class Asset {
        final String tag, name, digest;
        final long size;
        Asset(String tag, String name, String digest, long size) {
            this.tag = tag;
            this.name = name;
            this.digest = digest;
            this.size = size;
        }
    }

    interface Progress { void update(int percent); }

    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        return connection;
    }

    static Asset find() throws IOException, JSONException {
        HttpURLConnection connection = connect("https://api.github.com/repos/" + REPO + "/releases/latest");
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (body.size() + count > 262144) throw new IOException("Release response too large");
                body.write(buffer, 0, count);
            }
            JSONObject release = new JSONObject(body.toString("UTF-8"));
            String tag = release.getString("tag_name");
            if (!tag.matches("v[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}")) throw new IOException("Invalid release tag");
            String[] offered = tag.substring(1).split("\\."), current = VERSION.split("\\.");
            int comparison = 0;
            for (int i = 0; i < current.length && comparison == 0; i++) {
                comparison = Integer.compare(Integer.parseInt(offered[i]), Integer.parseInt(current[i]));
            }
            if (comparison <= 0) return null;
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                if (!name.equals("TSSP-Android.apk") && !name.equals("TSSP-Android-" + tag.substring(1) + ".apk")) continue;
                String digest = asset.optString("digest");
                long size = asset.getLong("size");
                if (asset.getString("state").equals("uploaded") && digest.matches("sha256:[a-f0-9]{64}")
                    && size > 0 && size <= 256L * 1024 * 1024) {
                    return new Asset(tag, name, digest.substring(7), size);
                }
            }
            throw new IOException("Missing verified Android asset");
        } finally {
            connection.disconnect();
        }
    }

    static void download(Asset asset, File cache, Progress progress) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection connection = connect("https://github.com/" + REPO + "/releases/download/" + asset.tag + "/" + asset.name);
        File temporary = null;
        try {
            temporary = File.createTempFile("update-", ".part", cache);
            long received = 0;
            int previous = -1;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[128 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    received += count;
                    if (received > asset.size) throw new IOException("Invalid download size");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    int percent = (int) (received * 100 / asset.size);
                    if (percent != previous) progress.update(percent);
                    previous = percent;
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (received != asset.size || !asset.digest.equals(hex.toString())) throw new IOException("Invalid download hash");
            if (!temporary.renameTo(new File(cache, "update.apk"))) throw new IOException("Could not save APK");
        } finally {
            connection.disconnect();
            if (temporary != null) temporary.delete();
        }
    }
}
