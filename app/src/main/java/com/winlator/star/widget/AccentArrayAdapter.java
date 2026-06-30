package com.winlator.star.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.winlator.star.ui.theme.AppThemeState;

import java.util.List;

/**
 * ArrayAdapter for the controller-binding spinners (binding_spinner_item /
 * binding_spinner_dropdown_item). Those layouts hardcode textColor="@color/colorPrimary"
 * (accent text — a deliberate readability choice on this screen's black background), which
 * is baked at inflation. This re-applies the *runtime* theme accent to the item + dropdown
 * TextView so the binding spinners follow the in-app theme picker instead of the static
 * #0055FF — but with a luminance floor: if the accent is too dark to read on the black
 * background it falls back to white, so the text can never become invisible (the original
 * black-on-black bug this screen was fixed for). Applied per row inflation; these screens
 * are short-lived (recreated each open), so no live update while open is needed.
 */
public class AccentArrayAdapter<T> extends ArrayAdapter<T> {
    public AccentArrayAdapter(Context context, int resource, T[] objects) {
        super(context, resource, objects);
    }

    public AccentArrayAdapter(Context context, int resource, List<T> objects) {
        super(context, resource, objects);
    }

    // Theme accent, or white when the accent is too dark to read on this screen's black
    // background. Threshold is low (0.18) so the default blue (#0055FF, ~0.31) and all the
    // built-in presets keep their colour — only genuinely dark custom accents fall back.
    private static int legibleAccentOnBlack() {
        int argb = AppThemeState.getCurrentAccentArgb();
        double lum = (0.2126 * Color.red(argb) + 0.7152 * Color.green(argb) + 0.0722 * Color.blue(argb)) / 255.0;
        return lum < 0.18 ? 0xFFFFFFFF : (0xFF000000 | (argb & 0x00FFFFFF));
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = super.getView(position, convertView, parent);
        if (view instanceof TextView) ((TextView) view).setTextColor(legibleAccentOnBlack());
        return view;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        View view = super.getDropDownView(position, convertView, parent);
        if (view instanceof TextView) ((TextView) view).setTextColor(legibleAccentOnBlack());
        return view;
    }
}
