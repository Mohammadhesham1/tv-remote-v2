package com.tvremote;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Pairing screen: shown when TV is found but not yet paired.
 * Shows the 6-char code input after connecting to TV pairing port.
 */
public class PairDeviceActivity extends AppCompatActivity {

    private Prefs prefs;
    private AtvCertManager certManager;
    private AtvPairing pairing;

    private TextView tvStatus;
    private EditText etCode;
    private Button btnSubmit;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pair_device);

        prefs = new Prefs(this);
        tvStatus = findViewById(R.id.tvPairStatus);
        etCode = findViewById(R.id.etPairCode);
        btnSubmit = findViewById(R.id.btnSubmitCode);
        progressBar = findViewById(R.id.pairProgress);

        // Hide code input until TV shows code
        etCode.setVisibility(View.GONE);
        btnSubmit.setVisibility(View.GONE);

        findViewById(R.id.btnScanDevices).setOnClickListener(v ->
            startActivity(new Intent(this, DevicesActivity.class)));

        findViewById(R.id.btnStartPairing).setOnClickListener(v -> startPairing());

        btnSubmit.setOnClickListener(v -> {
            String code = etCode.getText().toString().trim().toUpperCase();
            if (code.length() == 6) {
                submitCode(code);
            } else {
                Toast.makeText(this, "أدخل الكود المكوّن من 6 أرقام/حروف", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnSkip).setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        // If IP already set, show start pairing button
        String ip = prefs.getTvIp();
        if (!ip.isEmpty()) {
            tvStatus.setText("التليفزيون: " + ip + "\nاضغط 'بدء الربط'");
        } else {
            tvStatus.setText("ابحث عن تليفزيون أولاً");
        }
    }

    private void startPairing() {
        String ip = prefs.getTvIp();
        if (ip.isEmpty()) {
            Toast.makeText(this, "اختر تليفزيون أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("جارٍ الاتصال بـ " + ip + "...");

        certManager = new AtvCertManager(this);
        try {
            certManager.init();
        } catch (Exception e) {
            tvStatus.setText("خطأ في الشهادة: " + e.getMessage());
            progressBar.setVisibility(View.GONE);
            return;
        }

        pairing = new AtvPairing(ip, certManager, new AtvPairing.PairingCallback() {
            @Override
            public void onCodeRequired() {
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("ظهر كود على شاشة التليفزيون\nأدخله هنا:");
                etCode.setVisibility(View.VISIBLE);
                btnSubmit.setVisibility(View.VISIBLE);
                etCode.requestFocus();
            }

            @Override
            public void onSuccess() {
                progressBar.setVisibility(View.GONE);
                prefs.setTvPaired(true);
                tvStatus.setText("✅ تم الربط بنجاح!");
                Toast.makeText(PairDeviceActivity.this, "تم الربط!", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(PairDeviceActivity.this, MainActivity.class));
                finish();
            }

            @Override
            public void onError(String message) {
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("❌ خطأ: " + message);
                etCode.setText("");
            }
        });

        pairing.startPairing();
    }

    private void submitCode(String code) {
        if (pairing == null) { startPairing(); return; }
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("جارٍ التحقق من الكود...");
        pairing.submitCode(code);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String ip = prefs.getTvIp();
        if (!ip.isEmpty() && tvStatus != null &&
            !tvStatus.getText().toString().contains("الاتصال") &&
            !tvStatus.getText().toString().contains("الكود")) {
            tvStatus.setText("التليفزيون: " + ip + "\nاضغط 'بدء الربط'");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pairing != null) pairing.close();
    }
}
