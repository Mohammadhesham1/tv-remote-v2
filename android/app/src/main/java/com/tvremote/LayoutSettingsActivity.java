package com.tvremote;

import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.appcompat.app.AppCompatActivity;

public class LayoutSettingsActivity extends AppCompatActivity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_layout_settings);

        prefs = new Prefs(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        RadioGroup rgNav = findViewById(R.id.rgNavMode);
        RadioGroup rgQa  = findViewById(R.id.rgQaMode);

        // Set saved nav mode
        String navMode = prefs.getNavMode();
        if ("touch".equals(navMode)) {
            ((RadioButton) findViewById(R.id.rbTouch)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.rbDpad)).setChecked(true);
        }

        // Set saved QA mode
        String qaMode = prefs.getQaMode();
        switch (qaMode) {
            case "color": ((RadioButton) findViewById(R.id.rbColor)).setChecked(true); break;
            case "media": ((RadioButton) findViewById(R.id.rbMedia)).setChecked(true); break;
            case "none":  ((RadioButton) findViewById(R.id.rbNone)).setChecked(true);  break;
            default:      ((RadioButton) findViewById(R.id.rbApps)).setChecked(true);  break;
        }

        rgNav.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.rbTouch) prefs.setNavMode("touch");
            else                    prefs.setNavMode("dpad");
        });

        rgQa.setOnCheckedChangeListener((g, id) -> {
            if      (id == R.id.rbColor) prefs.setQaMode("color");
            else if (id == R.id.rbMedia) prefs.setQaMode("media");
            else if (id == R.id.rbNone)  prefs.setQaMode("none");
            else                         prefs.setQaMode("apps");
        });
    }
}
