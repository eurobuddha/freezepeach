package com.eurobuddha.wallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.keys.Signature;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/**
 * The live signing path ({@link WalletCore#signTransactionID}) and the pure coin logic beneath the send
 * flow. The signing tests are the ones that matter: they pin "one signature consumes exactly one leaf,
 * and the advance is durable BEFORE the signature exists".
 */
public class SigningAndSelectionTest {

    private static final MiniData SEED = new MiniData("0x1122334455667788990011223344556677889900112233445566778899001122");
    private static final MiniData TXID = new MiniData("0xAABBCCDDAABBCCDDAABBCCDDAABBCCDDAABBCCDDAABBCCDDAABBCCDDAABBCCDD");

    @Test public void eachSignatureConsumesExactlyOneLeaf() {
        MemKeyUses uses = new MemKeyUses();
        WalletCore core = new WalletCore(SEED, uses);

        assertEquals(0, uses.currentUses(0));
        Signature a = core.signTransactionID(TXID, 0);
        assertEquals("one signature must consume exactly one leaf", 1, uses.currentUses(0));
        Signature b = core.signTransactionID(TXID, 0);
        assertEquals(2, uses.currentUses(0));

        // Same message, different leaves -> different leaf public keys. An identical leaf key across two
        // signatures over different data is precisely the reuse that leaks the key.
        assertNotEquals("consecutive signatures must come from different leaves",
                leafKey(a), leafKey(b));
    }

    /** The public key of the LAST proof in the chain — the one-time leaf that actually signed the data. */
    private static String leafKey(Signature zSig) {
        java.util.ArrayList<org.minima.objects.keys.SignatureProof> proofs = zSig.getAllSignatureProofs();
        return proofs.get(proofs.size() - 1).getPublicKey().to0xString();
    }

    /** If the advance cannot be persisted, NO signature may be produced. */
    @Test public void aFailedCounterWriteBlocksTheSignature() {
        MemKeyUses uses = new MemKeyUses();
        WalletCore core = new WalletCore(SEED, uses);
        uses.failWrites = true;
        try {
            core.signTransactionID(TXID, 0);
            fail("signing must not proceed when the uses advance cannot be persisted");
        } catch (IllegalStateException expected) { /* correct */ }
        assertEquals(0, uses.currentUses(0));
    }

    /** A counter already at the tree's limit must refuse rather than wrap back to leaf 0 — TreeKey.sign
     *  silently resets its own counter on overflow, so the guard has to live above it. */
    @Test public void anExhaustedKeyRefusesToSign() {
        MemKeyUses uses = new MemKeyUses();
        uses.recordExternalUses(0, Util.WOTS_MAX_USES);
        WalletCore core = new WalletCore(SEED, uses);
        try {
            core.signTransactionID(TXID, 0);
            fail("an exhausted key must refuse, never wrap around to leaf 0");
        } catch (IllegalStateException expected) { /* correct */ }
    }

    @Test public void derivationIsDeterministicAndIndexSeparated() {
        WalletCore core = new WalletCore(SEED, new MemKeyUses());
        assertEquals(core.derivePrivateSeed(0).to0xString(), core.derivePrivateSeed(0).to0xString());
        assertNotEquals(core.derivePrivateSeed(0).to0xString(), core.derivePrivateSeed(1).to0xString());
    }

    // ---------------------------------------------------------------------------------------------
    // Coin selection / aggregation
    // ---------------------------------------------------------------------------------------------

    /** {@link MiniNumber} has no {@code equals()} override — comparing with assertEquals would test
     *  object identity, not value. Always go through {@code isEqual}. */
    private static void assertAmount(String zExpected, MiniNumber zActual) {
        assertTrue("expected " + zExpected + " but was " + zActual, new MiniNumber(zExpected).isEqual(zActual));
    }

    @SuppressWarnings("unchecked")
    private static JSONObject coin(String zTokenId, String zAmount, String zCoinId) {
        JSONObject c = new JSONObject();
        c.put("tokenid", zTokenId);
        c.put("amount", zAmount);
        c.put("coinid", zCoinId);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static JSONArray coins(JSONObject... zCoins) {
        JSONArray a = new JSONArray();
        for (JSONObject c : zCoins) a.add(c);
        return a;
    }

    @Test public void selectionTakesTheFewestLargestCoins() {
        JSONArray all = coins(coin("0x00", "1", "0x01"), coin("0x00", "50", "0x02"), coin("0x00", "10", "0x03"));
        List<JSONObject> sel = CoinSelector.selectToCover(all, "0x00", new MiniNumber("55"));
        assertEquals(2, sel.size());
        assertAmount("60", CoinSelector.sumRaw(sel));
    }

    @Test public void selectionRefusesWhenShort() {
        JSONArray all = coins(coin("0x00", "1", "0x01"), coin("0x00", "2", "0x02"));
        try {
            CoinSelector.selectToCover(all, "0x00", new MiniNumber("10"));
            fail("must not select an amount it cannot cover");
        } catch (CoinSelector.InsufficientFundsException expected) { /* correct */ }
    }

    @Test public void selectionIgnoresOtherTokens() {
        JSONArray all = coins(coin("0x00", "100", "0x01"), coin("0xFEED", "100", "0x02"));
        List<JSONObject> sel = CoinSelector.selectToCover(all, "0xFEED", new MiniNumber("100"));
        assertEquals(1, sel.size());
        assertEquals("0x02", String.valueOf(sel.get(0).get("coinid")));
    }

    @Test public void aggregationGroupsPerTokenWithMinimaFirst() {
        JSONArray all = coins(coin("0xFEED", "5", "0x01"), coin("0x00", "3", "0x02"), coin("0x00", "4", "0x03"));
        List<CoinAggregator.Agg> aggs = CoinAggregator.aggregate(all);
        assertEquals(2, aggs.size());
        assertTrue("native Minima must be listed first", aggs.get(0).isMinima());
        assertAmount("7", aggs.get(0).rawTotal);
        assertEquals(2, aggs.get(0).count);
        assertAmount("5", aggs.get(1).rawTotal);
    }

    // ---------------------------------------------------------------------------------------------
    // Auto-lock policy
    // ---------------------------------------------------------------------------------------------

    @Test public void autoLockRules() {
        assertTrue("\"Immediately\" locks on every background return",
                SessionPolicy.shouldLock(1_000L, 1_000L, SessionPolicy.IMMEDIATELY_MS));
        assertFalse(SessionPolicy.shouldLock(1_000L, 2_000L, 5_000L));
        assertTrue(SessionPolicy.shouldLock(1_000L, 7_000L, 5_000L));
        assertFalse("exactly at the timeout is not yet stale",
                SessionPolicy.shouldLock(1_000L, 6_000L, 5_000L));
    }

    @Test public void passphraseMinimumIsEnforced() {
        assertFalse(SessionPolicy.passphraseMeetsMin("short"));
        assertFalse(SessionPolicy.passphraseMeetsMin(null));
        assertTrue(SessionPolicy.passphraseMeetsMin("longenough"));
    }
}
