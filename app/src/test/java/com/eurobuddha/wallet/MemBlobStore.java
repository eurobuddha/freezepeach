package com.eurobuddha.wallet;

/** In-memory {@link SeedVault.BlobStore} so the vault's fund-critical state machine runs off-device. */
final class MemBlobStore implements SeedVault.BlobStore {

    private String mHex;

    @Override public boolean contains() { return mHex != null; }
    @Override public String read() { return mHex; }
    @Override public void write(String zHex) { mHex = zHex; }
}
