package com.eurobuddha.freezepeach;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.LocalEcCryptoProvider;
import com.eurobuddha.comms.MailboxClient;
import com.eurobuddha.comms.MailboxHttp;
import com.eurobuddha.comms.MailboxTransport;
import com.eurobuddha.comms.MediaTransport;
import com.eurobuddha.comms.Opened;
import com.eurobuddha.comms.Sodium;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * FreezePeach — self-custodial, censorship-resistant chat on MaxLite.
 * Premium two-theme UI (Onyx Electric / Ink) built to the design spec: real icons, glow strip, avatars,
 * asymmetric bubbles, bottom sheets, QR scan + clipboard add-contact, and end-to-end sealed image messages.
 */
public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    private static final String PREFS = "freezepeach";
    private static final String RELAY_URL = "https://relay.privateprivate.org";
    private static final int PICK_IMAGE = 4201, PICK_VIDEO = 4202, CAPTURE_PHOTO = 4203, CAPTURE_VIDEO = 4204, EDIT_IMAGE = 4205, REQ_REVEAL = 4206, REQ_APPLOCK = 4207, REQ_EXPORT = 4208, REQ_RESTORE = 4209;
    private boolean appUnlocked;   // (legacy device-credential app-lock; superseded by the vault passphrase)
    private com.eurobuddha.wallet.WalletSession session;   // Application-scoped open-vault session (survives recreate)
    private com.eurobuddha.wallet.SeedVault vault;         // passphrase-encrypted seed + WOTS keyuses (portable backup)
    private Bundle savedState;                             // deferred savedInstanceState restore, applied post-unlock
    private String pendingExportPass;                     // export passphrase held between the dialog and the file-picker result
    private boolean upgradePrompted;                       // legacy→v2 key move offered once per session
    private Wallet legacyWallet;                           // v1 (node-shared) wallet — sweep/balance reads only
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.US);

    private SharedPreferences prefs;
    private CommsIdentity me;
    private CryptoProvider crypto;
    private Db db;
    private MailboxHttp http;
    private MailboxClient.MetaStore meta;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable drainLoop;
    private String openContact;            // 1:1 conversation pubid (or null)
    private String openGroup;              // group conversation gid (or null)
    private boolean openWallet;            // wallet screen shown
    private Wallet wallet;                 // in-app Minima wallet (local signing)
    private RemoteNode node;               // wallet's remote node transport (null until configured)
    private boolean sending;               // guards against an accidental double-broadcast
    private final java.util.Set<String> sharedAddrTo = new java.util.HashSet<>();   // per-session address-share throttle
    private LinearLayout walletBalCard;    // the live balance card (for auto-refresh)
    private String walletMx;               // derived receive address (for auto-refresh)
    private volatile long lastSendTs;      // distinguishes change (self-return) from real receives in history
    private volatile boolean pollInFlight; // guards pollHistory against overlapping runs (duplicate rows)
    static volatile boolean sAppForeground;// true while the Activity is started → FpService yields draining to it
    private long lastToneMs;               // throttle the receive tone to one beep per batch
    private LinearLayout walletHistBox;    // wallet history list container (endless scroll)
    private int walletHistOffset;          // history pagination offset
    private boolean walletHistLoading, walletHistDone;
    private final Runnable walletRefresh = new Runnable() {
        @Override public void run() {
            if (!openWallet || walletMx == null || walletBalCard == null) return;
            fetchBalance(walletMx, walletBalCard);
            pollHistory(walletMx);
            main.postDelayed(this, 15000);
        }
    };
    private String pendingImageContact;    // 1:1 target for a picked photo/video (or null)
    private String pendingGroup;           // group target for a picked photo/video (or null)
    private File pendingCaptureFile;       // temp file for a camera capture
    private boolean atChats;               // for back navigation
    private EditText currentInput;         // active composer (to preserve a draft across drain re-renders)
    private LinearLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Theme.init(prefs);
        db = new Db(this);
        meta = new PrefsMeta(prefs);
        http = new MailboxHttp(RELAY_URL);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);
        applyStatusBar();
        createNotifChannel();
        requestNotifPermission();

        session = com.eurobuddha.wallet.WalletSession.get(this);
        vault = session.vault();
        savedState = b;
        if (!vault.exists()) {
            migrateOrSetup();                                     // fresh onboarding, or migrate a legacy SeedStore seed
        } else if (!vault.isOpen() || session.shouldLockOnForeground(System.currentTimeMillis())) {
            session.lock(); showUnlock();                         // locked, or auto-lock elapsed while backgrounded
        } else {
            session.onHandledForeground(true); enterFromVault();  // already open (survived a recreate)
        }
    }

    /** Post-unlock: derive the seed-bound chat identity + wallet from the OPEN vault and enter the app. */
    private void enterFromVault() {
        String phrase = vault.phrase();
        if (phrase == null) { showUnlock(); return; }
        deriveIdentity(phrase);
        session.touch();
        Bundle b = savedState; savedState = null;
        if (b != null) {                                            // restore across low-memory recreation
            openContact = b.getString("openContact");
            openGroup = b.getString("openGroup");
            pendingImageContact = b.getString("pendingImageContact");
            String pc = b.getString("pendingCapture");
            if (pc != null) pendingCaptureFile = new File(pc);
        }
        // A legacy vault still signs with the key the user's Minima node also holds. Offer the one-time
        // move to the domain-separated key before the app is usable, but never force it — declining must
        // still get you into your chats.
        if (vault.derivation() == com.eurobuddha.wallet.Derivation.V1 && !upgradePrompted) {
            upgradePrompted = true;
            showUpgradeToV2();
            return;
        }
        enterApp();
    }

    private void enterApp() {
        renderCurrent(); startDrain(); FpService.start(this);
    }

    private boolean unlockIsPin() { return prefs.getBoolean("unlock_pin", false); }
    /** Configure an unlock field as a 6-digit numeric PIN or a text passphrase. */
    private void configUnlock(EditText f, boolean pin, String passHint) {
        if (pin) {
            f.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            f.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(6) });
            f.setHint("6-digit PIN");
        } else {
            f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            f.setFilters(new android.text.InputFilter[0]);
            f.setHint(passHint);
        }
    }
    private boolean unlockOk(String v, boolean pin) { return pin ? v.matches("\\d{6}") : (v != null && v.length() >= 8); }
    private String unlockReqMsg(boolean pin) { return pin ? "PIN must be 6 digits" : "Passphrase must be at least 8 characters"; }

    /** The unlock gate: passphrase or 6-digit PIN (always works) + biometric (if enabled). Blocks the app until open. */
    private void showUnlock() {
        root.setBackgroundColor(Theme.BG());
        root.removeAllViews();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(26), dp(80), dp(26), dp(26));
        root.addView(heroMark(76));
        TextView wm = Ui.label(this, "FreezePeach", 26, 800, Theme.TEXT());
        wm.setPadding(0, dp(16), 0, 0); root.addView(wm);
        TextView tag = Ui.label(this, unlockIsPin() ? "Enter your PIN to unlock." : "Enter your passphrase to unlock.", 14, 400, Theme.DIM());
        tag.setPadding(0, dp(6), 0, dp(28)); root.addView(tag);
        final EditText pass = field("Passphrase", false);
        configUnlock(pass, unlockIsPin(), "Passphrase");
        root.addView(pass, matchW());
        gap(dp(16));
        LinearLayout go = Ui.pill(this, 0, "Unlock", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> {
            try {
                if (vault.open(pass.getText().toString())) enterFromVault();
                else toast("Wrong passphrase");
            } catch (com.eurobuddha.wallet.SeedVault.VaultCorruptException ce) {
                toast("Vault damaged — restore from your backup");
            }
        });
        root.addView(go, matchW());
        if (com.eurobuddha.wallet.BiometricUnlock.isEnabled(this) && com.eurobuddha.wallet.BiometricUnlock.isAvailable(this)) {
            gap(dp(10));
            root.addView(Ui.pill(this, 0, "Use fingerprint", Theme.SURFACE(), Theme.TEXT(), false, v -> promptBiometricUnlock()), matchW());
            main.postDelayed(this::promptBiometricUnlock, 350);   // auto-prompt: the user just touches the sensor, no extra tap
        }
    }

    /** Biometric convenience: releases the stored passphrase from hardware, then opens the vault. */
    private void promptBiometricUnlock() {
        com.eurobuddha.wallet.BiometricUnlock.unlock(this, new com.eurobuddha.wallet.BiometricUnlock.UnlockCallback() {
            @Override public void onPassphrase(String passphrase) {
                try {
                    if (vault.open(passphrase)) enterFromVault();
                    else toast("Biometric passphrase is out of date — use your passphrase");
                } catch (com.eurobuddha.wallet.SeedVault.VaultCorruptException ce) {
                    toast("Vault damaged — restore from your backup");
                }
            }
            @Override public void onError(String message, boolean permanentlyInvalidated) { if (permanentlyInvalidated) showUnlock(); }
            @Override public void onCancelled() { }
        });
    }

    /** After the user sets their PIN/passphrase, offer one-tap fingerprint enrolment using that same secret,
     *  so future unlocks auto-prompt the sensor. Skips if biometrics are unavailable or already enabled. */
    private void offerBiometric(final String secret) {
        if (!com.eurobuddha.wallet.BiometricUnlock.isAvailable(this) || com.eurobuddha.wallet.BiometricUnlock.isEnabled(this)) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Fingerprint unlock")
                .setMessage("Unlock FreezePeach with your fingerprint next time? Your PIN/passphrase still works as a fallback.")
                .setPositiveButton("Enable", (d, w) -> com.eurobuddha.wallet.BiometricUnlock.enable(this, secret, new com.eurobuddha.wallet.BiometricUnlock.Callback() {
                    @Override public void onSuccess() { toast("Fingerprint unlock on"); }
                    @Override public void onError(String m, boolean inv) { toast("Couldn't enable fingerprint: " + m); }
                    @Override public void onCancelled() { }
                }))
                .setNegativeButton("Not now", null).show();
    }

    /** Fresh onboarding, or a one-time migration of a legacy device-bound SeedStore seed into the vault. */
    private void migrateOrSetup() {
        String legacyPhrase = SeedStore.loadPhrase(prefs);
        if (legacyPhrase != null && !legacyPhrase.isEmpty()) showMigrate(legacyPhrase);
        else showSetup();
    }

    /** One-time: an existing device-bound seed → set a passphrase → move it (with its keyuses) into the vault. */
    private void showMigrate(final String legacyPhrase) {
        root.setBackgroundColor(Theme.BG());
        root.removeAllViews();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(26), dp(64), dp(26), dp(26));
        root.addView(heroMark(72));
        TextView wm = Ui.label(this, "Secure your wallet", 24, 800, Theme.TEXT());
        wm.setPadding(0, dp(16), 0, 0); root.addView(wm);
        TextView tag = Ui.label(this, "Set a passphrase to encrypt your seed. You'll enter it to open FreezePeach.", 14, 400, Theme.DIM());
        tag.setGravity(Gravity.CENTER_HORIZONTAL); tag.setPadding(0, dp(6), 0, dp(22)); root.addView(tag);
        final android.widget.CheckBox usePin = new android.widget.CheckBox(this);
        usePin.setText("Use a 6-digit PIN instead");
        usePin.setTextColor(Theme.DIM());
        root.addView(usePin, matchW()); gap(dp(8));
        final EditText p1 = field("New passphrase (min 8)", false);
        configUnlock(p1, false, "New passphrase (min 8)");
        root.addView(p1, matchW()); gap(dp(12));
        final EditText p2 = field("Confirm passphrase", false);
        configUnlock(p2, false, "Confirm passphrase");
        root.addView(p2, matchW()); gap(dp(16));
        usePin.setOnCheckedChangeListener((cb, ck) -> { configUnlock(p1, ck, "New passphrase (min 8)"); configUnlock(p2, ck, "Confirm passphrase"); p1.setText(""); p2.setText(""); });
        root.addView(Ui.pill(this, 0, "Secure & continue", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> {
            String pp = p1.getText().toString();
            boolean pin = usePin.isChecked();
            if (!unlockOk(pp, pin)) { toast(unlockReqMsg(pin)); return; }
            if (!pp.equals(p2.getText().toString())) { toast(pin ? "PINs don't match" : "Passphrases don't match"); return; }
            try {
                // Stays on the LEGACY key so this install's existing address and funds are preserved, and
                // is deliberately UNTRUSTED: that key is shared with the user's Minima node, so the local
                // mirrors are only a lower bound on how many one-time keys it has really spent. The
                // upgrade screen that follows offers the move to a key the node cannot derive.
                vault.migrateLegacy(legacyPhrase, pp);
                SeedStore.clear(prefs);                           // drop the device-bound copy — the vault is now the only seed store
                prefs.edit().putBoolean("unlock_pin", pin).apply();
                enterFromVault();
                offerBiometric(pp);
            } catch (Exception e) { toast("Could not secure the vault: " + e.getMessage()); }
        }), matchW());
    }

    /**
     * One-time screen for a LEGACY vault: move the wallet onto a key the user's Minima node cannot
     * derive.
     *
     * <p>Until now FreezePeach's wallet key was {@code hashObjects(baseSeed, 0)} — the same formula a
     * Minima node uses for its key index 0, and literally the same key wherever the base seeds coincide
     * (a node started with {@code -anyseed} on the same phrase). Where that happens both wallets sign
     * one key while each keeps a private one-time-use counter, so the app can sign a leaf the node
     * already spent, and reusing a Winternitz leaf leaks it. Moving to the HKDF-derived key ends the
     * possibility: a node cannot compute it, so FreezePeach is the only signer and the only counter.
     *
     * <p>The chat identity and every conversation are untouched — only the wallet address changes, and
     * the old address stays visible so its balance can be swept.
     */
    private void showUpgradeToV2() {
        root.setBackgroundColor(Theme.BG());
        root.removeAllViews();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(26), dp(56), dp(26), dp(26));
        root.addView(heroMark(64));
        TextView wm = Ui.label(this, "Secure your wallet key", 23, 800, Theme.TEXT());
        wm.setPadding(0, dp(16), 0, 0); root.addView(wm);
        TextView tag = Ui.label(this,
                "Your wallet key is built the same way a Minima node builds its first key, so a node set "
              + "up from this same phrase can end up holding the identical key. Neither side can see how "
              + "many one-time keys the other has spent, and spending one twice exposes it.\n\n"
              + "FreezePeach can move to its own key that no node can derive. Your chats, contacts and "
              + "your id all stay exactly the same — only your wallet receive address changes, and the "
              + "old address stays visible so you can move its balance across.", 14, 400, Theme.DIM());
        tag.setPadding(0, dp(10), 0, dp(20)); root.addView(tag);

        final android.widget.CheckBox otherDevice = new android.widget.CheckBox(this);
        otherDevice.setText("I also use FreezePeach with this same seed on another device");
        otherDevice.setTextColor(Theme.DIM());
        root.addView(otherDevice, matchW());
        TextView hint = Ui.label(this,
                "If you do, the new key is already in use there too, so sending stays locked until you "
              + "tell FreezePeach how many payments that device has sent.", 12, 400, Theme.DIM());
        hint.setPadding(0, dp(6), 0, dp(18)); root.addView(hint);

        root.addView(Ui.pill(this, R.drawable.ic_shield, "Move to a safer key", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> {
            try {
                vault.upgradeToV2(otherDevice.isChecked());
                deriveIdentity(vault.phrase());          // rebuild the wallet on the new key
                legacyWallet = null;
                toast(otherDevice.isChecked() ? "Moved — enter your other device's payment count to send"
                                              : "Moved to your own wallet key");
            } catch (Exception e) {
                toast("Could not move the key: " + e.getMessage());
            }
            enterApp();
        }), matchW());
        gap(dp(10));
        root.addView(Ui.pill(this, 0, "Not now", Theme.SURFACE(), Theme.TEXT(), false, v -> enterApp()), matchW());
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        if (session != null && vault != null && vault.isOpen()) session.touch();
    }

    @Override public void onBackPressed() {
        if (me != null && !atChats) showChats();
        else super.onBackPressed();
    }
    @Override protected void onResume() {
        super.onResume();
        if (session != null && vault != null && vault.isOpen()) {           // enforce auto-lock on a real foreground return
            if (session.shouldLockOnForeground(System.currentTimeMillis())) { session.lock(); showUnlock(); return; }
            session.onHandledForeground(true);
        }
        if (openWallet && walletMx != null) { main.removeCallbacks(walletRefresh); main.postDelayed(walletRefresh, 15000); }
    }
    // The Activity drains while it's the foreground; FpService drains when it isn't (they hand off via
    // sAppForeground, so exactly one runs). onStart also (re)starts the always-on service.
    @Override protected void onStart() {
        super.onStart(); sAppForeground = true;
        if (me != null && vault != null && vault.isOpen()) { FpService.start(this); startDrain(); }
    }
    @Override protected void onStop() {
        super.onStop(); sAppForeground = false; appUnlocked = false;
        main.removeCallbacks(drainLoop); main.removeCallbacks(walletRefresh);
    }
    /** App-lock: require the device credential (PIN/fingerprint) to enter FreezePeach when enabled. */
    private void requestAppUnlock() {
        if (appUnlocked || !prefs.getBoolean("app_lock", false)) return;
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardSecure()) { appUnlocked = true; return; }
        Intent i = km.createConfirmDeviceCredentialIntent("Unlock FreezePeach", "Confirm it's you to open FreezePeach");
        if (i != null) { try { startActivityForResult(i, REQ_APPLOCK); return; } catch (Exception e) {} }
        appUnlocked = true;
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (drainLoop != null) main.removeCallbacks(drainLoop);
        main.removeCallbacks(walletRefresh);
        try { http.onDestroy(); } catch (Exception e) {}
        try { if (node != null) node.onDestroy(); } catch (Exception e) {}
        try { db.close(); } catch (Exception e) {}
    }
    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putString("openContact", openContact);
        b.putString("openGroup", openGroup);
        b.putString("pendingImageContact", pendingImageContact);
        if (pendingCaptureFile != null) b.putString("pendingCapture", pendingCaptureFile.getAbsolutePath());
    }

    private void startDrain() {
        if (drainLoop != null) main.removeCallbacks(drainLoop);
        drainLoop = new Runnable() { @Override public void run() { doDrain(); main.postDelayed(this, 5000); } };
        main.postDelayed(drainLoop, 700);
    }
    /** A FreezePeach pairing code ({@code fpseed:<64hex>}) or a bare 64-char hex base seed → the hex;
     * otherwise null (treat the input as a mnemonic phrase). */
    private static String pairingHex(String input) {
        String s = input.trim();
        if (s.toLowerCase().startsWith("fpseed:")) s = s.substring(7).trim();
        s = s.toLowerCase();
        return (s.length() == 64 && s.matches("[0-9a-f]{64}")) ? s : null;
    }

    /**
     * Chat identity from the Minima seed (unchanged — it was ALWAYS a domain-separated HKDF child, so
     * your contacts still reach you at the same id); wallet from whichever key the vault records.
     */
    private void deriveIdentity(String phrase) {
        me = CommsIdentity.fromSeed(Sodium.get(), SeedDerive.seedFromPhrase(phrase));
        crypto = new LocalEcCryptoProvider(Sodium.get(), me);
        wallet = Wallet.forDerivation(this, phrase, vault.derivation());
    }
    private void renderCurrent() {
        if (isFinishing() || isDestroyed()) return;
        if (me == null) showSetup();
        else if (openWallet) showWallet();
        else if (openGroup != null) showGroupConversation(openGroup);
        else if (openContact != null) showConversation(openContact);
        else showChats();
    }
    private void applyStatusBar() {
        Window w = getWindow();
        w.setStatusBarColor(Theme.SURFACE());
        View dec = w.getDecorView();
        int flags = dec.getSystemUiVisibility();
        if (Theme.isDark()) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        dec.setSystemUiVisibility(flags);
    }

    // =============================================================================================
    // Setup
    // =============================================================================================

    private static final int MODE_GENERATE = 0, MODE_IMPORT_NEW = 1, MODE_IMPORT_USED = 2;

    /**
     * Onboarding. The wallet key is always {@link com.eurobuddha.wallet.Derivation#V2} — an HKDF child
     * of the seed that no Minima node can derive — so importing a phrase your node already spends from
     * is safe here: FreezePeach's key has never signed anything.
     *
     * <p>The ONE case that still needs a count is the same phrase already running FreezePeach on
     * another device, because that device's copy of this exact key has consumed leaves this one cannot
     * see. That is why the mode is an explicit choice with no safe-looking default: the previous build
     * defaulted to "trust at zero" for any typed phrase, which is what reused three leaves in
     * production.
     */
    private void showSetup() {
        root.setBackgroundColor(Theme.BG());
        root.removeAllViews();
        root.setGravity(Gravity.NO_GRAVITY);
        root.setPadding(0, 0, 0, 0);

        final ScrollView sc = new ScrollView(this);
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(26), dp(56), dp(26), dp(32));
        sc.addView(col);
        root.addView(sc, matchW());

        col.addView(heroMark(76));
        TextView wm = Ui.label(this, "FreezePeach", 28, 800, Theme.TEXT());
        wm.setPadding(0, dp(16), 0, 0); col.addView(wm);
        TextView tag = Ui.label(this, "Chat no one can freeze.", 15, 400, Theme.DIM());
        tag.setPadding(0, dp(6), 0, dp(26)); col.addView(tag);

        // ---- mode ----------------------------------------------------------------------------
        final int[] mode = { MODE_GENERATE };
        final android.widget.RadioGroup modes = new android.widget.RadioGroup(this);
        modes.setOrientation(LinearLayout.VERTICAL);
        final android.widget.RadioButton rGen  = setupRadio("Create a new wallet", 100);
        final android.widget.RadioButton rNew  = setupRadio("I have a seed phrase — new to FreezePeach", 101);
        final android.widget.RadioButton rUsed = setupRadio("I already use FreezePeach with this seed elsewhere", 102);
        modes.addView(rGen); modes.addView(rNew); modes.addView(rUsed);
        modes.check(100);
        col.addView(modes, matchW());
        gapIn(col, dp(12));

        // ---- seed ----------------------------------------------------------------------------
        final EditText seed = field("Seed phrase — 12/24 words or a memorable phrase", true);
        seed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        seed.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);   // keep the seed off the keyboard's learned dictionary / cloud sync
        col.addView(seed, matchW());
        gapIn(col, dp(8));
        final TextView seedHint = Ui.label(this, "", 12, 400, Theme.DIM());
        col.addView(seedHint, matchW());
        final android.widget.CheckBox wroteDown = new android.widget.CheckBox(this);
        wroteDown.setText("I have written these words down somewhere safe");
        wroteDown.setTextColor(Theme.DIM());
        col.addView(wroteDown, matchW());
        gapIn(col, dp(10));

        // ---- prior-use count (import-used only) ------------------------------------------------
        final EditText usedSigs = field("Payments already sent from your other device", false);
        usedSigs.setInputType(InputType.TYPE_CLASS_NUMBER);
        final TextView usedHint = Ui.label(this,
                "How many payments FreezePeach has already sent from THIS seed on your other device. If "
              + "unsure, enter a HIGHER number than you think — over-estimating only skips keys, "
              + "under-estimating reuses one and can lose your funds.", 12, 400, Theme.DIM());
        col.addView(usedSigs, matchW());
        gapIn(col, dp(6));
        col.addView(usedHint, matchW());
        gapIn(col, dp(12));

        final EditText name = field("What should people call you?", false);
        col.addView(name, matchW()); gapIn(col, dp(10));
        final android.widget.CheckBox usePin = new android.widget.CheckBox(this);
        usePin.setText("Use a 6-digit PIN instead of a passphrase");
        usePin.setTextColor(Theme.DIM());
        col.addView(usePin, matchW()); gapIn(col, dp(8));
        final EditText p1 = field("Unlock passphrase (min 8)", false);
        configUnlock(p1, false, "Unlock passphrase (min 8)");
        col.addView(p1, matchW()); gapIn(col, dp(10));
        final EditText p2 = field("Confirm passphrase", false);
        configUnlock(p2, false, "Confirm passphrase");
        col.addView(p2, matchW()); gapIn(col, dp(16));
        usePin.setOnCheckedChangeListener((cb, ck) -> { configUnlock(p1, ck, "Unlock passphrase (min 8)"); configUnlock(p2, ck, "Confirm passphrase"); p1.setText(""); p2.setText(""); });

        final Runnable applyMode = () -> {
            boolean gen  = mode[0] == MODE_GENERATE;
            boolean used = mode[0] == MODE_IMPORT_USED;
            seed.setEnabled(!gen);
            seed.setFocusable(!gen); seed.setFocusableInTouchMode(!gen);
            seedHint.setVisibility(gen ? View.VISIBLE : View.GONE);
            seedHint.setText(gen ? "These 24 words ARE your wallet. Anyone who has them can spend your funds, "
                                 + "and nobody can recover them for you." : "");
            wroteDown.setVisibility(gen ? View.VISIBLE : View.GONE);
            usedSigs.setVisibility(used ? View.VISIBLE : View.GONE);
            usedHint.setVisibility(used ? View.VISIBLE : View.GONE);
            if (gen && seed.getText().length() == 0) {
                seed.setText(org.minima.utils.BIP39.convertWordListToString(org.minima.utils.BIP39.getNewWordList()));
            } else if (!gen) {
                seed.setText("");
                wroteDown.setChecked(false);
            }
        };
        modes.setOnCheckedChangeListener((g, id) -> {
            mode[0] = (id == 101) ? MODE_IMPORT_NEW : (id == 102) ? MODE_IMPORT_USED : MODE_GENERATE;
            applyMode.run();
        });
        applyMode.run();

        LinearLayout go = Ui.pill(this, 0, "Create wallet", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> {
            String phrase = seed.getText().toString().trim();
            String pp = p1.getText().toString();
            boolean pin = usePin.isChecked();
            if (phrase.isEmpty()) { toast("Enter a seed phrase"); return; }
            if (mode[0] == MODE_GENERATE && !wroteDown.isChecked()) {
                toast("Write your 24 words down first — they cannot be recovered"); return;
            }
            if (!unlockOk(pp, pin)) { toast(unlockReqMsg(pin)); return; }
            if (!pp.equals(p2.getText().toString())) { toast(pin ? "PINs don't match" : "Passphrases don't match"); return; }
            prefs.edit().putString("myname", name.getText().toString().trim()).apply();
            try {
                if (mode[0] == MODE_IMPORT_USED) {
                    // RAISE the counter to the other device's count BEFORE trusting, so leaves it already
                    // consumed are skipped. Under-counting reuses a leaf and loses funds; over-counting is safe.
                    String cs = usedSigs.getText().toString().trim();
                    if (cs.isEmpty()) { toast("Enter how many payments your other device has sent"); return; }
                    int count;
                    try { count = Integer.parseInt(cs); }
                    catch (NumberFormatException nfe) { toast("Payments already sent must be a whole number"); return; }
                    if (count < 0) { toast("Payments already sent can't be negative"); return; }
                    vault.recoverV2(phrase, pp, count);
                } else {
                    // Generated, or imported but never used with FreezePeach: the v2 key is provably unused
                    // — a Minima node cannot derive it, so a node-shared history is irrelevant here.
                    vault.createV2(phrase, pp, true);
                }
                prefs.edit().putBoolean("unlock_pin", pin).apply();
                enterFromVault();
                offerBiometric(pp);
            } catch (Exception e) { toast("Could not create the wallet: " + e.getMessage()); }
        });
        col.addView(go, matchW());
    }

    private android.widget.RadioButton setupRadio(String label, int id) {
        android.widget.RadioButton r = new android.widget.RadioButton(this);
        r.setText(label); r.setId(id); r.setTextColor(Theme.TEXT()); r.setTextSize(14);
        return r;
    }

    // =============================================================================================
    // Chats list
    // =============================================================================================

    private void showChats() {
        openContact = null; openGroup = null; openWallet = false; atChats = true; currentInput = null;
        root.setBackgroundColor(Theme.BG());
        root.setGravity(Gravity.NO_GRAVITY);
        root.removeAllViews(); root.setPadding(0, 0, 0, 0);

        LinearLayout hdr = header();
        FrameLayout meAv = Ui.avatar(this, me.publicId(), myName(), 36);
        FrameLayout meWrap = new FrameLayout(this);
        meWrap.addView(meAv);
        FrameLayout badge = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(Theme.ACCENT());
        bg.setStroke(dp(1.5f), Theme.SURFACE()); badge.setBackground(bg);
        badge.addView(Ui.icon(this, R.drawable.ic_qr, 8, Theme.ON_ACCENT()),
                new FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.BOTTOM | Gravity.END);
        meWrap.addView(badge, blp);
        meWrap.setClickable(true); meWrap.setPadding(dp(2), dp(2), dp(2), dp(2)); Ui.press(meWrap);
        meWrap.setOnClickListener(v -> showSettings());
        hdr.addView(meWrap);
        TextView title = Ui.label(this, "Chats", 24, 700, Theme.TEXT());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12); tlp.gravity = Gravity.CENTER_VERTICAL; title.setLayoutParams(tlp);
        hdr.addView(title);
        hdr.addView(Ui.iconButton(this, R.drawable.ic_tune, 22, 44, Theme.ACCENT(), v -> showSettings()));   // visible Settings entry
        hdr.addView(Ui.iconButton(this, R.drawable.ic_wallet, 22, 44, Theme.ACCENT(), v -> showWallet()));
        int themeIcon = Theme.isDark() ? R.drawable.ic_sun : R.drawable.ic_moon;
        hdr.addView(Ui.iconButton(this, themeIcon, 22, 44, Theme.ACCENT(),
                v -> { Theme.toggle(); applyStatusBar(); renderCurrent(); }));
        FrameLayout newChat = Ui.iconButton(this, R.drawable.ic_plus, 22, 44, Theme.ACCENT(), v -> showNewChat(null));
        GradientDrawable ncBg = new GradientDrawable(); ncBg.setShape(GradientDrawable.OVAL);
        ncBg.setColor(Theme.SURFACE()); ncBg.setStroke(dp(1), Theme.HAIR());
        newChat.setBackground(ncBg);
        hdr.addView(newChat);
        root.addView(hdr);
        root.addView(Ui.glowStrip(this));

        List<Db.Contact> cs = db.contacts();
        List<Db.Group> gs = db.groups();
        if (cs.isEmpty() && gs.isEmpty()) { root.addView(emptyState()); return; }
        java.util.List<Entry> entries = new java.util.ArrayList<>();
        for (Db.Contact c : cs) {
            Entry e = new Entry(); e.group = false; e.key = c.pubid; e.name = nameOf(c.pubid);
            e.last = c.lastBody != null ? c.lastBody : Fp.shortId(c.pubid); e.mono = c.lastBody == null; e.ts = c.lastTs;
            entries.add(e);
        }
        for (Db.Group g : gs) {
            Entry e = new Entry(); e.group = true; e.key = g.gid; e.name = g.name != null ? g.name : "Group";
            e.last = g.lastBody != null ? g.lastBody : "New group"; e.mono = false; e.ts = g.lastTs;
            entries.add(e);
        }
        java.util.Collections.sort(entries, (a, b) -> Long.compare(b.ts, a.ts));

        ScrollView sc = new ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        for (Entry e : entries) list.addView(entryRow(e));
        sc.addView(list); root.addView(sc);
    }

    private static final class Entry { boolean group, mono; String key, name, last; long ts; }

    private View entryRow(final Entry e) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true); row.setBackground(Ui.rippleRect(this, 0)); Ui.press(row);
        row.setOnClickListener(v -> { if (e.group) showGroupConversation(e.key); else showConversation(e.key); });
        row.addView(Ui.avatar(this, e.key, e.name, 44));
        LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView n = Ui.label(this, e.name, 16, 600, Theme.TEXT()); n.setMaxLines(1);
        TextView p = Ui.label(this, e.last, 14, 400, Theme.DIM());
        p.setMaxLines(1); p.setPadding(0, dp(2), 0, 0);
        if (e.mono) p.setTypeface(Typeface.MONOSPACE);
        mid.addView(n); mid.addView(p); row.addView(mid);
        if (e.ts > 0) row.addView(Ui.mono(this, hhmm.format(new Date(e.ts * 1000)), 12, Theme.DIM()));
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        View div = new View(this); div.setBackgroundColor(Theme.HAIR());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.leftMargin = dp(72); div.setLayoutParams(dlp); wrap.addView(div);
        return wrap;
    }

    private View emptyState() {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL); col.setPadding(dp(32), dp(72), dp(32), dp(32));
        col.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(heroMark(96));
        TextView t = Ui.label(this, "No chats yet", 20, 700, Theme.TEXT()); t.setPadding(0, dp(24), 0, 0);
        col.addView(t);
        TextView s = Ui.label(this, "Your conversations are end-to-end, on your device only. Add someone to get started.", 15, 400, Theme.DIM());
        s.setGravity(Gravity.CENTER); s.setPadding(0, dp(8), 0, dp(24)); s.setLineSpacing(dp(2), 1f);
        s.setMaxWidth(dp(300)); col.addView(s);
        LinearLayout add = Ui.pill(this, R.drawable.ic_scan, "New chat", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> showNewChat(null));
        col.addView(add);
        TextView my = Ui.label(this, "Show my code", 15, 600, Theme.ACCENT());
        my.setPadding(dp(12), dp(20), dp(12), dp(12)); my.setClickable(true); Ui.press(my);
        my.setOnClickListener(v -> myCodeSheet()); col.addView(my);
        return col;
    }

    // =============================================================================================
    // Conversation
    // =============================================================================================

    // =============================================================================================
    // Wallet
    // =============================================================================================

    private void showSettings() {
        openContact = null; openGroup = null; openWallet = false; atChats = false; currentInput = null;
        root.setBackgroundColor(Theme.BG()); root.setGravity(Gravity.NO_GRAVITY);
        root.removeAllViews(); root.setPadding(0, 0, 0, 0);

        LinearLayout hdr = header();
        hdr.addView(Ui.iconButton(this, R.drawable.ic_back, 24, 44, Theme.TEXT(), v -> showChats()));
        TextView title = Ui.label(this, "Settings", 22, 700, Theme.TEXT());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8); tlp.gravity = Gravity.CENTER_VERTICAL; title.setLayoutParams(tlp);
        hdr.addView(title);
        root.addView(hdr); root.addView(Ui.glowStrip(this));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(6), dp(16), dp(24)); scroll.addView(col);

        col.addView(settingsSection("PROFILE"));
        col.addView(settingsRow(R.drawable.ic_qr, "My code & ID", myName(), v -> myCodeSheet()));

        col.addView(settingsSection("SEED & BACKUP"));
        col.addView(settingsRow(R.drawable.ic_shield, "Reveal seed phrase", "your recovery words", v -> seedBackupGate()));
        col.addView(settingsRow(R.drawable.ic_copy, "Export encrypted backup", "seed + keyuses, passphrase-locked", v -> exportBackup()));
        col.addView(settingsRow(R.drawable.ic_copy, "Restore from backup", "this or another device", v -> restoreBackup()));

        col.addView(settingsSection("SECURITY"));
        if (vault != null && !vault.isKeyUsesTrusted())
            col.addView(settingsRow(R.drawable.ic_shield, "Enable sending", "attest keyuses is current", v -> untrustedSendGate()));
        boolean bioAvail = com.eurobuddha.wallet.BiometricUnlock.isAvailable(this);
        boolean bioOn = com.eurobuddha.wallet.BiometricUnlock.isEnabled(this);
        col.addView(settingsRow(R.drawable.ic_shield, "Biometric unlock", !bioAvail ? "Unavailable" : (bioOn ? "On" : "Off"), v -> {
            if (!bioAvail) { toast("No biometric/PIN enrolled on this device"); return; }
            if (bioOn) { com.eurobuddha.wallet.BiometricUnlock.disable(this); toast("Biometric unlock off"); showSettings(); }
            else enableBiometric();
        }));
        col.addView(settingsRow(R.drawable.ic_bell, "Auto-lock",
                com.eurobuddha.wallet.SessionPolicy.TIMEOUT_LABELS[com.eurobuddha.wallet.SessionPolicy.indexOfTimeout(session.timeoutMs())], v -> pickAutoLock()));
        col.addView(settingsRow(R.drawable.ic_shield, "Change passphrase", "", v -> changePassphrase()));
        col.addView(settingsRow(R.drawable.ic_shield, "Lock now", "", v -> { session.lock(); showUnlock(); }));

        col.addView(settingsSection("NOTIFICATIONS"));
        col.addView(settingsRow(R.drawable.ic_bell, "Sound on receive", prefs.getBoolean("sound_on", true) ? "On" : "Off",
                v -> { prefs.edit().putBoolean("sound_on", !prefs.getBoolean("sound_on", true)).apply(); showSettings(); }));

        col.addView(settingsSection("APPEARANCE"));
        col.addView(settingsRow(Theme.isDark() ? R.drawable.ic_sun : R.drawable.ic_moon, "Theme", Theme.isDark() ? "Dark" : "Light",
                v -> { Theme.toggle(); applyStatusBar(); showSettings(); }));

        col.addView(settingsSection("WALLET"));
        col.addView(settingsRow(R.drawable.ic_wallet, "Wallet node", "relay endpoint + token", v -> nodeConfigSheet()));

        col.addView(settingsSection("ABOUT"));
        TextView ver = Ui.label(this, "FreezePeach", 14, 600, Theme.TEXT()); ver.setPadding(dp(4), dp(10), 0, 0); col.addView(ver);
        TextView vsub = Ui.label(this, "Self-custodial chat + wallet on Minima. Your seed is your identity — back it up.", 12, 400, Theme.DIM());
        vsub.setPadding(dp(4), dp(3), 0, 0); col.addView(vsub);

        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private TextView settingsSection(String t) {
        TextView s = Ui.label(this, t, 12, 700, Theme.DIM()); s.setLetterSpacing(0.1f); s.setPadding(dp(4), dp(20), 0, dp(8)); return s;
    }
    private View settingsRow(int icon, String label, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(Theme.SURFACE(), dp(14))); row.setPadding(dp(16), dp(15), dp(16), dp(15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8); row.setLayoutParams(lp);
        row.addView(Ui.icon(this, icon, 20, Theme.ACCENT()));
        TextView lbl = Ui.label(this, label, 15, 600, Theme.TEXT());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); llp.leftMargin = dp(14); lbl.setLayoutParams(llp);
        row.addView(lbl);
        if (value != null && !value.isEmpty()) { TextView v = Ui.label(this, value, 13, 400, Theme.DIM()); v.setMaxLines(1); v.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(v); }
        row.setClickable(true); Ui.press(row); row.setOnClickListener(onClick);
        return row;
    }

    /** Export the vault (seed + keyuses) as a passphrase-encrypted file via the system file picker. */
    private void exportBackup() {
        final EditText pass = field("Backup passphrase (min 8)", false);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Export encrypted backup")
                .setMessage("Choose a passphrase to protect this backup file — you'll need it (and only it) to restore on any device. It holds your seed + keyuses; use at least 12 characters, ideally several random words.")
                .setView(wrapField(pass))
                .setPositiveButton("Choose file…", (d, w) -> {
                    String p = pass.getText().toString();
                    if (p.length() < 8) { toast("Passphrase too short (min 8)"); return; }
                    pendingExportPass = p;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "freezepeach-backup.fpv");
                    try { startActivityForResult(i, REQ_EXPORT); } catch (Exception e) { toast("No file picker available"); }
                })
                .setNegativeButton("Cancel", null).show();
    }
    private void restoreBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, REQ_RESTORE); } catch (Exception e) { toast("No file picker available"); }
    }
    private void askRestorePass(final byte[] bytes) {
        final EditText pass = field("Backup passphrase", false);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Restore from backup")
                .setMessage("Enter the passphrase that protects this backup file.")
                .setView(wrapField(pass))
                .setPositiveButton("Restore", (d, w) -> {
                    try {
                        vault.importVault(bytes, pass.getText().toString(), pass.getText().toString());
                        toast("Restored. Sending stays BLOCKED until you attest this backup is up to date (Settings → Security).");
                        recreate();                                     // re-derive identity + wallet from the restored seed
                    } catch (Exception e) { toast("Restore failed: wrong passphrase or corrupt file"); }
                })
                .setNegativeButton("Cancel", null).show();
    }
    private void enableBiometric() {
        final boolean pin = unlockIsPin();
        final EditText pass = field("Unlock passphrase", false);
        configUnlock(pass, pin, "Unlock passphrase");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Enable biometric unlock")
                .setMessage("Confirm your " + (pin ? "PIN" : "passphrase") + ". It's stored in this device's secure hardware and released only after a fingerprint / face / device-PIN check.")
                .setView(wrapField(pass))
                .setPositiveButton("Continue", (d, w) -> {
                    String p = pass.getText().toString();
                    try { if (!vault.open(p)) { toast("Wrong passphrase"); return; } }
                    catch (com.eurobuddha.wallet.SeedVault.VaultCorruptException e) { toast("Vault damaged"); return; }
                    com.eurobuddha.wallet.BiometricUnlock.enable(this, p, new com.eurobuddha.wallet.BiometricUnlock.Callback() {
                        @Override public void onSuccess() { toast("Biometric unlock enabled"); showSettings(); }
                        @Override public void onError(String m, boolean inv) { toast("Could not enable: " + m); showSettings(); }
                        @Override public void onCancelled() { showSettings(); }
                    });
                })
                .setNegativeButton("Cancel", null).show();
    }
    private void pickAutoLock() {
        int cur = com.eurobuddha.wallet.SessionPolicy.indexOfTimeout(session.timeoutMs());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Auto-lock timeout")
                .setSingleChoiceItems(com.eurobuddha.wallet.SessionPolicy.TIMEOUT_LABELS, cur, (d, which) -> {
                    session.setTimeoutMs(com.eurobuddha.wallet.SessionPolicy.TIMEOUT_CHOICES_MS[which]); d.dismiss(); showSettings();
                })
                .setNegativeButton("Cancel", null).show();
    }
    private void changePassphrase() {
        final boolean wasPin = unlockIsPin();
        final EditText cur = field("Current " + (wasPin ? "PIN" : "passphrase"), false); configUnlock(cur, wasPin, "Current passphrase");
        final android.widget.CheckBox usePin = new android.widget.CheckBox(this);
        usePin.setText("New unlock is a 6-digit PIN"); usePin.setTextColor(Theme.DIM()); usePin.setChecked(wasPin);
        final EditText n1 = field("New passphrase (min 8)", false); configUnlock(n1, wasPin, "New passphrase (min 8)");
        final EditText n2 = field("Confirm", false); configUnlock(n2, wasPin, "Confirm");
        usePin.setOnCheckedChangeListener((cb, ck) -> { configUnlock(n1, ck, "New passphrase (min 8)"); configUnlock(n2, ck, "Confirm"); n1.setText(""); n2.setText(""); });
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(cur, matchW()); box.addView(usePin, matchW()); box.addView(n1, matchW()); box.addView(n2, matchW());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Change unlock")
                .setMessage("Your seed and keyuses stay the same — only the PIN/passphrase that encrypts them on this device changes.")
                .setView(box)
                .setPositiveButton("Change", (d, w) -> {
                    String c = cur.getText().toString(), a = n1.getText().toString(), b = n2.getText().toString();
                    boolean pin = usePin.isChecked();
                    if (!unlockOk(a, pin)) { toast(unlockReqMsg(pin)); return; }
                    if (!a.equals(b)) { toast(pin ? "PINs don't match" : "Passphrases don't match"); return; }
                    try { if (!vault.open(c)) { toast("Current " + (wasPin ? "PIN" : "passphrase") + " is wrong"); return; } }
                    catch (com.eurobuddha.wallet.SeedVault.VaultCorruptException e) { toast("Vault damaged"); return; }
                    try {
                        vault.changePassphrase(a);
                        prefs.edit().putBoolean("unlock_pin", pin).apply();
                        if (com.eurobuddha.wallet.BiometricUnlock.isEnabled(this)) com.eurobuddha.wallet.BiometricUnlock.disable(this);   // stored secret now stale → re-enable
                        toast(pin ? "PIN changed" : "Passphrase changed");
                    } catch (Exception e) { toast("Change failed (vault unchanged): " + e.getMessage()); }
                })
                .setNegativeButton("Cancel", null).show();
    }
    private View wrapField(EditText f) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(f, matchW()); return box;
    }
    private void writeUri(android.net.Uri uri, byte[] bytes) throws Exception {
        try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) { if (os == null) throw new java.io.IOException("null stream"); os.write(bytes); }
    }

    private void showWallet() {
        openContact = null; openGroup = null; openWallet = true; atChats = false; currentInput = null;
        root.setBackgroundColor(Theme.BG());
        root.setGravity(Gravity.NO_GRAVITY);
        root.removeAllViews(); root.setPadding(0, 0, 0, 0);

        LinearLayout hdr = header();
        hdr.addView(Ui.iconButton(this, R.drawable.ic_back, 24, 44, Theme.TEXT(), v -> showChats()));
        TextView title = Ui.label(this, "Wallet", 22, 700, Theme.TEXT());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8); tlp.gravity = Gravity.CENTER_VERTICAL; title.setLayoutParams(tlp);
        hdr.addView(title);
        hdr.addView(Ui.iconButton(this, R.drawable.ic_tune, 22, 44, Theme.DIM(), v -> walletSettingsSheet()));
        root.addView(hdr);
        root.addView(Ui.glowStrip(this));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(18), dp(20), dp(24));
        scroll.addView(col);

        final LinearLayout balCard = new LinearLayout(this); balCard.setOrientation(LinearLayout.VERTICAL);
        balCard.setBackground(Ui.round(Theme.SURFACE(), dp(18))); balCard.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams bclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bclp.bottomMargin = dp(22); balCard.setLayoutParams(bclp);
        LinearLayout brow = new LinearLayout(this); brow.setOrientation(LinearLayout.HORIZONTAL); brow.setGravity(Gravity.CENTER_VERTICAL);
        TextView blbl = Ui.label(this, "BALANCE", 12, 700, Theme.DIM()); blbl.setLetterSpacing(0.1f);
        blbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        brow.addView(blbl);
        brow.addView(Ui.iconButton(this, R.drawable.ic_rotate, 18, 34, Theme.DIM(), v -> { if (walletMx != null) fetchBalance(walletMx, balCard); }));
        balCard.addView(brow);
        TextView amt = Ui.label(this, "…", 26, 800, Theme.TEXT()); amt.setTag("bal_amount"); amt.setPadding(0, dp(4), 0, 0); balCard.addView(amt);
        TextView bsub = Ui.label(this, "your on-chain balance", 12, 400, Theme.DIM()); bsub.setPadding(0, dp(2), 0, 0); balCard.addView(bsub);
        LinearLayout tokBox = new LinearLayout(this); tokBox.setOrientation(LinearLayout.VERTICAL); tokBox.setTag("bal_tokens"); balCard.addView(tokBox);
        col.addView(balCard);

        // Funds still sitting on the old node-shared key, if any.
        final LinearLayout legacyBox = new LinearLayout(this);
        legacyBox.setOrientation(LinearLayout.VERTICAL);
        legacyBox.setVisibility(View.GONE);
        col.addView(legacyBox, matchW());
        checkLegacyBalance(legacyBox);

        LinearLayout sendBtn = Ui.pill(this, R.drawable.ic_send, "Send", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> {
            if (remoteNode() == null) { nodeConfigSheet(); return; }
            sendSheet(null);
        });
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.bottomMargin = dp(24); sendBtn.setLayoutParams(sblp);
        col.addView(sendBtn);

        TextView lbl = Ui.label(this, "RECEIVE", 12, 700, Theme.DIM());
        lbl.setLetterSpacing(0.1f); lbl.setPadding(0, 0, 0, dp(12)); col.addView(lbl);

        final ImageView qrv = new ImageView(this);
        qrv.setBackground(Ui.round(0xFFFFFFFF, dp(18))); qrv.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(230), dp(230)); qlp.gravity = Gravity.CENTER_HORIZONTAL;
        qrv.setLayoutParams(qlp); col.addView(qrv);

        final TextView addr = Ui.mono(this, "deriving your address…", 13, Theme.DIM());
        addr.setGravity(Gravity.CENTER); addr.setPadding(dp(8), dp(18), dp(8), dp(16)); col.addView(addr);

        final LinearLayout copyPill = Ui.pill(this, R.drawable.ic_copy, "Copy address", Theme.ACCENT(), Theme.ON_ACCENT(), true, null);
        copyPill.setAlpha(0.4f); copyPill.setEnabled(false); col.addView(copyPill, matchW());

        TextView hlbl = Ui.label(this, "HISTORY", 12, 700, Theme.DIM());
        hlbl.setLetterSpacing(0.1f); hlbl.setPadding(0, dp(30), 0, dp(4)); col.addView(hlbl);
        walletHistBox = new LinearLayout(this); walletHistBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(walletHistBox, matchW());
        scroll.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
            if (!walletHistLoading && !walletHistDone && sy + scroll.getHeight() >= col.getHeight() - dp(220)) loadMoreHistory();
        });

        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final Wallet w = wallet;
        if (w == null) { addr.setText("Wallet unavailable"); return; }
        new Thread(() -> {
            try { w.ensureAddress(); } catch (Exception e) { }
            final String mx = w.mx();
            main.post(() -> {
                if (!openWallet) return;
                if (mx == null) { addr.setText("Couldn't derive address"); return; }
                addr.setText(mx); addr.setTextColor(Theme.TEXT());
                Bitmap q = qr(mx, dp(202));
                if (q != null) qrv.setImageBitmap(q);
                copyPill.setAlpha(1f); copyPill.setEnabled(true);
                copyPill.setOnClickListener(v -> { copy(mx); haptic(col); toast("Address copied"); });
                fetchBalance(mx, balCard);
                walletMx = mx; walletBalCard = balCard;
                resetHistory(); pollHistory(mx);
                main.removeCallbacks(walletRefresh); main.postDelayed(walletRefresh, 15000);   // auto-refresh balance while open
            });
        }).start();
    }

    // =============================================================================================
    // Legacy (node-shared key) balance + sweep
    // =============================================================================================

    /** The v1 wallet — the key the user's Minima node ALSO holds. Reads and sweeps only, never a
     *  destination. Built lazily because deriving its address walks the WOTS tree. */
    private synchronized Wallet legacyWallet() {
        if (legacyWallet == null && vault != null && vault.isOpen()) {
            legacyWallet = Wallet.forDerivation(this, vault.phrase(), com.eurobuddha.wallet.Derivation.V1);
        }
        return legacyWallet;
    }

    /**
     * Show the legacy card only when there is actually something stranded on the old shared key. Silent
     * when the balance is zero (the common case once swept) so the wallet screen stays clean.
     */
    private void checkLegacyBalance(final LinearLayout box) {
        if (vault == null || vault.derivation() != com.eurobuddha.wallet.Derivation.V2) return;   // v1 vault: it IS the main balance
        final RemoteNode rn = remoteNode();
        if (rn == null) return;
        new Thread(() -> {
            final Wallet lw = legacyWallet();
            if (lw == null) return;
            try { lw.ensureAddress(); } catch (Exception e) { return; }
            final String lmx = lw.mx();
            if (lmx == null) return;
            main.post(() -> rn.coins(lmx, new RemoteNode.Cb() {
                @Override public void onResult(JSONObject r) {
                    org.json.JSONArray raw = r.optJSONArray("response");
                    if (raw == null || raw.length() == 0 || !openWallet) return;
                    buildLegacyCard(box, lmx, raw.length());
                }
                @Override public void onError(String m) { }
            }));
        }).start();
    }

    private void buildLegacyCard(LinearLayout box, final String legacyMx, int coinCount) {
        box.removeAllViews();
        box.setVisibility(View.VISIBLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Theme.SURFACE(), dp(18)));
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(22); card.setLayoutParams(lp);

        TextView t = Ui.label(this, "OLD SHARED ADDRESS", 12, 700, Theme.DANGER()); t.setLetterSpacing(0.1f);
        card.addView(t);
        TextView sub = Ui.label(this,
                coinCount + (coinCount == 1 ? " coin is" : " coins are") + " still held on the key your Minima "
              + "node also uses. Move them to your new address — while they sit there, both wallets can spend "
              + "with the same one-time keys.", 13, 400, Theme.DIM());
        sub.setPadding(0, dp(6), 0, dp(12)); card.addView(sub);
        TextView a = Ui.mono(this, com.eurobuddha.wallet.Util.shorten(legacyMx), 12, Theme.DIM());
        a.setPadding(0, 0, 0, dp(12)); card.addView(a);
        card.addView(Ui.pill(this, R.drawable.ic_send, "Move funds across", Theme.DANGER(), Theme.ON_ACCENT(), true,
                v -> startLegacySweep()), matchW());
        box.addView(card, matchW());
    }

    /**
     * Sweep everything at the legacy address to the current (v2) address.
     *
     * <p>Each token needs its own transaction and therefore its own one-time leaf, and coin selection is
     * capped at {@code CoinSelector.MAX_INPUTS}, so a fragmented balance takes several passes. The count
     * is shown before anything signs.
     *
     * <p>Sweeping necessarily signs with the compromised/shared key one more time. If that key's leaves
     * have already been reused, someone holding the leaked material could in principle race this
     * transaction — the warning says so, because "move your funds" is not risk-free advice here.
     */
    private void startLegacySweep() {
        if (sending) { toast("A send is already in progress…"); return; }
        if (!vault.isLegacyAttested()) { legacyAttestDialog(); return; }
        final RemoteNode rn = remoteNode();
        final Wallet lw = legacyWallet();
        if (rn == null || lw == null || wallet == null || wallet.mx() == null) { toast("Wallet not ready"); return; }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Move funds across")
                .setMessage("This signs with the OLD shared key one last time to move your coins to your new "
                        + "address.\n\nOne transaction (and one one-time key) per token, and heavily split "
                        + "balances may need more than one pass — run it again if anything is left.\n\n"
                        + "If this key's one-time keys have already been reused, treat the move as urgent but "
                        + "not guaranteed: whoever holds the leaked signature could try to spend first.")
                .setPositiveButton("Move funds", (d, w) -> doLegacySweep(rn, lw))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Raise the LEGACY counter before it can sign. Unlike the v2 gate this can never be fully safe —
     *  the node moves that counter invisibly — so the wording pushes hard toward over-estimating. */
    private void legacyAttestDialog() {
        final EditText count = new EditText(this);
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        count.setHint("Signatures already made by this seed");
        count.setTextColor(Theme.TEXT());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(count, matchW());
        TextView known = Ui.label(this,
                "This device has recorded " + vault.currentUses(com.eurobuddha.wallet.Derivation.V1, 0)
              + ". Your node's count is separate and is NOT included — check it with the node's `keys` "
              + "command and enter the higher number.", 12, 400, Theme.DIM());
        known.setPadding(0, dp(8), 0, 0);
        wrap.addView(known, matchW());

        new android.app.AlertDialog.Builder(this)
                .setTitle("Unlock the old key")
                .setMessage("The old address shares its key with your Minima node, so FreezePeach cannot know "
                        + "how many of its one-time keys the node has already spent.\n\n"
                        + "Enter a number at least as high as the node's. Over-estimating only skips keys; "
                        + "under-estimating reuses one and can lose your funds.")
                .setView(wrap)
                .setPositiveButton("Unlock", (d, w) -> {
                    String cs = count.getText().toString().trim();
                    if (cs.isEmpty()) { toast("Enter a count"); return; }
                    int n;
                    try { n = Integer.parseInt(cs); }
                    catch (NumberFormatException nfe) { toast("Enter a whole number"); return; }
                    if (n < 0) { toast("Can't be negative"); return; }
                    vault.attestLegacyKeyUses(n);
                    toast("Old key unlocked from " + n + " prior signatures");
                    startLegacySweep();
                })
                .setNeutralButton("I don't know", (d, w) -> {
                    int n = vault.currentUses(com.eurobuddha.wallet.Derivation.V1, 0) + UNKNOWN_USES_MARGIN;
                    vault.attestLegacyKeyUses(n);
                    toast("Skipped ahead to " + n + " to stay safe");
                    startLegacySweep();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doLegacySweep(final RemoteNode rn, final Wallet lw) {
        try { vault.assertLegacySigningAllowed(); }
        catch (Exception e) { toast(e.getMessage()); return; }
        sending = true;
        toast("Reading the old address…");
        rn.coins(lw.mx(), new RemoteNode.Cb() {
            @Override public void onResult(JSONObject r) {
                try {
                    org.json.JSONArray raw = r.optJSONArray("response");
                    org.minima.utils.json.JSONArray coins = (raw == null)
                            ? new org.minima.utils.json.JSONArray()
                            : (org.minima.utils.json.JSONArray) new org.minima.utils.json.parser.JSONParser().parse(raw.toString());
                    java.util.List<com.eurobuddha.wallet.CoinAggregator.Agg> aggs =
                            com.eurobuddha.wallet.CoinAggregator.aggregate(coins);
                    if (aggs.isEmpty()) { sending = false; toast("Nothing left on the old address"); return; }
                    sweepToken(rn, lw, coins, aggs, 0, 0);
                } catch (Exception e) {
                    sending = false;
                    toast("Couldn't read the old address: " + e.getMessage());
                }
            }
            @Override public void onError(String m) { sending = false; toast("Node error: " + m); }
        });
    }

    /** Sweep one token group, then recurse to the next. Sequential on purpose: each pass reserves a
     *  one-time leaf from the SAME key, and overlapping them would race the counter. */
    private void sweepToken(final RemoteNode rn, final Wallet lw, final org.minima.utils.json.JSONArray coins,
                            final java.util.List<com.eurobuddha.wallet.CoinAggregator.Agg> aggs,
                            final int i, final int done) {
        if (i >= aggs.size()) {
            sending = false;
            toast(done == 0 ? "Nothing could be moved" : "Moved " + done + (done == 1 ? " token" : " tokens") + " across");
            if (openWallet) showWallet();
            return;
        }
        final com.eurobuddha.wallet.CoinAggregator.Agg agg = aggs.get(i);
        final String tokId = agg.tokenid;
        final boolean isMinima = com.eurobuddha.wallet.Util.isMinima(tokId) || "0x00".equals(tokId);
        final java.util.List<org.minima.utils.json.JSONObject> sel;
        try {
            // Take the whole group (capped by MAX_INPUTS); the output is their exact sum, so no change coin.
            sel = com.eurobuddha.wallet.CoinSelector.selectToCover(coins, tokId, agg.rawTotal);
        } catch (Exception e) {
            sweepToken(rn, lw, coins, aggs, i + 1, done); return;   // too fragmented for one pass — next token
        }
        final java.util.List<String> coinids = new java.util.ArrayList<>();
        for (org.minima.utils.json.JSONObject cn : sel) coinids.add(String.valueOf(cn.get("coinid")));
        final org.minima.objects.base.MiniNumber sum = com.eurobuddha.wallet.CoinSelector.sumRaw(sel);
        final String destination = wallet.mx();

        prepareInputs(rn, lw.core().getScript(0), coinids, exactToken -> {
            if (!isMinima && exactToken == null) { sweepToken(rn, lw, coins, aggs, i + 1, done); return; }
            new Thread(() -> {
                try {
                    java.util.List<com.eurobuddha.wallet.TxnFactory.InputCoin> inputs = new java.util.ArrayList<>();
                    for (org.minima.utils.json.JSONObject cn : sel)
                        inputs.add(com.eurobuddha.wallet.TxnFactory.fromCoinJson(cn, 0, isMinima ? null : exactToken));
                    com.eurobuddha.wallet.TxnFactory factory = new com.eurobuddha.wallet.TxnFactory(lw.core());
                    final com.eurobuddha.wallet.TxnFactory.BuiltTxn built = factory.buildSend(
                            inputs, destination, sum, new org.minima.objects.base.MiniData(tokId),
                            org.minima.objects.base.MiniNumber.ZERO, newTxnId());
                    main.post(() -> rn.publish(built.getTxnImportCommand(), built.getID(), built.getTxnPostCommand(),
                            new RemoteNode.Cb() {
                                @Override public void onResult(JSONObject pr) {
                                    db.insertWalletTx("SWEEP", sum.toString(), isMinima ? "Minima" : tokId, tokId,
                                            destination, built.getID(), now());
                                    lastSendTs = now();
                                    sweepToken(rn, lw, coins, aggs, i + 1, done + 1);
                                }
                                @Override public void onError(String m) {
                                    toast("Move failed for one token: " + m);
                                    sweepToken(rn, lw, coins, aggs, i + 1, done);
                                }
                            }));
                } catch (Exception be) {
                    final String bem = be.getMessage() == null ? be.toString() : be.getMessage();
                    main.post(() -> { toast("Move failed: " + bem); sweepToken(rn, lw, coins, aggs, i + 1, done); });
                }
            }).start();
        });
    }

    // The preset relay (one for now; editable in the node sheet, ready for multiple URLs when we federate)
    // comes from BUILD CONFIG, injected from local.properties — see app/build.gradle. The token is a real
    // credential for the node's command proxy, so it must never live in source: this repo is public.
    // A build without those properties simply ships no preset — remoteNode() returns null and the wallet
    // asks the user to configure one.
    private static final String DEFAULT_NODE_URL   = BuildConfig.DEFAULT_NODE_URL;
    private static final String DEFAULT_NODE_TOKEN = BuildConfig.DEFAULT_NODE_TOKEN;

    private String nodeUrl()   { String u = prefs.getString("wallet_node_url", "");   return u.isEmpty() ? DEFAULT_NODE_URL   : u; }
    private String nodeToken() { String t = prefs.getString("wallet_node_token", ""); return t.isEmpty() ? DEFAULT_NODE_TOKEN : t; }

    private RemoteNode remoteNode() {
        if (node != null) return node;
        String url = nodeUrl();
        String tok = nodeToken();
        if (url == null || url.isEmpty() || tok == null || tok.isEmpty()) return null;
        node = new RemoteNode(url, tok, main);
        return node;
    }

    /** Refresh the balance IN PLACE — only the amount + token rows change, so the card never flashes/bounces. */
    private void fetchBalance(final String mx, final LinearLayout card) {
        final TextView amt = card.findViewWithTag("bal_amount");
        final LinearLayout tokBox = card.findViewWithTag("bal_tokens");
        if (amt == null || tokBox == null) return;
        RemoteNode rn = remoteNode();
        if (rn == null) {
            amt.setText("—");
            tokBox.removeAllViews();
            TextView s = Ui.label(this, "Connect your node to see balance and send.", 13, 400, Theme.DIM());
            s.setPadding(0, dp(10), 0, dp(10)); tokBox.addView(s);
            tokBox.addView(Ui.pill(this, R.drawable.ic_tune, "Set node", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> nodeConfigSheet()), matchW());
            return;
        }
        rn.balance(mx, new RemoteNode.Cb() {
            @Override public void onResult(JSONObject r) {
                if (!openWallet || isFinishing() || isDestroyed()) return;
                try {
                    org.json.JSONArray rows = r.optJSONArray("response");
                    String minima = "0";
                    java.util.List<com.eurobuddha.wallet.TokenBalance> tokens = new java.util.ArrayList<>();
                    if (rows != null) for (int i = 0; i < rows.length(); i++) {
                        com.eurobuddha.wallet.TokenBalance tb = com.eurobuddha.wallet.TokenBalance.from(rows.getJSONObject(i));
                        if (tb.isMinima()) minima = com.eurobuddha.wallet.Util.showAmount(com.eurobuddha.wallet.Util.held(tb.confirmed, tb.unconfirmed), tb.meta.decimals);
                        else tokens.add(tb);
                    }
                    amt.setText(minima + " MINIMA");
                    tokBox.removeAllViews();
                    for (com.eurobuddha.wallet.TokenBalance tb : tokens) tokBox.addView(tokenRow(tb));
                } catch (Exception e) { /* keep last-known balance on a parse hiccup — no flash */ }
            }
            @Override public void onError(String msg) {
                if (!openWallet || isFinishing() || isDestroyed()) return;
                // transient refresh error: keep the last-known balance rather than wiping it. Only note on first load.
                if ("…".contentEquals(amt.getText())) amt.setText("— node unreachable");
            }
        });
    }

    /** Full token row (ported from the wallet's BalancesView): identicon base + real icon on top +
     *  web-validation checkmark + ticker/name/NFT + sendable amount + coin count. */
    private View tokenRow(final com.eurobuddha.wallet.TokenBalance b) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(12), 0, dp(2));

        int sz = dp(40);
        FrameLayout slot = new FrameLayout(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(sz, sz); slp.rightMargin = dp(12);
        slot.setLayoutParams(slp);
        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new FrameLayout.LayoutParams(sz, sz));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.roundCorners(icon, dp(10));
        if (b.isMinima()) {
            icon.setImageBitmap(com.eurobuddha.wallet.Identicon.minima(sz, Theme.ACCENT()));
        } else {
            icon.setImageBitmap(com.eurobuddha.wallet.Identicon.forToken(b.tokenid, sz));   // deterministic base
            com.eurobuddha.wallet.ImageLoader.loadOver(this, b.meta.iconUrl, icon, null);    // real graphic on top when it loads
        }
        slot.addView(icon);
        boolean verified = b.isMinima();
        if (!b.isMinima() && b.meta.webvalidate != null && !b.meta.webvalidate.isEmpty()) {
            com.eurobuddha.wallet.WebValidate.ensure(this, b.tokenid, b.meta.webvalidate,
                    () -> { if (openWallet && walletMx != null && walletBalCard != null) fetchBalance(walletMx, walletBalCard); });
            verified = Boolean.TRUE.equals(com.eurobuddha.wallet.WebValidate.status(b.tokenid));
        }
        if (verified) {
            ImageView badge = new ImageView(this); int bs = dp(16);
            badge.setLayoutParams(new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END));
            badge.setImageBitmap(com.eurobuddha.wallet.Identicon.checkBadge(bs));
            slot.addView(badge);
        }
        row.addView(slot);

        LinearLayout ident = new LinearLayout(this); ident.setOrientation(LinearLayout.VERTICAL);
        String symbol = (b.meta.ticker != null && !b.meta.ticker.isEmpty()) ? b.meta.ticker : b.name;
        if (symbol == null || symbol.isEmpty()) symbol = "Token";
        TextView sym = Ui.label(this, symbol, 15, 700, Theme.TEXT());
        sym.setMaxLines(1); sym.setEllipsize(android.text.TextUtils.TruncateAt.END); ident.addView(sym);
        String secondary = (b.name != null && !b.name.isEmpty() && !b.name.equalsIgnoreCase(symbol)) ? b.name : null;
        if (b.isNft()) secondary = (secondary == null ? "NFT" : secondary + "  ·  NFT");
        if (secondary != null) {
            TextView nm = Ui.label(this, secondary, 11, 400, b.isNft() ? Theme.ACCENT() : Theme.DIM());
            nm.setMaxLines(1); nm.setEllipsize(android.text.TextUtils.TruncateAt.END); nm.setPadding(0, dp(2), 0, 0); ident.addView(nm);
        }
        row.addView(ident, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout amtCol = new LinearLayout(this); amtCol.setOrientation(LinearLayout.VERTICAL); amtCol.setGravity(Gravity.END);
        TextView av = Ui.label(this, com.eurobuddha.wallet.Util.showAmount(com.eurobuddha.wallet.Util.held(b.confirmed, b.unconfirmed), b.meta.decimals), 16, 700, Theme.TEXT());
        av.setGravity(Gravity.END); amtCol.addView(av);
        boolean pending = com.eurobuddha.wallet.Util.hasPending(b.unconfirmed);
        String cntTxt = b.coins + (b.coins == 1 ? " coin" : " coins") + (pending ? " · pending" : "");
        TextView cnt = Ui.mono(this, cntTxt, 10, pending ? Theme.ACCENT() : Theme.DIM());
        cnt.setGravity(Gravity.END); cnt.setPadding(0, dp(2), 0, 0); amtCol.addView(cnt);
        row.addView(amtCol);
        return row;
    }

    // ===== wallet history (app-owned; survives node resync) =====
    private static final int HIST_PAGE = 20;

    private String tokenLabelFor(org.json.JSONObject cn) {
        Object tk = cn.opt("token");
        if (tk instanceof org.json.JSONObject) {
            Object nm = ((org.json.JSONObject) tk).opt("name");
            if (nm instanceof org.json.JSONObject) return ((org.json.JSONObject) nm).optString("name", "Token");
            if (nm instanceof String) return (String) nm;
        } else if (tk instanceof String) return (String) tk;
        return "Token";
    }

    /** Detect newly-arrived coins for history: first poll per address seeds silently (no backlog spam); after
     *  that a new coinid is a RECEIVE, or CHANGE if it landed within 3 min of our own send. */
    private void pollHistory(final String mx) {
        final RemoteNode rn = remoteNode();
        if (rn == null || mx == null || pollInFlight) return;   // no overlapping runs → no duplicate rows
        pollInFlight = true;
        final boolean seeded = prefs.getBoolean("histseed_" + mx, false);
        rn.coins(mx, new RemoteNode.Cb() {
            @Override public void onResult(JSONObject r) {
                try { new Thread(() -> {
                    boolean any = false;
                    try {
                        org.json.JSONArray raw = r.optJSONArray("response");
                        if (raw != null) for (int i = 0; i < raw.length(); i++) {
                            org.json.JSONObject cn = raw.getJSONObject(i);
                            String coinid = cn.optString("coinid", "");
                            if (coinid.isEmpty() || db.isCoinSeen(coinid)) continue;
                            db.markCoinSeen(coinid, mx);
                            if (!seeded) continue;   // first run for this address — seed only, don't log the backlog
                            String tokenid = cn.optString("tokenid", "0x00");
                            boolean minima = tokenid.isEmpty() || "0x00".equals(tokenid);
                            boolean change = (now() - lastSendTs) < 180;
                            // Token coins carry the raw scaled amount in "amount"; the human value is "tokenamount".
                            String disp = cn.has("tokenamount") ? String.valueOf(cn.opt("tokenamount")) : cn.optString("amount", "0");
                            db.insertWalletTx(change ? "CHANGE" : "RECV", disp,
                                    minima ? "Minima" : tokenLabelFor(cn), tokenid, "", coinid, now());
                            any = true;
                        }
                    } catch (Exception e) { }
                    if (!seeded) prefs.edit().putBoolean("histseed_" + mx, true).apply();
                    final boolean fAny = any;
                    main.post(() -> { pollInFlight = false; if (fAny && openWallet && walletHistOffset <= HIST_PAGE) resetHistory(); });
                }).start(); } catch (Throwable t) { pollInFlight = false; }
            }
            @Override public void onError(String m) { pollInFlight = false; }
        });
    }

    /** Clear + reload the history list from the top (on wallet open + when new tx land). */
    private void resetHistory() {
        if (walletHistBox == null) return;
        walletHistBox.removeAllViews();
        walletHistOffset = 0; walletHistDone = false; walletHistLoading = false;
        loadMoreHistory();
    }
    /** Append the next page of history rows (endless scroll). */
    private void loadMoreHistory() {
        if (walletHistBox == null || walletHistLoading || walletHistDone) return;
        walletHistLoading = true;
        java.util.List<Db.WalletTx> page = db.walletTx(HIST_PAGE, walletHistOffset);
        if (walletHistOffset == 0 && page.isEmpty()) {
            TextView empty = Ui.label(this, "No wallet activity yet.", 13, 400, Theme.DIM());
            empty.setPadding(0, dp(10), 0, dp(4)); walletHistBox.addView(empty);
        }
        for (Db.WalletTx w : page) walletHistBox.addView(historyRow(w));
        walletHistOffset += page.size();
        if (page.size() < HIST_PAGE) walletHistDone = true;
        walletHistLoading = false;
    }
    private View historyRow(Db.WalletTx w) {
        boolean send = "SEND".equals(w.type), change = "CHANGE".equals(w.type);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(10), 0, dp(10));
        int col = change ? Theme.DIM() : (send ? 0xFFE5484D : 0xFF30A46C);   // grey / red-out / green-in
        int ic = change ? R.drawable.ic_dot : (send ? R.drawable.ic_arrow_up : R.drawable.ic_arrow_down);
        FrameLayout badge = new FrameLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(34), dp(34)); blp.rightMargin = dp(12); badge.setLayoutParams(blp);
        badge.setBackground(Ui.round(Ui.alpha(col, 0x22), dp(17)));
        badge.addView(Ui.icon(this, ic, 16, col), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        row.addView(badge);
        LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(Ui.label(this, change ? "Change" : (send ? "Sent" : "Received"), 14, 700, Theme.TEXT()));
        String when = new java.text.SimpleDateFormat("d MMM · HH:mm", java.util.Locale.getDefault()).format(new Date(w.ts * 1000));
        String sub = when + (w.party != null && !w.party.isEmpty() ? "  ·  " + com.eurobuddha.wallet.Util.shorten(w.party) : "");
        TextView subv = Ui.label(this, sub, 11, 400, Theme.DIM()); subv.setPadding(0, dp(2), 0, 0); mid.addView(subv);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView amt = Ui.label(this, (send ? "−" : "+") + com.eurobuddha.wallet.Util.showAmount(w.amount, "")
                + " " + (w.token == null ? "" : w.token), 14, 700, send ? 0xFFE5484D : Theme.TEXT());
        amt.setGravity(Gravity.END); row.addView(amt);
        return row;
    }

    private void nodeConfigSheet() {
        LinearLayout c = sheetColumn();
        TextView t = Ui.label(this, "Wallet node", 20, 700, Theme.TEXT()); t.setGravity(Gravity.CENTER); c.addView(t, centered());
        TextView s = Ui.label(this, "Your Minima wallet-node endpoint + token. Your seed never leaves this phone.", 13, 400, Theme.DIM());
        s.setGravity(Gravity.CENTER); s.setPadding(dp(6), dp(4), dp(6), dp(16)); c.addView(s, centered());
        final EditText urlF = field("https://wallet-rpc…/cmd", false);
        urlF.setText(nodeUrl()); c.addView(urlF, matchW());
        gapIn(c, dp(10));
        final EditText tokF = field("access token", false);
        tokF.setText(nodeToken()); c.addView(tokF, matchW());
        gapIn(c, dp(14));
        final LinearLayout save = Ui.pill(this, R.drawable.ic_check, "Save node", Theme.ACCENT(), Theme.ON_ACCENT(), true, null);
        c.addView(save, matchW());
        final Dialog d = bottomSheet(c);
        save.setOnClickListener(v -> {
            prefs.edit().putString("wallet_node_url", urlF.getText().toString().trim())
                        .putString("wallet_node_token", tokF.getText().toString().trim()).apply();
            if (node != null) { node.onDestroy(); node = null; }
            d.dismiss(); toast("Node saved"); if (openWallet) showWallet();
        });
    }

    private void sendSheet(String prefillAddr) { sendSheet(prefillAddr, null, null); }
    private void sendSheet(String prefillAddr, String recipientLabel) { sendSheet(prefillAddr, recipientLabel, null); }
    private void sendSheet(String prefillAddr, String recipientLabel, final String pubid) {
        LinearLayout c = sheetColumn();
        TextView t = Ui.label(this, recipientLabel != null ? "Send to " + recipientLabel : "Send MINIMA", 20, 700, Theme.TEXT()); t.setGravity(Gravity.CENTER); c.addView(t, centered());
        TextView s = Ui.label(this, "Signed on this phone — your seed never leaves it.", 13, 400, Theme.DIM());
        s.setGravity(Gravity.CENTER); s.setPadding(dp(6), dp(4), dp(6), dp(16)); c.addView(s, centered());
        final EditText addrF = field("recipient Mx… address", false);
        if (prefillAddr != null) addrF.setText(prefillAddr);
        c.addView(addrF, matchW());
        gapIn(c, dp(10));
        // token selector — MINIMA + every held token (scrollable chips)
        final String[] selTokenId = { "0x00" };
        final String[] selTokenName = { "MINIMA" };
        final LinearLayout chipRow = new LinearLayout(this); chipRow.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false); chipScroll.addView(chipRow);
        c.addView(chipScroll, matchW());
        gapIn(c, dp(10));
        final EditText amtF = field("amount", false);
        amtF.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        c.addView(amtF, matchW());
        gapIn(c, dp(10));
        final EditText memoF = field("note (optional)", false);
        c.addView(memoF, matchW());
        gapIn(c, dp(14));
        final LinearLayout sendPill = Ui.pill(this, R.drawable.ic_send, "Review & send", Theme.ACCENT(), Theme.ON_ACCENT(), true, null);
        c.addView(sendPill, matchW());
        final Dialog d = bottomSheet(c);
        sendPill.setOnClickListener(v -> {
            String addr = addrF.getText().toString().trim(), amt = amtF.getText().toString().trim();
            if (addr.isEmpty() || amt.isEmpty()) { toast("Enter address and amount"); return; }
            confirmSend(addr, amt, selTokenId[0], selTokenName[0], memoF.getText().toString().trim(), pubid, d);
        });
        buildTokenChips(chipRow, selTokenId, selTokenName);
    }

    /** Fill the send sheet's token chips: MINIMA (default) + every held token, fetched live. */
    private void buildTokenChips(final LinearLayout chipRow, final String[] selTokenId, final String[] selTokenName) {
        chipRow.removeAllViews();
        addTokenChip(chipRow, "0x00", "MINIMA", selTokenId, selTokenName);
        RemoteNode rn = remoteNode();
        if (walletMx == null || rn == null) return;
        rn.balance(walletMx, new RemoteNode.Cb() {
            @Override public void onResult(JSONObject r) {
                try {
                    org.json.JSONArray rows = r.optJSONArray("response");
                    if (rows == null) return;
                    for (int i = 0; i < rows.length(); i++) {
                        com.eurobuddha.wallet.TokenBalance tb = com.eurobuddha.wallet.TokenBalance.from(rows.getJSONObject(i));
                        if (tb.isMinima()) continue;
                        String label = (tb.meta.ticker != null && !tb.meta.ticker.isEmpty()) ? tb.meta.ticker : tb.name;
                        addTokenChip(chipRow, tb.tokenid, (label == null || label.isEmpty()) ? "Token" : label, selTokenId, selTokenName);
                    }
                } catch (Exception e) { }
            }
            @Override public void onError(String m) { }
        });
    }
    private void addTokenChip(final LinearLayout chipRow, final String tokenid, final String label,
                              final String[] selTokenId, final String[] selTokenName) {
        final TextView chip = Ui.label(this, label, 13, 700, Theme.TEXT());
        chip.setTag(tokenid);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8); chip.setLayoutParams(lp);
        boolean on = tokenid.equals(selTokenId[0]);
        chip.setBackground(Ui.round(on ? Theme.ACCENT() : Theme.RECV(), dp(16)));
        chip.setTextColor(on ? Theme.ON_ACCENT() : Theme.TEXT());
        chip.setOnClickListener(v -> {
            selTokenId[0] = tokenid; selTokenName[0] = label;
            for (int i = 0; i < chipRow.getChildCount(); i++) {
                View ch = chipRow.getChildAt(i);
                boolean sel = tokenid.equals(ch.getTag());
                ch.setBackground(Ui.round(sel ? Theme.ACCENT() : Theme.RECV(), dp(16)));
                if (ch instanceof TextView) ((TextView) ch).setTextColor(sel ? Theme.ON_ACCENT() : Theme.TEXT());
            }
        });
        chipRow.addView(chip);
    }

    private void confirmSend(final String addr, final String amt, final String tokenid, final String tokenName,
                             final String memo, final String pubid, final Dialog sheet) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Confirm send")
                .setMessage(amt + " " + tokenName + "\nto " + addr + "\n\nSigned locally, broadcast via your node.")
                .setPositiveButton("Sign & send", (dd, w) -> doSend(addr, amt, tokenid, tokenName, memo, pubid, sheet))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Make each input coin spendable on our untracked megammr node BEFORE broadcasting: register our
     * (public) script, then pull each coin's proof out of the megammr and import it into the tracked set,
     * so txnbasics can attach script + proof (fixes "Script Missing"). Best-effort + idempotent — an
     * "already tracked" error is fine; runs onReady on the main thread when done. Seed never leaves device.
     */
    /** Receives the byte-exact {@link org.minima.objects.Token} parsed from an input coin's coinexport
     *  CoinProof (null for native Minima), once the backfill has run. */
    private interface TokenSink { void ready(org.minima.objects.Token exactToken); }

    private void prepareInputs(final RemoteNode rn, final String script, final java.util.List<String> coinids, final TokenSink onReady) {
        final org.minima.objects.Token[] tok = { null };
        rn.trackScript(script, new RemoteNode.Cb() {
            @Override public void onResult(JSONObject r) { backfillCoin(rn, coinids, 0, tok, onReady); }
            @Override public void onError(String m)      { backfillCoin(rn, coinids, 0, tok, onReady); }
        });
    }
    private void backfillCoin(final RemoteNode rn, final java.util.List<String> coinids, final int i,
                              final org.minima.objects.Token[] tok, final TokenSink onReady) {
        if (i >= coinids.size()) { onReady.ready(tok[0]); return; }
        rn.coinExport(coinids.get(i), new RemoteNode.Cb() {
            @Override public void onResult(JSONObject r) {
                org.json.JSONObject resp = r.optJSONObject("response");
                String blob = (resp == null) ? null : resp.optString("data", null);
                if (blob == null || blob.isEmpty()) { backfillCoin(rn, coinids, i + 1, tok, onReady); return; }
                if (tok[0] == null) {   // capture the byte-exact token descriptor from the binary CoinProof
                    try {
                        org.minima.objects.CoinProof cp = org.minima.objects.CoinProof.convertMiniDataVersion(
                                new org.minima.objects.base.MiniData(blob));
                        org.minima.objects.Token t = cp.getCoin().getToken();
                        if (t != null) tok[0] = t;
                    } catch (Exception e) { }
                }
                rn.coinImport(blob, new RemoteNode.Cb() {
                    @Override public void onResult(JSONObject r2) { backfillCoin(rn, coinids, i + 1, tok, onReady); }
                    @Override public void onError(String m)      { backfillCoin(rn, coinids, i + 1, tok, onReady); }  // e.g. already tracked
                });
            }
            @Override public void onError(String m) { backfillCoin(rn, coinids, i + 1, tok, onReady); }
        });
    }

    /** Safety margin applied when the user genuinely does not know their prior-use count. Skipping this
     *  many one-time keys costs nothing but the leaves; reusing a single one can lose the funds. */
    private static final int UNKNOWN_USES_MARGIN = 256;

    /**
     * The CRITICAL WOTS-reuse guard: signing stays blocked while the true one-time-key count is unknown,
     * because signing a leaf that was already signed elsewhere leaks it.
     *
     * <p>The field is deliberately EMPTY. It used to be pre-filled with {@code vault.currentUses(0)},
     * which on a fresh import reads 0 — so the safest-looking action (accept the default) was the
     * dangerous one, and accepting it signed from leaf 0 against a key that had already spent leaves.
     * What this device has recorded is shown as information only; the user must type a number, or take
     * the explicit "I don't know" path which over-estimates by {@link #UNKNOWN_USES_MARGIN}.
     */
    private void untrustedSendGate() {
        final EditText count = new EditText(this);
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        count.setHint("Payments already sent");
        count.setTextColor(Theme.TEXT());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(count, matchW());
        TextView known = Ui.label(this,
                "This device has recorded " + vault.currentUses(0) + " so far — that is a minimum, not an "
              + "answer.", 12, 400, Theme.DIM());
        known.setPadding(0, dp(8), 0, 0);
        wrap.addView(known, matchW());

        new android.app.AlertDialog.Builder(this)
                .setTitle("Enable sending")
                .setMessage("FreezePeach can't tell how many of this wallet's one-time keys have already been "
                        + "used on another device. Enter how many payments it has sent there.\n\n"
                        + "Over-estimating is safe (it just skips keys); under-estimating can reuse a key "
                        + "and lose your funds.")
                .setView(wrap)
                .setPositiveButton("Enable sending", (d, w) -> {
                    String cs = count.getText().toString().trim();
                    if (cs.isEmpty()) { toast("Enter how many payments were already sent"); return; }
                    int n;
                    try { n = Integer.parseInt(cs); }
                    catch (NumberFormatException nfe) { toast("Enter a whole number"); return; }
                    if (n < 0) { toast("Can't be negative"); return; }
                    vault.attestKeyUses(0, n);
                    toast("Sending enabled from " + n + " prior signatures");
                })
                .setNeutralButton("I don't know", (d, w) -> {
                    int n = vault.currentUses(0) + UNKNOWN_USES_MARGIN;
                    vault.attestKeyUses(0, n);
                    toast("Skipped ahead to " + n + " to stay safe");
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void doSend(final String recipient, final String amountStr, final String tokenid, final String tokenName,
                        final String memo, final String pubid, final Dialog sheet) {
        if (sending) { toast("A send is already in progress…"); return; }
        if (!vault.isKeyUsesTrusted()) { untrustedSendGate(); return; }   // CRITICAL: an imported seed can't sign until keyuses is attested
        final RemoteNode rn = remoteNode();
        if (rn == null || wallet == null) { toast("Set your node first"); return; }
        final boolean isMinima = tokenid == null || tokenid.isEmpty() || "0x00".equals(tokenid) || "0x0".equals(tokenid);
        final String tokId = isMinima ? "0x00" : tokenid;
        final String tokName = (tokenName == null || tokenName.isEmpty()) ? (isMinima ? "Minima" : tokId) : tokenName;
        final org.minima.objects.base.MiniNumber amount;
        try {
            if (!com.eurobuddha.wallet.Util.isValidAddress(recipient)) { toast("Invalid recipient address"); return; }
            amount = new org.minima.objects.base.MiniNumber(amountStr);
            if (amount.isLessEqual(org.minima.objects.base.MiniNumber.ZERO)) { toast("Amount must be greater than 0"); return; }
        } catch (Exception e) { toast("Invalid amount"); return; }
        sending = true;
        toast("Preparing…");
        rn.coins(wallet.mx(), new RemoteNode.Cb() {
            @Override public void onResult(JSONObject r) {
                try { new Thread(() -> {
                    try {
                        org.json.JSONArray raw = r.optJSONArray("response");
                        org.minima.utils.json.JSONArray coins = (raw == null)
                                ? new org.minima.utils.json.JSONArray()
                                : (org.minima.utils.json.JSONArray) new org.minima.utils.json.parser.JSONParser().parse(raw.toString());
                        // Custom tokens carry a scale: convert the DISPLAY amount to the RAW on-chain amount
                        // (Minima: raw == display). The factory does no decimal conversion (fund-critical).
                        org.minima.objects.base.MiniNumber amountRaw = amount;
                        if (!isMinima) {
                            org.minima.objects.Token tokDesc = null;
                            for (int i = 0; i < coins.size(); i++) {
                                org.minima.utils.json.JSONObject cn = (org.minima.utils.json.JSONObject) coins.get(i);
                                if (tokId.equals(String.valueOf(cn.get("tokenid")))) {
                                    tokDesc = com.eurobuddha.wallet.TxnFactory.fromCoinJson(cn, 0).getToken(); break;
                                }
                            }
                            if (tokDesc == null) throw new Exception("No coins of that token in this wallet");
                            amountRaw = tokDesc.getScaledMinimaAmount(amount);
                        }
                        final org.minima.objects.base.MiniNumber reqRaw = amountRaw;
                        final java.util.List<org.minima.utils.json.JSONObject> sel = com.eurobuddha.wallet.CoinSelector.selectToCover(coins, tokId, reqRaw);
                        final java.util.List<String> coinids = new java.util.ArrayList<>();
                        for (org.minima.utils.json.JSONObject cn : sel) coinids.add(String.valueOf(cn.get("coinid")));
                        final String inScript = wallet.core().getScript(0);
                        // Backfill FIRST (register script + coinexport + coinimport), capturing the byte-exact Token
                        // from the coinexport CoinProof; THEN build with that exact Token — the coins JSON can't
                        // round-trip a JSON-object token name, giving a wrong tokenid ("TokenID doesn't match").
                        main.post(() -> prepareInputs(rn, inScript, coinids, exactToken -> {
                            if (!isMinima && exactToken == null) { sending = false; toast("Couldn't read the token — try again"); return; }
                            new Thread(() -> {
                                try {
                                    java.util.List<com.eurobuddha.wallet.TxnFactory.InputCoin> inputs = new java.util.ArrayList<>();
                                    for (org.minima.utils.json.JSONObject cn : sel)
                                        inputs.add(com.eurobuddha.wallet.TxnFactory.fromCoinJson(cn, 0, isMinima ? null : exactToken));
                                    com.eurobuddha.wallet.TxnFactory factory = new com.eurobuddha.wallet.TxnFactory(wallet.core());
                                    // buildSend == local WOTS signing; PrefsKeyUses reserves-before-sign into two durable mirrors.
                                    final com.eurobuddha.wallet.TxnFactory.BuiltTxn built = factory.buildSend(
                                            inputs, recipient, reqRaw, new org.minima.objects.base.MiniData(tokId),
                                            org.minima.objects.base.MiniNumber.ZERO, newTxnId());
                                    // The one-time leaf is spent HERE, at build time — not when the broadcast
                                    // lands. Refresh the vault's portable snapshot immediately so an encrypted
                                    // backup can never claim a lower count than has actually been signed; a
                                    // stale snapshot restored elsewhere would hand out an already-used leaf.
                                    // Runs on this background thread (PBKDF2, ~200ms) and must not wait for publish.
                                    try { vault.syncKeyUses(0); } catch (Exception se) { /* counter is already durable */ }
                                    main.post(() -> rn.publish(built.getTxnImportCommand(), built.getID(), built.getTxnPostCommand(), new RemoteNode.Cb() {
                                        @Override public void onResult(JSONObject pr) {
                                            sending = false;
                                            if (isFinishing() || isDestroyed()) return;
                                            if (sheet != null) sheet.dismiss();
                                            db.insertWalletTx("SEND", amountStr, tokName, tokId, recipient, built.getID(), now());   // app-owned history
                                            lastSendTs = now();
                                            if (pubid != null) recordAndNotifyPayment(pubid, amountStr, tokName, memo);   // payment bubble + notify recipient
                                            toast("Sent ✓");
                                            if (openWallet) showWallet();
                                        }
                                        @Override public void onError(String m) {
                                            sending = false;
                                            // txnimport/txnbasics fail BEFORE broadcast (safe to retry); a txnpost failure may
                                            // mean the txn actually landed but the ack was lost — do NOT invite a blind retry.
                                            if (m != null && m.startsWith("txnpost"))
                                                toast("Broadcast may already have been sent — check your balance before retrying.");
                                            else
                                                toast("Not broadcast, safe to retry: " + m);
                                        }
                                    }));
                                } catch (Exception be) {
                                    final String bem = be.getMessage() == null ? be.toString() : be.getMessage();
                                    main.post(() -> { sending = false; toast("Send failed: " + bem); });
                                }
                            }).start();
                        }));
                    } catch (com.eurobuddha.wallet.CoinSelector.InsufficientFundsException ife) {
                        main.post(() -> { sending = false; toast("Insufficient funds"); });
                    } catch (Exception e) {
                        final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                        main.post(() -> { sending = false; toast("Send failed: " + m); });
                    }
                }).start(); } catch (Throwable t) { sending = false; toast("Couldn't start the send"); }
            }
            @Override public void onError(String msg) { sending = false; toast("Node error: " + msg); }
        });
    }

    private String newTxnId() {
        byte[] r = new byte[8]; new java.security.SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder("fp");
        for (byte x : r) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Seal my current receive address to a contact so they can pay me (once per session per contact). */
    private void shareMyAddress(final String pubid) {
        final Wallet w = wallet;
        if (w == null || crypto == null || http == null) return;
        if (!sharedAddrTo.add(pubid)) return;
        new Thread(() -> {
            try { w.ensureAddress(); } catch (Exception e) { }
            final String mx = w.mx();
            if (mx == null) { sharedAddrTo.remove(pubid); return; }
            try {
                org.json.JSONObject env = new org.json.JSONObject(); env.put("fpw", 1); env.put("mx", mx);
                final byte[] wire = env.toString().getBytes(StandardCharsets.UTF_8);
                main.post(() -> MailboxTransport.sendMessage(http, crypto, pubid, wire, new MailboxTransport.SendCb() {
                    @Override public void onSent(String msgid) { }
                    @Override public void onFailed(String message) { sharedAddrTo.remove(pubid); }   // retry on next open
                }));
            } catch (Exception e) { sharedAddrTo.remove(pubid); }
        }).start();
    }

    private void sendToContact(final String pubid) {
        if (remoteNode() == null) { toast("Set your wallet node first"); nodeConfigSheet(); return; }
        String addr = db.contactAddress(pubid);
        if (addr == null || addr.isEmpty()) {
            toast(nameOf(pubid) + " hasn't shared a wallet address yet — ask them to open this chat.");
            return;
        }
        sendSheet(addr, nameOf(pubid), pubid);
    }

    /** After a contact send confirms: drop a "You sent" bubble in the chat + notify the recipient so theirs shows "You received". */
    private void recordAndNotifyPayment(final String pubid, final String amount, final String token, final String memo) {
        final String pj = paymentJson(amount, token, memo);
        db.insertMessage(pubid, true, memo == null ? "" : memo, now(), newTxnId(), Db.KIND_PAYMENT, pj);
        try {
            org.json.JSONObject env = new org.json.JSONObject();
            env.put("fpw", 2); env.put("amt", amount); env.put("tok", token);
            if (memo != null && !memo.isEmpty()) env.put("memo", memo);
            MailboxTransport.sendMessage(http, crypto, pubid, env.toString().getBytes(StandardCharsets.UTF_8),
                    new MailboxTransport.SendCb() {
                        @Override public void onSent(String mid) { }
                        @Override public void onFailed(String m) { }
                    });
        } catch (Exception e) { }
        if (pubid.equals(openContact)) showConversation(pubid);
    }

    private static String paymentJson(String amt, String tok, String memo) {
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("amt", amt); j.put("tok", tok == null ? "Minima" : tok);
            if (memo != null && !memo.isEmpty()) j.put("memo", memo);
            return j.toString();
        } catch (Exception e) { return "{}"; }
    }

    private void walletSettingsSheet() {
        LinearLayout c = sheetColumn();
        TextView t = Ui.label(this, "Wallet settings", 20, 700, Theme.TEXT()); t.setGravity(Gravity.CENTER); c.addView(t, centered());
        final Dialog d = bottomSheet(c);
        c.addView(sheetRow(R.drawable.ic_tune, "Wallet node", Theme.ACCENT(), Theme.TEXT(), v -> { d.dismiss(); nodeConfigSheet(); }));
        c.addView(sheetRow(R.drawable.ic_shield, "Seed & Backup", Theme.ACCENT(), Theme.TEXT(), v -> { d.dismiss(); seedBackupGate(); }));
    }

    /** Require a device-credential (PIN/pattern/biometric) before revealing the seed. */
    private void seedBackupGate() {
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardSecure()) {
            Intent i = km.createConfirmDeviceCredentialIntent("Reveal your seed", "Confirm it's you before showing your wallet seed");
            if (i != null) { try { startActivityForResult(i, REQ_REVEAL); return; } catch (Exception e) {} }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("No screen lock")
                .setMessage("This device has no PIN or biometric, so we can't protect this. Anyone holding your phone could see your seed. Continue?")
                .setPositiveButton("Show seed", (dd, w) -> showSeedBackup())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSeedBackup() {
        if (vault == null || !vault.isOpen()) { toast("Unlock first"); return; }
        final String phrase = vault.phrase();

        LinearLayout c = sheetColumn();
        TextView t = Ui.label(this, "Seed phrase", 20, 700, Theme.TEXT()); t.setGravity(Gravity.CENTER); c.addView(t, centered());
        TextView warn = Ui.label(this, "Your master secret — controls your funds AND identity. Never share it. Write it on paper and store it offline.", 13, 400, Theme.DANGER());
        warn.setGravity(Gravity.CENTER); warn.setPadding(dp(8), dp(6), dp(8), dp(14)); c.addView(warn, centered());

        TextView lbl = Ui.label(this, "YOUR WORDS", 12, 700, Theme.DIM()); lbl.setLetterSpacing(0.1f); lbl.setPadding(0, 0, 0, dp(6)); c.addView(lbl);
        TextView ph = Ui.mono(this, phrase, 16, Theme.TEXT());
        ph.setBackground(Ui.round(Theme.RECV(), dp(12))); ph.setPadding(dp(14), dp(14), dp(14), dp(14)); c.addView(ph, matchW());

        TextView note = Ui.label(this, "To move to another device, use Export encrypted backup instead — it's passphrase-locked and carries your keyuses safety counter. Your shareable contact code is a different thing: Settings → My code & ID.", 12, 400, Theme.DIM());
        note.setPadding(dp(2), dp(14), dp(2), 0); c.addView(note);

        final Dialog d = bottomSheet(c);
        // Block screenshots / screen-recording while the seed is on screen.
        if (d.getWindow() != null) d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout copyPh = Ui.pill(this, R.drawable.ic_copy, "Copy seed phrase", Theme.ACCENT(), Theme.ON_ACCENT(), true,
                v -> { copy(phrase); haptic(c); toast("Phrase copied"); });
        copyPh.setPadding(0, dp(12), 0, 0); c.addView(copyPh, matchW());
    }

    private void showConversation(final String pubid) {
        openContact = pubid; openGroup = null; atChats = false;
        root.setBackgroundColor(Theme.BG());
        root.setGravity(Gravity.NO_GRAVITY);
        root.removeAllViews(); root.setPadding(0, 0, 0, 0);

        LinearLayout hdr = header();
        hdr.addView(Ui.iconButton(this, R.drawable.ic_back, 24, 44, Theme.TEXT(), v -> showChats()));
        FrameLayout av = Ui.avatar(this, pubid, nameOf(pubid), 36);
        LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(dp(36), dp(36)); avlp.leftMargin = dp(4);
        av.setLayoutParams(avlp); hdr.addView(av);
        LinearLayout who = new LinearLayout(this); who.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.leftMargin = dp(10); wlp.gravity = Gravity.CENTER_VERTICAL; who.setLayoutParams(wlp);
        TextView n = Ui.label(this, nameOf(pubid), 17, 600, Theme.TEXT()); n.setMaxLines(1);
        who.addView(n); who.addView(Ui.mono(this, Fp.shortId(pubid), 12, Theme.DIM()));
        who.setClickable(true); Ui.press(who); who.setOnClickListener(v -> contactSheet(pubid));
        hdr.addView(who);
        hdr.addView(Ui.iconButton(this, R.drawable.ic_more, 22, 44, Theme.TEXT(), v -> contactSheet(pubid)));
        root.addView(hdr);
        root.addView(Ui.glowStrip(this));

        final ScrollView sc = new ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final LinearLayout msgs = new LinearLayout(this); msgs.setOrientation(LinearLayout.VERTICAL);
        msgs.setPadding(dp(12), dp(12), dp(12), dp(8));
        for (Db.Msg m : db.messages(pubid)) msgs.addView(bubble(m));
        sc.addView(msgs); root.addView(sc);
        sc.post(() -> sc.fullScroll(View.FOCUS_DOWN));

        LinearLayout comp = new LinearLayout(this); comp.setOrientation(LinearLayout.VERTICAL);
        comp.setBackgroundColor(Theme.SURFACE());
        View topline = new View(this);
        topline.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ Ui.alpha(Theme.ACCENT(), 0), Ui.alpha(Theme.ACCENT(), 0x66), Ui.alpha(Theme.ACCENT(), 0) }));
        comp.addView(topline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM); bar.setPadding(dp(6), dp(8), dp(8), dp(10));
        bar.addView(Ui.iconButton(this, R.drawable.ic_attach, 24, 44, Theme.DIM(), v -> attachMenu(pubid)));
        bar.addView(Ui.iconButton(this, R.drawable.ic_wallet, 24, 44, Theme.DIM(), v -> sendToContact(pubid)));
        shareMyAddress(pubid);   // auto-exchange receive addresses so contacts can pay each other (throttled per session)
        final EditText in = new EditText(this);
        in.setHint("Message"); in.setHintTextColor(Theme.DIM()); in.setTextColor(Theme.TEXT());
        in.setBackground(Ui.round(Theme.RECV(), dp(22))); in.setPadding(dp(16), dp(11), dp(16), dp(11));
        in.setTextSize(15); in.setMaxLines(5);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = dp(4); ilp.rightMargin = dp(8); in.setLayoutParams(ilp);
        bar.addView(in); currentInput = in;
        final FrameLayout send = new FrameLayout(this);
        GradientDrawable sg = new GradientDrawable(); sg.setShape(GradientDrawable.OVAL); sg.setColor(Theme.ACCENT());
        send.setBackground(sg);
        send.addView(Ui.icon(this, R.drawable.ic_send, 20, Theme.ON_ACCENT()),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        send.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        send.setAlpha(0f); send.setScaleX(0.6f); send.setScaleY(0.6f);
        send.setClickable(true); Ui.press(send);
        send.setOnClickListener(v -> {
            String text = in.getText().toString();
            if (text.trim().isEmpty()) return;
            in.setText("");
            send.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70).withEndAction(
                    () -> send.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator()).setDuration(140).start()).start();
            sendText(pubid, text);
        });
        bar.addView(send);
        comp.addView(bar); root.addView(comp);
        in.addTextChangedListener(new TextWatcher() {
            boolean shown = false;
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean has = s.toString().trim().length() > 0;
                if (has && !shown) { shown = true; send.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new OvershootInterpolator(1.5f)).start(); }
                else if (!has && shown) { shown = false; send.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(140).start(); }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private View bubble(Db.Msg m) {
        LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(3), 0, dp(3)); line.setGravity(m.mine ? Gravity.END : Gravity.START);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(m.mine ? Gravity.END : Gravity.START);
        bubbleBody(m, col); line.addView(col); return line;
    }
    private View groupBubble(Db.Msg m) {
        LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(3), 0, dp(3)); line.setGravity(m.mine ? Gravity.END : Gravity.START);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(m.mine ? Gravity.END : Gravity.START);
        if (!m.mine && m.sender != null) {
            TextView s = Ui.label(this, nameOf(m.sender), 11, 700, Fp.avatarColor(m.sender));
            s.setPadding(dp(10), 0, dp(4), dp(2)); col.addView(s);
        }
        bubbleBody(m, col); line.addView(col); return line;
    }
    private void bubbleBody(Db.Msg m, LinearLayout col) {
        if (m.kind == Db.KIND_PAYMENT) { paymentBubble(m, col); return; }
        if (m.kind == Db.KIND_IMAGE || m.kind == Db.KIND_VIDEO) {
            final boolean video = m.kind == Db.KIND_VIDEO;
            String thumbPath = video ? videoThumb(m.media) : m.media;
            Bitmap bmp = thumbPath != null ? decodeSampled(thumbPath, 512) : null;   // ~display size, limit bitmap memory
            int w = dp(232);
            FrameLayout frame = new FrameLayout(this);
            frame.setBackground(Ui.bubble(this, m.mine)); frame.setPadding(dp(2), dp(2), dp(2), dp(2));
            FrameLayout media = new FrameLayout(this);
            if (bmp != null) {
                int h = Math.max(dp(120), Math.min(dp(320), Math.round(w * (float) bmp.getHeight() / bmp.getWidth())));
                ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(bmp); iv.setLayoutParams(new FrameLayout.LayoutParams(w, h));
                Ui.roundCorners(iv, dp(14)); media.addView(iv);
                media.setLayoutParams(new FrameLayout.LayoutParams(w, h));
            } else {
                media.setBackground(Ui.round(Theme.RECV(), dp(14)));
                media.setLayoutParams(new FrameLayout.LayoutParams(w, dp(160)));
                media.addView(Ui.icon(this, video ? R.drawable.ic_video : R.drawable.ic_photo, 30, Theme.DIM()),
                        new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            }
            if (video) {
                FrameLayout play = new FrameLayout(this);
                GradientDrawable pg = new GradientDrawable(); pg.setShape(GradientDrawable.OVAL); pg.setColor(0x99000000);
                play.setBackground(pg);
                play.addView(Ui.icon(this, R.drawable.ic_play, 24, 0xFFFFFFFF), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
                media.addView(play, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
            }
            final String mediaPath = m.media;
            if (mediaPath != null) {
                media.setClickable(true); Ui.press(media);
                media.setOnClickListener(v -> { if (video) playVideo(mediaPath); else openImageViewer(mediaPath); });
            }
            frame.addView(media);
            col.addView(frame);
        } else {
            TextView b = new TextView(this); b.setText(m.body); b.setTextSize(15);
            b.setTextColor(m.mine ? Theme.SENT_TEXT() : Theme.TEXT());
            b.setPadding(dp(14), dp(10), dp(14), dp(10));
            b.setLineSpacing(0, 1.12f);
            b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78));
            b.setBackground(Ui.bubble(this, m.mine));
            col.addView(b);
        }
        TextView t = Ui.mono(this, hhmm.format(new Date(m.ts * 1000)) + (m.mine ? " ✓" : ""), 11,
                m.mine ? Ui.alpha(Theme.DIM(), 0xCC) : Theme.DIM());
        t.setPadding(dp(4), dp(3), dp(4), 0); col.addView(t);
    }

    /** A payment bubble (accent card): ↑ You sent / ↓ You received, big amount + token, optional note, time. */
    private void paymentBubble(Db.Msg m, LinearLayout col) {
        String amt = "", tok = "Minima", memo = null;
        try {
            org.json.JSONObject j = new org.json.JSONObject(m.media == null ? "{}" : m.media);
            amt = j.optString("amt", ""); tok = j.optString("tok", "Minima"); memo = j.optString("memo", null);
        } catch (Exception e) { }
        if ((memo == null || memo.isEmpty()) && m.body != null && !m.body.isEmpty()) memo = m.body;

        LinearLayout pay = new LinearLayout(this); pay.setOrientation(LinearLayout.VERTICAL);
        pay.setBackground(Ui.round(Theme.ACCENT(), dp(16)));
        pay.setPadding(dp(16), dp(12), dp(16), dp(12));
        pay.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.66), ViewGroup.LayoutParams.WRAP_CONTENT));

        int hc = Ui.alpha(Theme.ON_ACCENT(), 0xCC);
        LinearLayout hd = new LinearLayout(this); hd.setOrientation(LinearLayout.HORIZONTAL); hd.setGravity(Gravity.CENTER_VERTICAL);
        hd.addView(Ui.icon(this, m.mine ? R.drawable.ic_arrow_up : R.drawable.ic_arrow_down, 13, hc));
        hd.addView(Ui.label(this, m.mine ? "  You sent" : "  You received", 12, 600, hc));
        pay.addView(hd);

        TextView av = Ui.label(this, amt + "  " + tok, 20, 800, Theme.ON_ACCENT());
        av.setPadding(0, dp(5), 0, 0); pay.addView(av);

        if (memo != null && !memo.isEmpty()) {
            TextView mv = Ui.label(this, memo, 13, 400, Ui.alpha(Theme.ON_ACCENT(), 0xDD));
            mv.setPadding(0, dp(5), 0, 0); pay.addView(mv);
        }
        col.addView(pay);
        TextView tt = Ui.mono(this, hhmm.format(new Date(m.ts * 1000)) + (m.mine ? " ✓" : ""), 11, Theme.DIM());
        tt.setPadding(dp(4), dp(3), dp(4), 0); col.addView(tt);
    }

    // =============================================================================================
    // Messaging
    // =============================================================================================

    private void sendText(final String pubid, final String text) {
        MailboxTransport.sendMessage(http, crypto, pubid, text.getBytes(StandardCharsets.UTF_8),
                new MailboxTransport.SendCb() {
                    @Override public void onSent(String msgid) {
                        db.insertMessage(pubid, true, text, now(), msgid);
                        if (pubid.equals(openContact)) showConversation(pubid);
                    }
                    @Override public void onFailed(String message) { toast("Send failed: " + message); }
                });
    }

    private void sendImageUri(String pubid, Uri uri) {
        new Thread(() -> {
            try { byte[] r = readUri(uri); main.post(() -> sendImageBytes(pubid, r)); }
            catch (Exception e) { main.post(() -> toast("Couldn't read that image")); }
        }).start();
    }
    private void sendImageBytes(final String pubid, final byte[] raw) {
        toast("Sending photo…");
        new Thread(() -> {
            final byte[] jpg = compressImage(raw);
            main.post(() -> MediaTransport.send(http, crypto, Sodium.get(), pubid, jpg, "image/jpeg",
                    new MediaTransport.SendCb() {
                        @Override public void onSent(String msgid) {
                            new Thread(() -> {
                                final String path = saveImage(msgid, jpg);
                                main.post(() -> {
                                    db.insertMessage(pubid, true, "📷 Photo", now(), msgid, Db.KIND_IMAGE, path);
                                    if (pubid.equals(openContact)) showConversation(pubid);
                                });
                            }).start();
                        }
                        @Override public void onFailed(String m) { toast("Photo failed: " + m); }
                    }));
        }).start();
    }
    private void sendVideoUri(String pubid, Uri uri) {
        new Thread(() -> {
            try { byte[] r = readUri(uri); main.post(() -> sendVideoBytes(pubid, r)); }
            catch (Exception e) { main.post(() -> toast("Video too large (max 25 MB) or unreadable")); }
        }).start();
    }
    private void sendVideoBytes(final String pubid, final byte[] mp4) {
        if (mp4.length > 25L * 1024 * 1024) { toast("Video too large (max 25 MB)"); return; }
        toast("Sending video…");
        MediaTransport.send(http, crypto, Sodium.get(), pubid, mp4, "video/mp4", new MediaTransport.SendCb() {
            @Override public void onSent(String msgid) {
                new Thread(() -> {
                    final String path = saveVideoFile(msgid, mp4);
                    main.post(() -> {
                        db.insertMessage(pubid, true, "🎬 Video", now(), msgid, Db.KIND_VIDEO, path);
                        if (pubid.equals(openContact)) showConversation(pubid);
                    });
                }).start();
            }
            @Override public void onFailed(String m) { toast("Video failed: " + m); }
        });
    }

    // ===== notifications + receive tone =====
    private static final String NOTIF_CHANNEL = "fp_messages";

    private void createNotifChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    NOTIF_CHANNEL, "Messages", android.app.NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("New FreezePeach messages");
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
    private void requestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 5100); } catch (Exception e) { }
        }
    }
    /** Notify + tone for an incoming message, unless that exact chat is open. Runs on the MAIN thread
     *  (MailboxHttp marshals drain callbacks back to main), so the openContact/openGroup reads are race-free. */
    private void maybeNotify(String fromPubid, String gid, String preview) {
        boolean viewing = (gid != null) ? gid.equals(openGroup) : (fromPubid != null && fromPubid.equals(openContact));
        if (viewing) return;
        String key = gid != null ? gid : (fromPubid != null ? fromPubid : "fp");
        String title = (gid != null) ? (db.group(gid) != null ? db.group(gid).name : "Group") : nameOf(fromPubid);
        notifyIncoming(title, preview, key.hashCode());
    }
    private void notifyIncoming(String title, String text, int id) {
        boolean canNotify = android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (canNotify) {
            try {
                Intent it = new Intent(this, MainActivity.class); it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, it,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                android.app.Notification n = new android.app.Notification.Builder(this, NOTIF_CHANNEL)
                        .setSmallIcon(R.drawable.ic_send)
                        .setContentTitle(title == null ? "New message" : title)
                        .setContentText(text).setAutoCancel(true).setContentIntent(pi).build();
                android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
                if (nm != null) nm.notify(id, n);   // stable per-conversation id → one line per chat, replaced not stacked
            } catch (Exception e) { }
        }
        playReceiveTone();
    }
    private void playReceiveTone() {
        if (!prefs.getBoolean("sound_on", true)) return;
        long t = android.os.SystemClock.uptimeMillis();
        if (t - lastToneMs < 1200) return;   // throttle a drain batch to a single beep
        lastToneMs = t;
        try {
            final android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 180);
            main.postDelayed(tg::release, 400);
        } catch (Exception e) { }
    }

    private void doDrain() {
        if (me == null) return;
        new MailboxClient(http, crypto, meta, me.publicId(),
                new MailboxClient.Router() {
                    @Override public boolean handle(String msgid, Opened o, JSONObject mm) {
                        JSONObject grp = tryJson(o.plaintext);
                        if (grp != null && grp.optInt("fpg", 0) == 1) {
                            String gid = grp.optString("gid", "");
                            if (gid.isEmpty() || !grp.has("gid")) return false;
                            if (grp.has("members")) {                     // group roster (create or membership update)
                                Db.Group existing = db.group(gid);
                                if (existing != null && !adminsContains(existing.admin, o.fromPublicId)) return false;  // only an admin may edit an existing roster
                                org.json.JSONArray mem = grp.optJSONArray("members");
                                db.insertGroup(gid, grp.optString("name", "Group"), mem != null ? mem.toString() : "[]", grp.optString("admin", null));
                                return true;
                            }
                            if (!db.groupMembers(gid).contains(o.fromPublicId)) return false;   // group messages only from current members
                            if (grp.has("mm")) {                          // group media
                                JSONObject mediaManifest = grp.optJSONObject("mm");
                                boolean vid = mediaManifest != null && mediaManifest.optString("mime", "").startsWith("video");
                                boolean isNew = db.insertGroupMessage(gid, o.fromPublicId, false, vid ? "🎬 Video" : "📷 Photo", now(), msgid, vid ? Db.KIND_VIDEO : Db.KIND_IMAGE, null);
                                if (isNew) { if (mediaManifest != null) fetchIncomingMedia(gid, msgid, mediaManifest, vid); maybeNotify(o.fromPublicId, gid, vid ? "🎬 Video" : "📷 Photo"); }
                                return isNew;
                            }
                            boolean gNew = db.insertGroupMessage(gid, o.fromPublicId, false, grp.optString("text", ""), now(), msgid, Db.KIND_TEXT, null);
                            if (gNew) maybeNotify(o.fromPublicId, gid, grp.optString("text", ""));
                            return gNew;
                        }
                        if (grp != null && grp.optInt("fpw", 0) == 1) {   // 1:1 wallet-address share (control msg, no chat entry)
                            String mx = grp.optString("mx", "");
                            if (!mx.isEmpty()) db.setContactAddress(o.fromPublicId, mx);
                            return true;
                        }
                        if (grp != null && grp.optInt("fpw", 0) == 2) {   // incoming payment → a "You received" bubble
                            String amt = grp.optString("amt", "0"), tok = grp.optString("tok", "Minima"), pmemo = grp.optString("memo", "");
                            boolean pNew = db.insertMessage(o.fromPublicId, false, pmemo, now(), msgid, Db.KIND_PAYMENT, paymentJson(amt, tok, pmemo));
                            if (pNew) maybeNotify(o.fromPublicId, null, "Received " + amt + " " + tok);
                            return pNew;
                        }
                        JSONObject manifest = MediaTransport.asManifest(o.plaintext);
                        if (manifest != null) {
                            boolean vid = manifest.optString("mime", "").startsWith("video");
                            boolean isNew = db.insertMessage(o.fromPublicId, false, vid ? "🎬 Video" : "📷 Photo", now(), msgid,
                                    vid ? Db.KIND_VIDEO : Db.KIND_IMAGE, null);
                            if (isNew) { fetchIncomingMedia(o.fromPublicId, msgid, manifest, vid); maybeNotify(o.fromPublicId, null, vid ? "🎬 Video" : "📷 Photo"); }
                            return isNew;
                        }
                        boolean tNew = db.insertMessage(o.fromPublicId, false, new String(o.plaintext, StandardCharsets.UTF_8), now(), msgid);
                        if (tNew) maybeNotify(o.fromPublicId, null, new String(o.plaintext, StandardCharsets.UTF_8));
                        return tNew;
                    }
                },
                new MailboxClient.Listener() {
                    @Override public void onDone(boolean ok, int n) {
                        if (n <= 0) return;
                        main.post(() -> {
                            // don't wipe a half-typed draft mid-conversation; the message is stored and shows on next render
                            if ((openContact != null || openGroup != null) && currentInput != null && currentInput.getText().length() > 0) return;
                            renderCurrent();
                        });
                    }
                }).drain();
    }

    private void fetchIncomingMedia(final String pubid, final String msgid, JSONObject manifest, final boolean video) {
        MediaTransport.fetch(http, Sodium.get(), manifest, new MediaTransport.FetchCb() {
            @Override public void onMedia(byte[] data, String mime) {
                new Thread(() -> {
                    final String path = video ? saveVideoFile(msgid, data) : saveImage(msgid, data);
                    main.post(() -> { db.setMedia(msgid, path); renderCurrent(); });
                }).start();
            }
            @Override public void onFailed(String message) { /* keep the placeholder bubble */ }
        });
    }

    // =============================================================================================
    // New chat (add contact)
    // =============================================================================================

    private void showNewChat(String prefillId) {
        openContact = null; openGroup = null; atChats = false;
        root.setBackgroundColor(Theme.BG());
        root.setGravity(Gravity.NO_GRAVITY);
        root.removeAllViews(); root.setPadding(0, 0, 0, 0);

        LinearLayout hdr = header();
        hdr.addView(Ui.iconButton(this, R.drawable.ic_close, 22, 44, Theme.TEXT(), v -> showChats()));
        TextView title = Ui.label(this, "New chat", 20, 700, Theme.TEXT());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(6); tlp.gravity = Gravity.CENTER_VERTICAL; title.setLayoutParams(tlp);
        hdr.addView(title); root.addView(hdr); root.addView(Ui.glowStrip(this));

        ScrollView sc = new ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(24), dp(16), dp(24));

        LinearLayout ng = new LinearLayout(this); ng.setOrientation(LinearLayout.HORIZONTAL); ng.setGravity(Gravity.CENTER_VERTICAL);
        ng.setBackground(Ui.roundStroke(Theme.SURFACE(), Theme.HAIR(), dp(16), dp(1)));
        ng.setPadding(dp(14), dp(14), dp(14), dp(14));
        ng.setClickable(true); ng.setForeground(Ui.rippleRect(this, dp(16))); Ui.press(ng);
        ng.setOnClickListener(v -> showNewGroup());
        FrameLayout gdisc = new FrameLayout(this);
        GradientDrawable gdg = new GradientDrawable(); gdg.setShape(GradientDrawable.OVAL); gdg.setColor(Theme.ACCENT()); gdisc.setBackground(gdg);
        gdisc.addView(Ui.icon(this, R.drawable.ic_group, 22, Theme.ON_ACCENT()), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        ng.addView(gdisc, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView ngt = Ui.label(this, "New group", 16, 600, Theme.TEXT()); ngt.setPadding(dp(14), 0, 0, 0); ng.addView(ngt);
        body.addView(ng, matchW());
        gapIn(body, dp(20));

        LinearLayout scan = new LinearLayout(this); scan.setOrientation(LinearLayout.VERTICAL);
        scan.setGravity(Gravity.CENTER_HORIZONTAL);
        scan.setBackground(Ui.roundStroke(Theme.SURFACE(), Theme.HAIR(), dp(20), dp(1)));
        scan.setPadding(dp(20), dp(20), dp(20), dp(20));
        scan.setClickable(true); scan.setForeground(Ui.rippleRect(this, dp(20))); Ui.press(scan);
        scan.setOnClickListener(v -> startScan());
        FrameLayout disc = new FrameLayout(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(Theme.ACCENT());
        disc.setBackground(dg); disc.addView(Ui.icon(this, R.drawable.ic_scan, 28, Theme.ON_ACCENT()),
                new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        scan.addView(disc, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView st = Ui.label(this, "Scan QR code", 17, 700, Theme.TEXT()); st.setPadding(0, dp(12), 0, 0); scan.addView(st);
        TextView ss = Ui.label(this, "Point your camera at a friend's FreezePeach code.", 14, 400, Theme.DIM());
        ss.setGravity(Gravity.CENTER); ss.setPadding(0, dp(4), 0, 0); scan.addView(ss);
        body.addView(scan, matchW());

        LinearLayout orRow = new LinearLayout(this); orRow.setGravity(Gravity.CENTER_VERTICAL);
        orRow.setPadding(0, dp(24), 0, dp(24));
        orRow.addView(hairline(1f));
        TextView or = Ui.mono(this, "or", 12, Theme.DIM()); or.setPadding(dp(12), 0, dp(12), 0); orRow.addView(or);
        orRow.addView(hairline(1f));
        body.addView(orRow, matchW());

        body.addView(Ui.section(this, "Add by id"));
        final EditText id = field("Paste id (0x…)", true);
        id.setTypeface(Typeface.MONOSPACE); id.setTextSize(13);
        LinearLayout.LayoutParams idlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        idlp.topMargin = dp(10); id.setLayoutParams(idlp); body.addView(id);
        final TextView derived = Ui.mono(this, "", 12, Theme.DIM()); derived.setPadding(dp(4), dp(6), 0, 0);
        body.addView(derived);
        gapIn(body, dp(12));
        body.addView(Ui.section(this, "Name (optional)"));
        final EditText nm = field("Name", false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10); nm.setLayoutParams(nlp); body.addView(nm);
        gapIn(body, dp(20));

        final LinearLayout[] holder = new LinearLayout[1];
        final Runnable rebuild = () -> {
            String p = id.getText().toString().trim();
            boolean ok = CommsIdentity.isValidPublicId(p);
            derived.setText(ok ? "This is " + Fp.shortId(p) : "");
            if (holder[0] != null) body.removeView(holder[0]);
            holder[0] = ok
                    ? Ui.pill(this, 0, "Add contact", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> {
                        db.upsertContact(p, nm.getText().toString().trim());
                        toast("Contact added"); showConversation(p);
                    })
                    : Ui.pill(this, 0, "Add contact", Theme.RECV(), Theme.DIM(), false, null);
            body.addView(holder[0], matchW());
        };
        id.addTextChangedListener(simpleWatcher(rebuild));

        String clip = clipboardText();
        if (prefillId != null && CommsIdentity.isValidPublicId(prefillId)) id.setText(prefillId);
        else if (clip != null && CommsIdentity.isValidPublicId(clip)) {
            id.setText(clip);
            TextView pasted = Ui.label(this, "Pasted from clipboard ✓", 12, 400, Theme.ACCENT());
            pasted.setPadding(dp(4), dp(6), 0, 0); body.addView(pasted, body.indexOfChild(id) + 1);
        }
        rebuild.run();

        sc.addView(body); root.addView(sc);
    }

    private void startScan() {
        IntentIntegrator ii = new IntentIntegrator(this);
        ii.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        ii.setPrompt("Scan a friend's FreezePeach code");
        ii.setBeepEnabled(false); ii.setOrientationLocked(true);   // portrait-locked: a rotate mid-scan must not recreate MainActivity and drop the result
        ii.initiateScan();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult res = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (res != null) {
            if (res.getContents() != null && CommsIdentity.isValidPublicId(res.getContents().trim()))
                showNewChat(res.getContents().trim());
            else if (res.getContents() != null) toast("That's not a FreezePeach code");
            return;
        }
        if (requestCode == REQ_REVEAL) { if (resultCode == RESULT_OK) showSeedBackup(); return; }   // device-credential confirmed
        if (requestCode == REQ_APPLOCK) { if (resultCode == RESULT_OK) appUnlocked = true; else moveTaskToBack(true); return; }
        if (requestCode == REQ_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExportPass != null) {
                try { byte[] bytes = vault.exportBytes(pendingExportPass); writeUri(data.getData(), bytes); toast("Backup saved (" + bytes.length + " bytes)"); }
                catch (Exception e) { toast("Backup failed: " + e.getMessage()); }
            }
            pendingExportPass = null; return;
        }
        if (requestCode == REQ_RESTORE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try { askRestorePass(readUri(data.getData())); } catch (Exception e) { toast("Could not read file: " + e.getMessage()); }
            }
            return;
        }
        if (resultCode != RESULT_OK || (pendingImageContact == null && pendingGroup == null)) { super.onActivityResult(requestCode, resultCode, data); return; }
        Uri picked = data != null ? data.getData() : null;
        if (requestCode == PICK_IMAGE && picked != null) editFromUri(picked);                 // photos go through the editor
        else if (requestCode == PICK_VIDEO && picked != null) routeVideoUri(picked);
        else if (requestCode == CAPTURE_PHOTO && pendingCaptureFile != null) openEditor(pendingCaptureFile.getAbsolutePath());
        else if (requestCode == CAPTURE_VIDEO && pendingCaptureFile != null) sendCapturedVideo();
        else if (requestCode == EDIT_IMAGE && data != null && data.getStringExtra("out") != null) {
            final String out = data.getStringExtra("out");
            new Thread(() -> { try { byte[] bytes = readFile(new File(out)); main.post(() -> routeImageBytes(bytes)); } catch (Exception e) { main.post(() -> toast("Send failed")); } }).start();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
    private void routeImageBytes(byte[] jpg) {
        if (pendingGroup != null) sendGroupImageBytes(pendingGroup, jpg);
        else if (pendingImageContact != null) sendImageBytes(pendingImageContact, jpg);
    }
    private void routeVideoUri(final Uri uri) {
        new Thread(() -> { try { final byte[] b = readUri(uri); main.post(() -> routeVideoBytes(b)); }
            catch (Exception e) { main.post(() -> toast("Video too large or unreadable")); } }).start();
    }
    private void routeVideoBytes(byte[] mp4) {
        if (pendingGroup != null) sendGroupVideoBytes(pendingGroup, mp4);
        else if (pendingImageContact != null) sendVideoBytes(pendingImageContact, mp4);
    }
    private void sendCapturedVideo() {
        final File f = pendingCaptureFile; if (f == null) return;
        new Thread(() -> {
            try { final byte[] b = readFile(f); main.post(() -> routeVideoBytes(b)); }
            catch (Exception e) { main.post(() -> toast("Capture failed")); }
        }).start();
    }
    private void sendGroupImageBytes(final String gid, final byte[] raw) {
        toast("Sending photo…");
        new Thread(() -> {
            final byte[] jpg = compressImage(raw);
            main.post(() -> MediaTransport.upload(http, Sodium.get(), jpg, "image/jpeg", new MediaTransport.ManifestCb() {
                @Override public void onManifest(JSONObject manifest) { fanoutGroupMedia(gid, manifest, jpg, false); }
                @Override public void onFailed(String m) { toast("Photo failed: " + m); }
            }));
        }).start();
    }
    private void sendGroupVideoBytes(final String gid, final byte[] mp4) {
        if (mp4.length > 25L * 1024 * 1024) { toast("Video too large (max 25 MB)"); return; }
        toast("Sending video…");
        MediaTransport.upload(http, Sodium.get(), mp4, "video/mp4", new MediaTransport.ManifestCb() {
            @Override public void onManifest(JSONObject manifest) { fanoutGroupMedia(gid, manifest, mp4, true); }
            @Override public void onFailed(String m) { toast("Video failed: " + m); }
        });
    }
    private void fanoutGroupMedia(final String gid, JSONObject manifest, final byte[] bytes, final boolean video) {
        try {
            JSONObject env = new JSONObject(); env.put("fpg", 1); env.put("gid", gid); env.put("mm", manifest);
            byte[] wire = env.toString().getBytes(StandardCharsets.UTF_8);
            for (String m : db.groupMembers(gid)) if (!m.equals(me.publicId())) MailboxTransport.sendMessage(http, crypto, m, wire, noopSend());
        } catch (Exception e) { toast("Send failed"); return; }
        final String msgid = localMsgid(video ? "v" : "i", gid, String.valueOf(bytes.length));
        new Thread(() -> {
            final String path = video ? saveVideoFile(msgid, bytes) : saveImage(msgid, bytes);
            main.post(() -> {
                db.insertGroupMessage(gid, me.publicId(), true, video ? "🎬 Video" : "📷 Photo", now(), msgid, video ? Db.KIND_VIDEO : Db.KIND_IMAGE, path);
                if (gid.equals(openGroup)) showGroupConversation(gid);
            });
        }).start();
    }
    private void editFromUri(final Uri uri) {
        new Thread(() -> {
            try {
                byte[] b = readUri(uri);
                File dir = new File(getCacheDir(), "pick"); dir.mkdirs();
                File f = new File(dir, "pick_" + System.nanoTime() + ".jpg");
                try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(b); }
                main.post(() -> openEditor(f.getAbsolutePath()));
            } catch (Exception e) { main.post(() -> toast("Couldn't read that image")); }
        }).start();
    }
    private void openEditor(String path) {
        startActivityForResult(new Intent(this, EditorActivity.class).putExtra("path", path), EDIT_IMAGE);
    }
    private void sendCaptured(final boolean video) {
        final String pubid = pendingImageContact; final File f = pendingCaptureFile;
        if (pubid == null || f == null) return;
        new Thread(() -> {
            try { final byte[] b = readFile(f); main.post(() -> { if (video) sendVideoBytes(pubid, b); else sendImageBytes(pubid, b); }); }
            catch (Exception e) { main.post(() -> toast("Capture failed")); }
        }).start();
    }
    private byte[] readFile(File f) throws Exception {
        try (InputStream is = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
    private void openImageViewer(final String path) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF000000);
        ZoomImageView iv = new ZoomImageView(this);
        Bitmap bmp = decodeSampled(path, 2200);
        if (bmp != null) iv.setImageBitmap(bmp);
        iv.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setOnDismiss(d::dismiss);
        root.addView(iv);
        d.setContentView(root); d.show();
    }
    private void playVideo(final String path) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF000000);
        VideoView vv = new VideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER; vv.setLayoutParams(lp);
        MediaController mc = new MediaController(this); mc.setAnchorView(vv); vv.setMediaController(mc);
        vv.setVideoPath(path);
        vv.setOnPreparedListener(mp -> vv.start());
        root.addView(vv);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(44), dp(44)); clp.gravity = Gravity.TOP | Gravity.END; clp.topMargin = dp(24); clp.rightMargin = dp(8);
        FrameLayout close = Ui.iconButton(this, R.drawable.ic_close, 24, 44, 0xFFFFFFFF, v -> d.dismiss());
        close.setLayoutParams(clp); root.addView(close);
        d.setOnDismissListener(x -> { try { vv.stopPlayback(); } catch (Exception e) {} });  // release MediaPlayer, stop audio
        d.setContentView(root); d.show();
    }

    // =============================================================================================
    // Groups
    // =============================================================================================

    private void showNewGroup() {
        openContact = null; openGroup = null; atChats = false;
        root.setBackgroundColor(Theme.BG()); root.setGravity(Gravity.NO_GRAVITY); root.removeAllViews(); root.setPadding(0, 0, 0, 0);
        LinearLayout hdr = header();
        hdr.addView(Ui.iconButton(this, R.drawable.ic_close, 22, 44, Theme.TEXT(), v -> showNewChat(null)));
        TextView title = Ui.label(this, "New group", 20, 700, Theme.TEXT());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(6); tlp.gravity = Gravity.CENTER_VERTICAL; title.setLayoutParams(tlp);
        hdr.addView(title); root.addView(hdr); root.addView(Ui.glowStrip(this));

        ScrollView sc = new ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(20), dp(16), dp(20));
        final EditText name = field("Group name", false); body.addView(name, matchW());
        gapIn(body, dp(18));
        body.addView(Ui.section(this, "Add members")); gapIn(body, dp(6));
        final java.util.Set<String> sel = new java.util.HashSet<>();
        List<Db.Contact> cs = db.contacts();
        if (cs.isEmpty()) {
            TextView e = Ui.label(this, "Add contacts first, then you can group them.", 14, 400, Theme.DIM());
            e.setPadding(0, dp(8), 0, 0); body.addView(e);
        }
        for (Db.Contact c : cs) body.addView(memberRow(c, sel), matchW());
        gapIn(body, dp(20));
        body.addView(Ui.pill(this, R.drawable.ic_group, "Create group", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> {
            String nm = name.getText().toString().trim();
            if (nm.isEmpty()) { toast("Name your group"); return; }
            if (sel.isEmpty()) { toast("Pick at least one member"); return; }
            createGroup(nm, new java.util.ArrayList<>(sel));
        }), matchW());
        sc.addView(body); root.addView(sc);
    }

    private View memberRow(final Db.Contact c, final java.util.Set<String> sel) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(8), dp(4), dp(8)); r.setClickable(true); r.setBackground(Ui.rippleRect(this, dp(10))); Ui.press(r);
        r.addView(Ui.avatar(this, c.pubid, c.name, 40));
        TextView n = Ui.label(this, nameOf(c.pubid), 16, 600, Theme.TEXT());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(12); n.setLayoutParams(nlp); r.addView(n);
        final ImageView check = Ui.icon(this, R.drawable.ic_check, 22, Theme.DIM()); r.addView(check);
        r.setOnClickListener(v -> {
            if (sel.remove(c.pubid)) check.setColorFilter(Theme.DIM());
            else { sel.add(c.pubid); check.setColorFilter(Theme.ACCENT()); }
        });
        return r;
    }

    private void createGroup(String name, java.util.List<String> memberPubids) {
        byte[] r = new byte[16]; new java.security.SecureRandom().nextBytes(r);
        String gid = Hex.to(r);
        org.json.JSONArray members = new org.json.JSONArray();
        for (String m : memberPubids) members.put(m);
        members.put(me.publicId());
        org.json.JSONArray admins = new org.json.JSONArray(); admins.put(me.publicId());   // I'm the first admin
        db.insertGroup(gid, name, members.toString(), admins.toString());
        broadcastGroupMeta(gid);
        toast("Group created"); showGroupConversation(gid);
    }
    /** Send the group's roster (name/members/admin) sealed to every member except me — creates/updates it for them. */
    private void broadcastGroupMeta(String gid) {
        Db.Group g = db.group(gid); if (g == null || g.members == null) return;
        try {
            org.json.JSONArray members = new org.json.JSONArray(g.members);
            JSONObject meta = new JSONObject();
            meta.put("fpg", 1); meta.put("gid", gid); meta.put("name", g.name); meta.put("members", members); meta.put("admin", g.admin);
            byte[] wire = meta.toString().getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < members.length(); i++) { String m = members.optString(i); if (!m.equals(me.publicId())) MailboxTransport.sendMessage(http, crypto, m, wire, noopSend()); }
        } catch (Exception e) {}
    }
    private void addGroupMember(String gid, String pubid) {
        Db.Group g = db.group(gid); if (g == null) return;
        try {
            org.json.JSONArray members = new org.json.JSONArray(g.members);
            for (int i = 0; i < members.length(); i++) if (members.optString(i).equals(pubid)) return;
            members.put(pubid);
            db.insertGroup(gid, g.name, members.toString(), g.admin);
        } catch (Exception e) { return; }
        broadcastGroupMeta(gid); toast("Member added"); showGroupConversation(gid);
    }
    private void removeGroupMember(String gid, String pubid) {
        Db.Group g = db.group(gid); if (g == null) return;
        try {
            org.json.JSONArray members = new org.json.JSONArray(g.members), keep = new org.json.JSONArray();
            for (int i = 0; i < members.length(); i++) { String m = members.optString(i); if (!m.equals(pubid)) keep.put(m); }
            db.insertGroup(gid, g.name, keep.toString(), g.admin);
        } catch (Exception e) { return; }
        broadcastGroupMeta(gid); toast("Member removed"); showGroupConversation(gid);
    }
    private static org.json.JSONArray adminsOf(String field) {
        if (field == null || field.isEmpty()) return new org.json.JSONArray();
        String f = field.trim();
        if (f.startsWith("[")) { try { return new org.json.JSONArray(f); } catch (Exception e) {} }
        org.json.JSONArray a = new org.json.JSONArray(); a.put(field); return a;   // legacy single-admin value
    }
    private static boolean adminsContains(String field, String pubid) {
        org.json.JSONArray a = adminsOf(field);
        for (int i = 0; i < a.length(); i++) if (a.optString(i).equals(pubid)) return true;
        return false;
    }
    private void setAdmin(String gid, String pubid, boolean makeAdmin) {
        Db.Group g = db.group(gid); if (g == null) return;
        org.json.JSONArray admins = adminsOf(g.admin), out = new org.json.JSONArray();
        for (int i = 0; i < admins.length(); i++) { String a = admins.optString(i); if (!a.equals(pubid)) out.put(a); }
        if (makeAdmin) out.put(pubid);
        db.insertGroup(gid, g.name, g.members, out.toString());
        broadcastGroupMeta(gid); toast(makeAdmin ? "Now an admin" : "No longer an admin"); showGroupConversation(gid);
    }
    private void memberActionsSheet(final String gid, final String pubid) {
        Db.Group g = db.group(gid); if (g == null) return;
        final boolean theyAdmin = adminsContains(g.admin, pubid);
        LinearLayout c = sheetColumn();
        c.addView(Ui.avatar(this, pubid, nameOf(pubid), 56), centeredSize(56));
        TextView n = Ui.label(this, nameOf(pubid), 18, 700, Theme.TEXT()); n.setGravity(Gravity.CENTER); n.setPadding(0, dp(10), 0, dp(8)); c.addView(n, centered());
        final Dialog d = bottomSheet(c);
        c.addView(sheetRow(R.drawable.ic_shield, theyAdmin ? "Remove admin" : "Make admin", Theme.ACCENT(), Theme.TEXT(),
                v -> { d.dismiss(); setAdmin(gid, pubid, !theyAdmin); }));
        c.addView(sheetRow(R.drawable.ic_trash, "Remove from group", Theme.DANGER(), Theme.DANGER(),
                v -> { d.dismiss(); removeGroupMember(gid, pubid); }));
    }
    private void addMemberPicker(final String gid) {
        java.util.List<String> members = db.groupMembers(gid);
        LinearLayout c = sheetColumn();
        TextView t = Ui.label(this, "Add member", 18, 700, Theme.TEXT()); t.setPadding(0, 0, 0, dp(8)); c.addView(t);
        final Dialog d = bottomSheet(c);
        boolean any = false;
        for (final Db.Contact ct : db.contacts()) {
            if (members.contains(ct.pubid)) continue;
            any = true;
            LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(4), dp(9), dp(4), dp(9)); r.setClickable(true); r.setBackground(Ui.rippleRect(this, dp(10))); Ui.press(r);
            r.addView(Ui.avatar(this, ct.pubid, ct.name, 36));
            TextView nm = Ui.label(this, nameOf(ct.pubid), 15, 400, Theme.TEXT()); nm.setPadding(dp(12), 0, 0, 0); r.addView(nm);
            r.setOnClickListener(v -> { d.dismiss(); addGroupMember(gid, ct.pubid); });
            c.addView(r);
        }
        if (!any) { TextView e = Ui.label(this, "All your contacts are already in this group.", 14, 400, Theme.DIM()); e.setPadding(0, dp(8), 0, dp(8)); c.addView(e); }
    }

    private void sendGroupText(final String gid, final String text) {
        try {
            JSONObject env = new JSONObject(); env.put("fpg", 1); env.put("gid", gid); env.put("text", text);
            byte[] wire = env.toString().getBytes(StandardCharsets.UTF_8);
            for (String m : db.groupMembers(gid)) if (!m.equals(me.publicId())) MailboxTransport.sendMessage(http, crypto, m, wire, noopSend());
        } catch (Exception e) { toast("Send failed"); return; }
        db.insertGroupMessage(gid, me.publicId(), true, text, now(), localMsgid("t", gid, text), Db.KIND_TEXT, null);
        showGroupConversation(gid);
    }

    private void showGroupConversation(final String gid) {
        Db.Group g = db.group(gid);
        if (g == null) { showChats(); return; }
        openGroup = gid; openContact = null; atChats = false;
        root.setBackgroundColor(Theme.BG()); root.setGravity(Gravity.NO_GRAVITY); root.removeAllViews(); root.setPadding(0, 0, 0, 0);
        LinearLayout hdr = header();
        hdr.addView(Ui.iconButton(this, R.drawable.ic_back, 24, 44, Theme.TEXT(), v -> showChats()));
        FrameLayout av = Ui.avatar(this, gid, g.name, 36);
        LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(dp(36), dp(36)); avlp.leftMargin = dp(4); av.setLayoutParams(avlp); hdr.addView(av);
        LinearLayout who = new LinearLayout(this); who.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.leftMargin = dp(10); wlp.gravity = Gravity.CENTER_VERTICAL; who.setLayoutParams(wlp);
        who.addView(Ui.label(this, g.name, 17, 600, Theme.TEXT()));
        who.addView(Ui.label(this, db.groupMembers(gid).size() + " members", 12, 400, Theme.DIM()));
        who.setClickable(true); Ui.press(who); who.setOnClickListener(v -> groupSheet(gid));
        hdr.addView(who);
        hdr.addView(Ui.iconButton(this, R.drawable.ic_more, 22, 44, Theme.TEXT(), v -> groupSheet(gid)));
        root.addView(hdr); root.addView(Ui.glowStrip(this));

        final ScrollView sc = new ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout msgs = new LinearLayout(this); msgs.setOrientation(LinearLayout.VERTICAL);
        msgs.setPadding(dp(12), dp(12), dp(12), dp(8));
        for (Db.Msg m : db.groupMessages(gid)) msgs.addView(groupBubble(m));
        sc.addView(msgs); root.addView(sc);
        sc.post(() -> sc.fullScroll(View.FOCUS_DOWN));

        LinearLayout comp = new LinearLayout(this); comp.setOrientation(LinearLayout.VERTICAL); comp.setBackgroundColor(Theme.SURFACE());
        View topline = new View(this);
        topline.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ Ui.alpha(Theme.ACCENT(), 0), Ui.alpha(Theme.ACCENT(), 0x66), Ui.alpha(Theme.ACCENT(), 0) }));
        comp.addView(topline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(dp(6), dp(8), dp(8), dp(10));
        bar.addView(Ui.iconButton(this, R.drawable.ic_attach, 24, 44, Theme.DIM(), v -> groupAttachMenu(gid)));
        final EditText in = new EditText(this);
        in.setHint("Message"); in.setHintTextColor(Theme.DIM()); in.setTextColor(Theme.TEXT());
        in.setBackground(Ui.round(Theme.RECV(), dp(22))); in.setPadding(dp(16), dp(11), dp(16), dp(11)); in.setTextSize(15); in.setMaxLines(5);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); ilp.leftMargin = dp(4); ilp.rightMargin = dp(8); in.setLayoutParams(ilp);
        bar.addView(in); currentInput = in;
        final FrameLayout send = new FrameLayout(this);
        GradientDrawable sg = new GradientDrawable(); sg.setShape(GradientDrawable.OVAL); sg.setColor(Theme.ACCENT()); send.setBackground(sg);
        send.addView(Ui.icon(this, R.drawable.ic_send, 20, Theme.ON_ACCENT()), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        send.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        send.setClickable(true); Ui.press(send);
        send.setOnClickListener(v -> { String t = in.getText().toString(); if (t.trim().isEmpty()) return; in.setText(""); sendGroupText(gid, t); });
        bar.addView(send); comp.addView(bar); root.addView(comp);
    }

    private void groupSheet(final String gid) {
        Db.Group g = db.group(gid);
        if (g == null) return;
        final boolean isAdmin = adminsContains(g.admin, me.publicId());
        LinearLayout c = sheetColumn();
        c.addView(Ui.avatar(this, gid, g.name, 64), centeredSize(64));
        TextView n = Ui.label(this, g.name, 20, 700, Theme.TEXT()); n.setPadding(0, dp(12), 0, 0); n.setGravity(Gravity.CENTER); c.addView(n, centered());
        java.util.List<String> members = db.groupMembers(gid);
        TextView sub = Ui.label(this, members.size() + " members" + (isAdmin ? " · you're admin" : ""), 12, 400, Theme.DIM());
        sub.setPadding(0, dp(2), 0, dp(8)); sub.setGravity(Gravity.CENTER); c.addView(sub, centered());
        final Dialog d = bottomSheet(c);
        if (isAdmin) c.addView(sheetRow(R.drawable.ic_person_add, "Add member", Theme.ACCENT(), Theme.TEXT(), v -> { d.dismiss(); addMemberPicker(gid); }));
        for (final String m : members) {
            LinearLayout mr = new LinearLayout(this); mr.setOrientation(LinearLayout.HORIZONTAL); mr.setGravity(Gravity.CENTER_VERTICAL);
            mr.setPadding(dp(4), dp(8), dp(4), dp(8));
            mr.addView(Ui.avatar(this, m, nameOf(m), 34));
            LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); mlp.leftMargin = dp(12); mid.setLayoutParams(mlp);
            mid.addView(Ui.label(this, m.equals(me.publicId()) ? "You" : nameOf(m), 15, 400, Theme.TEXT()));
            if (adminsContains(g.admin, m)) mid.addView(Ui.label(this, "Admin", 11, 400, Theme.ACCENT()));
            mr.addView(mid);
            if (isAdmin && !m.equals(me.publicId())) {
                mr.setClickable(true); mr.setBackground(Ui.rippleRect(this, dp(10))); Ui.press(mr);
                mr.setOnClickListener(v -> { d.dismiss(); memberActionsSheet(gid, m); });
            }
            c.addView(mr);
        }
        c.addView(sheetRow(R.drawable.ic_trash, "Leave group", Theme.DANGER(), Theme.DANGER(), v -> {
            d.dismiss(); db.removeGroup(gid); openGroup = null; showChats(); toast("Left group");
        }));
    }

    private MailboxTransport.SendCb noopSend() {
        return new MailboxTransport.SendCb() { @Override public void onSent(String id) {} @Override public void onFailed(String m) {} };
    }
    private String localMsgid(String tag, String key, String body) {
        try { return Hex.to(java.security.MessageDigest.getInstance("SHA-256").digest((tag + ":" + key + ":" + System.nanoTime() + ":" + body).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { return tag + System.nanoTime(); }
    }
    private static JSONObject tryJson(byte[] b) {
        try { return new JSONObject(new String(b, StandardCharsets.UTF_8)); } catch (Exception e) { return null; }
    }

    // =============================================================================================
    // Sheets
    // =============================================================================================

    private void contactSheet(final String pubid) {
        LinearLayout c = sheetColumn();
        c.addView(Ui.avatar(this, pubid, nameOf(pubid), 64), centeredSize(64));
        TextView n = Ui.label(this, nameOf(pubid), 20, 700, Theme.TEXT()); n.setPadding(0, dp(12), 0, 0);
        n.setGravity(Gravity.CENTER); c.addView(n, centered());
        TextView sid = Ui.mono(this, Fp.shortId(pubid), 12, Theme.DIM()); sid.setPadding(0, dp(2), 0, dp(8));
        sid.setGravity(Gravity.CENTER); c.addView(sid, centered());
        final Dialog d = bottomSheet(c);
        c.addView(sheetRow(R.drawable.ic_wallet, "Send funds", Theme.ACCENT(), Theme.TEXT(), v -> { d.dismiss(); sendToContact(pubid); }));
        c.addView(sheetRow(R.drawable.ic_edit, "Rename", Theme.ACCENT(), Theme.TEXT(), v -> { d.dismiss(); renameDialog(pubid); }));
        c.addView(sheetRow(R.drawable.ic_copy, "Copy their id", Theme.ACCENT(), Theme.TEXT(), v -> { copy(pubid); haptic(c); toast("Id copied"); }));
        c.addView(sheetRow(R.drawable.ic_trash, "Remove contact", Theme.DANGER(), Theme.DANGER(), v -> {
            d.dismiss(); db.removeContact(pubid); openContact = null; showChats(); toast("Removed");
        }));
    }

    private void myCodeSheet() {
        LinearLayout c = sheetColumn();
        TextView t = Ui.label(this, "My code", 20, 700, Theme.TEXT()); t.setGravity(Gravity.CENTER); c.addView(t, centered());
        TextView s = Ui.label(this, "Let someone scan or paste this to add you.", 14, 400, Theme.DIM());
        s.setGravity(Gravity.CENTER); s.setPadding(0, dp(4), 0, dp(16)); c.addView(s, centered());
        Bitmap q = qr(me.publicId(), dp(212));
        if (q != null) {
            ImageView iv = new ImageView(this); iv.setImageBitmap(q);
            iv.setBackground(Ui.round(0xFFFFFFFF, dp(18))); iv.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(240)); lp.gravity = Gravity.CENTER_HORIZONTAL;
            iv.setLayoutParams(lp); c.addView(iv);
            iv.setScaleX(0.9f); iv.setScaleY(0.9f); iv.setAlpha(0f);
            iv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        }
        // The FULL 130-char id in a tap-to-copy box — the addressable id, shown in full so the short
        // label elsewhere can never be mistaken for something to save.
        TextView idBox = Ui.mono(this, me.publicId(), 12, Theme.TEXT());
        idBox.setGravity(Gravity.CENTER);
        idBox.setTextIsSelectable(true);
        idBox.setBackground(Ui.roundStroke(Theme.SURFACE(), Theme.HAIR(), dp(12), dp(1)));
        idBox.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ibp.topMargin = dp(16); ibp.bottomMargin = dp(6); idBox.setLayoutParams(ibp);
        idBox.setOnClickListener(v -> { copy(me.publicId()); haptic(c); toast("Your id is copied"); });
        c.addView(idBox);
        TextView idHint = Ui.label(this, "Tap to copy your full id (130 characters). This whole string is your id — the short 0x…code shown elsewhere is just a label.", 12, 400, Theme.DIM());
        idHint.setGravity(Gravity.CENTER); idHint.setPadding(0, 0, 0, dp(12)); c.addView(idHint, centered());
        final Dialog d = bottomSheet(c);
        LinearLayout copyPill = Ui.pill(this, R.drawable.ic_copy, "Copy my id", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> {
            copy(me.publicId()); haptic(c); toast("Your id is copied");
        });
        c.addView(copyPill, matchW());
        final boolean soundOn = prefs.getBoolean("sound_on", true);
        c.addView(sheetRow(R.drawable.ic_bell, "Sound on receive  ·  " + (soundOn ? "on" : "off"), Theme.ACCENT(), Theme.TEXT(), v -> {
            prefs.edit().putBoolean("sound_on", !soundOn).apply();
            d.dismiss(); toast(!soundOn ? "Receive sound on" : "Receive sound off");
        }));
    }

    private void attachMenu(String pubid) { pendingImageContact = pubid; pendingGroup = null; attachSheet(); }
    private void groupAttachMenu(String gid) { pendingGroup = gid; pendingImageContact = null; attachSheet(); }
    private void attachSheet() {
        LinearLayout c = sheetColumn();
        final Dialog d = bottomSheet(c);
        c.addView(sheetRow(R.drawable.ic_photo, "Photo", Theme.ACCENT(), Theme.TEXT(), v -> { d.dismiss(); pick("image/*", PICK_IMAGE, "Send photo"); }));
        c.addView(sheetRow(R.drawable.ic_video, "Video", Theme.ACCENT(), Theme.TEXT(), v -> { d.dismiss(); pick("video/*", PICK_VIDEO, "Send video"); }));
        c.addView(sheetRow(R.drawable.ic_camera, "Take photo", Theme.ACCENT(), Theme.TEXT(), v -> { d.dismiss(); capture(false); }));
        c.addView(sheetRow(R.drawable.ic_video, "Record video", Theme.ACCENT(), Theme.TEXT(), v -> { d.dismiss(); capture(true); }));
    }
    private void pick(String type, int req, String title) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType(type);
        startActivityForResult(Intent.createChooser(i, title), req);
    }
    private void capture(boolean video) {
        try {
            File dir = new File(getCacheDir(), "capture"); dir.mkdirs();
            pendingCaptureFile = new File(dir, "cap_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg"));
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingCaptureFile);
            Intent i = new Intent(video ? android.provider.MediaStore.ACTION_VIDEO_CAPTURE : android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(i, video ? CAPTURE_VIDEO : CAPTURE_PHOTO);
        } catch (Exception e) { toast("Camera unavailable"); }
    }

    private void renameDialog(final String pubid) {
        LinearLayout c = sheetColumn();
        TextView t = Ui.label(this, "Rename", 18, 700, Theme.TEXT()); t.setPadding(0, 0, 0, dp(12)); c.addView(t);
        final EditText e = field("Name", false);
        String existing = null; for (Db.Contact x : db.contacts()) if (x.pubid.equals(pubid)) existing = x.name;
        if (existing != null) e.setText(existing);
        c.addView(e, matchW()); gapIn(c, dp(16));
        final Dialog d = bottomSheet(c);
        c.addView(Ui.pill(this, 0, "Save", Theme.ACCENT(), Theme.ON_ACCENT(), true, v -> {
            db.upsertContact(pubid, e.getText().toString().trim()); d.dismiss(); renderCurrent();
        }), matchW());
    }

    // =============================================================================================
    // Sheet + view helpers
    // =============================================================================================

    private LinearLayout sheetColumn() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); return c;
    }
    private Dialog bottomSheet(View content) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Theme.SURFACE());
        float r = dp(24); bg.setCornerRadii(new float[]{ r, r, r, r, 0, 0, 0, 0 });
        wrap.setBackground(bg); wrap.setPadding(dp(16), 0, dp(16), dp(24));
        View handle = new View(this); handle.setBackground(Ui.round(Theme.DIM(), dp(2)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL; hlp.topMargin = dp(10); hlp.bottomMargin = dp(8);
        handle.setLayoutParams(hlp); wrap.addView(handle);
        wrap.addView(content);
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM); w.setBackgroundDrawable(Ui.transparent()); w.setDimAmount(0.6f);
        }
        d.show();
        wrap.post(() -> { wrap.setTranslationY(wrap.getHeight()); wrap.animate().translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator()).start(); });
        return d;
    }
    private View sheetRow(int iconRes, String text, int iconTint, int textColor, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(4), dp(15), dp(4), dp(15));
        row.setClickable(true); row.setBackground(Ui.rippleRect(this, dp(8))); Ui.press(row);
        row.setOnClickListener(l);
        row.addView(Ui.icon(this, iconRes, 22, iconTint));
        TextView t = Ui.label(this, text, 16, 400, textColor); t.setPadding(dp(16), 0, 0, 0); row.addView(t);
        return row;
    }

    private LinearLayout header() {
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL);
        h.setBackgroundColor(Theme.SURFACE()); h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(12), dp(12), dp(10), dp(12)); h.setMinimumHeight(dp(56));
        return h;
    }
    private View heroMark(int diaDp) {
        FrameLayout f = new FrameLayout(this);
        int d = dp(diaDp);
        f.setLayoutParams(new LinearLayout.LayoutParams(d, d));
        View bloom = new View(this);
        GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(Theme.GLOW());
        bloom.setBackground(bg);
        f.addView(bloom, new FrameLayout.LayoutParams(d, d));
        View disc = new View(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(Theme.RECV());
        dg.setStroke(dp(1.5f), Ui.alpha(Theme.ACCENT(), 0x88)); disc.setBackground(dg);
        int inner = Math.round(d * 0.8f);
        f.addView(disc, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        int ic = Math.round(diaDp * 0.42f);
        f.addView(Ui.icon(this, R.drawable.ic_shield, ic, Theme.ACCENT()),
                new FrameLayout.LayoutParams(dp(ic), dp(ic), Gravity.CENTER));
        return f;
    }
    private View hairline(float weight) {
        View v = new View(this); v.setBackgroundColor(Theme.HAIR());
        v.setLayoutParams(new LinearLayout.LayoutParams(0, dp(1), weight)); return v;
    }
    private EditText field(String hint, boolean multiline) {
        EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(Theme.DIM()); e.setTextColor(Theme.TEXT());
        e.setBackground(Ui.round(Theme.RECV(), dp(14))); e.setPadding(dp(16), dp(14), dp(16), dp(14)); e.setTextSize(15);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        return e;
    }

    // =============================================================================================
    // Media + QR + small utils
    // =============================================================================================

    private byte[] readUri(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n; long total = 0;
            while ((n = is.read(buf)) > 0) { total += n; if (total > 30L * 1024 * 1024) throw new Exception("too big"); bos.write(buf, 0, n); }
            return bos.toByteArray();
        }
    }
    private byte[] compressImage(byte[] raw) {
        try {
            int rot = 0;                                   // honour EXIF orientation so photos aren't sideways
            try {
                ExifInterface ex = new ExifInterface(new java.io.ByteArrayInputStream(raw));
                switch (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    case ExifInterface.ORIENTATION_ROTATE_90:  rot = 90;  break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
                }
            } catch (Exception ignore) {}
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int max = Math.max(o.outWidth, o.outHeight), s = 1;
            while (max / s > 1600) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length, o2);
            if (bm == null) return raw;
            if (rot != 0) { Matrix m = new Matrix(); m.postRotate(rot); bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true); }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 85, bos); return bos.toByteArray();
        } catch (Exception e) { return raw; }
    }
    // msgid is used as a filename — it must be a clean sha256 hex (never a relay-controlled path).
    private static boolean safeId(String msgid) { return msgid != null && msgid.matches("[0-9a-f]{64}"); }
    private String saveImage(String msgid, byte[] bytes) {
        if (!safeId(msgid)) return null;
        try {
            File dir = new File(getFilesDir(), "media"); dir.mkdirs();
            File f = new File(dir, msgid + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(bytes); }
            return f.getAbsolutePath();
        } catch (Exception e) { return null; }
    }
    private String saveVideoFile(String msgid, byte[] bytes) {
        if (!safeId(msgid)) return null;
        try {
            File dir = new File(getFilesDir(), "media"); dir.mkdirs();
            File f = new File(dir, msgid + ".mp4");
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(bytes); }
            MediaMetadataRetriever r = null;
            try {
                r = new MediaMetadataRetriever(); r.setDataSource(f.getAbsolutePath());
                Bitmap frame = r.getFrameAtTime(0);
                if (frame != null) try (FileOutputStream tfo = new FileOutputStream(new File(dir, msgid + ".thumb.jpg"))) { frame.compress(Bitmap.CompressFormat.JPEG, 80, tfo); }
            } catch (Exception ignore) {}
            finally { if (r != null) try { r.release(); } catch (Exception e) {} }
            return f.getAbsolutePath();
        } catch (Exception e) { return null; }
    }
    private String videoThumb(String videoPath) {
        if (videoPath == null) return null;
        int dot = videoPath.lastIndexOf('.');
        return (dot > 0 ? videoPath.substring(0, dot) : videoPath) + ".thumb.jpg";
    }
    private Bitmap decodeSampled(String path, int maxPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int max = Math.max(o.outWidth, o.outHeight), s = 1;
            while (max / s > maxPx) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = s;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Exception e) { return null; }
    }
    private Bitmap qr(String data, int size) {
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            com.google.zxing.common.BitMatrix bm = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(data, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) { int off = y * size; for (int x = 0; x < size; x++) px[off + x] = bm.get(x, y) ? 0xFF090A0D : 0xFFFFFFFF; }
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            b.setPixels(px, 0, size, 0, 0, size, size); return b;
        } catch (Exception e) { return null; }
    }

    private TextWatcher simpleWatcher(final Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { r.run(); }
            @Override public void afterTextChanged(Editable s) {}
        };
    }
    private String myName() { return prefs.getString("myname", ""); }
    private String nameOf(String pubid) {
        for (Db.Contact c : db.contacts()) if (c.pubid.equals(pubid))
            return (c.name != null && !c.name.isEmpty()) ? c.name : Fp.shortId(pubid);
        return Fp.shortId(pubid);
    }
    private long now() { return System.currentTimeMillis() / 1000; }
    private void copy(String s) { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("freezepeach", s)); }
    private String clipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0)
                return String.valueOf(cm.getPrimaryClip().getItemAt(0).getText()).trim();
        } catch (Exception e) {}
        return null;
    }
    private void haptic(View v) { try { v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); } catch (Exception e) {} }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(float v) { return Ui.dp(this, v); }
    private LinearLayout.LayoutParams matchW() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams centered() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.gravity = Gravity.CENTER_HORIZONTAL; return p; }
    private LinearLayout.LayoutParams centeredSize(int diaDp) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(diaDp), dp(diaDp)); p.gravity = Gravity.CENTER_HORIZONTAL; return p; }
    private void gap(int px) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, px)); root.addView(v); }
    private void gapIn(LinearLayout parent, int px) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, px)); parent.addView(v); }

    static final class PrefsMeta implements MailboxClient.MetaStore {
        private final SharedPreferences p;
        PrefsMeta(SharedPreferences p) { this.p = p; }
        @Override public String getMeta(String k, String def) { return p.getString("meta_" + k, def); }
        @Override public void setMeta(String k, String v) { p.edit().putString("meta_" + k, v).apply(); }
    }
}
