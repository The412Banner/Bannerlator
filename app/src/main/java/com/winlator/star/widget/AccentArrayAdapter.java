package com.winlator.star.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.winlator.star.ui.theme.AppThemeState;

import java.util.List;

/**
 * ArrayAdapter for the controller-binding spinners (binding_spinner_item /
 * binding_spinner_dropdown_item). Those layouts hardcode textColor="@color/colorPrimary"
 * (accent text — a deliberate readability choice), which is baked at inflation. This
 * re-applies the *runtime* theme accent to the item + dropdown TextView so the binding
 * spinners follow the in-app theme picker instead of the static #0055FF. Applied once
 * per row inflation; these screens are short-lived (recreated each open), so no live
 * update while open is needed.
 */
public class AccentArrayAdapter<T> extends ArrayAdapter<T> {
    public AccentArrayAdapter(Context context, int resource, T[] objects) {
        super(context, resource, objects);
    }

    public AccentArrayAdapter(Context context, int resource, List<T> objects) {
        super(context, resource, objects);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = super.getView(position, convertView, parent);
        if (view instanceof TextView) ((TextView) view).setTextColor(AppThemeState.getCurrentAccentArgb());
        return view;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        View view = super.getDropDownView(position, convertView, parent);
        if (view instanceof TextView) ((TextView) view).setTextColor(AppThemeState.getCurrentAccentArgb());
        return view;
    }
}
