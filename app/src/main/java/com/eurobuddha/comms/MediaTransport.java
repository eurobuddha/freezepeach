package com.eurobuddha.comms;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.SecretBox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Large-media path for MaxLite (images / video). The mailbox only carries small sealed messages, so media
 * goes to the content-addressed blob store: encrypt the whole file with a fresh symmetric key, split the
 * CIPHERTEXT into fixed chunks, PUT each to {@code /blob}, and send a small SEALED MANIFEST
 * ({@code key,nonce,chunkids,mime,size}) as a normal mailbox message. The recipient fetches the chunks,
 * reassembles, and decrypts. The relay only ever holds opaque, hash-addressed ciphertext chunks plus a
 * manifest it cannot open — the media is end-to-end sealed exactly like a text message.
 */
public final class MediaTransport {

    public static final int CHUNK_SIZE = 256 * 1024;                // ciphertext bytes per chunk
    public static final int MAX_MEDIA_CHUNKS = 400;                 // reject a manifest listing more chunks (anti-OOM)
    public static final long MAX_MEDIA_BYTES = 30L * 1024 * 1024;   // hard ceiling on reassembled media (anti-OOM/DoS)

    public interface SendCb  { void onSent(String msgid); void onFailed(String message); }
    public interface FetchCb { void onMedia(byte[] data, String mime); void onFailed(String message); }
    public interface ManifestCb { void onManifest(JSONObject manifest); void onFailed(String message); }

    /** If {@code plaintext} is a media manifest, return it parsed; else null (it's a normal text message). */
    public static JSONObject asManifest(byte[] plaintext) {
        try {
            JSONObject j = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            return j.optInt("maxlite_media", 0) == 1 ? j : null;
        } catch (Exception e) { return null; }
    }

