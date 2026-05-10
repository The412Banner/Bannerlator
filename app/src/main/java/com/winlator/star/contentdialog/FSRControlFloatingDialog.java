package com.winlator.star.contentdialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import com.winlator.star.R;
import com.winlator.star.XServerDisplayActivity;
import com.winlator.star.renderer.GLRenderer;
import com.winlator.star.renderer.effects.FSREffect;
import com.winlator.star.renderer.effects.HDREffect;
import com.winlator.star.widget.SeekBar;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FSRControlFloatingDialog extends Dialog {

    private final XServerDisplayActivity activity;
    private final GLRenderer renderer;
    
    private final SeekBar sbFSR;
    private final Switch swFSR;
    private final Spinner spUpscalerMode;
    private final TextView lblSharpnessHeader;
    private final Switch swHDR;

    public FSRControlFloatingDialog(XServerDisplayActivity activity) {
        super(activity);
        this.activity = activity;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.fsr_control_dialog);

        Window window = getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 100;
            params.y = 100;
            window.setAttributes(params);
        }

        spUpscalerMode = findViewById(R.id.SPUpscalerMode);
        swFSR = findViewById(R.id.SWEnableFSR);
        sbFSR = findViewById(R.id.SBSharpness);
        lblSharpnessHeader = findViewById(R.id.LBLSharpnessHeader);
        swHDR = findViewById(R.id.SWEnableHDR);
        Button btClose = findViewById(R.id.BTClose);
        TextView title = findViewById(R.id.LBLTitle);

        renderer = activity.getXServerView().getRenderer();
        if (renderer == null) return;

        List<String> modes = new ArrayList<>();
        modes.add("Super Resolution"); 
        modes.add("DLS (Color Boost)"); 

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, modes) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.parseColor("#00E5FF")); 
                view.setTextSize(14);
                view.setTypeface(null, android.graphics.Typeface.BOLD);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setBackgroundColor(Color.parseColor("#333333"));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spUpscalerMode.setAdapter(adapter);

        FSREffect fsr = (FSREffect) renderer.getEffectComposer().getEffect(FSREffect.class);
        if (fsr != null) {
            swFSR.setChecked(true);
            
            int modeIndex = (fsr.getMode() == FSREffect.MODE_DLS) ? 1 : 0;
            spUpscalerMode.setSelection(modeIndex);

            float level = fsr.getLevel(); 
            int progress = (int)((level - 1.0f) * 25.0f);
            sbFSR.setValue(progress);
            updateSharpnessLabel(level);
        } else {
            swFSR.setChecked(false);
            spUpscalerMode.setSelection(0); 
            sbFSR.setValue(0); 
            updateSharpnessLabel(1.0f);
        }

        HDREffect hdr = (HDREffect) renderer.getEffectComposer().getEffect(HDREffect.class);
        swHDR.setChecked(hdr != null);

        spUpscalerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (swFSR.isChecked()) updateLive();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        sbFSR.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_MOVE) v.post(this::updateLive);
            return false;
        });
        
        swFSR.setOnClickListener(v -> updateLive());
        swHDR.setOnClickListener(v -> updateLive());

        title.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (window == null) return false;
                WindowManager.LayoutParams params = window.getAttributes();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        window.setAttributes(params);
                        return true;
                }
                return false;
            }
        });

        btClose.setOnClickListener(v -> dismiss());
    }

    private void updateSharpnessLabel(float level) {
        activity.runOnUiThread(() -> lblSharpnessHeader.setText(String.format(Locale.US, "Strength: %.0f", level)));
    }

    private void updateLive() {
        if (renderer == null || renderer.getEffectComposer() == null) return;

        float sliderVal = sbFSR.getValue(); 
        float level = 1.0f;
        if (sliderVal > 12) level = 2.0f;
        if (sliderVal > 37) level = 3.0f;
        if (sliderVal > 62) level = 4.0f;
        if (sliderVal > 87) level = 5.0f;
        
        updateSharpnessLabel(level);

        FSREffect currentFsr = (FSREffect) renderer.getEffectComposer().getEffect(FSREffect.class);
        if (currentFsr != null) renderer.getEffectComposer().removeEffect(currentFsr);

        if (swFSR.isChecked()) {
            FSREffect newFsr = new FSREffect();
            newFsr.setLevel(level);
            
            int selectedMode = spUpscalerMode.getSelectedItemPosition();
            newFsr.setMode(selectedMode == 1 ? FSREffect.MODE_DLS : FSREffect.MODE_SUPER_RESOLUTION);
            
            renderer.getEffectComposer().addEffect(newFsr);
        }

        HDREffect currentHdr = (HDREffect) renderer.getEffectComposer().getEffect(HDREffect.class);
        if (currentHdr != null) renderer.getEffectComposer().removeEffect(currentHdr);

        if (swHDR.isChecked()) {
            HDREffect newHdr = new HDREffect();
            newHdr.setStrength(1.0f);
            renderer.getEffectComposer().addEffect(newHdr);
        }
    }
}