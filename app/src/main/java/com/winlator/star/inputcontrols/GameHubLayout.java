package com.winlator.star.inputcontrols;

import android.graphics.Path;
import android.graphics.RectF;

public final class GameHubLayout {

    public enum Role {
        BTN_A, BTN_B, BTN_X, BTN_Y, LB, LT, RB, RT, L3, R3, LSTICK, RSTICK, DPAD, START, SELECT
    }

    public enum RenderShape {
        CIRCLE, ROUND_RECT, TRIGGER_LT, TRIGGER_LB, TRIGGER_RT, TRIGGER_RB, DPAD_CROSS
    }

    public static RenderShape triggerShapeFor(Role role) {
        if (role == null) return null;
        switch (role) {
            case LT: return RenderShape.TRIGGER_LT;
            case LB: return RenderShape.TRIGGER_LB;
            case RT: return RenderShape.TRIGGER_RT;
            case RB: return RenderShape.TRIGGER_RB;
            default: return null;
        }
    }

    public static Role roleFor(ControlElement element) {
        if (element == null) return null;
        ControlElement.Type type = element.getType();

        if (type == ControlElement.Type.STICK) {
            for (int i = 0; i < element.getBindingCount(); i++) {
                Binding b = element.getBindingAt(i);
                if (b == Binding.GAMEPAD_LEFT_THUMB_UP
                        || b == Binding.GAMEPAD_LEFT_THUMB_DOWN
                        || b == Binding.GAMEPAD_LEFT_THUMB_LEFT
                        || b == Binding.GAMEPAD_LEFT_THUMB_RIGHT) return Role.LSTICK;
                if (b == Binding.GAMEPAD_RIGHT_THUMB_UP
                        || b == Binding.GAMEPAD_RIGHT_THUMB_DOWN
                        || b == Binding.GAMEPAD_RIGHT_THUMB_LEFT
                        || b == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) return Role.RSTICK;
            }
            return null;
        }
        if (type == ControlElement.Type.D_PAD) return Role.DPAD;
        if (type != ControlElement.Type.BUTTON) return null;

        Binding b0 = element.getBindingAt(0);
        switch (b0) {
            case GAMEPAD_BUTTON_A: return Role.BTN_A;
            case GAMEPAD_BUTTON_B: return Role.BTN_B;
            case GAMEPAD_BUTTON_X: return Role.BTN_X;
            case GAMEPAD_BUTTON_Y: return Role.BTN_Y;
            case GAMEPAD_BUTTON_L1: return Role.LB;
            case GAMEPAD_BUTTON_R1: return Role.RB;
            case GAMEPAD_BUTTON_L2: return Role.LT;
            case GAMEPAD_BUTTON_R2: return Role.RT;
            case GAMEPAD_BUTTON_L3: return Role.L3;
            case GAMEPAD_BUTTON_R3: return Role.R3;
            case GAMEPAD_BUTTON_START: return Role.START;
            case GAMEPAD_BUTTON_SELECT: return Role.SELECT;
            default: return null;
        }
    }

    public static void buildTriggerPath(Path out, RenderShape shape, float left, float top, float right, float bottom) {
        out.reset();
        float w = right - left;
        float h = bottom - top;
        float r = Math.min(w, h) * 0.1875f;
        float f = 0.2f * w;
        float diag = (float) Math.sqrt(h * h + f * f);
        float sx = (f / diag) * r;
        float sy = (h / diag) * r;

        RectF tmp = new RectF();
        switch (shape) {
            case TRIGGER_LT: {
                out.moveTo(left + sx, bottom - sy);
                out.lineTo(left + f - sx, top + sy);
                out.quadTo(left + f, top, left + f + r, top);
                out.lineTo(right - r, top);
                tmp.set(right - 2 * r, top, right, top + 2 * r);
                out.arcTo(tmp, -90, 90, false);
                out.lineTo(right, bottom - r);
                tmp.set(right - 2 * r, bottom - 2 * r, right, bottom);
                out.arcTo(tmp, 0, 90, false);
                out.lineTo(left + r, bottom);
                out.quadTo(left, bottom, left + sx, bottom - sy);
                out.close();
                break;
            }
            case TRIGGER_LB: {
                out.moveTo(left + r, top);
                out.lineTo(right - r, top);
                tmp.set(right - 2 * r, top, right, top + 2 * r);
                out.arcTo(tmp, -90, 90, false);
                out.lineTo(right, bottom - r);
                tmp.set(right - 2 * r, bottom - 2 * r, right, bottom);
                out.arcTo(tmp, 0, 90, false);
                out.lineTo(left + f + r, bottom);
                out.quadTo(left + f, bottom, left + f - sx, bottom - sy);
                out.lineTo(left + sx, top + sy);
                out.quadTo(left, top, left + r, top);
                out.close();
                break;
            }
            case TRIGGER_RT: {
                out.moveTo(right - sx, bottom - sy);
                out.lineTo(right - f + sx, top + sy);
                out.quadTo(right - f, top, right - f - r, top);
                out.lineTo(left + r, top);
                tmp.set(left, top, left + 2 * r, top + 2 * r);
                out.arcTo(tmp, 270, -90, false);
                out.lineTo(left, bottom - r);
                tmp.set(left, bottom - 2 * r, left + 2 * r, bottom);
                out.arcTo(tmp, 180, -90, false);
                out.lineTo(right - r, bottom);
                out.quadTo(right, bottom, right - sx, bottom - sy);
                out.close();
                break;
            }
            case TRIGGER_RB: {
                out.moveTo(right - r, top);
                out.lineTo(left + r, top);
                tmp.set(left, top, left + 2 * r, top + 2 * r);
                out.arcTo(tmp, 270, -90, false);
                out.lineTo(left, bottom - r);
                tmp.set(left, bottom - 2 * r, left + 2 * r, bottom);
                out.arcTo(tmp, 180, -90, false);
                out.lineTo(right - f - r, bottom);
                out.quadTo(right - f, bottom, right - f + sx, bottom - sy);
                out.lineTo(right - sx, top + sy);
                out.quadTo(right, top, right - r, top);
                out.close();
                break;
            }
            default:
                out.addRoundRect(left, top, right, bottom, r, r, Path.Direction.CW);
                break;
        }
    }