    /** Encrypt + chunk + upload {@code media}, then seal a manifest to {@code toPublicId} and post it. */
    public static void send(final MailboxHttp http, final CryptoProvider crypto, final LazySodium ls,
                            final String toPublicId, final byte[] media, final String mime, final SendCb cb) {
        try {
            final byte[] key = new byte[SecretBox.KEYBYTES];
            final byte[] nonce = new byte[SecretBox.NONCEBYTES];
            SecureRandom r = new SecureRandom(); r.nextBytes(key); r.nextBytes(nonce);
            final byte[] cipher = new byte[media.length + SecretBox.MACBYTES];
            if (!ls.cryptoSecretBoxEasy(cipher, media, media.length, nonce, key)) { cb.onFailed("encrypt failed"); return; }

            final List<byte[]> chunks = new ArrayList<>();
            for (int off = 0; off < cipher.length; off += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, cipher.length - off);
                byte[] c = new byte[len]; System.arraycopy(cipher, off, c, 0, len); chunks.add(c);
            }
            final JSONArray chunkids = new JSONArray();
            uploadChunk(http, chunks, 0, chunkids, cb, () -> {
                try {
                    JSONObject m = new JSONObject();
                    m.put("maxlite_media", 1);
                    m.put("mime", mime == null ? "application/octet-stream" : mime);
                    m.put("size", media.length);
                    m.put("key", Hex.to(key));
                    m.put("nonce", Hex.to(nonce));
                    m.put("chunks", chunkids);
                    MailboxTransport.sendMessage(http, crypto, toPublicId,
                            m.toString().getBytes(StandardCharsets.UTF_8),
                            new MailboxTransport.SendCb() {
                                @Override public void onSent(String msgid) { cb.onSent(msgid); }
                                @Override public void onFailed(String msg) { cb.onFailed(msg); }
                            });
                } catch (Exception e) { cb.onFailed(e.getMessage()); }
            });
        } catch (Exception e) { cb.onFailed(e.getMessage()); }
    }

    private static void uploadChunk(final MailboxHttp http, final List<byte[]> chunks, final int i,
                                    final JSONArray chunkids, final SendCb cb, final Runnable done) {
        if (i >= chunks.size()) { done.run(); return; }
        http.postBytes("/blob", chunks.get(i), new MailboxHttp.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("ok", false)) { cb.onFailed("blob store rejected a chunk"); return; }
                chunkids.put(j.optString("chunkid", ""));
                uploadChunk(http, chunks, i + 1, chunkids, cb, done);
            }
            @Override public void onError(String message) { cb.onFailed(message); }
        });
    }

    /** Encrypt + chunk + upload {@code media}, then return the sealed manifest WITHOUT posting a message —
     *  used for group media, where the same manifest is wrapped + fanned out to every member. */
    public static void upload(final MailboxHttp http, final LazySodium ls, final byte[] media, final String mime, final ManifestCb cb) {
        try {
            final byte[] key = new byte[SecretBox.KEYBYTES];
            final byte[] nonce = new byte[SecretBox.NONCEBYTES];
            SecureRandom r = new SecureRandom(); r.nextBytes(key); r.nextBytes(nonce);
            final byte[] cipher = new byte[media.length + SecretBox.MACBYTES];
            if (!ls.cryptoSecretBoxEasy(cipher, media, media.length, nonce, key)) { cb.onFailed("encrypt failed"); return; }
            final List<byte[]> chunks = new ArrayList<>();
            for (int off = 0; off < cipher.length; off += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, cipher.length - off);
                byte[] c = new byte[len]; System.arraycopy(cipher, off, c, 0, len); chunks.add(c);
            }
            final JSONArray chunkids = new JSONArray();
            uploadChunkM(http, chunks, 0, chunkids, cb, () -> {
                try {
                    JSONObject m = new JSONObject();
                    m.put("maxlite_media", 1);
                    m.put("mime", mime == null ? "application/octet-stream" : mime);
                    m.put("size", media.length);
                    m.put("key", Hex.to(key));
                    m.put("nonce", Hex.to(nonce));
                    m.put("chunks", chunkids);
                    cb.onManifest(m);
                } catch (Exception e) { cb.onFailed(e.getMessage()); }
            });
        } catch (Exception e) { cb.onFailed(e.getMessage()); }
    }

    private static void uploadChunkM(final MailboxHttp http, final List<byte[]> chunks, final int i,
                                     final JSONArray chunkids, final ManifestCb cb, final Runnable done) {
        if (i >= chunks.size()) { done.run(); return; }
        http.postBytes("/blob", chunks.get(i), new MailboxHttp.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("ok", false)) { cb.onFailed("blob store rejected a chunk"); return; }
                chunkids.put(j.optString("chunkid", ""));
                uploadChunkM(http, chunks, i + 1, chunkids, cb, done);
            }
            @Override public void onError(String message) { cb.onFailed(message); }
        });
    }

    /** Fetch every chunk in {@code manifest}, reassemble, decrypt, and return the media bytes. */
    public static void fetch(final MailboxHttp http, final LazySodium ls, final JSONObject manifest, final FetchCb cb) {
        try {
            final byte[] key = Hex.from(manifest.getString("key"));
            final byte[] nonce = Hex.from(manifest.getString("nonce"));
            final String mime = manifest.optString("mime", "application/octet-stream");
            final JSONArray chunkids = manifest.getJSONArray("chunks");
            if (chunkids.length() > MAX_MEDIA_CHUNKS || manifest.optLong("size", 0) > MAX_MEDIA_BYTES) {
                cb.onFailed("media too large — rejected"); return;    // don't fetch an attacker-oversized manifest
            }
            final ByteArrayOutputStream cipher = new ByteArrayOutputStream();
            fetchChunk(http, chunkids, 0, cipher, cb, () -> {
                try {
                    byte[] c = cipher.toByteArray();
                    if (c.length < SecretBox.MACBYTES) { cb.onFailed("media too short"); return; }
                    byte[] out = new byte[c.length - SecretBox.MACBYTES];
                    if (!ls.cryptoSecretBoxOpenEasy(out, c, c.length, nonce, key)) { cb.onFailed("decrypt failed"); return; }
                    cb.onMedia(out, mime);
                } catch (Exception e) { cb.onFailed(e.getMessage()); }
            });
        } catch (Exception e) { cb.onFailed(e.getMessage()); }
    }

    private static void fetchChunk(final MailboxHttp http, final JSONArray chunkids, final int i,
                                   final ByteArrayOutputStream sink, final FetchCb cb, final Runnable done) {
        if (i >= chunkids.length()) { done.run(); return; }
        http.getBytes("/blob/" + chunkids.optString(i), new MailboxHttp.RawCb() {
            @Override public void onBytes(byte[] data) {
                sink.write(data, 0, data.length);
                if (sink.size() > MAX_MEDIA_BYTES) { cb.onFailed("media exceeded size cap"); return; }
                fetchChunk(http, chunkids, i + 1, sink, cb, done);
            }
            @Override public void onError(String message) { cb.onFailed("chunk fetch failed: " + message); }
        });
    }

    private MediaTransport() {}
}
