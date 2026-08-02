package com.eurobuddha.comms;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869) over the platform HMAC — a standard construction, no novel crypto. Used to
 * domain-separate independent 32-byte sub-seeds from the Minima seed, so the derived identities are
 * deterministic but unrelated:
 *
 * <ul>
 *   <li>{@code freezepeach-box-v1} / {@code freezepeach-sign-v1} — the X25519 + Ed25519 chat identity
 *       ({@link CommsIdentity});</li>
 *   <li>{@code freezepeach-wallet-v1} — the WOTS wallet base seed
 *       ({@code SeedDerive.walletSeedFromPhrase}). This one is FUND-CRITICAL: it is what keeps our
 *       wallet key out of the Minima node's key space, which only ever contains
 *       {@code hashObjects(baseSeed, n)} for integer n.</li>
 * </ul>
 *
 * <p>Public because the wallet layer (a different package) derives from it — it must be the SAME
 * implementation, never a re-implementation.
 */
public final class Hkdf {

    public static byte[] derive(byte[] ikm, String info, int len) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // Extract: PRK = HMAC(salt=0x00*32, IKM)
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            // Expand
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] infoB = info.getBytes(StandardCharsets.UTF_8);
            byte[] okm = new byte[len];
            byte[] t = new byte[0];
            int pos = 0;
            for (int ctr = 1; pos < len; ctr++) {
                mac.update(t);
                mac.update(infoB);
                mac.update((byte) ctr);
                t = mac.doFinal();   // resets the MAC, retains the key
                int n = Math.min(t.length, len - pos);
                System.arraycopy(t, 0, okm, pos, n);
                pos += n;
            }
            return okm;
        } catch (Exception e) {
            throw new RuntimeException("HKDF failed", e);
        }
    }

    private Hkdf() {}
}
