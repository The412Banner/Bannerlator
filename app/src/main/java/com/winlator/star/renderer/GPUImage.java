package com.winlator.star.renderer;

import androidx.annotation.Keep;
import com.winlator.star.xserver.Drawable;
import java.nio.ByteBuffer;

public class GPUImage extends Texture {
    private long hardwareBufferPtr;
    private ByteBuffer virtualData;
    private short stride;
    private static boolean supported = false;

    static {
        System.loadLibrary("winlator");
    }

    public GPUImage(short width, short height) {
        hardwareBufferPtr = createHardwareBuffer(width, height);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        }
    }

    public GPUImage(int socketFd) {
        hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
    }

    @Override
    public void allocateTexture(short width, short height, ByteBuffer data) {
    }

    @Override
    public void updateFromDrawable(Drawable drawable) {
        needsUpdate = false;
    }

    public long getHardwareBufferPtr() {
        return hardwareBufferPtr;
    }

    public short getStride() {
        return stride;
    }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    public int unlock() {
        if (hardwareBufferPtr != 0) {
            int fence = unlockHardwareBuffer(hardwareBufferPtr);
            virtualData = null;
            return fence;
        }
        return -1;
    }

    public void lock() {
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
        }
    }

    @Override
    public void destroy() {
        if (hardwareBufferPtr != 0) {
            destroyHardwareBuffer(hardwareBufferPtr);
            hardwareBufferPtr = 0;
        }
        virtualData = null;
        super.destroy();
    }

    public static boolean isSupported() {
        return supported;
    }

    public static void checkIsSupported() {
        final short size = 8;
        GPUImage gpuImage = new GPUImage(size, size);
        supported = gpuImage.hardwareBufferPtr != 0 && gpuImage.virtualData != null;
        android.util.Log.d("GPUImage", "checkIsSupported: supported=" + supported);
        gpuImage.destroy();
    }

    private native long hardwareBufferFromSocket(int fd);
    private native long createHardwareBuffer(short width, short height);
    private native void destroyHardwareBuffer(long hardwareBufferPtr);
    private native int  unlockHardwareBuffer(long hardwareBufferPtr);
    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);
}
