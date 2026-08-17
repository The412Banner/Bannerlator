package com.winlator.star.inputcontrols;

public enum VisualStyle {
    ORIGINAL,
    GAMEHUB;

    public static VisualStyle fromPreference(String name) {
        if (name == null) return GAMEHUB;
        try {
            return VisualStyle.valueOf(name);
        } catch (IllegalArgumentException e) {
            return GAMEHUB;
        }
    }
}
