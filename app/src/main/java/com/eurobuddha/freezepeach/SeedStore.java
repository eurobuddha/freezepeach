package com.eurobuddha.freezepeach;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the Minima seed (and, when known, its mnemonic phrase) at rest under a device-bound,
 * non-exportable Android Keystore key (AES-256-GCM, hardware-backed where available). The seed only
 * exists in cleartext in memory (unavoidable — it drives all crypto) and as keystore-encrypted bytes on
 * disk, so a device backup/extraction can't recover it. Combined with {@code allowBackup="false"} +
 * PrefsKeyUses' WOTS reuse guard, this is the funds-safety baseline.
 *
 * <p>The 256-bit base seed is always present; the mnemonic {@link #loadPhrase phrase} is present only for
 * wallets set up AFTER phrase-storage shipped (older installs kept only the derived base seed). Portable
 * cross-device backup therefore uses the base seed ("pairing code") which is always available.
 */
public final class SeedStore {

    private static final String KS         = "AndroidKeyStore";
    private static final String KEY_ALIAS  = "freezepeach_seed_key";
    private static final String P_ENC      = "seed_enc",   P_IV    = "seed_iv";     // base seed (hex)
    private static final String P_PH_ENC   = "phrase_enc", P_PH_IV = "phrase_iv";   // mnemonic phrase
    private static final String P_LEGACY   = "seed";       // pre-vault plaintext hex

    /** The 256-bit base seed hex, migrating any legacy plaintext seed into the encrypted store. */
    public static String load(SharedPreferences prefs) {
        String legacy = prefs.getString(P_LEGACY, null);
        if (legacy != null && !legacy.isEmpty()) {
            try { encrypt(prefs, legacy, P_ENC, P_IV); prefs.edit().remove(P_LEGACY).apply(); }
            catch (Exception e) { return legacy; }   // keystore unavailable — never lose the seed
            return legacy;
        }
        return decrypt(prefs, P_ENC, P_IV);
    }

    /** Encrypt + persist the base seed hex. Throws if the keystore fails. */
    public static void store(SharedPreferences prefs, String seedHex) throws Exception {
        encrypt(prefs, seedHex, P_ENC, P_IV);
        prefs.edit().remove(P_LEGACY).apply();
    }

    /** Store the mnemonic phrase (encrypted) so it can be revealed for backup. Best-effort. */
    public static void storePhrase(SharedPreferences prefs, String phrase) {
        try { encrypt(prefs, phrase, P_PH_ENC, P_PH_IV); } catch (Exception e) { /* reveal just won't be offered */ }
    }

    /** The stored mnemonic, or null if this wallet has no phrase saved (older installs / pairing-code import). */
    /** Wipe every device-bound seed copy (base seed, phrase, legacy) — after migrating into the SeedVault. */
    public static void clear(SharedPreferences prefs) {
        prefs.edit().remove(P_ENC).remove(P_IV).remove(P_PH_ENC).remove(P_PH_IV).remove(P_LEGACY).apply();
    }
    public static String loadPhrase(SharedPreferences prefs) {
        return decrypt(prefs, P_PH_ENC, P_PH_IV);
    }

    // ── crypto ──
    private static void encrypt(SharedPreferences prefs, String plaintext, String encKey, String ivKey) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        prefs.edit()
                .putString(encKey, Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(ivKey, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply();
    }

    private static String decrypt(SharedPreferences prefs, String encKey, String ivKey) {
        String enc = prefs.getString(encKey, null), iv = prefs.getString(ivKey, null);
        if (enc == null || iv == null) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(enc, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS); ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    private SeedStore() {}
}