    public static void buildDpadArrows(Path out, float cx, float cy, float radius) {
        out.reset();
        for (int side = 0; side < 4; side++) buildDpadArrow(out, side, cx, cy, radius);
    }

    public static final int DPAD_UP = 0;
    public static final int DPAD_DOWN = 1;
    public static final int DPAD_LEFT = 2;
    public static final int DPAD_RIGHT = 3;

    public static void buildDpadArrow(Path out, int side, float cx, float cy, float radius) {
        float outer = radius * 0.95f;
        float shoulder = radius * 0.45f;
        float inner = radius * 0.18f;
        float halfOuter = radius * 0.275f;
        float cornerR = radius * 0.05f;

        switch (side) {
            case DPAD_UP:
                out.moveTo(cx - halfOuter + cornerR, cy - outer);
                out.lineTo(cx + halfOuter - cornerR, cy - outer);
                out.quadTo(cx + halfOuter, cy - outer, cx + halfOuter, cy - outer + cornerR);
                out.lineTo(cx + halfOuter, cy - shoulder);
                out.lineTo(cx, cy - inner);
                out.lineTo(cx - halfOuter, cy - shoulder);
                out.lineTo(cx - halfOuter, cy - outer + cornerR);
                out.quadTo(cx - halfOuter, cy - outer, cx - halfOuter + cornerR, cy - outer);
                out.close();
                break;
            case DPAD_DOWN:
                out.moveTo(cx + halfOuter - cornerR, cy + outer);
                out.lineTo(cx - halfOuter + cornerR, cy + outer);
                out.quadTo(cx - halfOuter, cy + outer, cx - halfOuter, cy + outer - cornerR);
                out.lineTo(cx - halfOuter, cy + shoulder);
                out.lineTo(cx, cy + inner);
                out.lineTo(cx + halfOuter, cy + shoulder);
                out.lineTo(cx + halfOuter, cy + outer - cornerR);
                out.quadTo(cx + halfOuter, cy + outer, cx + halfOuter - cornerR, cy + outer);
                out.close();
                break;
            case DPAD_LEFT:
                out.moveTo(cx - outer, cy + halfOuter - cornerR);
                out.lineTo(cx - outer, cy - halfOuter + cornerR);
                out.quadTo(cx - outer, cy - halfOuter, cx - outer + cornerR, cy - halfOuter);
                out.lineTo(cx - shoulder, cy - halfOuter);
                out.lineTo(cx - inner, cy);
                out.lineTo(cx - shoulder, cy + halfOuter);
                out.lineTo(cx - outer + cornerR, cy + halfOuter);
                out.quadTo(cx - outer, cy + halfOuter, cx - outer, cy + halfOuter - cornerR);
                out.close();
                break;
            case DPAD_RIGHT:
                out.moveTo(cx + outer, cy - halfOuter + cornerR);
                out.lineTo(cx + outer, cy + halfOuter - cornerR);
                out.quadTo(cx + outer, cy + halfOuter, cx + outer - cornerR, cy + halfOuter);
                out.lineTo(cx + shoulder, cy + halfOuter);
                out.lineTo(cx + inner, cy);
                out.lineTo(cx + shoulder, cy - halfOuter);
                out.lineTo(cx + outer - cornerR, cy - halfOuter);
                out.quadTo(cx + outer, cy - halfOuter, cx + outer, cy - halfOuter + cornerR);
                out.close();
                break;
        }
    }

    public static void dpadArrowCenter(int side, float cx, float cy, float radius, float[] outXY) {
        float along = radius * 0.565f;
        switch (side) {
            case DPAD_UP:    outXY[0] = cx;          outXY[1] = cy - along; break;
            case DPAD_DOWN:  outXY[0] = cx;          outXY[1] = cy + along; break;
            case DPAD_LEFT:  outXY[0] = cx - along;  outXY[1] = cy;         break;
            case DPAD_RIGHT: outXY[0] = cx + along;  outXY[1] = cy;         break;
            default:         outXY[0] = cx;          outXY[1] = cy;         break;
        }
    }

    private GameHubLayout() {}
}
