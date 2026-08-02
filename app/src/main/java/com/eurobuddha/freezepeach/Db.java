package com.eurobuddha.freezepeach;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Local store for contacts, groups, and messages (text + media), persisted across restarts. */
public final class Db extends SQLiteOpenHelper {

    public static final int KIND_TEXT = 0, KIND_IMAGE = 1, KIND_VIDEO = 2, KIND_PAYMENT = 3;

    public Db(Context c) { super(c, "freezepeach.db", null, 6); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE contacts(pubid TEXT PRIMARY KEY, name TEXT, last_body TEXT, last_ts INTEGER, address TEXT)");
        db.execSQL("CREATE TABLE groups(gid TEXT PRIMARY KEY, name TEXT, members TEXT, last_body TEXT, last_ts INTEGER, admin TEXT)");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, pubid TEXT, gid TEXT, mine INTEGER, "
                + "body TEXT, ts INTEGER, msgid TEXT, kind INTEGER DEFAULT 0, media TEXT)");
        db.execSQL("CREATE UNIQUE INDEX ux_msgid ON messages(msgid)");
        db.execSQL("CREATE TABLE wallet_tx(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, amount TEXT, token TEXT, tokenid TEXT, party TEXT, ref TEXT, ts INTEGER)");
        db.execSQL("CREATE TABLE seen_coins(coinid TEXT PRIMARY KEY, address TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN kind INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN media TEXT");
        }
        if (oldV < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN gid TEXT");
            db.execSQL("CREATE TABLE groups(gid TEXT PRIMARY KEY, name TEXT, members TEXT, last_body TEXT, last_ts INTEGER)");
        }
        if (oldV < 4) {
            db.execSQL("ALTER TABLE groups ADD COLUMN admin TEXT");
        }
        if (oldV < 5) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN address TEXT");
        }
        if (oldV < 6) {
            db.execSQL("CREATE TABLE wallet_tx(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, amount TEXT, token TEXT, tokenid TEXT, party TEXT, ref TEXT, ts INTEGER)");
            db.execSQL("CREATE TABLE seen_coins(coinid TEXT PRIMARY KEY, address TEXT)");
        }
    }

    public static final class Contact { public String pubid, name, lastBody; public long lastTs; }
    public static final class Group { public String gid, name, members, lastBody, admin; public long lastTs; }
    public static final class Msg { public String body, msgid, media, sender; public boolean mine; public long ts; public int kind; }
    public static final class WalletTx { public long id, ts; public String type, amount, token, tokenid, party, ref; }

    // ---- contacts (1:1) ----
    public void upsertContact(String pubid, String name) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT name FROM contacts WHERE pubid=?", new String[]{ pubid });
        boolean exists = c.moveToFirst(); c.close();
        if (!exists) {
            ContentValues v = new ContentValues(); v.put("pubid", pubid);
            if (name != null && !name.isEmpty()) v.put("name", name);
            db.insert("contacts", null, v);
        } else if (name != null && !name.isEmpty()) {
            db.execSQL("UPDATE contacts SET name=? WHERE pubid=?", new Object[]{ name, pubid });
        }
    }
    public void removeContact(String pubid) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("contacts", "pubid=?", new String[]{ pubid });
        db.delete("messages", "pubid=? AND gid IS NULL", new String[]{ pubid });
    }
    public List<Contact> contacts() {
        List<Contact> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pubid,name,last_body,last_ts FROM contacts ORDER BY last_ts DESC, pubid ASC", null);
        while (c.moveToNext()) {
            Contact k = new Contact();
            k.pubid = c.getString(0); k.name = c.getString(1); k.lastBody = c.getString(2); k.lastTs = c.getLong(3);
            out.add(k);
        }
        c.close(); return out;
    }

    /** The contact's shared Minima receive address, or null if they haven't shared one yet. */
    public String contactAddress(String pubid) {
        Cursor c = getReadableDatabase().rawQuery("SELECT address FROM contacts WHERE pubid=?", new String[]{ pubid });
        String a = c.moveToFirst() ? c.getString(0) : null; c.close(); return a;
    }
    /** Store a contact's shared receive address (upserts the contact if new). */
    public void setContactAddress(String pubid, String address) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT pubid FROM contacts WHERE pubid=?", new String[]{ pubid });
        boolean exists = c.moveToFirst(); c.close();
        if (exists) db.execSQL("UPDATE contacts SET address=? WHERE pubid=?", new Object[]{ address, pubid });
        else { ContentValues v = new ContentValues(); v.put("pubid", pubid); v.put("address", address); db.insert("contacts", null, v); }
    }

    // ---- wallet history (app-owned; survives node resync) ----
    public void insertWalletTx(String type, String amount, String token, String tokenid, String party, String ref, long ts) {
        ContentValues v = new ContentValues();
        v.put("type", type); v.put("amount", amount); v.put("token", token);
        v.put("tokenid", tokenid); v.put("party", party); v.put("ref", ref); v.put("ts", ts);
        getWritableDatabase().insert("wallet_tx", null, v);
    }
    public List<WalletTx> walletTx(int limit, int offset) {
        List<WalletTx> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,type,amount,token,tokenid,party,ref,ts FROM wallet_tx ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?",
                new String[]{ String.valueOf(limit), String.valueOf(offset) });
        while (c.moveToNext()) {
            WalletTx w = new WalletTx();
            w.id = c.getLong(0); w.type = c.getString(1); w.amount = c.getString(2); w.token = c.getString(3);
            w.tokenid = c.getString(4); w.party = c.getString(5); w.ref = c.getString(6); w.ts = c.getLong(7);
            out.add(w);
        }
        c.close(); return out;
    }
    public boolean isCoinSeen(String coinid) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM seen_coins WHERE coinid=?", new String[]{ coinid });
        boolean seen = c.moveToFirst(); c.close(); return seen;
    }
    public void markCoinSeen(String coinid, String address) {
        ContentValues v = new ContentValues(); v.put("coinid", coinid); v.put("address", address);
        getWritableDatabase().insertWithOnConflict("seen_coins", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    // ---- groups ----
    public void insertGroup(String gid, String name, String membersJson, String admin) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT gid FROM groups WHERE gid=?", new String[]{ gid });
        boolean exists = c.moveToFirst(); c.close();
        if (!exists) {
            ContentValues v = new ContentValues();
            v.put("gid", gid); v.put("name", name); v.put("members", membersJson); v.put("admin", admin);
            db.insert("groups", null, v);
        } else if (name != null && !name.isEmpty()) {
            // membership/name updates from the admin's broadcast; keep admin if provided
            if (admin != null && !admin.isEmpty())
                db.execSQL("UPDATE groups SET name=?, members=?, admin=? WHERE gid=?", new Object[]{ name, membersJson, admin, gid });
            else
                db.execSQL("UPDATE groups SET name=?, members=? WHERE gid=?", new Object[]{ name, membersJson, gid });
        }
    }
    public void removeGroup(String gid) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("groups", "gid=?", new String[]{ gid });
        db.delete("messages", "gid=?", new String[]{ gid });
    }
    public List<Group> groups() {
        List<Group> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT gid,name,members,last_body,last_ts FROM groups ORDER BY last_ts DESC, gid ASC", null);
        while (c.moveToNext()) {
            Group g = new Group();
            g.gid = c.getString(0); g.name = c.getString(1); g.members = c.getString(2);
            g.lastBody = c.getString(3); g.lastTs = c.getLong(4);
            out.add(g);
        }
        c.close(); return out;
    }
    public Group group(String gid) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT gid,name,members,last_body,last_ts,admin FROM groups WHERE gid=?", new String[]{ gid });
        Group g = null;
        if (c.moveToFirst()) {
            g = new Group(); g.gid = c.getString(0); g.name = c.getString(1); g.members = c.getString(2);
            g.lastBody = c.getString(3); g.lastTs = c.getLong(4); g.admin = c.getString(5);
        }
        c.close(); return g;
    }
    public List<String> groupMembers(String gid) {
        List<String> out = new ArrayList<>();
        Group g = group(gid);
        if (g != null && g.members != null) {
            try { JSONArray a = new JSONArray(g.members); for (int i = 0; i < a.length(); i++) out.add(a.getString(i)); }
            catch (Exception e) {}
        }
        return out;
    }

    // ---- messages ----
    public boolean insertMessage(String pubid, boolean mine, String body, long ts, String msgid) {
        return insertMessage(pubid, mine, body, ts, msgid, KIND_TEXT, null);
    }
    /** Insert a 1:1 message (text or media); returns true if new (msgid unique). Updates the contact preview. */
    public boolean insertMessage(String pubid, boolean mine, String body, long ts, String msgid, int kind, String media) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("pubid", pubid); v.put("mine", mine ? 1 : 0); v.put("body", body); v.put("ts", ts);
        v.put("msgid", msgid); v.put("kind", kind); v.put("media", media);
        long row = db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (row < 0) return false;
        upsertContact(pubid, null);
        db.execSQL("UPDATE contacts SET last_body=?, last_ts=? WHERE pubid=?", new Object[]{ body, ts, pubid });
        return true;
    }

    /** Insert a GROUP message (sender = the author's publicId). Returns true if new. Updates the group preview. */
    public boolean insertGroupMessage(String gid, String sender, boolean mine, String body, long ts, String msgid, int kind, String media) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("pubid", sender); v.put("gid", gid); v.put("mine", mine ? 1 : 0); v.put("body", body);
        v.put("ts", ts); v.put("msgid", msgid); v.put("kind", kind); v.put("media", media);
        long row = db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (row < 0) return false;
        db.execSQL("UPDATE groups SET last_body=?, last_ts=? WHERE gid=?", new Object[]{ body, ts, gid });
        return true;
    }

    public void setMedia(String msgid, String path) {
        getWritableDatabase().execSQL("UPDATE messages SET media=? WHERE msgid=?", new Object[]{ path, msgid });
    }

    public List<Msg> messages(String pubid) {
        return query("SELECT mine,body,ts,msgid,kind,media,pubid FROM messages WHERE pubid=? AND gid IS NULL ORDER BY ts ASC, id ASC", pubid);
    }
    public List<Msg> groupMessages(String gid) {
        return query("SELECT mine,body,ts,msgid,kind,media,pubid FROM messages WHERE gid=? ORDER BY ts ASC, id ASC", gid);
    }
    private List<Msg> query(String sql, String arg) {
        List<Msg> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, new String[]{ arg });
        while (c.moveToNext()) {
            Msg m = new Msg();
            m.mine = c.getInt(0) == 1; m.body = c.getString(1); m.ts = c.getLong(2);
            m.msgid = c.getString(3); m.kind = c.getInt(4); m.media = c.getString(5); m.sender = c.getString(6);
            out.add(m);
        }
        c.close(); return out;
    }
}
