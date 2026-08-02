package com.eurobuddha.comms;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP client for a MaxLite mailbox daemon — the off-chain replacement for {@link NodeApi} as the
 * message bus. Same callback shape and threading contract as {@link NodeApi}: the request runs on a
 * background thread and the result is delivered back ON THE MAIN THREAD, so callers (and the UI) are
 * safe. The daemon speaks JSON and returns {@code {"ok":false,"error":...}} with a 4xx on bad input, so
 * an HTTP error still yields a parsed JSON result here (surfaced via {@code ok}) rather than an
 * exception — mirroring how {@link NodeApi} passes node errors through as JSON.
 *
 * <p>The mailbox never sees plaintext: this only ever moves the opaque sealed blob produced by
 * {@link CryptoProvider#seal}. TLS ({@code https://}) is expected once the daemon faces the open web
 * (Phase 1+); for a trusted-LAN Phase-0 box plain {@code http://} is acceptable.
 */
public final class MailboxHttp {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
    }

    /** Raw-bytes callback for the binary media-chunk endpoints (/blob). */
    public interface RawCb {
        void onBytes(byte[] data);
        void onError(String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;   // guard against a runaway response body

    private final String baseUrl;                              // e.g. "https://mailbox.example.com" (no trailing /)
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newFixedThreadPool(3);   // allow concurrent send + drain
    private volatile boolean released = false;

    public MailboxHttp(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrl = b;
    }

    public String base() { return baseUrl; }

    public void get(String path, Cb cb)                 { run("GET", path, null, cb); }
    public void post(String path, JSONObject body, Cb cb) { run("POST", path, body, cb); }

    /** GET raw bytes (a media chunk). */
    public void getBytes(String path, RawCb cb) {
        if (released) return;
        exec.execute(() -> {
            try {
                byte[] data = doHttpRaw("GET", path, null);
                main.post(() -> { if (!released && cb != null) cb.onBytes(data); });
            } catch (Exception e) {
                String msg = friendly(e);
                main.post(() -> { if (!released && cb != null) cb.onError(msg); });
            }
        });
    }

    /** POST a raw octet-stream body (a media chunk) and parse the JSON reply ({ok, chunkid, …}). */
    public void postBytes(String path, byte[] body, Cb cb) {
        if (released) return;
        exec.execute(() -> {
            try {
                byte[] resp = doHttpRaw("POST", path, body);
                JSONObject j = new JSONObject(new String(resp, StandardCharsets.UTF_8));
                main.post(() -> { if (!released && cb != null) cb.onResult(j); });
            } catch (Exception e) {
                String msg = friendly(e);
                main.post(() -> { if (!released && cb != null) cb.onError(msg); });
            }
        });
    }

    private byte[] doHttpRaw(String method, String path, byte[] body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(body); }
            }
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            byte[] data = readAllBytes(in);
            if (code < 200 || code >= 400)
                throw new Exception("HTTP " + code + (data.length > 0 ? ": " + new String(data, StandardCharsets.UTF_8) : ""));
            return data;
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_RESPONSE) throw new Exception("response too large");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public void onDestroy() {
        released = true;
        exec.shutdownNow();
    }

    private void run(final String method, final String path, final JSONObject body, final Cb cb) {
        if (released) return;
        exec.execute(() -> {
            JSONObject result;
            try {
                result = doHttp(method, path, body);
            } catch (final Exception e) {
                final String msg = friendly(e);
                main.post(() -> { if (!released && cb != null) cb.onError(msg); });
                return;
            }
            final JSONObject r = result;
            main.post(() -> { if (!released && cb != null) cb.onResult(r); });
        });
    }

    private JSONObject doHttp(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(payload); }
            }
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (text.isEmpty()) throw new Exception("empty response (HTTP " + code + ")");
            return new JSONObject(text);   // daemon returns JSON on both success and 4xx
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_RESPONSE) throw new Exception("response too large");
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        return "mailbox unreachable" + (m != null && !m.isEmpty() ? " (" + m + ")" : "");
    }
}
