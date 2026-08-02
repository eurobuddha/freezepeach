package com.eurobuddha.wallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

import com.eurobuddha.comms.Hkdf;

import org.minima.objects.base.MiniData;
import org.minima.utils.Crypto;

/**
 * Regression cover for the production incident: three Winternitz one-time leaves reused in quick
 * succession, compromising a key that also guarded funds on the user's Minima node.
 *
 * <p>The PRIMARY cause was FreezePeach reusing leaves against ITSELF: the vault's portable keyuses
 * snapshot was never refreshed after a send, so every exported backup still recorded zero uses. Restore
 * that backup (or set the same phrase up on a second device) and the live counter starts at zero again,
 * while the attestation dialog pre-filled zero as its default answer. Accepting it signed leaves 0, 1, 2
 * a second time — three reuses in quick succession.
 *
 * <p>A SECONDARY, conditional hazard: the v1 wallet key was {@code hashObjects(baseSeed, 0)}, the same
 * formula a Minima node uses for its key index 0. The base seeds usually differ (see
 * {@link #baseSeedIsCaseDivergedFromMinimaCoresCanonicalPhrase}), but a node started with
 * {@code -anyseed} on the same text collides exactly. {@link Derivation#V2} removes that class entirely.
 *
 * <p>These tests pin the fixes for both.
 */
public class KeyReuseRegressionTest {

    private static final String PHRASE = "spirit lounge fabric ignore mango velvet ripple orbit";

    /** The exact key a Minima node derives for index n: {@code hashObjects(baseSeed, MiniData(n))}. */
    private static MiniData nodeKeySeed(byte[] zBaseSeed, int zIndex) {
        return Crypto.getInstance().hashObjects(new MiniData(zBaseSeed),
                new MiniData(BigInteger.valueOf(zIndex)));
    }

    /** SHA3-256(cleanSeedPhrase) — what both minima-core and FreezePeach call the base seed. */
    private static byte[] baseSeed(String zPhrase) {
        return com.eurobuddha.freezepeach.SeedDerive.seedFromPhrase(zPhrase);
    }

    /**
     * Pins the ACTUAL relationship between FreezePeach's base seed and a Minima node's, which is not what
     * {@code SeedDerive}'s original comment claimed.
     *
     * <p>FreezePeach hashes the phrase LOWERCASED; minima-core's {@code BIP39.cleanSeedPhrase} canonicalises
     * against the BIP39 word list and UPPERCASES. So for a standard mnemonic the two base seeds differ,
     * and the app's key index 0 is NOT the node's key index 0.
     *
     * <p>The exception is a node started with {@code -anyseed}, which takes the phrase verbatim: give it
     * the same lowercase text and the seeds — and therefore key index 0 — DO collide. That is the case
     * {@link Derivation#V2} removes.
     *
     * <p>This behaviour is pinned rather than "fixed": changing the lowercasing would change every
     * existing user's chat identity and wallet address.
     */
    @Test public void baseSeedIsCaseDivergedFromMinimaCoresCanonicalPhrase() {
        String canonical = org.minima.utils.BIP39.cleanSeedPhrase(PHRASE);   // UPPERCASE canonical form
        assertNotEquals("uppercase-canonical and lowercase phrases must not be assumed equal",
                org.minima.utils.BIP39.convertStringToSeed(canonical).to0xString(),
                new MiniData(baseSeed(PHRASE)).to0xString());

        // ...but a verbatim (-anyseed) node fed the same lowercase text lands on the SAME base seed.
        assertEquals("an -anyseed node using this exact text shares our base seed",
                org.minima.utils.BIP39.convertStringToSeed(PHRASE).to0xString(),
                new MiniData(baseSeed(PHRASE)).to0xString());
    }

    /** The SHIPPED derivation — call the real thing so a change to it fails this test. */
    private static byte[] walletSeed(String zPhrase) {
        return com.eurobuddha.freezepeach.SeedDerive.walletSeedFromPhrase(zPhrase);
    }

    // ---------------------------------------------------------------------------------------------
    // Defect 1: the wallet key must be OUTSIDE the node's key space
    // ---------------------------------------------------------------------------------------------

