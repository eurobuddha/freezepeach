package com.eurobuddha.wallet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The vault container, blob format and restore rules — the parts the class javadocs describe as
 * fund-critical but which had no test until the key-reuse incident.
 */
public class VaultTest {

    private static final String PHRASE = "spirit lounge fabric ignore mango velvet ripple orbit";
    private static final String PASS   = "correct horse battery";

    // ---------------------------------------------------------------------------------------------
    // VaultCrypto
    // ---------------------------------------------------------------------------------------------

    /** Low iteration count: these tests exercise the CONSTRUCTION, not the KDF cost. */
    private static final int FAST_ITERS = 1000;

    @Test public void roundTrips() throws Exception {
        byte[] plain = "the seed".getBytes(StandardCharsets.UTF_8);
        byte[] box = VaultCrypto.encrypt(PASS, plain, FAST_ITERS);
        assertArrayEquals(plain, VaultCrypto.decrypt(PASS, box));
    }

    @Test public void wrongPassphraseIsRejectedNotGuessed() throws Exception {
        byte[] box = VaultCrypto.encrypt(PASS, "the seed".getBytes(StandardCharsets.UTF_8), FAST_ITERS);
        try {
            VaultCrypto.decrypt(PASS + "!", box);
            fail("a wrong passphrase must throw, never return garbage plaintext");
        } catch (VaultCrypto.BadPassphraseException expected) { /* correct */ }
    }

    @Test public void encryptionIsRandomisedPerCall() throws Exception {
        byte[] plain = "the seed".getBytes(StandardCharsets.UTF_8);
        byte[] a = VaultCrypto.encrypt(PASS, plain, FAST_ITERS);
        byte[] b = VaultCrypto.encrypt(PASS, plain, FAST_ITERS);
        assertFalse("a fresh salt+nonce must make two encryptions differ",
                java.util.Arrays.equals(a, b));
    }

    /** The whole header is GCM AAD, so tampering with the KDF parameters must fail authentication
     *  rather than silently downgrade the derivation. */
    @Test public void headerTamperingIsDetected() throws Exception {
        byte[] box = VaultCrypto.encrypt(PASS, "the seed".getBytes(StandardCharsets.UTF_8), FAST_ITERS);
        box[6] ^= 0x01;                      // flip a bit inside the iteration count
        try {
            VaultCrypto.decrypt(PASS, box);
            fail("a tampered header must not authenticate");
        } catch (java.security.GeneralSecurityException expected) { /* correct */ }
    }

    @Test public void ciphertextTamperingIsDetected() throws Exception {
        byte[] box = VaultCrypto.encrypt(PASS, "the seed".getBytes(StandardCharsets.UTF_8), FAST_ITERS);
        box[box.length - 1] ^= 0x01;
        try {
            VaultCrypto.decrypt(PASS, box);
            fail("a tampered ciphertext must not authenticate");
        } catch (VaultCrypto.BadPassphraseException expected) { /* correct */ }
    }

    @Test public void nonVaultBytesAreRejected() {
        try {
            VaultCrypto.decrypt(PASS, "not a vault at all, just some bytes".getBytes(StandardCharsets.UTF_8));
            fail("bad magic must be rejected");
        } catch (java.security.GeneralSecurityException expected) { /* correct */ }
    }

    // ---------------------------------------------------------------------------------------------
    // VaultBlob
    // ---------------------------------------------------------------------------------------------

    @Test public void blobRoundTripsIncludingDerivation() {
        Map<Integer, Integer> uses = new LinkedHashMap<>();
        uses.put(0, 7); uses.put(3, 2);
        VaultBlob in = new VaultBlob(PHRASE, true, uses, Derivation.V2);
        VaultBlob out = VaultBlob.fromBytes(in.toBytes());
        assertEquals(PHRASE, out.getPhrase());
        assertTrue(out.isKeyUsesTrusted());
        assertEquals(Derivation.V2, out.getDerivation());
        assertEquals(Integer.valueOf(7), out.getKeyUses().get(0));
        assertEquals(Integer.valueOf(2), out.getKeyUses().get(3));
    }

    /** A blob written before v2 has no derivation tag. It must read back as the LEGACY key — defaulting
     *  it to v2 would point an existing wallet at a different address and orphan its funds. */
    @Test public void preV2BlobDefaultsToLegacyDerivation() {
        String legacyJson = "{\"v\":1,\"phrase\":\"" + PHRASE + "\",\"trusted\":true,\"keyuses\":{\"0\":4}}";
        VaultBlob out = VaultBlob.fromBytes(legacyJson.getBytes(StandardCharsets.UTF_8));
        assertEquals(Derivation.V1, out.getDerivation());
        assertEquals(PHRASE, out.getPhrase());
        assertEquals(Integer.valueOf(4), out.getKeyUses().get(0));
    }

    @Test public void unknownDerivationTagFallsBackToLegacy() {
        String json = "{\"v\":9,\"phrase\":\"x\",\"trusted\":false,\"derivation\":\"v99\",\"keyuses\":{}}";
        assertEquals(Derivation.V1, VaultBlob.fromBytes(json.getBytes(StandardCharsets.UTF_8)).getDerivation());
    }

