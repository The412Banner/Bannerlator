package com.winlator.star.inputcontrols;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

import com.winlator.star.core.CubicBezierInterpolator;
import com.winlator.star.math.Mathf;
import com.winlator.star.widget.InputControlsView;
import com.winlator.star.widget.TouchpadView;
import com.winlator.star.winhandler.MouseEventFlags;
import com.winlator.star.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class ControlElement {
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final float DPAD_DEAD_ZONE = 0.3f;
    public static final float STICK_SENSITIVITY = 2.0f;
    public static final float TRACKPAD_MIN_SPEED = 0.8f;
    public static final float TRACKPAD_MAX_SPEED = 20.0f;
    public static final byte TRACKPAD_ACCELERATION_THRESHOLD = 4;
    public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;
    public enum Type {
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD;

        public static String[] names() {
            Type[] types = values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) names[i] = types[i].name().replace("_", "-");
            return names;
        }
    }
    public enum Shape {
        CIRCLE, RECT, ROUND_RECT, SQUARE;

        public static String[] names() {
            Shape[] shapes = values();
            String[] names = new String[shapes.length];
            for (int i = 0; i < shapes.length; i++) names[i] = shapes[i].name().replace("_", " ");
            return names;
        }
    }
    public enum Range {
        FROM_A_TO_Z(26), FROM_0_TO_9(10), FROM_F1_TO_F12(12), FROM_NP0_TO_NP9(10);
        public final byte max;

        Range(int max) {
            this.max = (byte)max;
        }

        public static String[] names() {
            Range[] ranges = values();
            String[] names = new String[ranges.length];
            for (int i = 0; i < ranges.length; i++) names[i] = ranges[i].name().replace("_", " ");
            return names;
        }
    }
    private final InputControlsView inputControlsView;
    private Type type = Type.BUTTON;
    private Shape shape = Shape.CIRCLE;
    private Binding[] bindings = {Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE};
    private float scale = 1.0f;
    private short x;
    private short y;
    private boolean selected = false;
    private boolean toggleSwitch = false;
    private int currentPointerId = -1;
    private final Rect boundingBox = new Rect();
    private boolean[] states = new boolean[4];
    private final Path path = new Path();
    private boolean boundingBoxNeedsUpdate = true;
    private String text = "";
    private byte iconId;
    private Range range;
    private byte orientation;
    private PointF currentPosition;
    private RangeScroller scroller;
    private CubicBezierInterpolator interpolator;
    private Object touchTime;

    public ControlElement(InputControlsView inputControlsView) {
        this.inputControlsView = inputControlsView;
    }

    private void reset() {
        setBinding(Binding.NONE);
        scroller = null;

        if (type == Type.STICK) {
            bindings[0] = Binding.KEY_W;
            bindings[1] = Binding.KEY_D;
            bindings[2] = Binding.KEY_S;
            bindings[3] = Binding.KEY_A;
        }
        else if(type == Type.D_PAD){
            bindings[0] = Binding.GAMEPAD_DPAD_UP;
            bindings[1] = Binding.GAMEPAD_DPAD_RIGHT;
            bindings[2] = Binding.GAMEPAD_DPAD_DOWN;
            bindings[3] = Binding.GAMEPAD_DPAD_LEFT;
        }
        else if (type == Type.TRACKPAD) {
            bindings[0] = Binding.GAMEPAD_RIGHT_THUMB_UP;
            bindings[1] = Binding.GAMEPAD_RIGHT_THUMB_RIGHT;
            bindings[2] = Binding.GAMEPAD_RIGHT_THUMB_DOWN;
            bindings[3] = Binding.GAMEPAD_RIGHT_THUMB_LEFT;
        }
        else if (type == Type.RANGE_BUTTON) {
            scroller = new RangeScroller(inputControlsView, this);
        }

        text = "";
        iconId = 0;
        range = null;
        boundingBoxNeedsUpdate = true;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
        reset();
    }

    public int getBindingCount() {
        return bindings.length;
    }

    public void setBindingCount(int bindingCount) {
        bindings = new Binding[bindingCount];
        setBinding(Binding.NONE);
        states = new boolean[bindingCount];
        boundingBoxNeedsUpdate = true;
    }

    public Shape getShape() {
        return shape;
    }

    public void setShape(Shape shape) {
        this.shape = shape;
        boundingBoxNeedsUpdate = true;
    }

    public Range getRange() {
        return range != null ? range : Range.FROM_A_TO_Z;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public byte getOrientation() {
        return orientation;
    }

    public void setOrientation(byte orientation) {
        this.orientation = orientation;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isToggleSwitch() {
        return toggleSwitch;
    }

    public void setToggleSwitch(boolean toggleSwitch) {
        this.toggleSwitch = toggleSwitch;
    }

    public Binding getBindingAt(int index) {
        return index < bindings.length ? bindings[index] : Binding.NONE;
    }

    public void setBindingAt(int index, Binding binding) {
        if (index >= bindings.length) {
            int oldLength = bindings.length;
            bindings = Arrays.copyOf(bindings, index+1);
            Arrays.fill(bindings, oldLength-1, bindings.length, Binding.NONE);
            states = new boolean[bindings.length];
            boundingBoxNeedsUpdate = true;
        }
        bindings[index] = binding;
    }

    public void setBinding(Binding binding) {
        Arrays.fill(bindings, binding);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
        boundingBoxNeedsUpdate = true;
    }

    public short getX() {
        return x;
    }

    public void setX(int x) {
        this.x = (short)x;
        boundingBoxNeedsUpdate = true;
    }

    public short getY() {
        return y;
    }

    public void setY(int y) {
        this.y = (short)y;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public byte getIconId() {
        return iconId;
    }

    public void setIconId(int iconId) {
        this.iconId = (byte)iconId;
    }

    public Rect getBoundingBox() {
        if (boundingBoxNeedsUpdate) computeBoundingBox();
        return boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = inputControlsView.getSnappingSize();
        int halfWidth = 0;
        int halfHeight = 0;

        switch (type) {
            case BUTTON:
                switch (shape) {
                    case RECT:
                    case ROUND_RECT:
                        halfWidth = snappingSize * 4;
                        halfHeight = snappingSize * 2;
                        break;
                    case SQUARE:
                        halfWidth = (int)(snappingSize * 2.5f);
                        halfHeight = (int)(snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
                break;
            case D_PAD: {
                halfWidth = snappingSize * 7;
                halfHeight = snappingSize * 7;
                break;
            }
            case TRACKPAD:
            case STICK: {
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            }
            case RANGE_BUTTON: {
                halfWidth = snappingSize * ((bindings.length * 4) / 2);
                halfHeight = snappingSize * 2;

                if (orientation == 1) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
            }
        }

        halfWidth *= scale;
        halfHeight *= scale;
        boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        boundingBoxNeedsUpdate = false;
        return boundingBox;
    }



    private String getDisplayText() {
        if (text != null && !text.isEmpty()) {
            return text;
        }
        else {
            Binding binding = getBindingAt(0);
            String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
            if (text.length() > 7) {
                String[] parts = text.split(" ");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) sb.append(part.charAt(0));
                return (binding.isMouse() ? "M" : "")+ sb;
            }
            else return text;
        }
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        return testTextSize * desiredWidth / paint.measureText(text);
    }

    private static String getRangeTextForIndex(Range range, int index) {
        String text = "";
        switch (range) {
            case FROM_A_TO_Z:
                text = String.valueOf((char)(65 + index));
                break;
            case FROM_0_TO_9:
                text = String.valueOf((index + 1) % 10);
                break;
            case FROM_F1_TO_F12:
                text = "F"+(index + 1);
                break;
            case FROM_NP0_TO_NP9:
                text = "NP"+((index + 1) % 10);
                break;
        }
        return text;
    }

    public void draw(Canvas canvas) {
        if (inputControlsView.getVisualStyle() == VisualStyle.GAMEHUB) {
            drawGameHub(canvas);
            return;
        }
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        int primaryColor = inputControlsView.getPrimaryColor();
        int overlayAlpha = Color.alpha(primaryColor);
        int blackFill = Color.argb(overlayAlpha, 0, 0, 0);

        paint.setColor(selected ? 0xff0277bd : primaryColor);
        paint.setStyle(Paint.Style.STROKE);
        float strokeWidth = snappingSize * 0.25f;
        paint.setStrokeWidth(strokeWidth);
        Rect boundingBox = getBoundingBox();

        boolean isL3R3 = type == Type.BUTTON && (getBindingAt(0) == Binding.GAMEPAD_BUTTON_L3 || getBindingAt(0) == Binding.GAMEPAD_BUTTON_R3);
        boolean isShoulderButton = type == Type.BUTTON && (getBindingAt(0) == Binding.GAMEPAD_BUTTON_L1 || getBindingAt(0) == Binding.GAMEPAD_BUTTON_R1 || getBindingAt(0) == Binding.GAMEPAD_BUTTON_L2 || getBindingAt(0) == Binding.GAMEPAD_BUTTON_R2);

        switch (type) {
            case BUTTON: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                int oldColor = paint.getColor();
                Shape effectiveShape = isShoulderButton ? Shape.ROUND_RECT : shape;
                boolean pressed = states[0];
                int activeColor = pressed ? 0xff64ddff : 0xff0277bd;

                if (isL3R3) {
                    // Render L3/R3 like joystick circles
                    float radius = boundingBox.width() * 0.5f;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(blackFill);
                    canvas.drawCircle(cx, cy, radius, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(activeColor);
                    paint.setStrokeWidth(strokeWidth);
                    canvas.drawCircle(cx, cy, radius, paint);

                    if (iconId > 0) {
                        drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId);
                    } else {
                        String text = getDisplayText();
                        paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), snappingSize * 2 * scale));
                        paint.setTextAlign(Paint.Align.CENTER);
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(activeColor);
                        canvas.drawText(text, x, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                    }
                    paint.setColor(oldColor);
                    break;
                }

                // Fill - black with opacity
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(blackFill);
                switch (effectiveShape) {
                    case CIRCLE:
                        canvas.drawCircle(cx, cy, boundingBox.width() * 0.5f, paint);
                        break;
                    case RECT:
                        canvas.drawRect(boundingBox, paint);
                        break;
                    case ROUND_RECT:
                    case SQUARE: {
                        float radius = effectiveShape == Shape.ROUND_RECT ? boundingBox.height() * 0.5f : snappingSize * 0.75f * scale;
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                        break;
                    }
                }

                // Stroke - glow blue when pressed
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(activeColor);
                paint.setStrokeWidth(strokeWidth);
                switch (effectiveShape) {
                    case CIRCLE:
                        canvas.drawCircle(cx, cy, boundingBox.width() * 0.5f, paint);
                        break;
                    case RECT:
                        canvas.drawRect(boundingBox, paint);
                        break;
                    case ROUND_RECT:
                    case SQUARE: {
                        float radius = effectiveShape == Shape.ROUND_RECT ? boundingBox.height() * 0.5f : snappingSize * 0.75f * scale;
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                        break;
                    }
                }

                // Text/Icon - glow blue when pressed
                if (iconId > 0) {
                    drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId);
                }
                else {
                    String text = getDisplayText();
                    paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), snappingSize * 2 * scale));
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(activeColor);
                    canvas.drawText(text, x, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                }
                paint.setColor(oldColor);
                break;
            }
            case D_PAD: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                int oldColor = paint.getColor();
                Path path = inputControlsView.getPath();
                path.reset();

                // 4 separate rounded rectangle buttons with arrows
                float btnSize = snappingSize * 3.5f * scale;
                float gap = snappingSize * 0.75f * scale;
                float arrowW = btnSize * 0.35f;
                float arrowH = btnSize * 0.5f;
                float btnRadius = snappingSize * 0.5f * scale;

                // Draw each directional button: up, down, left, right
                // Each button is a rounded rect with an arrow inside

                // Helper: draw one direction button
                // [up]
                float upCx = cx;
                float upCy = boundingBox.top + btnSize * 0.5f;
                // fill
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(blackFill);
                canvas.drawRoundRect(upCx - btnSize * 0.5f, upCy - btnSize * 0.5f, upCx + btnSize * 0.5f, upCy + btnSize * 0.5f, btnRadius, btnRadius, paint);
                // stroke
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(states[0] ? 0xff64ddff : 0xff0277bd);
                paint.setStrokeWidth(strokeWidth);
                canvas.drawRoundRect(upCx - btnSize * 0.5f, upCy - btnSize * 0.5f, upCx + btnSize * 0.5f, upCy + btnSize * 0.5f, btnRadius, btnRadius, paint);
                // up arrow
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(states[0] ? 0xff64ddff : 0xff0277bd);
                paint.setStrokeWidth(strokeWidth * 1.2f);
                path.moveTo(upCx, upCy - arrowH * 0.5f);
                path.lineTo(upCx - arrowW * 0.5f, upCy + arrowH * 0.5f);
                path.lineTo(upCx + arrowW * 0.5f, upCy + arrowH * 0.5f);
                path.close();
                canvas.drawPath(path, paint);

                // [down]
                float downCx = cx;
                float downCy = boundingBox.bottom - btnSize * 0.5f;
                path.reset();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(blackFill);
                canvas.drawRoundRect(downCx - btnSize * 0.5f, downCy - btnSize * 0.5f, downCx + btnSize * 0.5f, downCy + btnSize * 0.5f, btnRadius, btnRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(states[2] ? 0xff64ddff : 0xff0277bd);
                paint.setStrokeWidth(strokeWidth);
                canvas.drawRoundRect(downCx - btnSize * 0.5f, downCy - btnSize * 0.5f, downCx + btnSize * 0.5f, downCy + btnSize * 0.5f, btnRadius, btnRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(states[2] ? 0xff64ddff : 0xff0277bd);
                paint.setStrokeWidth(strokeWidth * 1.2f);
                path.moveTo(downCx, downCy + arrowH * 0.5f);
                path.lineTo(downCx - arrowW * 0.5f, downCy - arrowH * 0.5f);
                path.lineTo(downCx + arrowW * 0.5f, downCy - arrowH * 0.5f);
                path.close();
                canvas.drawPath(path, paint);

                // [left]
                float leftCx = boundingBox.left + btnSize * 0.5f;
                float leftCy = cy;
                path.reset();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(blackFill);
                canvas.drawRoundRect(leftCx - btnSize * 0.5f, leftCy - btnSize * 0.5f, leftCx + btnSize * 0.5f, leftCy + btnSize * 0.5f, btnRadius, btnRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(states[3] ? 0xff64ddff : 0xff0277bd);
                paint.setStrokeWidth(strokeWidth);
                canvas.drawRoundRect(leftCx - btnSize * 0.5f, leftCy - btnSize * 0.5f, leftCx + btnSize * 0.5f, leftCy + btnSize * 0.5f, btnRadius, btnRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(states[3] ? 0xff64ddff : 0xff0277bd);
                paint.setStrokeWidth(strokeWidth * 1.2f);
                path.moveTo(leftCx - arrowH * 0.5f, leftCy);
                path.lineTo(leftCx + arrowH * 0.5f, leftCy - arrowW * 0.5f);
                path.lineTo(leftCx + arrowH * 0.5f, leftCy + arrowW * 0.5f);
                path.close();
                canvas.drawPath(path, paint);

                // [right]
                float rightCx = boundingBox.right - btnSize * 0.5f;
                float rightCy = cy;
                path.reset();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(blackFill);
                canvas.drawRoundRect(rightCx - btnSize * 0.5f, rightCy - btnSize * 0.5f, rightCx + btnSize * 0.5f, rightCy + btnSize * 0.5f, btnRadius, btnRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(states[1] ? 0xff64ddff : 0xff0277bd);
                paint.setStrokeWidth(strokeWidth);
                canvas.drawRoundRect(rightCx - btnSize * 0.5f, rightCy - btnSize * 0.5f, rightCx + btnSize * 0.5f, rightCy + btnSize * 0.5f, btnRadius, btnRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(states[1] ? 0xff64ddff : 0xff0277bd);
                paint.setStrokeWidth(strokeWidth * 1.2f);
                path.moveTo(rightCx + arrowH * 0.5f, rightCy);
                path.lineTo(rightCx - arrowH * 0.5f, rightCy - arrowW * 0.5f);
                path.lineTo(rightCx - arrowH * 0.5f, rightCy + arrowW * 0.5f);
                path.close();
                canvas.drawPath(path, paint);

                // Rounded center square - black fill + blue stroke
                float centerSize = snappingSize * 1.2f * scale;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(blackFill);
                canvas.drawRoundRect(cx - centerSize, cy - centerSize, cx + centerSize, cy + centerSize, centerSize * 0.3f, centerSize * 0.3f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0xff0277bd);
                paint.setStrokeWidth(strokeWidth);
                canvas.drawRoundRect(cx - centerSize, cy - centerSize, cx + centerSize, cy + centerSize, centerSize * 0.3f, centerSize * 0.3f, paint);

                paint.setColor(oldColor);
                break;
            }
            case RANGE_BUTTON: {
                Range range = getRange();
                int oldColor = paint.getColor();
                float radius = snappingSize * 0.75f * scale;
                float elementSize = scroller.getElementSize();
                float minTextSize = snappingSize * 2 * scale;
                float scrollOffset = scroller.getScrollOffset();
                byte[] rangeIndex = scroller.getRangeIndex();
                Path path = inputControlsView.getPath();
                path.reset();

                if (orientation == 0) {
                    float lineTop = boundingBox.top + strokeWidth * 0.5f;
                    float lineBottom = boundingBox.bottom - strokeWidth * 0.5f;
                    float startX = boundingBox.left;
                    canvas.drawRoundRect(startX, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                    canvas.save();
                    path.addRoundRect(startX, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                    canvas.clipPath(path);
                    startX -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(oldColor);

                        if (startX > boundingBox.left && startX  < boundingBox.right) canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
                        String text = getRangeTextForIndex(range, index);

                        if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(primaryColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, startX + elementSize * 0.5f, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                        }
                        startX += elementSize;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();
                }
                else {
                    float lineLeft = boundingBox.left + strokeWidth * 0.5f;
                    float lineRight = boundingBox.right - strokeWidth * 0.5f;
                    float startY = boundingBox.top;
                    canvas.drawRoundRect(boundingBox.left, startY, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                    canvas.save();
                    path.addRoundRect(boundingBox.left, startY, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                    canvas.clipPath(inputControlsView.getPath());
                    startY -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(oldColor);

                        if (startY > boundingBox.top && startY < boundingBox.bottom) canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
                        String text = getRangeTextForIndex(range, i);

                        if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(primaryColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, x, startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f), paint);
                        }
                        startY += elementSize;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();
                }
                break;
            }
            case STICK: {
                int cx = boundingBox.centerX();
                int cy = boundingBox.centerY();
                int oldColor = paint.getColor();
                float outerRadius = boundingBox.height() * 0.5f;

                // Outer circle - black fill
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(blackFill);
                canvas.drawCircle(cx, cy, outerRadius, paint);

                // Outer circle - light blue stroke
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0xff0277bd);
                paint.setStrokeWidth(strokeWidth);
                canvas.drawCircle(cx, cy, outerRadius, paint);

                // Inner thumbstick
                float thumbstickX = getCurrentPosition().x;
                float thumbstickY = getCurrentPosition().y;
                short thumbRadius = (short) (snappingSize * 3.5f * scale);

                // Thumb - black fill
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(blackFill);
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint);

                // Thumb - light blue stroke
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0xff0277bd);
                paint.setStrokeWidth(strokeWidth);
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius + strokeWidth * 0.5f, paint);

                paint.setColor(oldColor);
                break;
            }

            case TRACKPAD: {
                float radius = boundingBox.height() * 0.15f;
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                float offset = strokeWidth * 2.5f;
                float innerStrokeWidth = strokeWidth * 2;
                float innerHeight = boundingBox.height() - offset * 2;
                radius = (innerHeight / boundingBox.height()) * radius - (innerStrokeWidth * 0.5f + strokeWidth * 0.5f);
                paint.setStrokeWidth(innerStrokeWidth);
                canvas.drawRoundRect(boundingBox.left + offset, boundingBox.top + offset, boundingBox.right - offset, boundingBox.bottom - offset, radius, radius, paint);
                break;
            }
        }
    }

    private boolean isEngaged() {
        return currentPointerId != -1 || (toggleSwitch && selected);
    }

    private int resolveAccentColor() {
        return -1;
    }

    private GameHubLayout.RenderShape gameHubTriggerShape() {
        return GameHubLayout.triggerShapeFor(GameHubLayout.roleFor(this));
    }

    private void drawGameHub(Canvas canvas) {
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        float effectiveOpacity = inputControlsView.isEditMode() ? Math.max(0.15f, 1.0f) : 1.0f;
        float overlayOpacity = inputControlsView.getOverlayOpacity();
        boolean engaged = isEngaged();
        Rect boundingBox = getBoundingBox();

        int accent = resolveAccentColor();
        boolean hasAccent = accent != -1;

        float gameHubDim = Math.min(1.0f, 0.5f + overlayOpacity * 0.7f);
        int fillAlpha = (int) (90 * gameHubDim * effectiveOpacity);
        int strokeAlpha = (int) (150 * gameHubDim * effectiveOpacity);
        int pressedFillAlpha = (int) (60 * gameHubDim * effectiveOpacity);
        int pressedStrokeAlpha = (int) (220 * gameHubDim * effectiveOpacity);
        int textAlpha = (int) (255 * gameHubDim * effectiveOpacity);
        int glassEdgeAlpha = (int) (75 * gameHubDim * effectiveOpacity);

        int fillColor = Color.argb(fillAlpha, 0, 0, 0);
        int strokeColor = hasAccent
                ? Color.argb(Math.max(strokeAlpha, 110), Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.argb(strokeAlpha, 0x1C, 0x85, 0xFE);
        int pressedFillBase = hasAccent ? accent : 0xFF1C85FE;
        int pressedFillColor = Color.argb(pressedFillAlpha, Color.red(pressedFillBase), Color.green(pressedFillBase), Color.blue(pressedFillBase));
        int pressedStrokeColor = hasAccent
                ? Color.argb(Math.max(pressedStrokeAlpha, 160), Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.argb(pressedStrokeAlpha, 0x64, 0xDD, 0xFF);
        int textColor = hasAccent
                ? Color.argb(textAlpha, Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.argb(textAlpha, 0x1C, 0x85, 0xFE);

        if (selected && !hasAccent) {
            int highlightAlpha = (int) (255 * overlayOpacity);
            strokeColor = Color.argb(highlightAlpha, 2, 119, 189);
        }

        float strokeWidth = Math.max(2f, snappingSize * 0.18f);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);

        switch (type) {
            case BUTTON: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                GameHubLayout.RenderShape triggerShape = gameHubTriggerShape();
                boolean isTrigger = triggerShape != null;

                if (isTrigger) {
                    GameHubLayout.buildTriggerPath(
                            path, triggerShape,
                            boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom);
                    paint.setShadowLayer(snappingSize * 0.08f, 0, snappingSize * 0.04f, 0x401C85FE);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(fillColor);
                    canvas.drawPath(path, paint);
                    paint.setShadowLayer(0f, 0f, 0f, 0);
                    if (engaged) {
                        paint.setColor(pressedFillColor);
                        canvas.drawPath(path, paint);
                    }
                    drawGameHubGlassOnPath(
                            canvas, paint, path, cx, cy,
                            Math.max(boundingBox.width(), boundingBox.height()) * 0.5f, glassEdgeAlpha);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(engaged ? pressedStrokeColor : strokeColor);
                    canvas.drawPath(path, paint);
                } else {
                    paint.setShadowLayer(snappingSize * 0.08f, 0, snappingSize * 0.04f, 0x401C85FE);
                    drawGameHubShape(canvas, paint, boundingBox, fillColor, true);
                    paint.setShadowLayer(0f, 0f, 0f, 0);
                    if (engaged) drawGameHubShape(canvas, paint, boundingBox, pressedFillColor, true);
                    drawGameHubGlassShape(canvas, paint, boundingBox, glassEdgeAlpha);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(engaged ? pressedStrokeColor : strokeColor);
                    drawGameHubShape(canvas, paint, boundingBox, 0, false);
                }

                if (iconId > 0) {
                    drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId);
                } else {
                    String label = getDisplayText();
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(textColor);
                    paint.setTextSize(
                            Math.min(
                                    getTextSizeForWidth(paint, label, boundingBox.width() - strokeWidth * 2),
                                    snappingSize * 2 * scale));
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setFakeBoldText(true);
                    canvas.drawText(label, cx, (cy - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                    paint.setFakeBoldText(false);
                }
                break;
            }
            case STICK: {
                int cx = boundingBox.centerX();
                int cy = boundingBox.centerY();
                float ringRadius = boundingBox.height() * 0.5f;

                int ringFillAlpha = fillAlpha;
                int ringFill = Color.argb(ringFillAlpha, 0, 0, 0);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ringFill);
                canvas.drawCircle(cx, cy, ringRadius, paint);

                if (glassEdgeAlpha > 0) {
                    paint.setShader(new RadialGradient(
                            cx, cy, ringRadius,
                            Color.argb(0, 0, 0, 0), Color.argb(glassEdgeAlpha, 0, 0, 0),
                            Shader.TileMode.CLAMP));
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, ringRadius, paint);
                    paint.setShader(null);
                }

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(engaged ? pressedStrokeColor : strokeColor);
                canvas.drawCircle(cx, cy, ringRadius - strokeWidth * 0.5f, paint);

                float thumbX = engaged ? getCurrentPosition().x : cx;
                float thumbY = engaged ? getCurrentPosition().y : cy;
                float thumbRadius = ringRadius * 0.48f;
                int thumbFillAlpha = (int) ((engaged ? 100 : 77) * gameHubDim * effectiveOpacity);
                int thumbFill = hasAccent
                        ? Color.argb(thumbFillAlpha, Color.red(accent), Color.green(accent), Color.blue(accent))
                        : Color.argb(thumbFillAlpha, 0x1C, 0x85, 0xFE);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(thumbFill);
                canvas.drawCircle(thumbX, thumbY, thumbRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(engaged ? pressedStrokeColor : strokeColor);
                canvas.drawCircle(thumbX, thumbY, thumbRadius - strokeWidth * 0.5f, paint);
                break;
            }
            case D_PAD: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();

                float radius = Math.min(boundingBox.width(), boundingBox.height()) * 0.5f;
                float[] arrowCenter = new float[2];
                float arrowGradR = radius * 0.5f;
                for (int side = 0; side < 4; side++) {
                    path.reset();
                    GameHubLayout.buildDpadArrow(path, side, cx, cy, radius);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(fillColor);
                    canvas.drawPath(path, paint);
                    if (engaged) {
                        paint.setColor(pressedFillColor);
                        canvas.drawPath(path, paint);
                    }
                    if (glassEdgeAlpha > 0) {
                        GameHubLayout.dpadArrowCenter(side, cx, cy, radius, arrowCenter);
                        drawGameHubGlassOnPath(
                                canvas, paint, path, arrowCenter[0], arrowCenter[1], arrowGradR, glassEdgeAlpha);
                    }
                }
                GameHubLayout.buildDpadArrows(path, cx, cy, radius);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(engaged ? pressedStrokeColor : strokeColor);
                canvas.drawPath(path, paint);
                break;
            }
            case TRACKPAD: {
                float radius = boundingBox.height() * 0.18f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fillColor);
                canvas.drawRoundRect(
                        boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
                        radius, radius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(engaged ? pressedStrokeColor : strokeColor);
                canvas.drawRoundRect(
                        boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
                        radius, radius, paint);
                break;
            }
            case RANGE_BUTTON: {
                Range range = getRange();
                float radius = snappingSize * 0.75f * scale;
                float elementSize = scroller.getElementSize();
                float minTextSize = snappingSize * 2 * scale;
                float scrollOffset = scroller.getScrollOffset();
                byte[] rangeIndex = scroller.getRangeIndex();
                path.reset();

                drawGameHubShape(canvas, paint, boundingBox, fillColor, true, Shape.ROUND_RECT);
                if (engaged) drawGameHubShape(canvas, paint, boundingBox, pressedFillColor, true, Shape.ROUND_RECT);
                drawGameHubGlassShape(canvas, paint, boundingBox, glassEdgeAlpha, Shape.ROUND_RECT);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(engaged ? pressedStrokeColor : strokeColor);
                drawGameHubShape(canvas, paint, boundingBox, 0, false, Shape.ROUND_RECT);

                canvas.save();
                path.addRoundRect(
                        boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom,
                        radius, radius, Path.Direction.CW);
                canvas.clipPath(path);

                if (orientation == 0) {
                    float lineTop = boundingBox.top + strokeWidth * 0.5f;
                    float lineBottom = boundingBox.bottom - strokeWidth * 0.5f;
                    float startX = boundingBox.left - (scrollOffset % elementSize);

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(strokeColor);
                        if (startX > boundingBox.left && startX < boundingBox.right)
                            canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
                        String text = getRangeTextForIndex(range, index);
                        if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(textColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, startX + elementSize * 0.5f, (boundingBox.centerY() - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                        }
                        startX += elementSize;
                    }
                } else {
                    float lineLeft = boundingBox.left + strokeWidth * 0.5f;
                    float lineRight = boundingBox.right - strokeWidth * 0.5f;
                    float startY = boundingBox.top - (scrollOffset % elementSize);

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(strokeColor);
                        if (startY > boundingBox.top && startY < boundingBox.bottom)
                            canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
                        String text = getRangeTextForIndex(range, i);
                        if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(textColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, boundingBox.centerX(), startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f), paint);
                        }
                        startY += elementSize;
                    }
                }
                canvas.restore();
                break;
            }
            default:
                drawOriginalLegacy(canvas);
                break;
        }
    }

    private void drawGameHubShape(Canvas canvas, Paint paint, Rect bb, int color, boolean fill) {
        drawGameHubShape(canvas, paint, bb, color, fill, shape);
    }

    private void drawGameHubShape(Canvas canvas, Paint paint, Rect bb, int color, boolean fill, Shape overrideShape) {
        if (fill) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
        }
        int snappingSize = inputControlsView.getSnappingSize();
        switch (overrideShape) {
            case CIRCLE:
                canvas.drawCircle(bb.centerX(), bb.centerY(), bb.width() * 0.5f, paint);
                break;
            case RECT:
                canvas.drawRect(bb, paint);
                break;
            case ROUND_RECT: {
                float r = bb.height() * 0.5f;
                canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
                break;
            }
            case SQUARE: {
                float r = snappingSize * 0.85f * scale;
                canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
                break;
            }
        }
    }

    private void drawGameHubGlassShape(Canvas canvas, Paint paint, Rect bb, int edgeAlpha) {
        drawGameHubGlassShape(canvas, paint, bb, edgeAlpha, shape);
    }

    private void drawGameHubGlassShape(Canvas canvas, Paint paint, Rect bb, int edgeAlpha, Shape overrideShape) {
        if (edgeAlpha <= 0) return;
        float cx = bb.exactCenterX();
        float cy = bb.exactCenterY();
        float gradR = Math.max(bb.width(), bb.height()) * 0.5f;
        paint.setShader(new RadialGradient(
                cx, cy, gradR,
                Color.argb(0, 0, 0, 0), Color.argb(edgeAlpha, 0, 0, 0),
                Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        int snappingSize = inputControlsView.getSnappingSize();
        switch (overrideShape) {
            case CIRCLE:
                canvas.drawCircle(cx, cy, bb.width() * 0.5f, paint);
                break;
            case RECT:
                canvas.drawRect(bb, paint);
                break;
            case ROUND_RECT: {
                float r = bb.height() * 0.5f;
                canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
                break;
            }
            case SQUARE: {
                float r = snappingSize * 0.85f * scale;
                canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
                break;
            }
        }
        paint.setShader(null);
    }

    private void drawGameHubGlassOnPath(
            Canvas canvas, Paint paint, Path path, float cx, float cy, float gradR, int edgeAlpha) {
        if (edgeAlpha <= 0 || gradR <= 0) return;
        paint.setShader(new RadialGradient(
                cx, cy, gradR,
                Color.argb(0, 0, 0, 0), Color.argb(edgeAlpha, 0, 0, 0),
                Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        paint.setShader(null);
    }

    private void drawOriginalLegacy(Canvas canvas) {
        VisualStyle saved = inputControlsView.getVisualStyle();
        inputControlsView.setVisualStyle(VisualStyle.ORIGINAL);
        draw(canvas);
        inputControlsView.setVisualStyle(saved);
    }

    private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId) {
        Bitmap icon = inputControlsView.getIcon((byte)iconId);
        if (icon == null) return;

        Paint paint = inputControlsView.getPaint();
        if (iconId < CustomIconManager.CUSTOM_ICON_ID_OFFSET) {
            boolean pressed = type == Type.BUTTON && states[0];
            if (inputControlsView.getVisualStyle() == VisualStyle.GAMEHUB) {
                paint.setColorFilter(new PorterDuffColorFilter(pressed ? 0xff64ddff : 0xff1C85FE, PorterDuff.Mode.SRC_IN));
            } else {
                paint.setColorFilter(new PorterDuffColorFilter(pressed ? 0xff64ddff : 0xff0277bd, PorterDuff.Mode.SRC_IN));
            }
        }
        int margin = (int)(inputControlsView.getSnappingSize() * (shape == Shape.CIRCLE || shape == Shape.SQUARE ? 2.0f : 1.0f) * scale);
        int halfSize = (int)((Math.min(width, height) - margin) * 0.5f);

        Rect srcRect = new Rect(0, 0, icon.getWidth(), icon.getHeight());
        Rect dstRect = new Rect((int)(cx - halfSize), (int)(cy - halfSize), (int)(cx + halfSize), (int)(cy + halfSize));
        canvas.drawBitmap(icon, srcRect, dstRect, paint);
        paint.setColorFilter(null);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject elementJSONObject = new JSONObject();
            elementJSONObject.put("type", type.name());
            elementJSONObject.put("shape", shape.name());

            JSONArray bindingsJSONArray = new JSONArray();
            for (Binding binding : bindings) bindingsJSONArray.put(binding.name());

            elementJSONObject.put("bindings", bindingsJSONArray);
            elementJSONObject.put("scale", Float.valueOf(scale));
            elementJSONObject.put("x", (float)x / inputControlsView.getMaxWidth());
            elementJSONObject.put("y", (float)y / inputControlsView.getMaxHeight());
            elementJSONObject.put("toggleSwitch", toggleSwitch);
            elementJSONObject.put("text", text);
            elementJSONObject.put("iconId", iconId);

            if (type == Type.RANGE_BUTTON && range != null) {
                elementJSONObject.put("range", range.name());
                if (orientation != 0) elementJSONObject.put("orientation", orientation);
            }
            return elementJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        return getBoundingBox().contains((int)(x + 0.5f), (int)(y + 0.5f));
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !toggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId;
            if (type == Type.BUTTON) {
                states[0] = true;
                inputControlsView.invalidate();
                if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();
                if (!toggleSwitch || !selected) {
                    inputControlsView.handleInputEvent(getBindingAt(0), true);
                    inputControlsView.handleInputEvent(getBindingAt(1), true);
                }
                return true;
            }
            else if (type == Type.RANGE_BUTTON) {
                scroller.handleTouchDown(x, y);
                return true;
            }
            else {
                if (type == Type.TRACKPAD) {
                    if (currentPosition == null) currentPosition = new PointF();
                    currentPosition.set(x, y);
                }
                return handleTouchMove(pointerId, x, y);
            }
        }
        else return false;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD)) {
            float deltaX, deltaY;
            Rect boundingBox = getBoundingBox();
            float radius = boundingBox.width() * 0.5f;
            TouchpadView touchpadView =  inputControlsView.getTouchpadView();

            if (type == Type.TRACKPAD) {
                if (currentPosition == null) currentPosition = new PointF();
                float[] deltaPoint = touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
                deltaX = deltaPoint[0];
                deltaY = deltaPoint[1];
                currentPosition.set(x, y);
            }
            else {
                float localX = x - boundingBox.left;
                float localY = y - boundingBox.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;

                float distance = Mathf.lengthSq(radius - localX, radius - localY);
                if (distance > radius * radius) {
                    float angle = (float)Math.atan2(offsetY, offsetX);
                    offsetX = (float)(Math.cos(angle) * radius);
                    offsetY = (float)(Math.sin(angle) * radius);
                }

                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);
            }

            if (type == Type.STICK) {
                if (currentPosition == null) currentPosition = new PointF();
                currentPosition.x = boundingBox.left + deltaX * radius + radius;
                currentPosition.y = boundingBox.top + deltaY * radius + radius;
                
                // Check if any binding is gamepad - if so, use unified stick input
                Binding firstBinding = getBindingAt(0);
                if (firstBinding.isGamepad()) {
                    // Use radial deadzone to prevent angle snapping
                    float magnitude = (float)Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                    
                    float finalX = 0;
                    float finalY = 0;
                    
                    if (magnitude > STICK_DEAD_ZONE) {
                        // Normalize and apply sensitivity
                        float normalizedX = deltaX / magnitude;
                        float normalizedY = deltaY / magnitude;
                        
                        // Scale magnitude by sensitivity, respecting deadzone
                        float scaledMagnitude = Math.max(0, magnitude - 0.01f) * STICK_SENSITIVITY;
                        scaledMagnitude = Math.min(scaledMagnitude, 1.0f);
                        
                        finalX = normalizedX * scaledMagnitude;
                        finalY = normalizedY * scaledMagnitude;
                    }
                    
                    // Use unified stick input method - sets both X and Y together
                    inputControlsView.handleStickInput(firstBinding, finalX, finalY);
                    
                    // Mark all directions as active for proper release handling
                    for (byte i = 0; i < 4; i++) {
                        this.states[i] = true;
                    }
                } else {
                    // Fallback to per-direction handling for mouse/keyboard bindings
                    final boolean[] states = {deltaY <= -STICK_DEAD_ZONE, deltaX >= STICK_DEAD_ZONE, deltaY >= STICK_DEAD_ZONE, deltaX <= -STICK_DEAD_ZONE};
                    for (byte i = 0; i < 4; i++) {
                        float value = i == 1 || i == 3 ? deltaX : deltaY;
                        Binding binding = getBindingAt(i);
                        boolean state = binding.isMouseMove() ? (states[i] || states[(i+2)%4]) : states[i];
                        inputControlsView.handleInputEvent(binding, state, value);
                        this.states[i] = state;
                    }
                }

                inputControlsView.invalidate();
            }
            else if (type == Type.TRACKPAD) {
                // Check if gamepad bindings - use unified handling
                Binding firstBinding = getBindingAt(0);
                if (firstBinding.isGamepad()) {
                    // Apply interpolation to both axes
                    if (interpolator == null) interpolator = new CubicBezierInterpolator();
                    interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);
                    
                    float valueX = deltaX;
                    float valueY = deltaY;
                    if (Math.abs(valueX) > TRACKPAD_ACCELERATION_THRESHOLD) valueX *= STICK_SENSITIVITY;
                    if (Math.abs(valueY) > TRACKPAD_ACCELERATION_THRESHOLD) valueY *= STICK_SENSITIVITY;
                    
                    float interpX = interpolator.getInterpolation(Math.min(1.0f, Math.abs(valueX / TRACKPAD_MAX_SPEED)));
                    float interpY = interpolator.getInterpolation(Math.min(1.0f, Math.abs(valueY / TRACKPAD_MAX_SPEED)));
                    
                    float finalX = Mathf.clamp(interpX * Mathf.sign(valueX), -1, 1);
                    float finalY = Mathf.clamp(interpY * Mathf.sign(valueY), -1, 1);
                    
                    // Use unified stick input
                    inputControlsView.handleStickInput(firstBinding, finalX, finalY);
                    
                    // Mark all as active
                    for (byte i = 0; i < 4; i++) {
                        this.states[i] = true;
                    }
                } else {
                    // Mouse movement handling
                    final boolean[] states = {deltaY <= -TRACKPAD_MIN_SPEED, deltaX >= TRACKPAD_MIN_SPEED, deltaY >= TRACKPAD_MIN_SPEED, deltaX <= -TRACKPAD_MIN_SPEED};
                    int cursorDx = 0;
                    int cursorDy = 0;

                    for (byte i = 0; i < 4; i++) {
                        float value = (i == 1 || i == 3 ? deltaX : deltaY);
                        Binding binding = getBindingAt(i);
                        if (Math.abs(value) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD) value *= TouchpadView.CURSOR_ACCELERATION;
                        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                            cursorDx = Mathf.roundPoint(value);
                        }
                        else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) {
                            cursorDy = Mathf.roundPoint(value);
                        }
                        else {
                            inputControlsView.handleInputEvent(binding, states[i], value);
                            this.states[i] = states[i];
                        }
                    }

                    if (cursorDx != 0 || cursorDy != 0)  {
                        XServer xServer = inputControlsView.getXServer();
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, cursorDx, cursorDy, 0);
                        else
                            inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
                    }
                }
            }
            else {
                final boolean[] states = {deltaY <= -DPAD_DEAD_ZONE, deltaX >= DPAD_DEAD_ZONE, deltaY >= DPAD_DEAD_ZONE, deltaX <= -DPAD_DEAD_ZONE};

                for (byte i = 0; i < 4; i++) {
                    float value = i == 1 || i == 3 ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    boolean state = binding.isMouseMove() ? (states[i] || states[(i+2)%4]) : states[i];
                    inputControlsView.handleInputEvent(binding, state, value);
                    this.states[i] = state;
                }
            }

            return true;
        }
        else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller.handleTouchMove(x, y);
            return true;
        }
        else return false;
    }

    public boolean handleTouchUp(int pointerId) {
        if (pointerId == currentPointerId) {
            if (type == Type.BUTTON) {
                states[0] = false;
                inputControlsView.invalidate();
                if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                    selected = (System.currentTimeMillis() - (long)touchTime) > BUTTON_MIN_TIME_TO_KEEP_PRESSED;
                    if (!selected) {
                        inputControlsView.handleInputEvent(getBindingAt(0), false);
                        inputControlsView.handleInputEvent(getBindingAt(1), false);
                    }
                    touchTime = null;
                }
                else if (!toggleSwitch || selected) {
                    inputControlsView.handleInputEvent(getBindingAt(0), false);
                    inputControlsView.handleInputEvent(getBindingAt(1), false);
                }

                if (toggleSwitch) {
                    selected = !selected;
                }
            }
            else if (type == Type.RANGE_BUTTON || type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD) {
                for (byte i = 0; i < states.length; i++) {
                    if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
                    states[i] = false;
                }

                if (type == Type.RANGE_BUTTON) {
                    scroller.handleTouchUp();
                }
                else if (type == Type.STICK) {
                    inputControlsView.invalidate();
                }

                if (currentPosition != null) currentPosition = null;
            }
            currentPointerId = -1;
            return true;
        }
        return false;
    }

    public PointF getCurrentPosition() {
        if (currentPosition == null) {
            currentPosition = new PointF(x, y); // Initialize to the center (same as outer circle)
        }
        return currentPosition;
    }

    // New setter for current position to allow resetting
    public void setCurrentPosition(float x, float y) {
        if (currentPosition == null) {
            currentPosition = new PointF();
        }
        currentPosition.set(x, y);
        // Optionally invalidate the view to trigger a redraw
        inputControlsView.invalidate();
    }
}
