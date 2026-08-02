package com.eurobuddha.wallet;

/**
 * Which wallet-key derivation a vault uses. FUND-CRITICAL: this decides both which private key signs
 * and which one-time-use counter guards it, and the two must never be crossed.
 *
 * <ul>
 *   <li>{@link #V1} — LEGACY. The wallet key was {@code hashObjects(baseSeed, 0)} — the SAME formula a
 *       Minima node uses for its key index 0. Whether it is literally the same key depends on whether
 *       the base seeds coincide (they do for a node started with {@code -anyseed} on the same lowercase
 *       phrase; see {@code SeedDerive}). Where they do, the node and the app sign with ONE Winternitz
 *       key while each keeps its OWN uses counter, neither able to see the other. A v1 vault can still
 *       derive its old address so funds can be swept off it, but v1 must not be used for new signing
 *       beyond that sweep, and its counter cannot be trusted from local state alone.</li>
 *   <li>{@link #V2} — CURRENT. The wallet key comes from a domain-separated HKDF child of the Minima
 *       seed ({@code SeedDerive.walletSeedFromPhrase}), which a node cannot derive. FreezePeach is the
 *       sole signer, so its counter is the only counter and reuse is structurally impossible.</li>
 * </ul>
 *
 * <p>The counters live in separate namespaces ({@link PrefsKeyUses}), so a v1 and a v2 count for the
 * same key index can never overwrite one another.
 */
public enum Derivation {

    V1("v1", ""),
    V2("v2", "v2");

    /** Tag persisted in the vault blob. */
    public final String tag;

    /** {@link PrefsKeyUses} namespace. V1 is {@code ""} so existing {@code uses_<i>} keys are untouched. */
    public final String keyUsesNamespace;

    Derivation(String zTag, String zNamespace) {
        tag = zTag;
        keyUsesNamespace = zNamespace;
    }

    /** Parse a persisted tag; anything unrecognised or absent is treated as the LEGACY v1 (safe default:
     *  it never silently promotes an old vault to the new key space). */
    public static Derivation fromTag(String zTag) {
        return V2.tag.equals(zTag) ? V2 : V1;
    }
}
