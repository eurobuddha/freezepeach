package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;

import org.minima.objects.base.MiniData;
import org.minima.utils.BaseConverter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SeedVault — the portable, seed-derived-encrypted store for the wallet seed + WOTS keyuses snapshot.
 * This REPLACES the interim device-bound {@code SeedStore} (Android-Keystore {@code
 * EncryptedSharedPreferences}, which does not restore across devices) with a container that is
 * decryptable on ANY device given the wallet passphrase (see {@link VaultCrypto}).
 *
 * <h3>Two-tier keyuses persistence (the fund-critical invariant preserved from M0/M2)</h3>
 * <ul>
 *   <li><b>Live counter</b> — {@link PrefsKeyUses}, UNCHANGED: plaintext, app-private, two mirrors,
 *       commit-before-return, MAX-on-read. Every signature advances THIS first (crash-safe). The vault
 *       wraps it; it never bypasses or weakens it.</li>
 *   <li><b>Portable snapshot</b> — a copy of the keyuses map inside the encrypted vault blob. Refreshed
 *       after every send ({@link #syncKeyUses}). On {@link #open}/{@link #importVault} it is folded into
 *       the live counter under the MAX rule ({@link VaultBlob#reconcileInto}) — raising, never lowering.
 *       The snapshot enumerates EVERY key index the live store knows about, so a higher index can never
 *       be silently dropped (and reset to 0) on a later restore.</li>
 * </ul>
 *
 * <h3>Fail-safe</h3>
 * If a seed is imported with no trustworthy keyuses history the blob is stored {@code trusted=false}.
 * While untrusted, {@link #assertSigningAllowed()} throws, so the Send/Split/Consolidate flows (the ONLY
 * code paths that reach {@link WalletCore#signTransactionID}) refuse to sign until the user either
 * confirms the seed is brand-new ({@link #confirmBrandNewSeed}) or, after a backup restore, explicitly
 * attests the restored counter is up to date ({@link #attestKeyUsesUpToDate}).
 *
 * <h3>Restore never silently re-trusts (C1)</h3>
 * A restored backup may be STALE — its keyuses snapshot could lag the true on-chain uses count if the
 * seed signed anything after the backup was taken. Trusting such a counter would risk reusing a
 * one-time WOTS leaf and exposing the key. Therefore {@link #importVault} always installs the restored
 * vault {@code trusted=false}; signing stays blocked by {@link #assertSigningAllowed()} until the user
 * makes the explicit WOTS-reuse attestation in the UI. Only {@link #createV2} — whose caller must state
 * WHY the key is provably unused — is {@code trusted=true} without an attestation. The Auto-Backup
 * restore path is additionally neutralised by EXCLUDING the vault file from cloud/device backup (see
 * {@code backup_rules.xml} / {@code data_extraction_rules.xml}), so a stale cloud copy can never
 * silently restore-and-trust.
 *
 * <h3>Derivation</h3>
 * A vault records WHICH wallet key it signs with ({@link Derivation}), and that choice selects the live
 * counter store:
 * <ul>
 *   <li>{@link Derivation#V1} — LEGACY, the bare base seed's key index 0, derived by the same formula a
 *       Minima node uses and literally the same key wherever the base seeds coincide. Two signers, one
 *       key, two counters that cannot see each other. Retained ONLY so existing funds can be swept off
 *       the old address, and its counter is never trustworthy from local state alone — see
 *       {@link #attestLegacyKeyUses}.</li>
 *   <li>{@link Derivation#V2} — CURRENT, an HKDF child of the seed that a node cannot derive, so
 *       FreezePeach is the sole signer and its counter is the only counter.</li>
 * </ul>
 * There is deliberately NO method that switches derivation implicitly: {@link #upgradeToV2} is the one
 * transition, and a restore ({@link #importVault}) always lands on the derivation the backup recorded.
 *
 * <h3>Session model</h3>
 * The vault is unlocked once per session ({@link #open} with the passphrase, or {@link #createV2}/
 * {@link #recoverV2}/{@link #migrateLegacy} at onboarding), caching the seed phrase + passphrase in
 * memory. Re-encryption on {@link #syncKeyUses}/{@link #confirmBrandNewSeed} reuses the cached
 * passphrase (a fresh salt+nonce are generated each time). Nothing here derives an address or signs —
 * that stays in {@link WalletCore}.
 */
public class SeedVault {

    private static final String FILE = "wallet_vault_v1";
    private static final String KEY_BLOB = "vault_blob_hex";

    /** Thrown when a signature is attempted while the keyuses record is not trustworthy. */
    public static final class SigningNotAllowedException extends IllegalStateException {
        public SigningNotAllowedException(String zMsg) { super(zMsg); }
    }

    /**
     * Thrown by {@link #open} when the passphrase was CORRECT (or the failure is not a passphrase
     * failure at all) but the vault could not be applied — a corrupt/unreadable blob or a failed
     * mirror commit. Distinct from a wrong passphrase (which {@link #open} reports by returning false)
     * so the UI can tell the user "wrong passphrase" apart from "your vault is damaged".
     */
    public static final class VaultCorruptException extends IllegalStateException {
        public VaultCorruptException(String zMsg, Throwable zCause) { super(zMsg, zCause); }
    }

    /**
     * Minimal durable string store for the single vault blob. Abstracted so the fund-critical state
     * machine is unit-testable off-device (Android impl wraps app-private {@link SharedPreferences};
     * tests use an in-memory impl). Writes MUST be durable (committed) or throw.
     */
    interface BlobStore {
        boolean contains();
        String read();                 // null if none
        void write(String hex);        // durable; throws IllegalStateException on failure
    }

    private final BlobStore mStore;
    /** One counter store per derivation — they count DIFFERENT keys and must never be merged. */
    private final KeyUses mKeyUsesV1;
    private final KeyUses mKeyUsesV2;

    // Session state (only while unlocked).
    private String mPhrase;        // decrypted seed phrase
    private String mPassphrase;    // wallet passphrase (to re-encrypt on sync)
    private boolean mTrusted;      // keyuses trust flag from the open blob
    private boolean mOpen;
    /** Which wallet key this vault signs with; also selects the live counter store. */
    private Derivation mDerivation = Derivation.V1;

    public SeedVault(Context zContext, PrefsKeyUses zKeyUsesV1, PrefsKeyUses zKeyUsesV2) {
        this(new PrefsBlobStore(zContext), zKeyUsesV1, zKeyUsesV2);
    }

    /** Package-private: inject a store (Android or in-memory) and both {@link KeyUses} — for tests. */
    SeedVault(BlobStore zStore, KeyUses zKeyUsesV1, KeyUses zKeyUsesV2) {
        mStore     = zStore;
        mKeyUsesV1 = zKeyUsesV1;
        mKeyUsesV2 = zKeyUsesV2;
    }

    /** Which wallet key this vault signs with. Defaults to {@link Derivation#V1} until a blob is opened
     *  or created, so nothing can assume the new key space before it is actually established. */
    public Derivation derivation() { return mDerivation; }

    /** The counter store for a given derivation — the legacy sweep needs the v1 one explicitly. */
    public KeyUses keyUsesFor(Derivation zDerivation) {
        return zDerivation == Derivation.V2 ? mKeyUsesV2 : mKeyUsesV1;
    }

    /** The counter store for the ACTIVE derivation — what every signature must advance. */
    private KeyUses active() { return keyUsesFor(mDerivation); }

    /** Android-backed {@link BlobStore} over app-private SharedPreferences. */
    private static final class PrefsBlobStore implements BlobStore {
        private final SharedPreferences mPrefs;
        PrefsBlobStore(Context zContext) {
            mPrefs = zContext.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
        @Override public boolean contains() { return mPrefs.contains(KEY_BLOB); }
        @Override public String read() { return mPrefs.getString(KEY_BLOB, null); }
        @Override public void write(String zHex) {
            if (!mPrefs.edit().putString(KEY_BLOB, zHex).commit()) {
                throw new IllegalStateException("Failed to persist wallet vault");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Existence / session
    // ---------------------------------------------------------------------------------------------

    /** True once a vault blob has been created on this device (or restored via Auto Backup). */
    public boolean exists() {
        return mStore.contains();
    }

    /** True while the vault is unlocked this session (seed available in memory). */
    public boolean isOpen() {
        return mOpen;
    }

    /** The decrypted seed phrase — only valid while {@link #isOpen()}. */
    public String phrase() {
        requireOpen();
        return mPhrase;
    }

    /** Whether the current keyuses record is trustworthy (drives the signing fail-safe). */
    public boolean isKeyUsesTrusted() {
        return mOpen && mTrusted;
    }

    /** Lock the session, wiping the in-memory seed + passphrase. */
    public synchronized void lock() {
        mPhrase = null;
        mPassphrase = null;
        mTrusted = false;
        mOpen = false;
        mLegacyAttested = false;   // a legacy attestation is only ever good for the session it was made in
    }

    // ---------------------------------------------------------------------------------------------
    // Onboarding: create new / import
    // ---------------------------------------------------------------------------------------------

    /**
     * Create a {@link Derivation#V2} wallet trusted at its current (normally 0) count.
     *
     * <p><b>Why trusting at 0 is sound here, and was NOT sound before.</b> The v2 wallet key is an HKDF
     * child of the Minima seed that no Minima node can derive, so importing a phrase a node has already
     * spent from is safe: the v2 key has never signed anything. The ONE remaining way to reach a
     * non-zero true count is the user running FreezePeach with the SAME phrase on ANOTHER DEVICE — the
     * caller must have ruled that out (or used {@link #recoverV2} instead) before calling this.
     *
     * <p>This replaces the old {@code createNew}, which trusted at 0 unconditionally. Because the app
     * has no seed generator, every phrase reaching it was a user-typed one — routinely a live Minima
     * node seed whose key index 0 had already signed. That is what caused the production reuse
     * incident, and it is why there is no longer any method that trusts a phrase without the caller
     * stating why it is safe.
     *
     * @param zProvablyUnusedByFreezePeach the caller's explicit assertion — app-generated phrase, or a
     *        user who has confirmed this seed is not in FreezePeach on another device. When false the
     *        vault is installed UNTRUSTED and signing stays blocked until attested.
     */
    public synchronized void createV2(String zPhrase, String zPassphrase,
                                      boolean zProvablyUnusedByFreezePeach) {
        installV2(zPhrase, zPassphrase, zProvablyUnusedByFreezePeach);
    }

    /**
     * Import a phrase already used by FreezePeach elsewhere: RAISE the v2 counter to
     * {@code zPriorFreezePeachSends} (MAX — never lowers) BEFORE trusting it, so leaves already consumed
     * on the other device are skipped rather than reused.
     *
     * <p>FUND-CRITICAL asymmetry: over-counting only skips leaves (safe); under-counting reuses one
     * (loses funds). The UI must steer the user to over-estimate.
     */
    public synchronized void recoverV2(String zPhrase, String zPassphrase, int zPriorFreezePeachSends) {
        mKeyUsesV2.recordExternalUses(0, Math.max(0, zPriorFreezePeachSends));   // durable raise FIRST
        installV2(zPhrase, zPassphrase, true);
    }

    private void installV2(String zPhrase, String zPassphrase, boolean zTrusted) {
        mDerivation = Derivation.V2;
        VaultBlob blob = new VaultBlob(zPhrase, zTrusted, currentSnapshot(), Derivation.V2);
        persist(blob, zPassphrase);
        openState(zPhrase, zPassphrase, zTrusted);
    }

    /**
     * Adopt a pre-existing device-bound seed ({@code SeedStore}) into the vault, keeping it on the
     * LEGACY {@link Derivation#V1} key so its existing address and funds are preserved.
     *
     * <p>Always installed UNTRUSTED. A v1 key is shared with the user's Minima node, which advances the
     * true count invisibly, so this install's local mirrors are only a LOWER BOUND — trusting them would
     * be the same mistake that caused the reuse incident. The user must attest a count (or, better,
     * upgrade to v2 via {@link #upgradeToV2} and sweep) before anything signs.
     */
    public synchronized void migrateLegacy(String zPhrase, String zPassphrase) {
        mDerivation = Derivation.V1;
        VaultBlob blob = new VaultBlob(zPhrase, false, currentSnapshot(), Derivation.V1);
        persist(blob, zPassphrase);
        openState(zPhrase, zPassphrase, false);
    }

    /**
     * Move an open LEGACY vault onto the {@link Derivation#V2} key. The phrase is unchanged (so the chat
     * identity is unchanged), but the wallet address changes to one no node can derive — after this the
     * app is the sole signer of its own key.
     *
     * <p>The v1 counter and any funds at the v1 address are untouched and stay reachable via
     * {@link #keyUsesFor} for the sweep.
     *
     * @param zUsedOnAnotherDevice true if this same phrase is already used by FreezePeach elsewhere; the
     *        v2 counter would then already be non-zero on that device, so the vault stays UNTRUSTED
     *        until the user attests a count.
     */
    public synchronized void upgradeToV2(boolean zUsedOnAnotherDevice) {
        requireOpen();
        if (mDerivation == Derivation.V2) return;
        installV2(mPhrase, mPassphrase, !zUsedOnAnotherDevice);
    }

    // ---------------------------------------------------------------------------------------------
    // Unlock
    // ---------------------------------------------------------------------------------------------

    /**
     * Unlock the stored vault with the passphrase. On success: caches the seed, reconciles the vault's
     * keyuses snapshot into the live counter under the MAX rule (never lowers), and returns true. A
     * WRONG passphrase (or tampered blob) returns false with no state change. A blob that decrypts with
     * the RIGHT passphrase but cannot be applied (corrupt payload, failed mirror commit) throws
     * {@link VaultCorruptException} — so the caller can distinguish the two situations for the user.
     */
    public synchronized boolean open(String zPassphrase) {
        String hex = mStore.read();
        if (hex == null) return false;

        byte[] plain;
        try {
            byte[] container = new MiniData(hex).getBytes();
            plain = VaultCrypto.decrypt(zPassphrase, container);
        } catch (VaultCrypto.BadPassphraseException wrongPass) {
            // GCM tag failure: genuinely the wrong passphrase (or a tampered blob) — caller reprompts.
            return false;
        } catch (Exception decodeErr) {
            // Not even a decodable container (bad hex / truncated) — not a passphrase problem.
            throw new VaultCorruptException("Wallet vault is unreadable: " + decodeErr.getMessage(), decodeErr);
        }

        try {
            VaultBlob blob = VaultBlob.fromBytes(plain);
            // Adopt the blob's derivation FIRST: it selects which counter store is the live one, so
            // reconciling before this would fold the snapshot into the wrong key's counter.
            mDerivation = blob.getDerivation();
            // Restore: fold the snapshot into the live counter (MAX rule) BEFORE anyone can sign.
            blob.reconcileInto(active());
            openState(blob.getPhrase(), zPassphrase, blob.isKeyUsesTrusted());
            return true;
        } catch (Exception applyErr) {
            // Decryption authenticated (passphrase was correct) but parsing or a mirror commit failed.
            throw new VaultCorruptException(
                    "Wallet vault decrypted but could not be applied: " + applyErr.getMessage(), applyErr);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fail-safe gate + keyuses sync
    // ---------------------------------------------------------------------------------------------

    /**
     * The single chokepoint the Send/Split/Consolidate controller MUST call before building a
     * transaction. Throws if the vault is locked or the keyuses record is not trustworthy — so a
     * signature (which would consume a one-time WOTS leaf) can never be produced against an unknown
     * uses count, which would risk a key-exposing reuse.
     */
    public void assertSigningAllowed() {
        if (!mOpen) {
            throw new SigningNotAllowedException("Wallet is locked");
        }
        if (!mTrusted) {
            throw new SigningNotAllowedException(
                    "Signing is blocked: the number of signatures already used by this seed is unknown. "
                  + "Reusing a one-time key EXPOSES it and can lose your funds. Confirm this seed is "
                  + "brand-new, or import a keyuses backup, before sending.");
        }
    }

    /**
     * After a successful send has advanced the live counter, refresh the vault's portable keyuses
     * snapshot to the current live values and re-encrypt, so a manual export stays current. Cheap
     * AES-GCM re-encrypt (a new PBKDF2 salt is generated, matching {@link VaultCrypto}). Enumerates
     * EVERY key index the live store knows about (plus any explicitly named, plus index 0), so no
     * higher index is ever dropped from the snapshot.
     */
    public synchronized void syncKeyUses(int... zKeyIndices) {
        if (!mOpen) return;
        LinkedHashMap<Integer, Integer> uses = currentSnapshot();
        if (zKeyIndices != null) {
            for (int idx : zKeyIndices) uses.put(idx, active().currentUses(idx));
        }
        VaultBlob blob = new VaultBlob(mPhrase, mTrusted, uses, mDerivation);
        persist(blob, mPassphrase);
    }

    /**
     * User confirms the (previously bare/untrusted) seed is brand-new: mark keyuses trustworthy at the
     * current (0) count. Only call after an explicit, warned user confirmation.
     */
    public synchronized void confirmBrandNewSeed() {
        requireOpen();
        mTrusted = true;
        syncKeyUses();   // persist trusted=true with the current snapshot
    }

    /**
     * User explicitly attests, AFTER a backup restore, that the restored keyuses counter is their
     * most-recent state — i.e. they have NOT signed any transaction from this seed since the backup was
     * made. This is the ONLY way a restored (initially untrusted) vault becomes trusted. Only call
     * after the loud WOTS-reuse warning dialog. Semantically identical to {@link #confirmBrandNewSeed}
     * (flip trusted + persist) but named for the restore intent.
     */
    public synchronized void attestKeyUsesUpToDate() {
        requireOpen();
        mTrusted = true;
        syncKeyUses();
    }

    /**
     * The ACTIVE derivation's live counter for {@code zKeyIndex}. Never signs.
     *
     * <p>NOTE: this is a LOWER BOUND on the true count, never an authority. It is safe to display as
     * "what this device has recorded", but it must NOT be offered as the default answer to an
     * attestation prompt — on a fresh import it reads 0, and accepting 0 for a key that has signed
     * elsewhere is exactly the reuse this class exists to prevent.
     */
    public int currentUses(int zKeyIndex) {
        return active().currentUses(zKeyIndex);
    }

    /** As {@link #currentUses} but for a specific derivation — the legacy sweep reports the v1 count. */
    public int currentUses(Derivation zDerivation, int zKeyIndex) {
        return keyUsesFor(zDerivation).currentUses(zKeyIndex);
    }

    /**
     * Count-aware attestation: the user states how many signatures this (untrusted) seed has already made,
     * so the live counter for {@code zKeyIndex} is RAISED to {@code zUses} (MAX — never lowered) BEFORE the
     * seed is trusted and signing is unblocked. This is the SAFE form of {@link #attestKeyUsesUpToDate} for
     * a BARE seed import, whose live counter is 0 — attesting "current" there would trust leaf 0 and reuse
     * the seed's already-spent one-time keys. For a restore the caller pre-fills {@code zUses} with the
     * reconciled count, so confirming is a no-op raise. FUND-CRITICAL: under-counting reuses a leaf (loss);
     * over-counting only skips leaves (safe) — the UI must steer the user to over-estimate when unsure.
     */
    public synchronized void attestKeyUses(int zKeyIndex, int zUses) {
        requireOpen();
        active().recordExternalUses(zKeyIndex, Math.max(0, zUses));
        mTrusted = true;
        syncKeyUses(zKeyIndex);
    }

    // ---------------------------------------------------------------------------------------------
    // Legacy (V1) sweep support
    // ---------------------------------------------------------------------------------------------

    /**
     * Session-scoped: has the user attested the LEGACY v1 count in THIS session? Deliberately not
     * persisted — a v1 key is shared with the user's Minima node, which can advance the true count at
     * any moment, so an attestation made yesterday says nothing about today. Every sweep re-asks.
     */
    private boolean mLegacyAttested;

    /**
     * Raise the LEGACY v1 counter to the user's attested prior-use count so a sweep can sign without
     * reusing a leaf. Does NOT touch {@link #isKeyUsesTrusted()} — that flag governs the ACTIVE (v2)
     * key, which is a different key entirely.
     *
     * <p>FUND-CRITICAL and weaker than the v2 case by nature: this counter is shared with the node, so
     * the attested number must be at least the node's {@code keys} uses for key index 0. Over-estimate.
     */
    public synchronized void attestLegacyKeyUses(int zUses) {
        requireOpen();
        mKeyUsesV1.recordExternalUses(0, Math.max(0, zUses));
        mLegacyAttested = true;
    }

    /** The gate the sweep MUST call before building: throws unless the v1 count was attested this session. */
    public void assertLegacySigningAllowed() {
        if (!mOpen) {
            throw new SigningNotAllowedException("Wallet is locked");
        }
        if (!mLegacyAttested) {
            throw new SigningNotAllowedException(
                    "Sweeping the legacy address needs its one-time-key count first. This key is shared "
                  + "with your Minima node, so FreezePeach cannot know how many of its one-time keys the "
                  + "node has already spent. Enter a count that is at least the node's — over-estimating "
                  + "only skips keys, under-estimating reuses one and can lose your funds.");
        }
    }

    public boolean isLegacyAttested() { return mOpen && mLegacyAttested; }

    /**
     * Re-encrypt the vault under a NEW wallet passphrase (Settings → Change unlock passphrase). The
     * seed, the {@code trusted} flag and the keyuses snapshot are carried over UNCHANGED — this only
     * rotates the passphrase-derived key (a fresh PBKDF2 salt + GCM nonce are generated by
     * {@link VaultCrypto#encrypt}).
     *
     * <p><b>Atomicity (never lose the vault on failure).</b> The new container is built and then
     * VERIFIED to decrypt back to the same seed + trust flag <i>before</i> the single stored blob is
     * overwritten. If encryption or the verify fails we throw and the existing (old-passphrase) blob is
     * left untouched, so a failed change can never brick the wallet. The store write itself is a single
     * atomic {@code commit()} of one key (SharedPreferences writes via a temp file + rename), so there is
     * no half-written intermediate state. Only after the durable write succeeds is the in-memory session
     * passphrase updated to the new one.
     *
     * <p>Requires the vault to be OPEN (the caller having proven the current passphrase by unlocking).
     * Signing / keyuses / trust state are not touched.
     */
    public synchronized void changePassphrase(String zNewPassphrase) {
        requireOpen();

        // Build the new blob from the exact current in-memory state.
        VaultBlob blob = new VaultBlob(mPhrase, mTrusted, currentSnapshot(), mDerivation);

        final byte[] container;
        try {
            container = VaultCrypto.encrypt(zNewPassphrase, blob.toBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt vault with new passphrase", e);
        }

        // VERIFY the new container decrypts to the same seed + trust BEFORE replacing the stored blob.
        try {
            VaultBlob check = VaultBlob.fromBytes(VaultCrypto.decrypt(zNewPassphrase, container));
            if (!mPhrase.equals(check.getPhrase()) || check.isKeyUsesTrusted() != mTrusted
                    || check.getDerivation() != mDerivation) {
                throw new IllegalStateException("Re-encrypted vault did not verify");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Re-encrypted vault failed verification", e);
        }

        // Only now overwrite the single stored blob (atomic commit; throws on failure, old blob intact).
        String hex = BaseConverter.encode16(container);
        mStore.write(hex);

        // Session now uses the new passphrase for subsequent syncKeyUses re-encrypts.
        mPassphrase = zNewPassphrase;
    }

    // ---------------------------------------------------------------------------------------------
    // Manual backup / restore (the authoritative recovery path)
    // ---------------------------------------------------------------------------------------------

    /**
     * Produce a portable backup file's bytes: the current {seed + keyuses snapshot}, encrypted under
     * {@code zExportPassphrase} (which may differ from the wallet passphrase). The result is a
     * self-describing {@link VaultCrypto} container — restore it on any device with only this passphrase.
     * The snapshot enumerates every known key index (not just index 0).
     */
    public synchronized byte[] exportBytes(String zExportPassphrase) throws Exception {
        requireOpen();
        VaultBlob blob = new VaultBlob(mPhrase, mTrusted, currentSnapshot(), mDerivation);
        return VaultCrypto.encrypt(zExportPassphrase, blob.toBytes());
    }

    /**
     * Decrypt a backup file for preview WITHOUT committing it (used to show the user what will be
     * restored). Throws on a wrong passphrase.
     */
    public static VaultBlob peekImport(byte[] zContainer, String zPassphrase) throws Exception {
        return VaultBlob.fromBytes(VaultCrypto.decrypt(zPassphrase, zContainer));
    }

    /**
     * Restore from a backup file: decrypts under {@code zFilePassphrase}, folds its keyuses snapshot
     * into the live counter (MAX — never lowers), and installs it as the local vault encrypted under
     * {@code zNewWalletPassphrase}. The restored vault is installed {@code trusted=false} (C1): a backup
     * can be STALE, so signing stays blocked by {@link #assertSigningAllowed()} until the user makes the
     * explicit up-to-date attestation ({@link #attestKeyUsesUpToDate}) in the UI. Opens the session.
     */
    public synchronized void importVault(byte[] zContainer, String zFilePassphrase, String zNewWalletPassphrase)
            throws Exception {
        VaultBlob restored = VaultBlob.fromBytes(VaultCrypto.decrypt(zFilePassphrase, zContainer));
        // The backup's own derivation decides which counter store its snapshot belongs to; adopt it
        // BEFORE reconciling so the counts never land on the other key's counter.
        mDerivation = restored.getDerivation();
        VaultBlob blob = reconcileRestored(restored, active());   // MAX-reconciles; returns trusted=false
        persist(blob, zNewWalletPassphrase);
        openState(restored.getPhrase(), zNewWalletPassphrase, false);   // untrusted until attested
    }

    /**
     * Build the vault blob to persist after a restore. MAX-reconciles the restored snapshot into the
     * live counter (raising, never lowering — the fund-critical restore invariant) and returns an
     * UNTRUSTED blob carrying the reconciled counts for every index the restore or the live store knew
     * about. Package-private + static so the C1 behaviour is directly unit-testable off-device.
     */
    static VaultBlob reconcileRestored(VaultBlob zRestored, KeyUses zLive) {
        // Raise the live counter to the restored snapshot (never lower).
        zRestored.reconcileInto(zLive);

        // Persist the reconciled (>=) values for every index seen in the restore OR already live, so a
        // later restore can never silently reset a higher index's counter.
        LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>();
        for (Integer idx : zRestored.getKeyUses().keySet()) {
            uses.put(idx, zLive.currentUses(idx));
        }
        for (Map.Entry<Integer, Integer> e : zLive.snapshotAllUses().entrySet()) {
            uses.put(e.getKey(), e.getValue());
        }
        if (!uses.containsKey(0)) uses.put(0, zLive.currentUses(0));
        // C1: restore is NOT auto-trusted. The derivation is carried over verbatim — a restore must land
        // on the SAME key it was taken from, never be promoted to v2 (that would orphan its funds).
        return new VaultBlob(zRestored.getPhrase(), false, uses, zRestored.getDerivation());
    }

    // ---------------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------------

    /** A snapshot of the ACTIVE derivation's counter for every known index, always including index 0
     *  (MAX-on-read). Never mixes derivations — the other key's counts belong to a different blob. */
    private LinkedHashMap<Integer, Integer> currentSnapshot() {
        KeyUses live = active();
        LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>(live.snapshotAllUses());
        uses.put(0, live.currentUses(0));   // primary key always present, even at 0 uses
        return uses;
    }

    private void persist(VaultBlob zBlob, String zPassphrase) {
        try {
            byte[] container = VaultCrypto.encrypt(zPassphrase, zBlob.toBytes());
            String hex = BaseConverter.encode16(container);   // already 0x-prefixed
            mStore.write(hex);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt/persist wallet vault", e);
        }
    }

    private void openState(String zPhrase, String zPassphrase, boolean zTrusted) {
        mPhrase = zPhrase;
        mPassphrase = zPassphrase;
        mTrusted = zTrusted;
        mOpen = true;
    }

    private void requireOpen() {
        if (!mOpen) throw new IllegalStateException("Vault is locked");
    }
}
