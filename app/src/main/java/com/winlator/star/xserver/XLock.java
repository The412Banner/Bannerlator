package com.winlator.star.xserver;

public interface XLock extends AutoCloseable {
    @Override
    void close();
}
