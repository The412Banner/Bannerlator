package com.winlator.star.fexcore;

import com.winlator.star.R;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.star.contents.ContentProfile;
import com.winlator.star.contents.ContentsManager;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.KeyValueSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class FEXCoreManager {
    public static void loadFEXCoreVersion(Context context, ContentsManager contentsManager, Spinner spinner, String fexcoreVersion) {
        String[] originalItems = context.getResources().getStringArray(R.array.fexcore_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        AppUtils.setSpinnerSelectionFromValue(spinner, fexcoreVersion);
    }
}