    /**
     * The v2 wallet seed must not equal any key a node derives from the same base seed. A node walks
     * indices 0, 1, 2 … from its base seed; if our key were any of them, both wallets could be signing
     * one key behind two counters that cannot see each other.
     *
     * <p>This is checked against the base seed FreezePeach actually produces, which is the one an
     * {@code -anyseed} node would share — the only configuration where v1 genuinely collided.
     */
    @Test public void walletSeedIsOutsideTheNodeKeySpace() {
        byte[] base = baseSeed(PHRASE);
        MiniData ours = new MiniData(walletSeed(PHRASE));

        assertNotEquals("v2 wallet key must not be the node's key index 0 — that collision reused leaves "
                        + "in production",
                nodeKeySeed(base, 0).to0xString(), ours.to0xString());

        // A node grows its key set on demand, so check well past the default set too.
        for (int i = 0; i < 128; i++) {
            assertNotEquals("v2 wallet key collides with node key index " + i,
                    nodeKeySeed(base, i).to0xString(), ours.to0xString());
        }
    }

    /** The LEGACY derivation is exactly the node's key 0 — the bug, pinned so nobody "fixes" v1 into
     *  something else and silently changes where existing funds live. */
    @Test public void legacySeedIsExactlyTheNodesKeyZero() {
        byte[] base = baseSeed(PHRASE);
        assertEquals(nodeKeySeed(base, 0).to0xString(),
                Crypto.getInstance().hashObjects(new MiniData(base), new MiniData(BigInteger.ZERO)).to0xString());
    }

    /** Domain separation must also hold against the CHAT sub-keys, so no two roles share key material. */
    @Test public void walletSeedDiffersFromTheChatSubSeeds() {
        byte[] base = baseSeed(PHRASE);
        String wallet = new MiniData(walletSeed(PHRASE)).to0xString();
        assertNotEquals(wallet, new MiniData(Hkdf.derive(base, "freezepeach-box-v1", 32)).to0xString());
        assertNotEquals(wallet, new MiniData(Hkdf.derive(base, "freezepeach-sign-v1", 32)).to0xString());
        assertNotEquals(wallet, new MiniData(base).to0xString());
    }

    /** Derivation must stay deterministic — a drift here silently orphans everyone's funds. */
    @Test public void walletSeedIsDeterministic() {
        assertEquals(new MiniData(walletSeed(PHRASE)).to0xString(),
                     new MiniData(walletSeed(PHRASE)).to0xString());
        assertNotEquals(new MiniData(walletSeed(PHRASE)).to0xString(),
                        new MiniData(walletSeed(PHRASE + " x")).to0xString());
    }

    // ---------------------------------------------------------------------------------------------
    // Defect 2: an imported phrase must never be trusted at zero
    // ---------------------------------------------------------------------------------------------

    private static SeedVault vault(MemKeyUses v1, MemKeyUses v2) {
        return new SeedVault(new MemBlobStore(), v1, v2);
    }

    @Test public void createV2WithoutProofIsNotTrusted() {
        SeedVault v = vault(new MemKeyUses(), new MemKeyUses());
        v.createV2(PHRASE, "passphrase123", false);
        assertFalse("an unproven import must not be trusted", v.isKeyUsesTrusted());
        try {
            v.assertSigningAllowed();
            fail("signing must be blocked while the count is unknown");
        } catch (SeedVault.SigningNotAllowedException expected) { /* correct */ }
    }

    @Test public void createV2WithProofIsTrustedOnTheV2Key() {
        MemKeyUses v1 = new MemKeyUses(), v2 = new MemKeyUses();
        SeedVault v = vault(v1, v2);
        v.createV2(PHRASE, "passphrase123", true);
        assertTrue(v.isKeyUsesTrusted());
        assertEquals(Derivation.V2, v.derivation());
        v.assertSigningAllowed();   // must not throw
    }

    @Test public void recoverV2RaisesTheCounterBeforeTrusting() {
        MemKeyUses v1 = new MemKeyUses(), v2 = new MemKeyUses();
        SeedVault v = vault(v1, v2);
        v.recoverV2(PHRASE, "passphrase123", 37);
        assertTrue(v.isKeyUsesTrusted());
        assertEquals("the counter must be raised BEFORE signing is unblocked", 37, v2.currentUses(0));
        assertEquals("the legacy counter is a different key and must be untouched", 0, v1.currentUses(0));
    }

    /** Attesting a LOWER count must never walk the counter backwards. */
    @Test public void attestationCanOnlyRaise() {
        MemKeyUses v2 = new MemKeyUses();
        SeedVault v = vault(new MemKeyUses(), v2);
        v.recoverV2(PHRASE, "passphrase123", 100);
        v.attestKeyUses(0, 5);
        assertEquals(100, v2.currentUses(0));
    }

