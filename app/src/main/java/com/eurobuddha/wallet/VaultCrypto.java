package com.eurobuddha.wallet;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * VaultCrypto — passphrase-based authenticated encryption for the wallet vault (seed + keyuses),
 * with a <b>portable, self-describing container</b> that is decryptable on ANY device given only the
 * passphrase. This is the whole point of M3's security model: unlike Android-Keystore-backed
 * {@code EncryptedSharedPreferences} (whose master key is device-bound and does not restore), a blob
 * produced here carries every parameter needed to re-derive its key from the passphrase, so an
 * Auto-Backup restore or a manual export file opens on a brand-new install / new phone.
 *
 * <h3>Scheme</h3>
 * <pre>
 *   masterKey = PBKDF2-HMAC-SHA256(passphrase_utf8, salt, iterations, dkLen=32)   // slow, memory-hard-ish
 *   aesKey    = HKDF-Expand-SHA256(masterKey, info="minima-wallet-vault/aes-256-gcm/v1", 32)
 *   ciphertext= AES-256-GCM(aesKey, nonce, plaintext, AAD=header)                 // 128-bit tag
 * </pre>
 *
 * <p><b>PBKDF2 is implemented by hand over {@link Mac} HmacSHA256</b> (rather than via
 * {@code SecretKeyFactory PBKDF2WithHmacSHA256}) precisely so the derivation is byte-identical on the
 * JVM (unit tests) and on Android — some platforms differ in how {@code PBEKeySpec}'s {@code char[]}
 * password is encoded. Here the passphrase is hashed as raw UTF-8 bytes, deterministically, everywhere.
 * The unit tests exercise THIS code, so what they prove (round-trip + portability) is exactly what runs
 * on-device.
 *
 * <h3>Container layout (all big-endian)</h3>
 * <pre>
 *   "MVLT"(4) | version(1) | kdfId(1) | iterations(4) | saltLen(1) | salt | nonceLen(1) | nonce | ct+tag
 * </pre>
 * The entire header (everything before the ciphertext) is fed to GCM as AAD, so tampering with the
 * KDF parameters, salt or nonce fails authentication (no silent downgrade).
 *
 * <p>Pure Java (JCE only), no Android imports — directly unit-testable off-device.
 */
public final class VaultCrypto {

    /** Magic marker for a wallet vault container. */
    private static final byte[] MAGIC = new byte[]{'M', 'V', 'L', 'T'};

    /** Container format version. */
    private static final byte VERSION = 1;

    /** KDF id 1 = PBKDF2-HMAC-SHA256 → HKDF-Expand-SHA256. */
    private static final byte KDF_PBKDF2_SHA256 = 1;

    /**
     * PBKDF2 iteration count. 210,000 is the OWASP-2023 recommendation for PBKDF2-HMAC-SHA256. It is
     * stored in every blob, so it can be raised for future blobs without breaking old ones.
     */
    public static final int DEFAULT_ITERATIONS = 210_000;

    private static final int SALT_LEN  = 16;   // 128-bit random salt
    private static final int NONCE_LEN = 12;   // 96-bit GCM nonce (recommended)
    private static final int KEY_LEN   = 32;   // AES-256
    private static final int TAG_BITS  = 128;  // GCM tag
    private static final int HASH_LEN  = 32;   // SHA-256 output

    private static final byte[] HKDF_INFO =
            "minima-wallet-vault/aes-256-gcm/v1".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RNG = new SecureRandom();

    private VaultCrypto() {}

    /** Thrown when decryption fails — wrong passphrase, corrupt blob, or tampering. */
    public static final class BadPassphraseException extends GeneralSecurityException {
        public BadPassphraseException(String zMsg) { super(zMsg); }
    }

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    /** Encrypt {@code zPlain} under {@code zPassphrase} at the default iteration count. */
    public static byte[] encrypt(String zPassphrase, byte[] zPlain) throws GeneralSecurityException {
        return encrypt(zPassphrase, zPlain, DEFAULT_ITERATIONS);
    }

