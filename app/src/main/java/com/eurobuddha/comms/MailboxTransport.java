package com.eurobuddha.comms;

import org.json.JSONObject;

/**
 * Off-chain send path for MaxLite — the mailbox replacement for {@link CommsTransport}'s on-chain send.
 *
 * <p>Instead of posting a 1-nano coin whose {@code state[99]} carries the sealed blob (which costs a
 * burned coin + phone proof-of-work + a block wait, and is size-capped by the TxPoW), this seals the
 * same blob with the SAME {@link CryptoProvider} and POSTs it to the recipient's mailbox daemon. The
 * mailbox is the always-taken source of truth for delivery; live/direct (iroh) delivery is layered on
 * later as a pure latency optimization writing the same blob under the same {@code msgid}.
 *
 * <p>The seal is byte-identical to the on-chain path, so a message is portable across transports and the
 * daemon only ever holds opaque ciphertext. The {@link SendCb} contract mirrors
 * {@link CommsTransport.SendCb} — {@code onSent} now returns the mailbox {@code msgid}
 * ({@code sha256(ciphertext)}) instead of a txpowid; callers treat it as an opaque delivery id.
 */
public final class MailboxTransport {

    public interface SendCb {
        /** Delivered/stored — {@code msgid} = sha256(sealed ciphertext), stable across transports. */
        void onSent(String msgid);
        void onFailed(String message);
    }

    /** Seal {@code wire} to {@code toPublicId} and store it in that recipient's mailbox. */
    public static void sendMessage(MailboxHttp http, CryptoProvider crypto,
                                   String toPublicId, byte[] wire, SendCb cb) {
        final String blob;
        try {
            blob = crypto.seal(toPublicId, wire);
        } catch (Exception e) {
            cb.onFailed("encrypt failed: " + e.getMessage());
            return;
        }
        postBlob(http, toPublicId, blob, cb);
    }

    /** Store an already-sealed blob (bare hex, no 0x) addressed to {@code toPublicId}. */
    public static void postBlob(MailboxHttp http, String toPublicId, String blobHex, SendCb cb) {
        final JSONObject body = new JSONObject();
        try {
            body.put("to", toPublicId);
            body.put("blob", "0x" + blobHex);   // hex-typed, exactly as coin state[99] stored it
        } catch (Exception e) {
            cb.onFailed(e.getMessage());
            return;
        }
        http.post("/store", body, new MailboxHttp.Cb() {
            @Override public void onResult(JSONObject j) {
                if (j.optBoolean("ok", false)) {
                    cb.onSent(j.optString("msgid", ""));
                } else {
                    cb.onFailed(j.optString("error", "the mailbox rejected the message"));
                }
            }
            @Override public void onError(String message) { cb.onFailed(message); }
        });
    }

    private MailboxTransport() {}
}