    @Test public void reconcileIntoOnlyRaises() {
        MemKeyUses live = new MemKeyUses();
        live.recordExternalUses(0, 50);
        Map<Integer, Integer> stale = new LinkedHashMap<>();
        stale.put(0, 5);
        new VaultBlob(PHRASE, true, stale, Derivation.V2).reconcileInto(live);
        assertEquals("a stale snapshot must never lower the counter", 50, live.currentUses(0));
    }

    // ---------------------------------------------------------------------------------------------
    // SeedVault restore rules (C1)
    // ---------------------------------------------------------------------------------------------

    @Test public void restoreIsNeverAutoTrusted() {
        MemKeyUses live = new MemKeyUses();
        Map<Integer, Integer> uses = new LinkedHashMap<>();
        uses.put(0, 9);
        VaultBlob restored = new VaultBlob(PHRASE, true, uses, Derivation.V2);   // backup claims trusted
        VaultBlob installed = SeedVault.reconcileRestored(restored, live);
        assertFalse("a backup can be stale, so a restore must land untrusted", installed.isKeyUsesTrusted());
        assertEquals(9, live.currentUses(0));
        assertEquals("a restore must stay on the derivation it was taken from",
                Derivation.V2, installed.getDerivation());
    }

    @Test public void restoreDoesNotLowerAHigherLiveCounter() {
        MemKeyUses live = new MemKeyUses();
        live.recordExternalUses(0, 40);
        Map<Integer, Integer> uses = new LinkedHashMap<>();
        uses.put(0, 3);
        VaultBlob installed = SeedVault.reconcileRestored(
                new VaultBlob(PHRASE, true, uses, Derivation.V2), live);
        assertEquals(40, live.currentUses(0));
        assertEquals(Integer.valueOf(40), installed.getKeyUses().get(0));
    }

    @Test public void restoreKeepsHigherIndicesTheBackupDidNotKnowAbout() {
        MemKeyUses live = new MemKeyUses();
        live.recordExternalUses(5, 11);
        Map<Integer, Integer> uses = new LinkedHashMap<>();
        uses.put(0, 1);
        VaultBlob installed = SeedVault.reconcileRestored(
                new VaultBlob(PHRASE, true, uses, Derivation.V1), live);
        assertEquals("a later restore must not silently reset a higher index",
                Integer.valueOf(11), installed.getKeyUses().get(5));
    }

    // ---------------------------------------------------------------------------------------------
    // Session round-trip through a real (in-memory) store
    // ---------------------------------------------------------------------------------------------

    @Test public void openRestoresDerivationAndTrust() {
        MemBlobStore store = new MemBlobStore();
        MemKeyUses v1 = new MemKeyUses(), v2 = new MemKeyUses();
        SeedVault a = new SeedVault(store, v1, v2);
        a.createV2(PHRASE, PASS, true);
        a.lock();

        SeedVault b = new SeedVault(store, v1, v2);
        assertTrue(b.open(PASS));
        assertEquals(Derivation.V2, b.derivation());
        assertTrue(b.isKeyUsesTrusted());
        assertEquals(PHRASE, b.phrase());
    }

    @Test public void wrongPassphraseLeavesTheSessionClosed() {
        MemBlobStore store = new MemBlobStore();
        SeedVault a = new SeedVault(store, new MemKeyUses(), new MemKeyUses());
        a.createV2(PHRASE, PASS, true);
        a.lock();
        assertFalse(a.open("wrong passphrase"));
        assertFalse(a.isOpen());
    }

    /** syncKeyUses is what keeps an exported backup honest — without it a backup taken after N sends
     *  still claims 0, and restoring it hands out already-spent leaves. */
    @Test public void syncKeyUsesRefreshesTheExportedSnapshot() throws Exception {
        MemBlobStore store = new MemBlobStore();
        MemKeyUses v2 = new MemKeyUses();
        SeedVault v = new SeedVault(store, new MemKeyUses(), v2);
        v.createV2(PHRASE, PASS, true);

        for (int i = 0; i < 3; i++) v2.reserveNextUse(0);   // three sends
        v.syncKeyUses(0);

        VaultBlob exported = SeedVault.peekImport(v.exportBytes(PASS), PASS);
        assertEquals("the backup must reflect leaves already consumed",
                Integer.valueOf(3), exported.getKeyUses().get(0));
    }

    @Test public void changePassphraseKeepsSeedTrustAndDerivation() {
        MemBlobStore store = new MemBlobStore();
        MemKeyUses v1 = new MemKeyUses(), v2 = new MemKeyUses();
        SeedVault v = new SeedVault(store, v1, v2);
        v.createV2(PHRASE, PASS, true);
        v.changePassphrase("a brand new passphrase");
        v.lock();

        SeedVault reopened = new SeedVault(store, v1, v2);
        assertFalse("the old passphrase must stop working", reopened.open(PASS));
        assertTrue(reopened.open("a brand new passphrase"));
        assertEquals(PHRASE, reopened.phrase());
        assertEquals(Derivation.V2, reopened.derivation());
        assertTrue(reopened.isKeyUsesTrusted());
    }
}