    /**
     * Encrypt {@code zPlain} under {@code zPassphrase}, producing a portable self-describing container.
     * A fresh random salt + nonce are generated per call, so two encryptions of the same plaintext
     * differ (and nonce reuse under one key never happens).
     */
    public static byte[] encrypt(String zPassphrase, byte[] zPlain, int zIterations)
            throws GeneralSecurityException {

        byte[] salt  = randomBytes(SALT_LEN);
        byte[] nonce = randomBytes(NONCE_LEN);

        byte[] aesKey = deriveAesKey(zPassphrase, salt, zIterations);
        byte[] header = header(zIterations, salt, nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(header);
        byte[] ct = cipher.doFinal(zPlain);

        Arrays.fill(aesKey, (byte) 0);

        ByteArrayOutputStream out = new ByteArrayOutputStream(header.length + ct.length);
        out.write(header, 0, header.length);
        out.write(ct, 0, ct.length);
        return out.toByteArray();
    }

    /**
     * Decrypt a container produced by {@link #encrypt}. Throws {@link BadPassphraseException} on a wrong
     * passphrase or any tampering (GCM authentication failure), never returning garbage plaintext.
     */
    public static byte[] decrypt(String zPassphrase, byte[] zContainer) throws GeneralSecurityException {
        if (zContainer == null || zContainer.length < MAGIC.length + 1 + 1 + 4 + 1 + SALT_LEN + 1 + NONCE_LEN) {
            throw new BadPassphraseException("Vault blob too short / not a vault");
        }
        int p = 0;
        for (byte b : MAGIC) {
            if (zContainer[p++] != b) throw new BadPassphraseException("Not a Minima vault blob (bad magic)");
        }
        byte version = zContainer[p++];
        if (version != VERSION) throw new BadPassphraseException("Unsupported vault version: " + version);
        byte kdf = zContainer[p++];
        if (kdf != KDF_PBKDF2_SHA256) throw new BadPassphraseException("Unsupported KDF id: " + kdf);

        int iterations = ((zContainer[p++] & 0xFF) << 24) | ((zContainer[p++] & 0xFF) << 16)
                | ((zContainer[p++] & 0xFF) << 8) | (zContainer[p++] & 0xFF);
        if (iterations < 1) throw new BadPassphraseException("Invalid iteration count");

        int saltLen = zContainer[p++] & 0xFF;
        if (saltLen < 1 || p + saltLen > zContainer.length) throw new BadPassphraseException("Bad salt length");
        byte[] salt = Arrays.copyOfRange(zContainer, p, p + saltLen);
        p += saltLen;

        int nonceLen = zContainer[p++] & 0xFF;
        if (nonceLen < 1 || p + nonceLen > zContainer.length) throw new BadPassphraseException("Bad nonce length");
        byte[] nonce = Arrays.copyOfRange(zContainer, p, p + nonceLen);
        p += nonceLen;

        byte[] header = Arrays.copyOfRange(zContainer, 0, p);
        byte[] ct = Arrays.copyOfRange(zContainer, p, zContainer.length);

        byte[] aesKey = deriveAesKey(zPassphrase, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            return cipher.doFinal(ct);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException etc → wrong passphrase or tampered blob.
            throw new BadPassphraseException("Wrong passphrase or corrupt vault");
        } finally {
            Arrays.fill(aesKey, (byte) 0);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // KDF
    // ---------------------------------------------------------------------------------------------

    /** PBKDF2 master key → HKDF-Expand → 32-byte AES key. */
    private static byte[] deriveAesKey(String zPassphrase, byte[] zSalt, int zIterations)
            throws GeneralSecurityException {
        byte[] pw = (zPassphrase == null ? "" : zPassphrase).getBytes(StandardCharsets.UTF_8);
        byte[] master = pbkdf2HmacSha256(pw, zSalt, zIterations, KEY_LEN);
        Arrays.fill(pw, (byte) 0);
        byte[] key = hkdfExpandSha256(master, HKDF_INFO, KEY_LEN);
        Arrays.fill(master, (byte) 0);
        return key;
    }

    /** Deterministic PBKDF2-HMAC-SHA256 (RFC 2898) over raw UTF-8 password bytes. */
    static byte[] pbkdf2HmacSha256(byte[] zPassword, byte[] zSalt, int zIterations, int zDkLen)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(zPassword, "HmacSHA256"));

        int blocks = (zDkLen + HASH_LEN - 1) / HASH_LEN;
        byte[] out = new byte[blocks * HASH_LEN];

        for (int i = 1; i <= blocks; i++) {
            // U1 = HMAC(pw, salt || INT_32_BE(i))
            mac.update(zSalt);
            mac.update((byte) (i >>> 24));
            mac.update((byte) (i >>> 16));
            mac.update((byte) (i >>> 8));
            mac.update((byte) i);
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int j = 1; j < zIterations; j++) {
                u = mac.doFinal(u);                 // U_{k} = HMAC(pw, U_{k-1})
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            System.arraycopy(t, 0, out, (i - 1) * HASH_LEN, HASH_LEN);
        }
        return Arrays.copyOf(out, zDkLen);
    }

    /** HKDF-Expand (RFC 5869) with SHA-256; {@code zLen} must be ≤ 32 (one output block). */
    static byte[] hkdfExpandSha256(byte[] zPrk, byte[] zInfo, int zLen) throws GeneralSecurityException {
        if (zLen > HASH_LEN) throw new IllegalArgumentException("hkdfExpand len must be <= 32");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(zPrk, "HmacSHA256"));
        mac.update(zInfo);
        mac.update((byte) 0x01);                    // T(1) counter
        byte[] t = mac.doFinal();
        return Arrays.copyOf(t, zLen);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static byte[] header(int zIterations, byte[] zSalt, byte[] zNonce) {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.write(MAGIC, 0, MAGIC.length);
        h.write(VERSION);
        h.write(KDF_PBKDF2_SHA256);
        h.write((zIterations >>> 24) & 0xFF);
        h.write((zIterations >>> 16) & 0xFF);
        h.write((zIterations >>> 8) & 0xFF);
        h.write(zIterations & 0xFF);
        h.write(zSalt.length & 0xFF);
        h.write(zSalt, 0, zSalt.length);
        h.write(zNonce.length & 0xFF);
        h.write(zNonce, 0, zNonce.length);
        return h.toByteArray();
    }

    private static byte[] randomBytes(int zLen) {
        byte[] b = new byte[zLen];
        RNG.nextBytes(b);
        return b;
    }
}
