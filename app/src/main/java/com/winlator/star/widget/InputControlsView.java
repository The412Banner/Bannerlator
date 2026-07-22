package com.winlator.star.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;

import com.winlator.star.R;
import com.winlator.star.ControlsEditorActivity;
import com.winlator.star.inputcontrols.Binding;
import com.winlator.star.inputcontrols.ControlElement;
import com.winlator.star.inputcontrols.ControlsProfile;
import com.winlator.star.inputcontrols.CustomIconManager;
import com.winlator.star.inputcontrols.ExternalController;
import com.winlator.star.inputcontrols.ExternalControllerBinding;
import com.winlator.star.inputcontrols.GamepadState;
import com.winlator.star.inputcontrols.VisualStyle;
import com.winlator.star.math.Mathf;
import com.winlator.star.ui.theme.AppThemeState;
import com.winlator.star.winhandler.MouseEventFlags;
import com.winlator.star.winhandler.WinHandler;
import com.winlator.star.xserver.Pointer;
import com.winlator.star.xserver.XServer;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class InputControlsView extends View {
    public interface OnEditorElementSettingsRequested {
        void onSettingsRequested(ControlElement element);
    }

    // 0.75 under the linear opacity mapping (ControlElement.drawGameHub) matches the visible
    // dimness the old 0.4 produced under the previous 0.5+0.7*opacity curve.
    public static final float DEFAULT_OVERLAY_OPACITY = 0.75f;
    private static final byte MOUSE_WHEEL_DELTA = 120;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private int snappingSize;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;
    private ControlsProfile profile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private VisualStyle visualStyle = VisualStyle.GAMEHUB;
    private TouchpadView touchpadView;
    private XServer xServer;
    private final Bitmap[] icons = new Bitmap[256];
    private final CustomIconManager customIconManager;
    private Timer mouseMoveTimer;
    private final PointF mouseMoveOffset = new PointF();
    private final Map<ExternalController, PointF> controllerMouseMoveOffsets = new IdentityHashMap<>();
    private boolean showTouchscreenControls = true;

    // Background image for editor reference
    private Bitmap backgroundImage;
    private float backgroundOpacity = 0.65f;

    private Handler timeoutHandler;
    private Runnable hideControlsRunnable; 

    private static final long EDITOR_SETTINGS_LONG_PRESS_MS = 500L;
    private int editorLongPressTouchSlop;
    private ControlElement editorLongPressElement;
    private float editorLongPressDownX;
    private float editorLongPressDownY;
    private long editorLongPressDownTimeMs;
    private boolean editorLongPressTriggered = false;
    private boolean editorBackgroundVisible = true;
    private OnEditorElementSettingsRequested onEditorElementSettingsRequested;
    private Runnable showKeyboardCallback;
    private final Map<Binding, Integer> activeVirtualBindings = new HashMap<>();
    private final Map<ControlElement, VirtualStickState> activeVirtualSticks = new HashMap<>();
    private final Map<VirtualMouseBindingKey, Float> activeVirtualMouseBindings = new HashMap<>();
    private ControlElement expandedElement;
    private final SparseBooleanArray swallowedExpandablePointers = new SparseBooleanArray();
    private final Map<ExternalController, Set<Integer>> activeControllerKeys = new IdentityHashMap<>();

    private static class VirtualStickState {
        final Binding binding;
        final float x;
        final float y;

        VirtualStickState(Binding binding, float x, float y) {
            this.binding = binding;
            this.x = x;
            this.y = y;
        }
    }

    private static class VirtualMouseBindingKey {
        final ControlElement owner;
        final int slot;
        final Binding binding;

        VirtualMouseBindingKey(ControlElement owner, int slot, Binding binding) {
            this.owner = owner;
            this.slot = slot;
            this.binding = binding;
        }

        @Override public boolean equals(Object obj) {
            if (!(obj instanceof VirtualMouseBindingKey)) return false;
            VirtualMouseBindingKey other = (VirtualMouseBindingKey)obj;
            return owner == other.owner && slot == other.slot && binding == other.binding;
        }

        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(owner) + slot) + binding.ordinal();
        }
    }

    private final Runnable editorLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            ControlElement element = editorLongPressElement;
            if (!editMode || editorLongPressTriggered || element == null) return;
            editorLongPressTriggered = true;
            if (onEditorElementSettingsRequested != null) {
                onEditorElementSettingsRequested.onSettingsRequested(element);
            } else if (getContext() instanceof ControlsEditorActivity) {
                ((ControlsEditorActivity)getContext()).showControlElementSettingsFor(element);
            }
            invalidate();
        }
    };

    private SharedPreferences preferences;
    @SuppressLint("ResourceType")
    public InputControlsView(Context context) {
        super(context);
        this.customIconManager = new CustomIconManager(context);
        initView();
    }

    @SuppressLint("ResourceType")
    public InputControlsView(Context context, Handler timeoutHandler, Runnable hideControlsRunnable) {
        super(context);
        this.customIconManager = new CustomIconManager(context);
        this.timeoutHandler = timeoutHandler; 
        this.hideControlsRunnable = hideControlsRunnable; 
        initView();
    }

    private void initView() {
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setPointerIcon(PointerIcon.load(getResources(), R.xml.hidden_pointer_arrow));
        if (getLayoutParams() == null) {
            setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        editorLongPressTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    public void setEditMode(boolean editMode) {
        if (this.editMode == editMode) return;
        releaseActiveControls();
        cancelEditorLongPress();
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable);
        }
        this.editMode = editMode;
        setVisibility(View.VISIBLE);
        invalidate();
    }

    public void setEditorBackgroundVisible(boolean visible) {
        editorBackgroundVisible = visible;
        invalidate();
    }

    public void setOnEditorElementSettingsRequested(OnEditorElementSettingsRequested listener) {
        onEditorElementSettingsRequested = listener;
    }

    public void setShowKeyboardCallback(Runnable callback) {
        showKeyboardCallback = callback;
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
        invalidate();
    }

    public float getOverlayOpacity() {
        return overlayOpacity;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public VisualStyle getVisualStyle() {
        return visualStyle;
    }

    public void setVisualStyle(VisualStyle style) {
        visualStyle = style != null ? style : VisualStyle.GAMEHUB;
        invalidate();
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) {
            readyToDraw = false;
            return;
        }

        snappingSize = Math.max(1, width / 100);
        readyToDraw = true;

        if (editMode) {
            if (editorBackgroundVisible) {
                canvas.drawColor(Color.BLACK);
                drawBackgroundImage(canvas);
            }
            drawGrid(canvas);
            drawCursor(canvas);
        }

        if (profile != null && showTouchscreenControls) {
            if (!profile.isElementsLoaded()) profile.loadElements(this);
            for (ControlElement element : profile.getElements()) {
                if (isElementHiddenByGroup(element)) continue;
                element.draw(canvas);
            }
            if (expandedElement != null && isElementHiddenByGroup(expandedElement)) {
                expandedElement.setExpanded(false);
                expandedElement = null;
            }
            ControlElement expandedOverlay = expandedElement != null ? expandedElement : selectedElement;
            if (expandedOverlay != null && !isElementHiddenByGroup(expandedOverlay)) {
                expandedOverlay.drawExpandedChildren(canvas);
            }
        }

        if (editMode && editorLongPressElement != null && !editorLongPressTriggered) {
            drawEditorLongPressPreview(canvas);
            postInvalidateOnAnimation();
        }

        super.onDraw(canvas);
    }

    private void drawGrid(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(snappingSize * 0.0625f);

        paint.setAntiAlias(false);
        paint.setColor(backgroundImage != null && !backgroundImage.isRecycled()
            ? Color.argb(72, 255, 255, 255)
            : Color.argb(110, 96, 96, 96));

        int width = getMaxWidth();
        int height = getMaxHeight();

        for (int i = 0; i < width; i += snappingSize) {
            canvas.drawLine(i, 0, i, height, paint);
            canvas.drawLine(0, i, width, i, paint);
        }

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(backgroundImage != null && !backgroundImage.isRecycled()
            ? Color.argb(112, 79, 195, 247)
            : Color.argb(150, 66, 66, 66));

        for (int i = 0; i < width; i += snappingSize * 2) {
            canvas.drawLine(cx, i, cx, i + snappingSize, paint);
            canvas.drawLine(i, cy, i + snappingSize, cy, paint);
        }

        paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xffc62828);

        paint.setAntiAlias(false);
        canvas.drawLine(0, cursor.y, getMaxWidth(), cursor.y, paint);
        canvas.drawLine(cursor.x, 0, cursor.x, getMaxHeight(), paint);

        paint.setAntiAlias(true);
    }

    private void drawEditorLongPressPreview(Canvas canvas) {
        if (editorLongPressElement == null) return;
        Rect box = editorLongPressElement.getBoundingBox();
        if (box == null || box.isEmpty()) return;

        long elapsedMs = System.currentTimeMillis() - editorLongPressDownTimeMs;
        float progress = Math.max(0f, Math.min(1f, elapsedMs / (float) EDITOR_SETTINGS_LONG_PRESS_MS));
        float pulse = 0.5f + 0.5f * (float)Math.sin(elapsedMs / 70f);
        float cx = box.centerX();
        float cy = box.centerY();
        float baseRadius = Math.max(box.width(), box.height()) * 0.5f + snappingSize * 0.15f;

        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, snappingSize * 0.18f));
        paint.setColor(Color.argb((int)(90 + 80 * pulse), 79, 195, 247));
        canvas.drawCircle(cx, cy, baseRadius + snappingSize * (0.15f + 0.15f * progress), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int)(20 + 18 * pulse), 79, 195, 247));
        canvas.drawCircle(cx, cy, Math.max(6f, snappingSize * (0.06f + 0.04f * progress)), paint);
    }

    private void startEditorLongPress(ControlElement element, float x, float y) {
        cancelEditorLongPress();
        editorLongPressElement = element;
        editorLongPressDownX = x;
        editorLongPressDownY = y;
        editorLongPressDownTimeMs = System.currentTimeMillis();
        editorLongPressTriggered = false;
        removeCallbacks(editorLongPressRunnable);
        postDelayed(editorLongPressRunnable, EDITOR_SETTINGS_LONG_PRESS_MS);
        invalidate();
    }

    private void cancelEditorLongPress() {
        removeCallbacks(editorLongPressRunnable);
        editorLongPressElement = null;
        editorLongPressDownX = 0f;
        editorLongPressDownY = 0f;
        editorLongPressDownTimeMs = 0L;
        invalidate();
    }

    private void drawBackgroundImage(Canvas canvas) {
        if (backgroundImage != null && !backgroundImage.isRecycled()) {
            paint.setAlpha((int)(backgroundOpacity * 255));
            canvas.drawBitmap(backgroundImage, null,
                new Rect(0, 0, getWidth(), getHeight()), paint);
            paint.setAlpha(255);
        }
    }

    public void setBackgroundImage(Bitmap bitmap) {
        if (backgroundImage == bitmap) return;
        if (backgroundImage != null && !backgroundImage.isRecycled()) {
            backgroundImage.recycle();
        }
        this.backgroundImage = bitmap;
        invalidate();
    }

    public void setBackgroundOpacity(float opacity) {
        this.backgroundOpacity = Math.max(0, Math.min(1, opacity));
        invalidate();
    }

    public float getBackgroundOpacity() {
        return backgroundOpacity;
    }

    public synchronized boolean addElement(ControlElement.Type type) {
        if (editMode && profile != null) {
            ControlElement element = new ControlElement(this);
            element.setType(type);
            element.setX(cursor.x);
            element.setY(cursor.y);
            profile.addElement(element);
            profile.save();
            selectElement(element);
            return true;
        }
        else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            profile.removeElement(selectedElement);
            selectedElement = null;
            profile.save();
            invalidate();
            return true;
        }
        else return false;
    }

    public ControlElement getSelectedElement() {
        return selectedElement;
    }

    private synchronized void deselectAllElements() {
        selectedElement = null;
        if (profile != null) {
            for (ControlElement element : profile.getElements()) element.setSelected(false);
        }
    }

    public void selectElementAt(ControlElement element) {
        selectElement(element);
    }

    private void selectElement(ControlElement element) {
        deselectAllElements();
        if (element != null) {
            selectedElement = element;
            selectedElement.setSelected(true);
        }
        invalidate();
    }

    public synchronized ControlsProfile getProfile() {
        return profile;
    }

    public synchronized void setProfile(ControlsProfile profile) {
        releaseActiveControls();
        activeControllerKeys.clear();
        stopMouseMoveTimer();
        if (profile != null) {
            this.profile = profile;
            if (!profile.isElementsLoaded() && getWidth() > 0 && getHeight() > 0 && snappingSize > 0) profile.loadElements(this);
            deselectAllElements();
        }
        else this.profile = null;
        updateTouchscreenMouseButtons();
        createMouseMoveTimer();
    }

    public synchronized void releaseActiveControls() {
        if (profile != null && profile.isElementsLoaded()) {
            for (ControlElement element : profile.getElements()) {
                element.releaseActiveInputs();
            }
        }
        activeVirtualBindings.clear();
        activeVirtualSticks.clear();
        activeVirtualMouseBindings.clear();
        expandedElement = null;
        swallowedExpandablePointers.clear();
        mouseMoveOffset.set(0, 0);
        synchronized (controllerMouseMoveOffsets) {
            controllerMouseMoveOffsets.clear();
        }
    }

    public synchronized void releaseAllInputs() {
        releaseActiveControls();
        activeControllerKeys.clear();
        if (profile == null) return;
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        for (ExternalController controller : profile.getControllers()) {
            for (int i = 0; i < controller.getControllerBindingCount(); i++) {
                Binding binding = controller.getControllerBindingAt(i).getBinding();
                handleInputEvent(controller, binding, false, 0, false);
            }
            controller.state.reset();
            controller.remappedState.reset();
            if (winHandler != null) winHandler.sendGamepadState(controller);
        }
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        if (this.showTouchscreenControls && !showTouchscreenControls) releaseActiveControls();
        this.showTouchscreenControls = showTouchscreenControls;
        updateTouchscreenMouseButtons();
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (winHandler != null) winHandler.sendGamepadState();
        invalidate();
    }

    public int getPrimaryColor() {
        return Color.argb((int)(overlayOpacity * 255), 255, 255, 255);
    }

    // Base accent for the on-screen controls: the active profile's custom accent when it opted in,
    // otherwise the live app theme accent. The accent getters below all derive from this so a
    // per-profile override takes precedence over the theme — but only IN-GAME. In the controls
    // EDITOR (editMode) we always use the app theme accent: a user's dark in-game custom colour
    // would otherwise render the editor's buttons/labels unreadable, and the editor should track
    // the app theme, not the per-profile in-game colour.
    private int resolveBaseAccentArgb() {
        if (!editMode && profile != null && profile.isCustomAccentEnabled()) return profile.getCustomAccentColor();
        return AppThemeState.getCurrentAccentArgb();
    }

    public int getAccentColor() {
        return 0xff000000 | (resolveBaseAccentArgb() & 0x00ffffff);
    }

    public int getAccentBrightColor() {
        int accent = resolveBaseAccentArgb();
        int r = lerpToWhite(Color.red(accent), 0.55f);
        int g = lerpToWhite(Color.green(accent), 0.55f);
        int b = lerpToWhite(Color.blue(accent), 0.55f);
        return Color.argb(255, r, g, b);
    }

    private static int lerpToWhite(int channel, float t) {
        int v = Math.round(channel + (255 - channel) * t);
        return Math.max(0, Math.min(255, v));
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (profile != null) {
            List<ControlElement> elements = profile.getElements();
            for (int i = elements.size() - 1; i >= 0; i--) {
                ControlElement element = elements.get(i);
                if (isElementHiddenByGroup(element)) continue;
                if (element.containsPoint(x, y)) return element;
            }
        }
        return null;
    }

    private boolean isElementHiddenByGroup(ControlElement element) {
        return element != null && element.isInGroup() && profile != null && !profile.isGroupVisible(element.getGroupId());
    }

    private int[] clampDragDelta(List<ControlElement> elements, int dx, int dy) {
        if (elements == null || elements.isEmpty()) return new int[]{0, 0};

        int minDx = Integer.MIN_VALUE;
        int maxDx = Integer.MAX_VALUE;
        int minDy = Integer.MIN_VALUE;
        int maxDy = Integer.MAX_VALUE;
        int maxWidth = Math.max(0, getMaxWidth());
        int maxHeight = Math.max(0, getMaxHeight());

        for (ControlElement element : elements) {
            if (element == null) continue;
            Rect box = element.getBoundingBox();
            minDx = Math.max(minDx, -box.left);
            maxDx = Math.min(maxDx, maxWidth - box.right);
            minDy = Math.max(minDy, -box.top);
            maxDy = Math.min(maxDy, maxHeight - box.bottom);
        }

        if (minDx > maxDx) {
            minDx = 0;
            maxDx = 0;
        }
        if (minDy > maxDy) {
            minDy = 0;
            maxDy = 0;
        }

        return new int[]{
            Math.max(minDx, Math.min(maxDx, dx)),
            Math.max(minDy, Math.min(maxDy, dy))
        };
    }

    private void setCursorClamped(float x, float y) {
        int snappedX = (int)Mathf.roundTo(x, snappingSize);
        int snappedY = (int)Mathf.roundTo(y, snappingSize);
        cursor.set(
            Math.max(0, Math.min(getMaxWidth(), snappedX)),
            Math.max(0, Math.min(getMaxHeight(), snappedY))
        );
    }

    public Paint getPaint() {
        return paint;
    }

    public Path getPath() {
        return path;
    }

    public TouchpadView getTouchpadView() {
        return touchpadView;
    }

    public void setTouchpadView(TouchpadView touchpadView) {
        this.touchpadView = touchpadView;
        updateTouchscreenMouseButtons();
    }

    public XServer getXServer() {
        return xServer;
    }

    public void setXServer(XServer xServer) {
        stopMouseMoveTimer();
        this.xServer = xServer;
        createMouseMoveTimer();
    }

    public int getMaxWidth() {
        return (int)Mathf.roundTo(getWidth(), snappingSize);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelEditorLongPress();
        releaseActiveControls();
        stopMouseMoveTimer();
        super.onDetachedFromWindow();
    }

    public int getMaxHeight() {
        return (int)Mathf.roundTo(getHeight(), snappingSize);
    }

    private void createMouseMoveTimer() {
        if (xServer == null || profile == null) return;
        WinHandler winHandler = xServer.getWinHandler();
        if (winHandler == null) return;
        if (mouseMoveTimer == null) {
            final float cursorSpeed = profile.getCursorSpeed();
            mouseMoveTimer = new Timer();
            mouseMoveTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    float controllerX = 0;
                    float controllerY = 0;
                    synchronized (controllerMouseMoveOffsets) {
                        for (PointF offset : controllerMouseMoveOffsets.values()) {
                            controllerX += offset.x;
                            controllerY += offset.y;
                        }
                    }
                    float moveX = Mathf.clamp(mouseMoveOffset.x + controllerX, -1, 1);
                    float moveY = Mathf.clamp(mouseMoveOffset.y + controllerY, -1, 1);
                    if (moveX != 0 || moveY != 0) {
                        if (xServer.isRelativeMouseMovement())
                            winHandler.mouseEvent(MouseEventFlags.MOVE, (int) (moveX * cursorSpeed * 10), (int) (moveY * cursorSpeed * 10), 0);
                        else
                            xServer.injectPointerMoveDelta(
                                (int) (moveX * cursorSpeed * 10),
                                (int) (moveY * cursorSpeed * 10)
                        );
                    }
                }
            }, 0, 1000 / 60); 
        }
    }

    private void processControllerMappings(ExternalController controller) {
        final int[] axes = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        };
        final float[] values = {
                controller.state.thumbLX, controller.state.thumbLY,
                controller.state.thumbRX, controller.state.thumbRY,
                controller.state.getDPadX(), controller.state.getDPadY()
        };

        Map<Binding, Float> mappedAxes = new HashMap<>();
        for (int i = 0; i < axes.length; i++) {
            float value = values[i];
            byte activeSign = Math.abs(value) > ControlElement.STICK_DEAD_ZONE ? Mathf.sign(value) : 0;
            for (byte sign = -1; sign <= 1; sign += 2) {
                int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
                if (controllerBinding != null) {
                    mergeAxisBindingState(mappedAxes, controllerBinding.getBinding(), sign == activeSign, value);
                }
            }
        }

        ExternalControllerBinding triggerL = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
        if (triggerL != null) mergeAxisBindingState(
                mappedAxes, triggerL.getBinding(), controller.state.triggerL > ControlElement.STICK_DEAD_ZONE, controller.state.triggerL);
        ExternalControllerBinding triggerR = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
        if (triggerR != null) mergeAxisBindingState(
                mappedAxes, triggerR.getBinding(), controller.state.triggerR > ControlElement.STICK_DEAD_ZONE, controller.state.triggerR);

        Set<Integer> activeKeys = activeControllerKeys.get(controller);
        for (int i = 0; i < controller.getControllerBindingCount(); i++) {
            ExternalControllerBinding controllerBinding = controller.getControllerBindingAt(i);
            int keyCode = controllerBinding.getKeyCode();
            mergeAxisBindingState(mappedAxes, controllerBinding.getBinding(),
                    activeKeys != null && activeKeys.contains(keyCode), 1f);
        }

        applyMappedGamepadState(controller.remappedState, mappedAxes);
        float controllerMouseX = getMappedDirectionalAxis(
                mappedAxes, Binding.MOUSE_MOVE_LEFT, Binding.MOUSE_MOVE_RIGHT);
        float controllerMouseY = getMappedDirectionalAxis(
                mappedAxes, Binding.MOUSE_MOVE_UP, Binding.MOUSE_MOVE_DOWN);
        synchronized (controllerMouseMoveOffsets) {
            if (controllerMouseX == 0 && controllerMouseY == 0)
                controllerMouseMoveOffsets.remove(controller);
            else
                controllerMouseMoveOffsets.put(controller, new PointF(controllerMouseX, controllerMouseY));
        }
        if (controllerMouseX != 0 || controllerMouseY != 0) createMouseMoveTimer();
        for (Map.Entry<Binding, Float> output : mappedAxes.entrySet()) {
            Binding binding = output.getKey();
            if (binding.isGamepad() || binding.isMouseMove()) continue;
            float value = output.getValue();
            handleInputEvent(controller, binding, Math.abs(value) > ControlElement.STICK_DEAD_ZONE, value, false);
        }

        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (winHandler != null) {
            winHandler.sendGamepadState(controller);
        }
    }

    public static void mergeAxisBindingState(
            Map<Binding, Float> mappedAxes, Binding binding, boolean active, float value) {
        if (binding == null || binding == Binding.NONE) return;
        float current = mappedAxes.getOrDefault(binding, 0f);
        if (!mappedAxes.containsKey(binding) || (active && Math.abs(value) > Math.abs(current))) {
            mappedAxes.put(binding, active ? value : 0f);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!editMode && profile != null) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                processControllerMappings(controller);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean hapticsEnabled = preferences.getBoolean("touchscreen_haptics_enabled", true);
        if (!editMode) resetTouchscreenTimeout();
        int actionMasked = event.getActionMasked();
        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            updateTouchscreenMouseButtons();
        }
        if (!editMode && !showTouchscreenControls) {
            if (touchpadView != null) touchpadView.onTouchEvent(event);
            return true;
        }

        if (editMode && readyToDraw) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = event.getX();
                    float y = event.getY();
                    ControlElement element = intersectElement(x, y);
                    moveCursor = true;
                    if (element != null) {
                        offsetX = x - element.getX();
                        offsetY = y - element.getY();
                        moveCursor = false;
                        startEditorLongPress(element, x, y);
                    } else {
                        cancelEditorLongPress();
                    }
                    selectElement(element);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (editorLongPressTriggered) {
                        return true;
                    }
                    if (editorLongPressElement != null) {
                        float dx = event.getX() - editorLongPressDownX;
                        float dy = event.getY() - editorLongPressDownY;
                        if ((dx * dx + dy * dy) > (float)(editorLongPressTouchSlop * editorLongPressTouchSlop)) {
                            cancelEditorLongPress();
                        }
                    }
                    if (selectedElement != null) {
                        int newX = (int)Mathf.roundTo(event.getX() - offsetX, snappingSize);
                        int newY = (int)Mathf.roundTo(event.getY() - offsetY, snappingSize);
                        int dx = newX - selectedElement.getX();
                        int dy = newY - selectedElement.getY();
                        if (selectedElement.isInGroup() && profile != null) {
                            List<ControlElement> groupElements = profile.getGroupElements(selectedElement.getGroupId());
                            if (groupElements != null && !groupElements.isEmpty()) {
                                int[] clampedDelta = clampDragDelta(groupElements, dx, dy);
                                for (ControlElement element : groupElements) {
                                    element.setPosition(element.getX() + clampedDelta[0], element.getY() + clampedDelta[1]);
                                }
                            } else {
                                int[] clampedDelta = clampDragDelta(java.util.Collections.singletonList(selectedElement), dx, dy);
                                selectedElement.setPosition(selectedElement.getX() + clampedDelta[0], selectedElement.getY() + clampedDelta[1]);
                            }
                        }
                        else {
                            int[] clampedDelta = clampDragDelta(java.util.Collections.singletonList(selectedElement), dx, dy);
                            selectedElement.setPosition(selectedElement.getX() + clampedDelta[0], selectedElement.getY() + clampedDelta[1]);
                        }
                        invalidate();
                    }
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    performClick();
                    boolean longPressWasTriggered = editorLongPressTriggered;
                    cancelEditorLongPress();
                    editorLongPressTriggered = false;
                    if (longPressWasTriggered) {
                        moveCursor = false;
                        break;
                    }
                    if (selectedElement != null && profile != null) profile.save();
                    if (moveCursor) setCursorClamped(event.getX(), event.getY());
                    invalidate();
                    break;
                }
                case MotionEvent.ACTION_CANCEL: {
                    cancelEditorLongPress();
                    editorLongPressTriggered = false;
                    if (selectedElement != null && profile != null) profile.save();
                    if (moveCursor) setCursorClamped(event.getX(), event.getY());
                    invalidate();
                    break;
                }
            }
        }

        if (!editMode && profile != null && showTouchscreenControls) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            boolean handled = false;

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);
                    if (expandedElement != null) {
                        if (isElementHiddenByGroup(expandedElement)) {
                            expandedElement.setExpanded(false);
                            expandedElement = null;
                        } else if (expandedElement.containsPoint(x, y)) {
                            swallowActiveExpandablePointer();
                            expandedElement.setExpanded(false);
                            expandedElement = null;
                            swallowedExpandablePointers.put(pointerId, true);
                            handled = true;
                        } else {
                            int childIndex = expandedElement.getExpandableChildIndex(x, y);
                            if (childIndex >= 0) {
                                handled = expandedElement.handleExpandableChildDown(pointerId, x, y);
                                if (!handled) swallowedExpandablePointers.put(pointerId, true);
                                handled = true;
                            } else {
                                swallowActiveExpandablePointer();
                                expandedElement.setExpanded(false);
                                expandedElement = null;
                                swallowedExpandablePointers.put(pointerId, true);
                                handled = true;
                            }
                        }
                    }
                    if (!handled) {
                        List<ControlElement> elements = profile.getElements();
                        for (int elementIndex = elements.size() - 1; elementIndex >= 0; elementIndex--) {
                            ControlElement element = elements.get(elementIndex);
                            if (isElementHiddenByGroup(element)) continue;
                            if (element.getType() == ControlElement.Type.EXPANDABLE_BUTTON
                                    && element.containsPoint(x, y)) {
                                expandedElement = element;
                                element.setExpanded(true);
                                swallowedExpandablePointers.put(pointerId, true);
                                handled = true;
                            } else if (element.handleTouchDown(pointerId, x, y)) {
                                handled = true;
                            }
                            if (handled) {
                                if (hapticsEnabled) {
                                    Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                                    if (vibrator != null && vibrator.hasVibrator()) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                                    }
                                }
                                break;
                            }
                        }
                    }
                    if (!handled && touchpadView != null) touchpadView.onTouchEvent(event);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    boolean eventContainsSwallowedPointer = hasSwallowedExpandablePointer(event);
                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        int pid = event.getPointerId(i);
                        handled = swallowedExpandablePointers.get(pid);
                        if (!handled && expandedElement != null) {
                            handled = expandedElement.handleExpandableChildMove(pid);
                        }
                        if (!handled) {
                            for (ControlElement element : profile.getElements()) {
                                if (isElementHiddenByGroup(element)) continue;
                                if (element.handleTouchMove(pid, x, y)) {
                                    handled = true;
                                    break;
                                }
                            }
                        }
                        if (!handled && !eventContainsSwallowedPointer && touchpadView != null) {
                            touchpadView.onTouchEvent(event);
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    boolean eventContainsSwallowedPointer = hasSwallowedExpandablePointer(event);
                    if (swallowedExpandablePointers.get(pointerId)) {
                        swallowedExpandablePointers.delete(pointerId);
                        handled = true;
                    } else if (expandedElement != null) {
                        handled = expandedElement.handleExpandableChildUp(pointerId);
                    }
                    if (!handled) {
                        for (ControlElement element : profile.getElements()) {
                            if (isElementHiddenByGroup(element)) continue;
                            if (element.handleTouchUp(pointerId)) {
                                handled = true;
                                break;
                            }
                        }
                    }
                    if (!handled && !eventContainsSwallowedPointer && touchpadView != null) {
                        touchpadView.onTouchEvent(event);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    releaseActiveControls();
                    if (touchpadView != null) touchpadView.onTouchEvent(event);
                    break;
            }
        }
        return true;
    }

    private void updateTouchscreenMouseButtons() {
        if (touchpadView == null) return;
        boolean blockLeft = false;
        boolean blockRight = false;
        if (profile != null && showTouchscreenControls) {
            for (ControlElement element : profile.getElements()) {
                if (isElementHiddenByGroup(element)) continue;
                if (element.getType() == ControlElement.Type.EXPANDABLE_BUTTON && !element.isExpanded()) continue;
                for (int index = 0; index < element.getBindingCount(); index++) {
                    if (!element.blocksTouchscreenMouseButtonsAt(index)) continue;
                    Binding binding = element.getBindingAt(index);
                    if (binding == Binding.MOUSE_LEFT_BUTTON) blockLeft = true;
                    else if (binding == Binding.MOUSE_RIGHT_BUTTON) blockRight = true;
                    Binding[] combo = element.getCombo(index);
                    if (combo != null) {
                        for (Binding comboBinding : combo) {
                            if (comboBinding == Binding.MOUSE_LEFT_BUTTON) blockLeft = true;
                            else if (comboBinding == Binding.MOUSE_RIGHT_BUTTON) blockRight = true;
                        }
                    }
                    if (blockLeft && blockRight) break;
                }
                if (blockLeft && blockRight) break;
            }
        }
        touchpadView.setPointerButtonLeftEnabled(!blockLeft);
        touchpadView.setPointerButtonRightEnabled(!blockRight);
    }

    private void swallowActiveExpandablePointer() {
        if (expandedElement == null) return;
        int pointerId = expandedElement.getActiveExpandablePointerId();
        if (pointerId >= 0) swallowedExpandablePointers.put(pointerId, true);
    }

    private boolean hasSwallowedExpandablePointer(MotionEvent event) {
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (swallowedExpandablePointers.get(event.getPointerId(index))) return true;
        }
        return false;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void resetTouchscreenTimeout() {
        if (timeoutHandler != null && hideControlsRunnable != null) {
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            timeoutHandler.postDelayed(hideControlsRunnable, 5000); 
        }
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (profile != null && event.getRepeatCount() == 0) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null) {
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(event.getKeyCode());
                if (controllerBinding != null) {
                    int action = event.getAction();
                    if (action == KeyEvent.ACTION_DOWN) {
                        activeControllerKeys.computeIfAbsent(controller, ignored -> new HashSet<>())
                                .add(event.getKeyCode());
                    }
                    else if (action == KeyEvent.ACTION_UP) {
                        Set<Integer> activeKeys = activeControllerKeys.get(controller);
                        if (activeKeys != null) {
                            activeKeys.remove(event.getKeyCode());
                            if (activeKeys.isEmpty()) activeControllerKeys.remove(controller);
                        }
                    }
                    processControllerMappings(controller);
                    return true;
                }
            }
        }
        return false;
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        handleInputEvent(null, binding, isActionDown, 0);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown) {
        handleInputEvent(controller, binding, isActionDown, 0);
    }

    public void handleStickInput(ControlElement owner, Binding firstBinding, float deltaX, float deltaY) {
        if (!firstBinding.isGamepad()) return;
        if (deltaX == 0 && deltaY == 0) activeVirtualSticks.remove(owner);
        else activeVirtualSticks.put(owner, new VirtualStickState(firstBinding, deltaX, deltaY));

        rebuildVirtualStickAxes();
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (winHandler != null) winHandler.sendGamepadState();
    }

    private void rebuildVirtualStickAxes() {
        if (profile == null) return;
        GamepadState state = profile.getGamepadState();
        state.thumbLX = state.thumbLY = state.thumbRX = state.thumbRY = 0;
        for (VirtualStickState stick : activeVirtualSticks.values()) {
            if (isLeftStickBinding(stick.binding)) {
                state.thumbLX = Mathf.clamp(state.thumbLX + stick.x, -1, 1);
                state.thumbLY = Mathf.clamp(state.thumbLY + stick.y, -1, 1);
            } else {
                state.thumbRX = Mathf.clamp(state.thumbRX + stick.x, -1, 1);
                state.thumbRY = Mathf.clamp(state.thumbRY + stick.y, -1, 1);
            }
        }
        for (Binding binding : activeVirtualBindings.keySet()) {
            if (isThumbBinding(binding)) applyThumbBinding(state, binding);
        }
    }

    public static void applyMappedGamepadState(GamepadState state, Map<Binding, Float> mappedInputs) {
        state.reset();
        for (Map.Entry<Binding, Float> input : mappedInputs.entrySet()) {
            Binding binding = input.getKey();
            float value = input.getValue();
            if (binding == null || !binding.isGamepad()
                    || Math.abs(value) <= ControlElement.STICK_DEAD_ZONE) continue;

            float magnitude = Math.min(1f, Math.abs(value));
            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx >= 0 && buttonIdx <= ExternalController.IDX_BUTTON_R2) {
                if (buttonIdx == ExternalController.IDX_BUTTON_L2)
                    state.triggerL = Math.max(state.triggerL, magnitude);
                else if (buttonIdx == ExternalController.IDX_BUTTON_R2)
                    state.triggerR = Math.max(state.triggerR, magnitude);
                else
                    state.setPressed(buttonIdx, true);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP)
                state.thumbLY = Mathf.clamp(state.thumbLY - magnitude, -1, 1);
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_DOWN)
                state.thumbLY = Mathf.clamp(state.thumbLY + magnitude, -1, 1);
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT)
                state.thumbLX = Mathf.clamp(state.thumbLX - magnitude, -1, 1);
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT)
                state.thumbLX = Mathf.clamp(state.thumbLX + magnitude, -1, 1);
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP)
                state.thumbRY = Mathf.clamp(state.thumbRY - magnitude, -1, 1);
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN)
                state.thumbRY = Mathf.clamp(state.thumbRY + magnitude, -1, 1);
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT)
                state.thumbRX = Mathf.clamp(state.thumbRX - magnitude, -1, 1);
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT)
                state.thumbRX = Mathf.clamp(state.thumbRX + magnitude, -1, 1);
            else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT
                    || binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT)
                state.dpad[binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal()] = true;
        }
    }

    public static float getMappedDirectionalAxis(
            Map<Binding, Float> mappedInputs, Binding negative, Binding positive) {
        float negativeValue = Math.abs(mappedInputs.getOrDefault(negative, 0f));
        float positiveValue = Math.abs(mappedInputs.getOrDefault(positive, 0f));
        if (negativeValue <= ControlElement.STICK_DEAD_ZONE) negativeValue = 0;
        if (positiveValue <= ControlElement.STICK_DEAD_ZONE) positiveValue = 0;
        return Mathf.clamp(positiveValue - negativeValue, -1, 1);
    }

    private static void applyThumbBinding(GamepadState state, Binding binding) {
        switch (binding) {
            case GAMEPAD_LEFT_THUMB_UP: state.thumbLY = Mathf.clamp(state.thumbLY - 1, -1, 1); break;
            case GAMEPAD_LEFT_THUMB_RIGHT: state.thumbLX = Mathf.clamp(state.thumbLX + 1, -1, 1); break;
            case GAMEPAD_LEFT_THUMB_DOWN: state.thumbLY = Mathf.clamp(state.thumbLY + 1, -1, 1); break;
            case GAMEPAD_LEFT_THUMB_LEFT: state.thumbLX = Mathf.clamp(state.thumbLX - 1, -1, 1); break;
            case GAMEPAD_RIGHT_THUMB_UP: state.thumbRY = Mathf.clamp(state.thumbRY - 1, -1, 1); break;
            case GAMEPAD_RIGHT_THUMB_RIGHT: state.thumbRX = Mathf.clamp(state.thumbRX + 1, -1, 1); break;
            case GAMEPAD_RIGHT_THUMB_DOWN: state.thumbRY = Mathf.clamp(state.thumbRY + 1, -1, 1); break;
            case GAMEPAD_RIGHT_THUMB_LEFT: state.thumbRX = Mathf.clamp(state.thumbRX - 1, -1, 1); break;
        }
    }

    private static boolean isThumbBinding(Binding binding) {
        return binding == Binding.GAMEPAD_LEFT_THUMB_UP
                || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT
                || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN
                || binding == Binding.GAMEPAD_LEFT_THUMB_LEFT
                || binding == Binding.GAMEPAD_RIGHT_THUMB_UP
                || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT
                || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN
                || binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT;
    }

    private static boolean isLeftStickBinding(Binding binding) {
        return binding == Binding.GAMEPAD_LEFT_THUMB_UP
                || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN
                || binding == Binding.GAMEPAD_LEFT_THUMB_LEFT
                || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT;
    }

    public void handleMouseMoveInput(ControlElement owner, int slot, Binding binding, boolean pressed, float offset) {
        VirtualMouseBindingKey key = new VirtualMouseBindingKey(owner, slot, binding);
        if (pressed) {
            float value = offset != 0 ? offset
                    : (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_UP ? -1 : 1);
            activeVirtualMouseBindings.put(key, value);
        } else {
            activeVirtualMouseBindings.remove(key);
        }

        float x = 0;
        float y = 0;
        for (Map.Entry<VirtualMouseBindingKey, Float> entry : activeVirtualMouseBindings.entrySet()) {
            Binding activeBinding = entry.getKey().binding;
            if (activeBinding == Binding.MOUSE_MOVE_LEFT || activeBinding == Binding.MOUSE_MOVE_RIGHT) {
                x += entry.getValue();
            } else if (activeBinding == Binding.MOUSE_MOVE_UP || activeBinding == Binding.MOUSE_MOVE_DOWN) {
                y += entry.getValue();
            }
        }
        mouseMoveOffset.x = Mathf.clamp(x, -1, 1);
        mouseMoveOffset.y = Mathf.clamp(y, -1, 1);
        if (pressed) createMouseMoveTimer();
        else if (activeVirtualMouseBindings.isEmpty() && !hasControllerMouseMovement()) stopMouseMoveTimer();
    }

    private boolean hasControllerMouseMovement() {
        synchronized (controllerMouseMoveOffsets) {
            return !controllerMouseMoveOffsets.isEmpty();
        }
    }

    private void stopMouseMoveTimer() {
        if (mouseMoveTimer != null) {
            mouseMoveTimer.cancel();
            mouseMoveTimer = null;
        }
    }

    /** Send a batched gamepad state update to Wine — call this ONCE after setting
     *  all combo keys with sendUpdate=false. */
    public void sendGamepadUpdate() {
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (winHandler != null) {
            winHandler.sendGamepadState();
        }
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(null, binding, isActionDown, offset);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset) {
        handleInputEvent(controller, binding, isActionDown, offset, true);
    }

    public void handleInputEvent(ExternalController controller, Binding binding, boolean isActionDown, float offset, boolean sendUpdate) {
        // Unbound slots (Binding.NONE) carry XKeycode.KEY_NONE (id 0). Without this guard they fall
        // through to injectKeyPress(KEY_NONE) -> keyboard.setKeyPress(0,0), which is NOT guarded against
        // keycode 0 and dispatches a phantom key event. The beta4 gamepad rewrite (ca13e7f) made BUTTON
        // press/release fire getBindingAt(1) unconditionally, so a normal one-binding button injected this
        // junk event on every tap. Skip NONE here; real dual-binding buttons still fire when slot 1 is set.
        if (binding == Binding.NONE) return;
        if (binding == Binding.SHOW_ANDROID_KEYBOARD) {
            if (isActionDown && showKeyboardCallback != null) showKeyboardCallback.run();
            return;
        }
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (binding.isGamepad()) {
            GamepadState state = (controller != null) ? controller.remappedState : profile.getGamepadState();
            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= ExternalController.IDX_BUTTON_R2) {
                if (buttonIdx == ExternalController.IDX_BUTTON_L2)
                    state.triggerL = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
                else if (buttonIdx == ExternalController.IDX_BUTTON_R2)
                    state.triggerR = isActionDown ? (offset != 0 ? offset : 1.0f) : 0f;
                else
                    state.setPressed(buttonIdx, isActionDown);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                state.thumbLY = isActionDown ? (binding == Binding.GAMEPAD_LEFT_THUMB_UP ? -val : val) : 0;
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                state.thumbLX = isActionDown ? (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT ? -val : val) : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                state.thumbRY = isActionDown ? (binding == Binding.GAMEPAD_RIGHT_THUMB_UP ? -val : val) : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                float val = (isActionDown && offset == 0) ? 1.0f : Math.abs(offset);
                state.thumbRX = isActionDown ? (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT ? -val : val) : 0;
            }
            else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT ||
                     binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                state.dpad[binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal()] = isActionDown;
            }

            if (winHandler != null && sendUpdate) {
                if (controller != null)
                    winHandler.sendGamepadState(controller);
                else
                    winHandler.sendGamepadState();
            }
        }
        else {
            if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                mouseMoveOffset.x = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_LEFT ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                mouseMoveOffset.y = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_UP ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else {
                Pointer.Button pointerButton = binding.getPointerButton();
                if (isActionDown) {
                    if (pointerButton != null) {
                        if (xServer.isRelativeMouseMovement()) {
                            int wheelDelta = pointerButton == Pointer.Button.BUTTON_SCROLL_UP ? MOUSE_WHEEL_DELTA : (pointerButton == Pointer.Button.BUTTON_SCROLL_DOWN ? -MOUSE_WHEEL_DELTA : 0);
                            winHandler.mouseEvent(MouseEventFlags.getFlagFor(pointerButton, true), 0, 0, wheelDelta);
                        } else {
                            xServer.injectPointerButtonPress(pointerButton);
                        }
                    }
                    else xServer.injectKeyPress(binding.keycode);
                }
                else {
                    if (pointerButton != null) {
                        if (xServer.isRelativeMouseMovement()) {
                            winHandler.mouseEvent(MouseEventFlags.getFlagFor(pointerButton, false), 0, 0, 0);
                        } else {
                            xServer.injectPointerButtonRelease(pointerButton);
                        }
                    }
                    else xServer.injectKeyRelease(binding.keycode);
                }
            }
        }
    }

    public void handleCountedInputEvent(Binding binding, boolean isActionDown, float offset, boolean sendUpdate) {
        if (binding == null || binding == Binding.NONE) return;
        Pointer.Button pointerButton = binding.getPointerButton();
        boolean momentary = binding == Binding.SHOW_ANDROID_KEYBOARD
                || binding.isMouseMove()
                || pointerButton == Pointer.Button.BUTTON_SCROLL_UP
                || pointerButton == Pointer.Button.BUTTON_SCROLL_DOWN;
        if (momentary) {
            handleInputEvent(null, binding, isActionDown, offset, sendUpdate);
            return;
        }

        int count = activeVirtualBindings.getOrDefault(binding, 0);
        boolean stateChanged = false;
        if (isActionDown) {
            activeVirtualBindings.put(binding, count + 1);
            stateChanged = count == 0;
        } else if (count <= 1) {
            activeVirtualBindings.remove(binding);
            stateChanged = count == 1;
        } else {
            activeVirtualBindings.put(binding, count - 1);
        }
        if (!stateChanged) return;
        if (isThumbBinding(binding)) {
            rebuildVirtualStickAxes();
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            if (winHandler != null && sendUpdate) winHandler.sendGamepadState();
        } else {
            handleInputEvent(null, binding, isActionDown, offset, sendUpdate);
        }
    }

    public Bitmap getIcon(int id) {
        int index = id;
        if (index >= icons.length) return null;

        if (icons[index] == null) {
            // Check if it's a custom icon (ID >= 100)
            if (index >= CustomIconManager.CUSTOM_ICON_ID_OFFSET) {
                icons[index] = customIconManager.loadIcon((short) index);
            } else {
                // Built-in icon from assets
                Context context = getContext();
                try (InputStream is = context.getAssets().open("inputcontrols/icons/" + index + ".png")) {
                    icons[index] = BitmapFactory.decodeStream(is);
                } catch (IOException e) {
                    Log.e("InputControlsView", "Failed to load asset icon: " + index);
                }
            }
        }
        return icons[index];
    }
}
