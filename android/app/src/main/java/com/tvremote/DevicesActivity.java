package com.tvremote;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class DevicesActivity extends AppCompatActivity {

    private Prefs prefs;
    private List<AtvDiscovery.TvDevice> foundDevices = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private List<String> displayList = new ArrayList<>();
    private ProgressBar progressBar;
    private TextView tvScanStatus;
    private AtvDiscovery discovery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_devices);

        prefs = new Prefs(this);
        progressBar = findViewById(R.id.progressBar);
        tvScanStatus = findViewById(R.id.tvScanStatus);

        ListView listView = findViewById(R.id.listDevices);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayList);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            AtvDiscovery.TvDevice device = foundDevices.get(position);
            confirmConnect(device);
        });

        discovery = new AtvDiscovery(this, new AtvDiscovery.DiscoveryListener() {
            @Override
            public void onDeviceFound(AtvDiscovery.TvDevice device) {
                if (!foundDevices.contains(device)) {
                    foundDevices.add(device);
                    displayList.add(device.toString());
                    adapter.notifyDataSetChanged();
                    tvScanStatus.setText("تم العثور على " + foundDevices.size() + " جهاز");
                }
            }

            @Override
            public void onDeviceLost(String name) {
                for (int i = 0; i < foundDevices.size(); i++) {
                    if (foundDevices.get(i).name.equals(name)) {
                        foundDevices.remove(i);
                        displayList.remove(i);
                        adapter.notifyDataSetChanged();
                        break;
                    }
                }
            }

            @Override
            public void onError(String message) {
                tvScanStatus.setText("خطأ: " + message);
                progressBar.setVisibility(View.GONE);
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnScan).setOnClickListener(v -> startScan());
        findViewById(R.id.btnManual).setOnClickListener(v -> showManualDialog());

        String currentIp = prefs.getTvIp();
        if (!currentIp.isEmpty()) {
            tvScanStatus.setText("متصل حالياً بـ " + currentIp);
        }
    }

    private void startScan() {
        foundDevices.clear();
        displayList.clear();
        adapter.notifyDataSetChanged();
        progressBar.setVisibility(View.VISIBLE);
        tvScanStatus.setText("جارٍ البحث عن أجهزة Android TV...");
        discovery.startDiscovery();

        // Stop after 15 seconds
        new android.os.Handler().postDelayed(() -> {
            discovery.stopDiscovery();
            progressBar.setVisibility(View.GONE);
            if (foundDevices.isEmpty()) {
                tvScanStatus.setText("لم يُعثر على أجهزة — تأكد من نفس الـ Wi-Fi");
            }
        }, 15000);
    }

    private void confirmConnect(AtvDiscovery.TvDevice device) {
        new AlertDialog.Builder(this)
            .setTitle("اتصال بـ " + device.name)
            .setMessage("هل تريد الاتصال بـ " + device.host + "؟")
            .setPositiveButton("اتصال", (d, w) -> connectDevice(device))
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void connectDevice(AtvDiscovery.TvDevice device) {
        prefs.setTvIp(device.host);
        prefs.setTvName(device.name);
        prefs.setTvPaired(false); // needs pairing
        Toast.makeText(this, "سيتم الربط مع " + device.name, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showManualDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("مثال: 192.168.1.100");
        input.setPadding(40, 20, 40, 20);
        new AlertDialog.Builder(this)
            .setTitle("إدخال IP يدوياً")
            .setView(input)
            .setPositiveButton("اتصال", (d, w) -> {
                String ip = input.getText().toString().trim();
                if (!ip.isEmpty()) {
                    prefs.setTvIp(ip);
                    prefs.setTvName("التيليفزيون");
                    prefs.setTvPaired(false);
                    Toast.makeText(this, "تم الحفظ: " + ip, Toast.LENGTH_SHORT).show();
                    finish();
                }
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discovery != null) discovery.stopDiscovery();
    }
}
