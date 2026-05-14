package com.tvremote;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Back arrow
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Navigate to sub-screens
        findViewById(R.id.rowLayout).setOnClickListener(v ->
            startActivity(new Intent(this, LayoutSettingsActivity.class)));

        findViewById(R.id.rowBehavior).setOnClickListener(v ->
            startActivity(new Intent(this, BehaviorSettingsActivity.class)));

        findViewById(R.id.rowApps).setOnClickListener(v ->
            startActivity(new Intent(this, AppsSettingsActivity.class)));

        findViewById(R.id.rowDevices).setOnClickListener(v ->
            startActivity(new Intent(this, DevicesActivity.class)));
    }
}
