package com.eurobuddha.wallet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory {@link KeyUses} honouring the same safety contract as {@link PrefsKeyUses}: reserve-before-
 * return, and MAX-on-read so an external count can only ever RAISE the counter.
 *
 * <p>Exists because {@code PrefsKeyUses} is SharedPreferences-backed and needs a device; the invariants
 * under test live in {@link WalletCore#signTransactionID} and {@link SeedVault}, which take the
 * interface.
 */
final class MemKeyUses implements KeyUses {

    private final LinkedHashMap<Integer, Integer> mUses = new LinkedHashMap<>();

    /** Set true to simulate a durable-write failure — reserveNextUse must then THROW, not return a leaf. */
    boolean failWrites;

    @Override public synchronized int currentUses(int zKeyIndex) {
        Integer v = mUses.get(zKeyIndex);
        return v == null ? 0 : v;
    }

    @Override public synchronized int reserveNextUse(int zKeyIndex) {
        int n = currentUses(zKeyIndex);
        if (failWrites) {
            throw new IllegalStateException("simulated durable-write failure — refusing to sign");
        }
        mUses.put(zKeyIndex, n + 1);   // persist the advance BEFORE handing the leaf out
        return n;
    }

    @Override public synchronized Map<Integer, Integer> snapshotAllUses() {
        return new LinkedHashMap<>(mUses);
    }

    @Override public synchronized void recordExternalUses(int zKeyIndex, int zUses) {
        mUses.put(zKeyIndex, Math.max(currentUses(zKeyIndex), zUses));
    }
}
