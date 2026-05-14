package com.tvremote;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import java.util.*;

public class AppsSettingsActivity extends AppCompatActivity {

    private Prefs prefs;
    private List<String> appList;
    private ArrayAdapter<String> adapter;

    private static final List<String> ALL_APPS = Arrays.asList(
        "Netflix", "YouTube", "Prime Video", "Disney+", "Apple TV", "HBO Max"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps_settings);

        prefs = new Prefs(this);
        appList = new ArrayList<>(prefs.getApps());

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            prefs.setApps(new HashSet<>(appList));
            finish();
        });

        ListView listView = findViewById(R.id.listApps);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, appList);
        listView.setAdapter(adapter);

        // Long press to remove
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String app = appList.get(position);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("إزالة تطبيق")
                .setMessage("هل تريد إزالة " + app + " من الاختصارات؟")
                .setPositiveButton("إزالة", (d, w) -> {
                    appList.remove(position);
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("إلغاء", null)
                .show();
            return true;
        });

        // Add app button
        findViewById(R.id.btnAddApp).setOnClickListener(v -> {
            List<String> available = new ArrayList<>();
            for (String a : ALL_APPS) {
                if (!appList.contains(a)) available.add(a);
            }
            if (available.isEmpty()) {
                Toast.makeText(this, "كل التطبيقات مضافة بالفعل", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] arr = available.toArray(new String[0]);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("إضافة تطبيق")
                .setItems(arr, (d, which) -> {
                    appList.add(arr[which]);
                    adapter.notifyDataSetChanged();
                })
                .show();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.setApps(new HashSet<>(appList));
    }
}
