package com.winlator.star.xserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReferenceArray;

// ERL bug report #2: Drawable buffers were allocated via ByteBuffer.allocateDirect,
// which lands in ART's non-moving space — a small (~57MB on some devices), non-compacting
// region. Cursor/drawable churn fragments it until allocation fails, independent of leaks.
// This pool backs those buffers with calloc/free in the native heap (libdrawablepool.so),
// bypassing ART's heap entirely, and reuses freed buffers of matching capacity.
public class DrawableBufferPool {
    static {
        System.loadLibrary("drawablepool");
    }

    private static native ByteBuffer nativeAlloc(int capacity);
    private static native void nativeFree(ByteBuffer buffer);
    private static native void nativeZero(ByteBuffer buffer, int capacity);

    private static final AtomicReferenceArray<ByteBuffer> pool = new AtomicReferenceArray<>(64);

    public static ByteBuffer obtain(int capacity) {
        for (int i = 0; i < pool.length(); i++) {
            ByteBuffer buffer = pool.get(i);
            if (buffer != null && buffer.capacity() == capacity && pool.compareAndSet(i, buffer, null)) {
                buffer.clear();
                nativeZero(buffer, capacity);
                return buffer;
            }
        }
        ByteBuffer fresh = nativeAlloc(capacity);
        if (fresh == null) throw new OutOfMemoryError("DrawableBufferPool: native allocation failed for " + capacity + " bytes");
        return fresh.order(ByteOrder.LITTLE_ENDIAN);
    }

    public static void release(ByteBuffer buffer) {
        for (int i = 0; i < pool.length(); i++) {
            if (pool.compareAndSet(i, null, buffer)) return;
        }
        nativeFree(buffer);
    }
}
