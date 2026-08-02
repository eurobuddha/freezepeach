package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

/**
 * WalletSession — the Application-scoped holder for the OPEN wallet session, so "the vault is unlocked"
 * survives an Activity {@code recreate()} and a brief trip to the background for the user's chosen
 * auto-lock timeout, instead of being lost on every Activity instance (which used to force a re-unlock).
 *
 * <h3>What it owns</h3>
 * <ul>
 *   <li>the single {@link SeedVault} (whose in-memory seed + passphrase are the ONLY decrypted-seed
 *       copy) and its {@link PrefsKeyUses} — created once and reused across Activity instances;</li>
 *   <li>the {@code lastActivity} timestamp (bumped on unlock and on user interaction) and the persisted
 *       auto-lock timeout choice.</li>
 * </ul>
 *
 * <h3>Where the decrypted seed lives / when it is cleared</h3>
 * The decrypted seed + passphrase live ONLY inside the held {@link SeedVault} (its {@code mPhrase} /
 * {@code mPassphrase} fields), never persisted in plaintext. {@link #lock()} calls {@link SeedVault#lock()},
 * which nulls both, and is invoked on: an explicit "Lock now", and an auto-lock decision on
 * {@code onResume}/{@code onCreate} (see {@link #shouldLockOnForeground}). The auto-lock only ever
 * <i>raises</i> safety (locks sooner); it never keeps a stale session open.
 *
 * <h3>Real-background detection (config-change safe)</h3>
 * A single {@link ProcessLifecycleOwner} observer records the moment the WHOLE app is stopped
 * ({@code ON_STOP}). ProcessLifecycleOwner debounces Activity restarts, so an Activity {@code recreate()}
 * (theme toggle, rotation) does NOT count as a background trip — only a genuine app background does. The
 * auto-lock check therefore fires on a real return-from-background, not on a foreground recreate, which
 * keeps "Immediately" from nuking a legitimate in-app recreate.
 */
public final class WalletSession implements DefaultLifecycleObserver {

    private static final String PREFS = "wallet_session";
    private static final String K_TIMEOUT_MS = "autolock_timeout_ms";

    private static WalletSession sInstance;

    private final SharedPreferences mPrefs;
    /** One counter store per {@link Derivation} — see {@link PrefsKeyUses#PrefsKeyUses(Context, String)}. */
    private final PrefsKeyUses mKeyUsesV1;
    private final PrefsKeyUses mKeyUsesV2;
    private final SeedVault mVault;

    /** Last time the user was active (unlock / interaction). Frozen while backgrounded. */
    private long mLastActivity;

    /** Wall-clock at the last real app background ({@code ON_STOP}); 0 while foreground / never. */
    private long mBackgroundedAt;

    private WalletSession(Context zApp) {
        mPrefs     = zApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mKeyUsesV1 = new PrefsKeyUses(zApp, Derivation.V1.keyUsesNamespace);
        mKeyUsesV2 = new PrefsKeyUses(zApp, Derivation.V2.keyUsesNamespace);
        mVault     = new SeedVault(zApp, mKeyUsesV1, mKeyUsesV2);
    }

    /** The process-wide session. Must be first called on the main thread (Activity onCreate). */
    public static synchronized WalletSession get(Context zContext) {
        if (sInstance == null) {
            Context app = zContext.getApplicationContext();
            sInstance = new WalletSession(app);
            ProcessLifecycleOwner.get().getLifecycle().addObserver(sInstance);
        }
        return sInstance;
    }

    // --- accessors -------------------------------------------------------------------------------

    public SeedVault vault() { return mVault; }

    /** The counter store for a specific derivation (the sweep flow needs the legacy one explicitly). */
    public PrefsKeyUses keyUses(Derivation zDerivation) {
        return zDerivation == Derivation.V2 ? mKeyUsesV2 : mKeyUsesV1;
    }

    /**
     * The counter store for the vault's ACTIVE derivation — the ONE instance the signer must use.
     *
     * <p>Callers must never construct their own {@link PrefsKeyUses}: its reserve-before-sign
     * read-modify-write is {@code synchronized} PER INSTANCE, so a second instance over the same prefs
     * files would not be mutually excluded from the first, reopening exactly the leaf-reuse race the
     * counter exists to prevent.
     */
    public PrefsKeyUses keyUses() { return keyUses(mVault.derivation()); }

    // --- auto-lock timeout config ----------------------------------------------------------------

    /** The configured auto-lock timeout in ms (default 5 min). */
    public long timeoutMs() {
        return mPrefs.getLong(K_TIMEOUT_MS, SessionPolicy.DEFAULT_TIMEOUT_MS);
    }

    /** Persist a new auto-lock timeout choice. */
    public void setTimeoutMs(long zTimeoutMs) {
        mPrefs.edit().putLong(K_TIMEOUT_MS, zTimeoutMs).apply();
    }

    // --- activity / lock state -------------------------------------------------------------------

    /** Bump the activity timestamp (call on a successful unlock and on user interaction). */
    public void touch() {
        mLastActivity = System.currentTimeMillis();
    }

    /**
     * Should the session be auto-locked as the UI comes to the foreground ({@code onResume}/
     * {@code onCreate})? True only when the vault is open, the app actually returned from a real
     * background, and the idle gap exceeds the configured timeout ({@link SessionPolicy#shouldLock}).
     * A foreground recreate (no {@code ON_STOP}) never locks. Does not mutate state.
     */
    public boolean shouldLockOnForeground(long zNow) {
        if (!mVault.isOpen()) return false;
        if (mBackgroundedAt == 0L) return false;   // never really backgrounded → don't lock
        return SessionPolicy.shouldLock(mLastActivity, zNow, timeoutMs());
    }

    /**
     * Consume the "returned from foreground" edge: clear the backgrounded marker and, if staying open,
     * bump the activity time. Call from {@code onResume} AFTER acting on {@link #shouldLockOnForeground}.
     */
    public void onHandledForeground(boolean zStayingOpen) {
        mBackgroundedAt = 0L;
        if (zStayingOpen) touch();
    }

    /** Lock the session now, wiping the in-memory seed + passphrase from the held vault. */
    public void lock() {
        mVault.lock();
        mBackgroundedAt = 0L;
    }

    // --- ProcessLifecycleOwner (whole-app foreground/background) ----------------------------------

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        // The entire app went to the background (config-change restarts are debounced away by
        // ProcessLifecycleOwner and do NOT reach here). Freeze the idle clock reference.
        if (mVault.isOpen()) mBackgroundedAt = System.currentTimeMillis();
    }
}
