package com.eurobuddha.freezepeach;

import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.Hkdf;
import com.goterl.lazysodium.LazySodium;

import org.bouncycastle.crypto.digests.SHA3Digest;

import java.nio.charset.StandardCharsets;

/**
 * Derives FreezePeach's identities from a seed phrase:
 *
 * <pre>
 *   baseSeed = SHA3-256( clean(phrase) )                 // clean() = trim + LOWERCASE + single-space
 *   identity = HKDF(baseSeed, "freezepeach-box/sign-v1") // in CommsIdentity
 *   wallet   = HKDF(baseSeed, "freezepeach-wallet-v1")   // walletSeedFromPhrase, below
 * </pre>
 *
 * This is what makes contacts reach you on any device — your identity is your seed phrase, so the same
 * phrase reproduces the same id on Android, on a future iPhone, anywhere.
 *
 * <p><b>Relationship to a Minima node (corrected).</b> An earlier comment here claimed {@link #clean} was
 * minima-core's {@code BIP39.cleanSeedPhrase} and that the base seed therefore matched a node's. It does
 * not: {@code cleanSeedPhrase} canonicalises each word against the BIP39 word list and returns the phrase
 * UPPERCASED, whereas this lowercases. For a standard mnemonic the two base seeds differ, so this app's
 * addresses are not a node's addresses. They DO coincide for a node started with {@code -anyseed} on the
 * same lowercase text, which is why the wallet key is additionally domain-separated below.
 *
 * <p>The lowercasing is deliberately left as it is: changing it would change every existing user's chat
 * identity and wallet address.
 */
public final class SeedDerive {

    public static byte[] seedFromPhrase(String phrase) {
        byte[] in = clean(phrase).getBytes(StandardCharsets.UTF_8);
        SHA3Digest d = new SHA3Digest(256);          // NIST SHA3-256, matching minima-core Crypto.hashData
        d.update(in, 0, in.length);
        byte[] out = new byte[d.getDigestSize()];
        d.doFinal(out, 0);
        return out;
    }

    public static CommsIdentity identityFromPhrase(LazySodium ls, String phrase) {
        return CommsIdentity.fromSeed(ls, seedFromPhrase(phrase));
    }

    /** HKDF domain for the WALLET sub-seed. Bump only with a new vault {@code derivation} tag. */
    public static final String WALLET_CONTEXT = "freezepeach-wallet-v1";

    /**
     * The FUND-CRITICAL wallet base seed — a domain-separated CHILD of the Minima seed, not the Minima
     * seed itself:
     *
     * <pre>
     *   walletSeed = HKDF-SHA256( baseSeed, "freezepeach-wallet-v1", 32 )
     * </pre>
     *
     * <p><b>Why this exists.</b> A Minima node derives its keys as
     * {@code hashObjects(baseSeed, MiniData(BigInteger(n)))} for n = 0, 1, 2 … and always creates key
     * index 0 at startup. FreezePeach used to derive its ONLY key by that same formula from its own base
     * seed. Wherever the two base seeds coincide — a node started with {@code -anyseed} on the same
     * lowercase phrase (see the class javadoc) — the app and the node hold the SAME Winternitz key while
     * each keeps a private uses counter. Neither can see the other's, nothing on-chain enforces leaf
     * monotonicity, and signing one leaf twice leaks its private material.
     *
     * <p>Because this seed is an HKDF child, the node can never compute it: every key it can derive is
     * {@code hashObjects(baseSeed, n)}, and this is not of that form for any n. FreezePeach is therefore
     * the sole signer of its own key, and that whole class of collision is removed by construction
     * rather than avoided by procedure.
     *
     * <p>The chat identity ({@link #identityFromPhrase}) is unchanged — it was ALREADY domain-separated
     * this way, which is exactly the pattern this follows.
     */
    public static byte[] walletSeedFromPhrase(String phrase) {
        return Hkdf.derive(seedFromPhrase(phrase), WALLET_CONTEXT, 32);
    }

    /**
     * The LEGACY (v1) wallet base seed: the bare Minima seed, shared with the node's key space. Kept
     * ONLY so an existing install can still derive its old address and sweep funds off it. Never use
     * this for a new wallet.
     */
    public static byte[] legacyWalletSeedFromPhrase(String phrase) {
        return seedFromPhrase(phrase);
    }

    /**
     * Trim, split on whitespace, LOWERCASE each token, single-space join.
     *
     * <p>NOT minima-core's {@code BIP39.cleanSeedPhrase}, despite what this comment used to say — that
     * one prefix-matches the BIP39 word list, rejects unknown words and UPPERCASES. This accepts any
     * text (the setup screen allows a memorable phrase) and lowercases. Every shipped identity and
     * address derives from this exact behaviour, so it must not change.
     */
    static String clean(String phrase) {
        StringBuilder sb = new StringBuilder();
        for (String tok : phrase.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok.toLowerCase());
        }
        return sb.toString();
    }

    private SeedDerive() {}
}
