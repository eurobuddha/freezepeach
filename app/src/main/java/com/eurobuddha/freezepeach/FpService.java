package com.eurobuddha.freezepeach;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.LocalEcCryptoProvider;
import com.eurobuddha.comms.MailboxClient;
import com.eurobuddha.comms.MailboxHttp;
import com.eurobuddha.comms.MediaTransport;
import com.eurobuddha.comms.Opened;
import com.eurobuddha.comms.Sodium;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Always-on background mailbox pump. A foreground service keeps the process alive so incoming messages are
 * received + notified even when the app is backgrounded or its Activity has been destroyed. It only drains
 * when the Activity is NOT foreground ({@link MainActivity#sAppForeground}) — so exactly one drainer runs at
 * a time, and since the app is backgrounded there's no open chat to suppress (notify everything).
 *
 * <p>Media is deliberately NOT inserted/acked here: we notify once (a dedup set) and leave the message on the
 * relay so the Activity fetches the blob + inserts + acks when it returns — otherwise the blob would be lost.
 * The router mirrors {@link MainActivity}'s; keep the two in sync when message types change.
 */
public final class FpService extends Service {

    private static final String PREFS = "freezepeach";
    private static final String RELAY_URL = "https://relay.privateprivate.org";
    private static final String SVC_CHANNEL = "fp_service";
    private static final String MSG_CHANNEL = "fp_messages";
    private static final int FG_ID = 4242;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Db db;
    private MailboxHttp http;
    private MailboxClient.MetaStore meta;
    private CommsIdentity me;
    private CryptoProvider crypto;
    private boolean running;
    private long lastToneMs;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!MainActivity.sAppForeground) drainOnce();   // the Activity drains while it's foreground
            main.postDelayed(this, 20000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        db = new Db(this);
        http = new MailboxHttp(RELAY_URL);
        meta = new MainActivity.PrefsMeta(prefs);
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(FG_ID, ongoingNotification()); } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        com.eurobuddha.wallet.SeedVault v = com.eurobuddha.wallet.WalletSession.get(this).vault();
        if (!v.exists() || !v.isOpen()) { stopSelf(); return START_NOT_STICKY; }   // no vault / locked → nothing to drain
        if (me == null) {
            try {
                me = CommsIdentity.fromSeed(Sodium.get(), SeedDerive.seedFromPhrase(v.phrase()));
                crypto = new LocalEcCryptoProvider(Sodium.get(), me);
            } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        }
        if (!running) { running = true; main.postDelayed(loop, 3000); }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false; main.removeCallbacks(loop);
        try { http.onDestroy(); } catch (Exception e) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private void drainOnce() {
        // Respect the vault lock: if it locked (auto-lock after backgrounding), drop the identity and idle.
        if (!com.eurobuddha.wallet.WalletSession.get(this).vault().isOpen()) { me = null; crypto = null; return; }
        if (me == null || crypto == null) return;
        new MailboxClient(http, crypto, meta, me.publicId(),
                new MailboxClient.Router() {
                    @Override public boolean handle(String msgid, Opened o, JSONObject mm) {
                        JSONObject grp = tryJson(o.plaintext);
                        if (grp != null && grp.optInt("fpg", 0) == 1) {
                            String gid = grp.optString("gid", "");
                            if (gid.isEmpty() || !grp.has("gid")) return false;
                            if (grp.has("members")) {                    // group roster
                                Db.Group existing = db.group(gid);
                                if (existing != null && !adminsContains(existing.admin, o.fromPublicId)) return false;
                                org.json.JSONArray mem = grp.optJSONArray("members");
                                db.insertGroup(gid, grp.optString("name", "Group"), mem != null ? mem.toString() : "[]", grp.optString("admin", null));
                                return true;
                            }
                            if (!db.groupMembers(gid).contains(o.fromPublicId)) return false;
                            if (grp.has("mm")) {
                                JSONObject gmm = grp.optJSONObject("mm");
                                boolean vid = gmm != null && gmm.optString("mime", "").startsWith("video");
                                boolean gmNew = db.insertGroupMessage(gid, o.fromPublicId, false, vid ? "🎬 Video" : "📷 Photo", now(), msgid, vid ? Db.KIND_VIDEO : Db.KIND_IMAGE, null);
                                if (gmNew) { if (gmm != null) fetchMedia(msgid, gmm, vid); postNotif(nameOfGroup(gid), vid ? "🎬 Video" : "📷 Photo", gid.hashCode()); }
                                return gmNew;
                            }
                            boolean gNew = db.insertGroupMessage(gid, o.fromPublicId, false, grp.optString("text", ""), now(), msgid, Db.KIND_TEXT, null);
                            if (gNew) postNotif(nameOfGroup(gid), grp.optString("text", ""), gid.hashCode());
                            return gNew;
                        }
                        if (grp != null && grp.optInt("fpw", 0) == 1) {   // 1:1 address share (control)
                            String mx = grp.optString("mx", "");
                            if (!mx.isEmpty()) db.setContactAddress(o.fromPublicId, mx);
                            return true;
                        }
                        if (grp != null && grp.optInt("fpw", 0) == 2) {   // incoming payment
                            String amt = grp.optString("amt", "0"), tok = grp.optString("tok", "Minima"), pmemo = grp.optString("memo", "");
                            boolean pNew = db.insertMessage(o.fromPublicId, false, pmemo, now(), msgid, Db.KIND_PAYMENT, paymentJson(amt, tok, pmemo));
                            if (pNew) postNotif(nameOf(o.fromPublicId), "Received " + amt + " " + tok, o.fromPublicId.hashCode());
                            return pNew;
                        }
                        JSONObject manifest = MediaTransport.asManifest(o.plaintext);
                        if (manifest != null) {
                            boolean vid = manifest.optString("mime", "").startsWith("video");
                            boolean imNew = db.insertMessage(o.fromPublicId, false, vid ? "🎬 Video" : "📷 Photo", now(), msgid, vid ? Db.KIND_VIDEO : Db.KIND_IMAGE, null);
                            if (imNew) { fetchMedia(msgid, manifest, vid); postNotif(nameOf(o.fromPublicId), vid ? "🎬 Video" : "📷 Photo", o.fromPublicId.hashCode()); }
                            return imNew;
                        }
                        boolean tNew = db.insertMessage(o.fromPublicId, false, new String(o.plaintext, StandardCharsets.UTF_8), now(), msgid);
                        if (tNew) postNotif(nameOf(o.fromPublicId), new String(o.plaintext, StandardCharsets.UTF_8), o.fromPublicId.hashCode());
                        return tNew;
                    }
                },
                new MailboxClient.Listener() { @Override public void onDone(boolean ok, int n) { } }
        ).drain();
    }

    /** Fully handle incoming media: fetch the encrypted blob, save it, point the row at it. drain() acks
     *  every opened message + advances the cursor regardless of the router's return, so we MUST fetch here —
     *  a deferred "return false" would permanently lose the blob. */
    private void fetchMedia(final String msgid, JSONObject manifest, final boolean video) {
        MediaTransport.fetch(http, Sodium.get(), manifest, new MediaTransport.FetchCb() {
            @Override public void onMedia(byte[] data, String mime) {
                new Thread(() -> {
                    String path = saveMedia(msgid, data, video);
                    if (path != null) db.setMedia(msgid, path);
                }).start();
            }
            @Override public void onFailed(String message) { }   // keep the placeholder; the Activity can retry
        });
    }
    private String saveMedia(String msgid, byte[] bytes, boolean video) {
        if (msgid == null || !msgid.matches("[A-Za-z0-9_.-]{1,80}")) return null;   // safe filename, no traversal
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "media"); dir.mkdirs();
            java.io.File f = new java.io.File(dir, msgid + (video ? ".mp4" : ".jpg"));
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f); fos.write(bytes); fos.close();
            return f.getAbsolutePath();
        } catch (Exception e) { return null; }
    }

    private String nameOf(String pubid) {
        for (Db.Contact c : db.contacts()) if (c.pubid.equals(pubid))
            return (c.name != null && !c.name.isEmpty()) ? c.name : Fp.shortId(pubid);
        return Fp.shortId(pubid);
    }
    private String nameOfGroup(String gid) {
        Db.Group g = db.group(gid);
        return (g != null && g.name != null) ? g.name : "Group";
    }

    private void postNotif(String title, String text, int id) {
        try {
            Intent it = new Intent(this, MainActivity.class); it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, MSG_CHANNEL)
                    .setSmallIcon(R.drawable.ic_send)
                    .setContentTitle(title == null ? "New message" : title)
                    .setContentText(text).setAutoCancel(true).setContentIntent(pi).build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(id, n);
        } catch (Exception e) { }
        playTone();
    }
    private void playTone() {
        if (!prefs.getBoolean("sound_on", true)) return;
        long t = android.os.SystemClock.uptimeMillis();
        if (t - lastToneMs < 1200) return;
        lastToneMs = t;
        try {
            final android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 180);
            main.postDelayed(tg::release, 400);
        } catch (Exception e) { }
    }

    private Notification ongoingNotification() {
        Intent it = new Intent(this, MainActivity.class); it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, SVC_CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("FreezePeach")
                .setContentText("Watching for messages")
                .setOngoing(true).setContentIntent(pi).build();
    }
    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel svc = new NotificationChannel(SVC_CHANNEL, "Background service", NotificationManager.IMPORTANCE_MIN);
            svc.setDescription("Keeps FreezePeach receiving messages"); nm.createNotificationChannel(svc);
            NotificationChannel msg = new NotificationChannel(MSG_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH);
            msg.setDescription("New FreezePeach messages"); nm.createNotificationChannel(msg);
        }
    }

    // ---- helpers mirrored from MainActivity's router (keep in sync) ----
    private static long now() { return System.currentTimeMillis() / 1000; }
    private static JSONObject tryJson(byte[] b) {
        try { return new JSONObject(new String(b, StandardCharsets.UTF_8)); } catch (Exception e) { return null; }
    }
    private static String paymentJson(String amt, String tok, String memo) {
        try {
            JSONObject j = new JSONObject();
            j.put("amt", amt); j.put("tok", tok == null ? "Minima" : tok);
            if (memo != null && !memo.isEmpty()) j.put("memo", memo);
            return j.toString();
        } catch (Exception e) { return "{}"; }
    }
    private static boolean adminsContains(String field, String pubid) {
        if (field == null || field.isEmpty()) return false;
        try {
            String f = field.trim();
            org.json.JSONArray a = f.startsWith("[") ? new org.json.JSONArray(f) : new org.json.JSONArray().put(field);
            for (int i = 0; i < a.length(); i++) if (a.optString(i).equals(pubid)) return true;
        } catch (Exception e) { }
        return false;
    }

    /** Start (or refresh) the always-on service. Safe to call repeatedly. */
    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, FpService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Exception e) { }
    }
}
