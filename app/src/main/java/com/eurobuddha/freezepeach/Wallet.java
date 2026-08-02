package com.eurobuddha.freezepeach;

import android.content.Context;

import com.eurobuddha.wallet.Derivation;
import com.eurobuddha.wallet.KeyUses;
import com.eurobuddha.wallet.WalletCore;
import com.eurobuddha.wallet.WalletSession;

import org.minima.objects.Address;
import org.minima.objects.base.MiniData;

/**
 * FreezePeach's in-app Minima wallet holder. Wraps the vendored local-signing engine
 * ({@link WalletCore}) and binds it to the ONE {@link KeyUses} counter store matching its
 * {@link Derivation}.
 *
 * <h3>Which seed drives it (the fund-critical part)</h3>
 * <ul>
 *   <li>{@link Derivation#V2} (current) — {@link SeedDerive#walletSeedFromPhrase}, an HKDF child of the
 *       Minima seed. A node derives its keys as {@code hashObjects(minimaSeed, n)} and can never produce
 *       this, so FreezePeach is the sole signer of this key and its counter is the only counter.</li>
 *   <li>{@link Derivation#V1} (legacy, sweep only) — the bare base seed, whose key index 0 uses the same
 *       formula as a node's and is literally the same key wherever the base seeds coincide (an
 *       {@code -anyseed} node on the same phrase). Construct this ONLY to read the old address or to
 *       sweep it, and only behind {@code SeedVault.assertLegacySigningAllowed()}.</li>
 * </ul>
 *
 * <p>The seed never leaves the device: signing is local; the node is only ever a relay. Address
 * derivation walks the WOTS tree (heavy) — call {@link #ensureAddress()} off the main thread and cache;
 * it is idempotent.
 */
public final class Wallet {

    private final WalletCore mCore;
    private final Derivation mDerivation;
    private volatile String mMx;   // Mx... receive address (index 0)
    private volatile String mHex;  // 0x... receive address (index 0)

    private Wallet(WalletCore zCore, Derivation zDerivation) {
        mCore = zCore;
        mDerivation = zDerivation;
    }

    /**
     * Build the wallet for {@code zDerivation} from the seed phrase, bound to the session's SHARED
     * counter store for that derivation.
     *
     * <p>The store must come from {@link WalletSession} and never be constructed here:
     * {@code PrefsKeyUses}' reserve-before-sign is {@code synchronized} PER INSTANCE, so a private
     * second instance over the same prefs files would not exclude the session's — reopening the very
     * race the counter exists to close.
     */
    public static Wallet forDerivation(Context zContext, String zPhrase, Derivation zDerivation) {
        byte[] seed = (zDerivation == Derivation.V2)
                ? SeedDerive.walletSeedFromPhrase(zPhrase)
                : SeedDerive.legacyWalletSeedFromPhrase(zPhrase);
        KeyUses uses = WalletSession.get(zContext).keyUses(zDerivation);
        return new Wallet(new WalletCore(new MiniData(seed), uses), zDerivation);
    }

    /** Derive the index-0 receive address once (heavy WOTS-root computation). Safe to call repeatedly. */
    public synchronized void ensureAddress() {
        if (mMx != null) return;
        Address a = mCore.getReceiveAddress();
        mMx = a.getMinimaAddress();
        mHex = a.getAddressData().to0xString();
    }

    public String mx()  { return mMx; }
    public String hex() { return mHex; }
    public WalletCore core() { return mCore; }
    public Derivation derivation() { return mDerivation; }
}
