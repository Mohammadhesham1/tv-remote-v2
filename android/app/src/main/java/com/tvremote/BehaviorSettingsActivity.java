package com.tvremote;

import android.os.Bundle;
import android.widget.Switch;
import androidx.appcompat.app.AppCompatActivity;

public class BehaviorSettingsActivity extends AppCompatActivity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_behavior_settings);

        prefs = new Prefs(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        Switch swHaptic   = findViewById(R.id.swHaptic);
        Switch swSmooth   = findViewById(R.id.swSmooth);
        Switch swKeepScr  = findViewById(R.id.swKeepScreen);
        Switch swTheater  = findViewById(R.id.swTheater);

        swHaptic.setChecked(prefs.isHaptic());
        swSmooth.setChecked(prefs.isSmooth());
        swKeepScr.setChecked(prefs.isKeepScreen());
        swTheater.setChecked(prefs.isTheater());

        swHaptic.setOnCheckedChangeListener((v, c) -> prefs.setHaptic(c));
        swSmooth.setOnCheckedChangeListener((v, c) -> prefs.setSmooth(c));
        swKeepScr.setOnCheckedChangeListener((v, c) -> prefs.setKeepScreen(c));
        swTheater.setOnCheckedChangeListener((v, c) -> prefs.setTheater(c));
    }
}
