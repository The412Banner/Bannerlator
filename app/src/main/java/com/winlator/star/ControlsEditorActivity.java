package com.winlator.star;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.star.R;
import com.winlator.star.contentdialog.ContentDialog;
import com.winlator.star.inputcontrols.Binding;
import com.winlator.star.inputcontrols.ControlElement;
import com.winlator.star.inputcontrols.ControlsProfile;
import com.winlator.star.inputcontrols.InputControlsManager;
import com.winlator.star.inputcontrols.CustomIconManager;
import com.winlator.star.math.Mathf;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.UnitUtils;
import com.winlator.star.widget.AccentArrayAdapter;
import com.winlator.star.widget.InputControlsView;
import com.winlator.star.widget.NumberPicker;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ControlsEditorActivity extends AppCompatActivity implements View.OnClickListener {
    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private CustomIconManager customIconManager;
    private ActivityResultLauncher<String> iconPickerLauncher;
    private ActivityResultLauncher<Intent> iconPickerInAppLauncher;
    private LinearLayout currentLLCustomIconList; // To refresh UI after picking

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        customIconManager = new CustomIconManager(this);
        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(0.6f);

        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));
        ((TextView)findViewById(R.id.TVProfileName)).setText(profile.getName());
        inputControlsView.setProfile(profile);

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        container.findViewById(R.id.BTAddElement).setOnClickListener(this);
        container.findViewById(R.id.BTRemoveElement).setOnClickListener(this);
        container.findViewById(R.id.BTElementSettings).setOnClickListener(this);

        // Custom-icon pickers: the built-in file picker (primary) and the system SAF picker (secondary).
        iconPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) addCustomIconFromUri(uri);
        });
        iconPickerInAppLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String path = result.getData().getStringExtra(FilePickerActivity.EXTRA_SELECTED_FILE);
                if (path != null) addCustomIconFromUri(Uri.fromFile(new java.io.File(path)));
            }
        });
    }

    // Shared: add a custom icon from any Uri (file:// from the in-app picker, content:// from SAF).
    private void addCustomIconFromUri(Uri uri) {
        if (currentLLCustomIconList != null) {
            customIconManager.addCustomIcon(uri);
            loadCustomIcons(currentLLCustomIconList, inputControlsView.getSelectedElement().getIconId());
        }
    }

    // Two-option chooser: built-in picker first, then system SAF.
    private void promptPickCustomIcon() {
        new android.app.AlertDialog.Builder(this)
            .setItems(new CharSequence[]{"Browse files", "Pick via system…"}, (d, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(this, FilePickerActivity.class);
                    intent.putExtra(FilePickerActivity.EXTRA_EXTENSIONS, new String[]{"png", "jpg", "jpeg", "webp", "bmp", "gif"});
                    intent.putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, "Select icon image");
                    iconPickerInAppLauncher.launch(intent);
                } else {
                    iconPickerLauncher.launch("image/*");
                }
            })
            .show();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.BTAddElement:
                if (!inputControlsView.addElement()) AppUtils.showToast(this, R.string.no_profile_selected);
                break;
            case R.id.BTRemoveElement:
                if (!inputControlsView.removeElement()) AppUtils.showToast(this, R.string.no_control_element_selected);
                break;
            case R.id.BTElementSettings:
                ControlElement selectedElement = inputControlsView.getSelectedElement();
                if (selectedElement != null) showControlElementSettings(v);
                else AppUtils.showToast(this, R.string.no_control_element_selected);
                break;
        }
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = inputControlsView.getSelectedElement();
        View view = LayoutInflater.from(this).inflate(R.layout.control_element_settings, null);

        final Runnable updateLayout = () -> {
            ControlElement.Type type = element.getType();
            View llShape = view.findViewById(R.id.LLShape);
            View cbToggle = view.findViewById(R.id.CBToggleSwitch);
            View llCustom = view.findViewById(R.id.LLCustomTextIcon);
            View llRange = view.findViewById(R.id.LLRangeOptions);

            if (llShape != null) llShape.setVisibility(type == ControlElement.Type.BUTTON ? View.VISIBLE : View.GONE);
            if (cbToggle != null) cbToggle.setVisibility(type == ControlElement.Type.BUTTON ? View.VISIBLE : View.GONE);
            if (llCustom != null) llCustom.setVisibility(type == ControlElement.Type.BUTTON ? View.VISIBLE : View.GONE);
            if (llRange != null) llRange.setVisibility(type == ControlElement.Type.RANGE_BUTTON ? View.VISIBLE : View.GONE);

            loadBindingSpinners(element, view);
        };

        loadTypeSpinner(element, view.findViewById(R.id.SType), updateLayout);
        loadShapeSpinner(element, view.findViewById(R.id.SShape));
        loadRangeSpinner(element, view.findViewById(R.id.SRange));

        RadioGroup rgOrientation = view.findViewById(R.id.RGOrientation);
        if (rgOrientation != null) {
            rgOrientation.check(element.getOrientation() == 1 ? R.id.RBVertical : R.id.RBHorizontal);
            rgOrientation.setOnCheckedChangeListener((group, checkedId) -> {
                element.setOrientation((byte)(checkedId == R.id.RBVertical ? 1 : 0));
                profile.save();
                inputControlsView.invalidate();
            });
        }

        NumberPicker npColumns = view.findViewById(R.id.NPColumns);
        if (npColumns != null) {
            npColumns.setValue(element.getBindingCount());
            npColumns.setOnValueChangeListener((numberPicker, value) -> {
                element.setBindingCount(value);
                profile.save();
                inputControlsView.invalidate();
            });
        }

        final TextView tvScale = view.findViewById(R.id.TVScale);
        SeekBar sbScale = view.findViewById(R.id.SBScale);
        if (sbScale != null) {
            sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (tvScale != null) tvScale.setText(progress+"%");
                    if (fromUser) {
                        progress = (int)Mathf.roundTo(progress, 5);
                        seekBar.setProgress(progress);
                        element.setScale(progress / 100.0f);
                        profile.save();
                        inputControlsView.invalidate();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            sbScale.setProgress((int)(element.getScale() * 100));
        }

        CheckBox cbToggleSwitch = view.findViewById(R.id.CBToggleSwitch);
        if (cbToggleSwitch != null) {
            cbToggleSwitch.setChecked(element.isToggleSwitch());
            cbToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                element.setToggleSwitch(isChecked);
                profile.save();
            });
        }

        final EditText etCustomText = view.findViewById(R.id.ETCustomText);
        if (etCustomText != null) etCustomText.setText(element.getText());

        // LOAD BOTH ICON LISTS
        final LinearLayout llIconList = view.findViewById(R.id.LLIconList);
        if (llIconList != null) loadIcons(llIconList, element.getIconId());

        currentLLCustomIconList = view.findViewById(R.id.LLCustomIconList);
        if (currentLLCustomIconList != null) loadCustomIcons(currentLLCustomIconList, element.getIconId());

        View btAddIcon = view.findViewById(R.id.BTAddCustomIcon);
        if (btAddIcon != null) btAddIcon.setOnClickListener(v -> promptPickCustomIcon());

        updateLayout.run();

        PopupWindow popupWindow = AppUtils.showPopupWindow(anchorView, view, 340, 0);
        popupWindow.setOnDismissListener(() -> {
            if (etCustomText != null) element.setText(etCustomText.getText().toString().trim());
            
            // Check both lists for selection
            short selectedIconId = 0;
            if (llIconList != null) selectedIconId = getSelectedIdFromList(llIconList);
            if (selectedIconId == 0 && currentLLCustomIconList != null) selectedIconId = getSelectedIdFromList(currentLLCustomIconList);

            element.setIconId((byte)selectedIconId);
            profile.save();
            inputControlsView.invalidate();
        });
    }

    private short getSelectedIdFromList(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.isSelected()) return (short)child.getTag();
        }
        return 0;
    }

    private void loadIcons(final LinearLayout parent, int selectedId) {
        parent.removeAllViews();
        List<Byte> iconIds = new ArrayList<>();
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            for (String file : filenames) iconIds.add(Byte.parseByte(FileUtils.getBasename(file)));
        } catch (Exception e) {}
        Collections.sort(iconIds);
        addIconViewsToParent(parent, iconIds, selectedId, false);
    }

    private void loadCustomIcons(final LinearLayout parent, int selectedId) {
        parent.removeAllViews();
        List<Short> iconIds = customIconManager.getCustomIconIds();
        addIconViewsToParent(parent, iconIds, selectedId, true);
    }

    private void addIconViewsToParent(LinearLayout parent, List<? extends Number> ids, int selectedId, boolean isCustom) {
        int size = (int)UnitUtils.dpToPx(40);
        int margin = (int)UnitUtils.dpToPx(2);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);

        for (Number idObj : ids) {
            final short id = idObj.shortValue();
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(params);
            imageView.setPadding(4, 4, 4, 4);
            imageView.setBackgroundResource(R.drawable.icon_background);
            imageView.setTag(id);
            imageView.setSelected(id == selectedId);

            if (isCustom) imageView.setImageBitmap(customIconManager.loadIcon(id));
            else {
                try (InputStream is = getAssets().open("inputcontrols/icons/" + id + ".png")) {
                    imageView.setImageBitmap(BitmapFactory.decodeStream(is));
                } catch (IOException e) {}
            }

            imageView.setOnClickListener(v -> {
                // Deselect others in BOTH lists
                View root = (View) parent.getParent().getParent().getParent();
                clearSelection((LinearLayout) root.findViewById(R.id.LLIconList));
                clearSelection((LinearLayout) root.findViewById(R.id.LLCustomIconList));
                v.setSelected(true);
            });
            parent.addView(imageView);
        }
    }

    private void clearSelection(LinearLayout layout) {
        if (layout == null) return;
        for (int i = 0; i < layout.getChildCount(); i++) layout.getChildAt(i).setSelected(false);
    }

    // --- REMAINDER OF YOUR SPINNER/BINDING LOGIC ---
    private void loadTypeSpinner(final ControlElement element, Spinner spinner, Runnable callback) {
        if (spinner == null) return;
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Type.names()));
        spinner.setSelection(element.getType().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setType(ControlElement.Type.values()[position]);
                profile.save();
                callback.run();
                inputControlsView.invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        if (spinner == null) return;
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names()));
        spinner.setSelection(element.getShape().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setShape(ControlElement.Shape.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        if (spinner == null) return;
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Range.names()));
        spinner.setSelection(element.getRange().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setRange(ControlElement.Range.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadBindingSpinners(ControlElement element, View view) {
        LinearLayout container = view.findViewById(R.id.LLBindings);
        if (container == null) return;
        container.removeAllViews();
        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) loadBindingSpinner(element, container, 0, R.string.binding);
        else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD) {
            loadBindingSpinner(element, container, 0, R.string.binding_up);
            loadBindingSpinner(element, container, 1, R.string.binding_right);
            loadBindingSpinner(element, container, 2, R.string.binding_down);
            loadBindingSpinner(element, container, 3, R.string.binding_left);
        }
    }

    private void loadBindingSpinner(final ControlElement element, LinearLayout container, final int index, int titleResId) {
        View view = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);
        ((TextView)view.findViewById(R.id.TVTitle)).setText(titleResId);
        final Spinner sBindingType = view.findViewById(R.id.SBindingType);
        final Spinner sBinding = view.findViewById(R.id.SBinding);

        // Set the binding-type adapter in code (was android:entries in XML) so it uses
        // our blue-text item layouts and stays readable on a black background.
        // AccentArrayAdapter routes binding_spinner_item's colorPrimary text to the
        // runtime theme accent (was static #0055FF baked at inflation).
        AccentArrayAdapter<CharSequence> typeAdapter = new AccentArrayAdapter<>(
                this, R.layout.binding_spinner_item, getResources().getTextArray(R.array.binding_type_entries));
        typeAdapter.setDropDownViewResource(R.layout.binding_spinner_dropdown_item);
        sBindingType.setAdapter(typeAdapter);

        Runnable update = () -> {
            String[] bindingEntries = null;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0: bindingEntries = Binding.keyboardBindingLabels(); break;
                case 1: bindingEntries = Binding.mouseBindingLabels(); break;
                case 2: bindingEntries = Binding.gamepadBindingLabels(); break;
            }
            AccentArrayAdapter<String> bindingAdapter =
                    new AccentArrayAdapter<>(this, R.layout.binding_spinner_item, bindingEntries);
            bindingAdapter.setDropDownViewResource(R.layout.binding_spinner_dropdown_item);
            sBinding.setAdapter(bindingAdapter);
            AppUtils.setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).toString());
        };

        sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { update.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        Binding selectedBinding = element.getBindingAt(index);
        if (selectedBinding.isKeyboard()) sBindingType.setSelection(0, false);
        else if (selectedBinding.isMouse()) sBindingType.setSelection(1, false);
        else if (selectedBinding.isGamepad()) sBindingType.setSelection(2, false);

        sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Binding binding = Binding.NONE;
                switch (sBindingType.getSelectedItemPosition()) {
                    case 0: binding = Binding.keyboardBindingValues()[position]; break;
                    case 1: binding = Binding.mouseBindingValues()[position]; break;
                    case 2: binding = Binding.gamepadBindingValues()[position]; break;
                }
                if (binding != element.getBindingAt(index)) {
                    element.setBindingAt(index, binding);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        update.run();
        container.addView(view);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_down, R.anim.slide_out_up);
    }
}
