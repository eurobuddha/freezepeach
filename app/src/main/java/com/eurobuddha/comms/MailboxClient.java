package com.eurobuddha.comms;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Off-chain receive path for MaxLite — drains MY mailbox by a monotonic cursor and routes each opened
 * message. This is the decoupled variant of the app's on-chain scanner: it defines its OWN
 * {@link Router}/{@link MetaStore}/{@link Listener} seam so it needs NO Minima-node/IPC dependency
 * (the future shared module will hoist these interfaces out of the on-chain CommsScanner to here).
 *
 * <p>No loss / no dup: the router stores durably before the cursor advances; every message carries a
 * stable {@code msgid = sha256(ciphertext)} dedup key; opened messages are ack'd so the mailbox deletes
 * them, and blobs that don't open for me are left to TTL-expire while the cursor still advances past them.
 */
public final class MailboxClient {

    /** Holds the drain cursor (namespaced per mailbox host). */
    public interface MetaStore {
        String getMeta(String k, String def);
        void setMeta(String k, String v);
    }

    /** Handles a message that opened for me (sealed to my box key + signature verified). Return true if new. */
    public interface Router {
        boolean handle(String msgid, Opened opened, JSONObject meta);
    }

    public interface Listener { void onDone(boolean ok, int newCount); }

    private final MailboxHttp http;
    private final CryptoProvider crypto;
    private final MetaStore meta;
    private final String myPublicId;
    private final Router router;
    private final Listener listener;
    private final String bmCursor;

    private boolean running = false;

    public MailboxClient(MailboxHttp http, CryptoProvider crypto, MetaStore meta,
                         String myPublicId, Router router, Listener listener) {
        this.http = http; this.crypto = crypto; this.meta = meta;
        this.myPublicId = myPublicId; this.router = router; this.listener = listener;
        this.bmCursor = "mbx_cursor_" + tagOf(http.base());
    }

    public void drain() {
        if (running) return;
        running = true;
        final long since = parseLong(meta.getMeta(bmCursor, "0"));
        final long ts = System.currentTimeMillis() / 1000;
        final String sig = crypto.sign(("fetch:" + myPublicId + ":" + since + ":" + ts)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        http.get("/fetch?to=" + myPublicId + "&since=" + since + "&ts=" + ts + "&sig=" + sig, new MailboxHttp.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("ok", false)) { finish(false, 0); return; }
                JSONArray msgs = j.optJSONArray("messages");
                long next = j.optLong("next_cursor", since);
                boolean more = j.optBoolean("more", false);
                int newCount = 0;
                JSONArray ackIds = new JSONArray();
                if (msgs != null) {
                    for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.optJSONObject(i);
                        if (m == null) continue;
                        String blob = m.optString("blob", "");
                        if (blob.isEmpty()) continue;
                        String msgid = msgidOf(blob);   // recompute locally = sha256(ciphertext); NEVER trust the relay's field
                        if (msgid.isEmpty()) continue;  // (relay-controlled msgid → filename path-traversal + dedup spoofing)
                        Opened o = crypto.open(blob);
                        if (o == null || !o.valid) continue;   // not for me / bad signature — leave to TTL
                        JSONObject meta = new JSONObject();
                        try {
                            meta.put("msgid", msgid);
                            meta.put("seq", m.optLong("seq", 0));
                            meta.put("recv_at", m.optLong("recv_at", 0));
                        } catch (Exception ignore) { }
                        if (router.handle(msgid, o, meta)) newCount++;
                        if (!msgid.isEmpty()) ackIds.put(msgid);
                    }
                }
                MailboxClient.this.meta.setMeta(bmCursor, String.valueOf(next));
                if (ackIds.length() > 0) ack(ackIds);
                finish(true, newCount);
                if (more) drain();   // server caps each page (FETCH_LIMIT) — keep paging until the box is drained
            }
            @Override public void onError(String message) { finish(false, 0); }
        });
    }

    private void ack(JSONArray msgids) {
        JSONObject body = new JSONObject();
        try {
            long ts = System.currentTimeMillis() / 1000;
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < msgids.length(); i++) { if (i > 0) joined.append(','); joined.append(msgids.optString(i)); }
            String sig = crypto.sign(("ack:" + myPublicId + ":" + ts + ":" + joined)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.put("to", myPublicId); body.put("msgids", msgids); body.put("ts", ts); body.put("sig", sig);
        } catch (Exception e) { return; }
        http.post("/ack", body, new MailboxHttp.Cb() {
            @Override public void onResult(JSONObject j) { }
            @Override public void onError(String message) { }
        });
    }

    private void finish(boolean ok, int newCount) {
        running = false;
        listener.onDone(ok, newCount);
    }

    private static String tagOf(String base) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 24; i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.length() == 0 ? "default" : sb.toString();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0L; }
    }

    /** msgid = sha256(raw ciphertext bytes) as lowercase hex — computed here, matching the server, so a
     *  hostile relay can't inject a path-traversal filename or a colliding/spoofed dedup key. */
    private static String msgidOf(String blobHex) {
        try {
            String hex = blobHex.startsWith("0x") ? blobHex.substring(2) : blobHex;
            if ((hex.length() & 1) != 0) return "";
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