    /** A migrated legacy vault keeps its address but must NOT be trusted: that key is shared with a node
     *  whose count this device cannot see. */
    @Test public void legacyMigrationStaysUntrustedOnV1() {
        MemKeyUses v1 = new MemKeyUses(), v2 = new MemKeyUses();
        SeedVault v = vault(v1, v2);
        v.migrateLegacy(PHRASE, "passphrase123");
        assertEquals(Derivation.V1, v.derivation());
        assertFalse("a node-shared key can never be trusted from local state alone", v.isKeyUsesTrusted());
    }

    // ---------------------------------------------------------------------------------------------
    // Counter namespaces + the legacy sweep gate
    // ---------------------------------------------------------------------------------------------

    /** v1 and v2 are different keys at the same index; their counts must not bleed into each other. */
    @Test public void derivationsKeepSeparateCounters() {
        MemKeyUses v1 = new MemKeyUses(), v2 = new MemKeyUses();
        SeedVault v = vault(v1, v2);
        v.migrateLegacy(PHRASE, "passphrase123");
        v.attestKeyUses(0, 12);                 // active == V1
        assertEquals(12, v1.currentUses(0));
        assertEquals("v2's counter must be untouched by v1 activity", 0, v2.currentUses(0));

        v.upgradeToV2(false);
        assertEquals(Derivation.V2, v.derivation());
        assertEquals("upgrading must not import the shared key's count", 0, v2.currentUses(0));
        assertEquals(12, v1.currentUses(0));
    }

    @Test public void upgradeFromAMultiDeviceSeedIsNotTrusted() {
        SeedVault v = vault(new MemKeyUses(), new MemKeyUses());
        v.migrateLegacy(PHRASE, "passphrase123");
        v.upgradeToV2(true);                    // same seed already running FreezePeach elsewhere
        assertEquals(Derivation.V2, v.derivation());
        assertFalse("the v2 key is already in use on the other device", v.isKeyUsesTrusted());
    }

    @Test public void legacySweepIsGatedUntilAttested() {
        MemKeyUses v1 = new MemKeyUses();
        SeedVault v = vault(v1, new MemKeyUses());
        v.createV2(PHRASE, "passphrase123", true);
        try {
            v.assertLegacySigningAllowed();
            fail("the legacy key must not sign before its count is attested");
        } catch (SeedVault.SigningNotAllowedException expected) { /* correct */ }

        v.attestLegacyKeyUses(64);
        v.assertLegacySigningAllowed();         // must not throw
        assertEquals(64, v1.currentUses(0));
    }

    /** A legacy attestation must not survive a lock: the node can advance that counter meanwhile. */
    @Test public void legacyAttestationDoesNotSurviveLock() {
        SeedVault v = vault(new MemKeyUses(), new MemKeyUses());
        v.createV2(PHRASE, "passphrase123", true);
        v.attestLegacyKeyUses(64);
        assertTrue(v.isLegacyAttested());
        v.lock();
        assertTrue(v.open("passphrase123"));
        assertFalse("a stale attestation must not carry into a new session", v.isLegacyAttested());
    }

    // ---------------------------------------------------------------------------------------------
    // The counter contract itself
    // ---------------------------------------------------------------------------------------------

    /** No leaf may ever be handed out twice — the property whose violation leaks the key. */
    @Test public void reserveNeverRepeatsALeaf() {
        MemKeyUses uses = new MemKeyUses();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertTrue("leaf handed out twice", seen.add(uses.reserveNextUse(0)));
        }
        assertEquals(500, uses.currentUses(0));
    }

    /** A failed durable write must THROW rather than yield a leaf against an unpersisted advance. */
    @Test public void reserveThrowsRatherThanSignAgainstAnUnpersistedAdvance() {
        MemKeyUses uses = new MemKeyUses();
        uses.reserveNextUse(0);
        uses.failWrites = true;
        try {
            uses.reserveNextUse(0);
            fail("a leaf must not be returned when the advance cannot be persisted");
        } catch (IllegalStateException expected) { /* correct */ }
        assertEquals(1, uses.currentUses(0));
    }

    @Test public void concurrentReservesNeverCollide() throws Exception {
        final MemKeyUses uses = new MemKeyUses();
        final Set<Integer> seen = java.util.Collections.synchronizedSet(new HashSet<Integer>());
        final int threads = 8, each = 200;
        Thread[] ts = new Thread[threads];
        final boolean[] dup = { false };
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < each; i++) {
                    if (!seen.add(uses.reserveNextUse(0))) dup[0] = true;
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        assertFalse("two threads received the same one-time leaf", dup[0]);
        assertEquals(threads * each, uses.currentUses(0));
    }
}
