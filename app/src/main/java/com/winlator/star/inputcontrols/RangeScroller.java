package com.winlator.star.inputcontrols;

import android.graphics.Rect;

import com.winlator.star.widget.InputControlsView;
import com.winlator.star.widget.TouchpadView;

public class RangeScroller {
    private final InputControlsView inputControlsView;
    private final ControlElement element;
    private float scrollOffset;
    private float currentOffset;
    private float lastPosition;
    private long touchTime;
    private Binding binding = Binding.NONE;
    private Binding pressedBinding = Binding.NONE;
    private boolean isActionDown = false;
    private boolean scrolling = false;
    private final Runnable longPressRunnable = () -> {
        if (isActionDown && !scrolling) pressBinding();
    };
    private final Runnable releaseBindingRunnable = this::releasePressedBinding;

    public RangeScroller(InputControlsView inputControlsView, ControlElement element) {
        this.inputControlsView = inputControlsView;
        this.element = element;
    }

    public float getElementSize() {
        Rect boundingBox = element.getBoundingBox();
        return (float)Math.max(boundingBox.width(), boundingBox.height()) / element.getBindingCount();
    }

    public float getScrollSize() {
        return getElementSize() * element.getRange().max;
    }

    public float getScrollOffset() {
        return scrollOffset;
    }

    public byte[] getRangeIndex() {
        ControlElement.Range range = element.getRange();
        byte from = (byte)Math.floor((scrollOffset / getElementSize()) % range.max);
        if (from < 0) from = (byte)(range.max + from);
        byte to = (byte)(from + element.getBindingCount() + 1);
        return new byte[]{from, to};
    }

    private Binding getBindingByPosition(float x, float y) {
        Rect boundingBox = element.getBoundingBox();
        ControlElement.Range range = element.getRange();
        float offset = element.getOrientation() == 0 ? x - boundingBox.left - currentOffset : y - boundingBox.top - currentOffset;
        int index = (int)Math.floor((offset / getElementSize()) % range.max);
        if (index < 0) index = range.max + index;

        switch (range) {
            case FROM_A_TO_Z:
                return Binding.valueOf("KEY_"+((char)(65 + index)));
            case FROM_0_TO_9:
                return Binding.valueOf("KEY_"+((index + 1) % 10));
            case FROM_F1_TO_F12:
                return Binding.valueOf("KEY_F"+(index + 1));
            case FROM_NP0_TO_NP9:
                return Binding.valueOf("KEY_KP_"+((index + 1) % 10));
            default:
                return Binding.NONE;
        }
    }

    private boolean isTap() {
        return (System.currentTimeMillis() - touchTime) < TouchpadView.MAX_TAP_MILLISECONDS;
    }

    private void cancelDeferredCallbacks() {
        inputControlsView.removeCallbacks(longPressRunnable);
        inputControlsView.removeCallbacks(releaseBindingRunnable);
    }

    private void pressBinding() {
        if (binding == Binding.NONE || pressedBinding != Binding.NONE) return;
        pressedBinding = binding;
        inputControlsView.handleCountedInputEvent(pressedBinding, true, 0, true);
    }

    private void releasePressedBinding() {
        if (pressedBinding == Binding.NONE) return;
        Binding bindingToRelease = pressedBinding;
        pressedBinding = Binding.NONE;
        inputControlsView.handleCountedInputEvent(bindingToRelease, false, 0, true);
    }

    public void handleTouchDown(float x, float y) {
        cancelDeferredCallbacks();
        releasePressedBinding();

        scrolling = false;
        isActionDown = true;
        binding = getBindingByPosition(x, y);
        touchTime = System.currentTimeMillis();
        lastPosition = element.getOrientation() == 0 ? x : y;
        element.setBinding(Binding.NONE);

        inputControlsView.postDelayed(longPressRunnable, TouchpadView.MAX_TAP_MILLISECONDS);
    }

    public void handleTouchMove(float x, float y) {
        if (isActionDown) {
            float position = element.getOrientation() == 0 ? x : y;
            float deltaPosition = position - lastPosition;

            if (Math.abs(deltaPosition) >= TouchpadView.MAX_TAP_TRAVEL_DISTANCE) {
                scrolling = true;
                inputControlsView.removeCallbacks(longPressRunnable);
            }

            if (scrolling) {
                currentOffset += deltaPosition;

                float scrollSize = getScrollSize();
                scrollOffset = -currentOffset % scrollSize;
                if (scrollOffset < 0) scrollOffset = scrollSize + scrollOffset;

                lastPosition = position;
                inputControlsView.invalidate();
            }
        }
    }

    public void handleTouchUp() {
        if (isActionDown) {
            isActionDown = false;
            inputControlsView.removeCallbacks(longPressRunnable);
            if (isTap() && !scrolling) {
                pressBinding();
                if (pressedBinding != Binding.NONE
                        && !inputControlsView.postDelayed(releaseBindingRunnable, 30)) {
                    releasePressedBinding();
                }
            }
            else releasePressedBinding();
        }
    }

    public void releaseActiveInputs() {
        isActionDown = false;
        scrolling = false;
        cancelDeferredCallbacks();
        releasePressedBinding();
        binding = Binding.NONE;
    }
}
