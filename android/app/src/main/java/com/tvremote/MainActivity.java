package com.tvremote;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private RemoteConnection connection;
    private AtvCertManager certManager;
    private Prefs prefs;
    private Vibrator vibrator;

    private int volume = 50;
    private int channel = 1;
    private boolean muted = false;
    private boolean powered = true;

    private TextView tvDeviceName;
    private TextView tvStatus;
    private View layoutPoweredOff;
    private View layoutRemote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        volume = prefs.getVolume();
        channel = prefs.getChannel();

        bindViews();
        setupButtons();

        // Init cert manager
        certManager = new AtvCertManager(this);
        try { certManager.init(); } catch (Exception e) {
            showToast("خطأ في الشهادة: " + e.getMessage());
        }

        // If not paired or no IP, go to pairing
        if (prefs.getTvIp().isEmpty() || !prefs.isTvPaired()) {
            startActivity(new Intent(this, PairDeviceActivity.class));
        } else {
            connectToTv();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPrefs();
        tvDeviceName.setText(prefs.getTvName());
        if (!prefs.getTvIp().isEmpty() && prefs.isTvPaired() &&
            (connection == null || !connection.isConnected())) {
            connectToTv();
        }
    }

    private void connectToTv() {
        if (certManager == null) return;
        String ip = prefs.getTvIp();
        if (ip.isEmpty()) return;

        tvStatus.setText("جارٍ الاتصال...");

        connection = new RemoteConnection(ip, certManager, new RemoteConnection.ConnectionCallback() {
            @Override public void onConnected() {
                tvStatus.setText("متصل ✓");
            }
            @Override public void onDisconnected() {
                tvStatus.setText("انقطع الاتصال");
                // Auto reconnect after 3s
                new android.os.Handler().postDelayed(() -> {
                    if (prefs.isTvPaired()) connectToTv();
                }, 3000);
            }
            @Override public void onError(String message) {
                tvStatus.setText("خطأ: " + message);
            }
        });
        connection.connect();
    }

    private void bindViews() {
        tvDeviceName   = findViewById(R.id.tvDeviceName);
        tvStatus       = findViewById(R.id.tvStatus);
        layoutPoweredOff = findViewById(R.id.layoutPoweredOff);
        layoutRemote   = findViewById(R.id.layoutRemote);
    }

    private void applyPrefs() {
        if (prefs.isKeepScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        tvDeviceName.setText(prefs.getTvName());
    }

    private void setupButtons() {
        // Power
        findViewById(R.id.btnPower).setOnClickListener(v -> {
            vibrate(); showPowerDialog();
        });

        // Settings
        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            vibrate();
            startActivity(new Intent(this, SettingsActivity.class));
        });

        // Device name
        tvDeviceName.setOnClickListener(v -> {
            vibrate();
            startActivity(new Intent(this, DevicesActivity.class));
        });

        // D-Pad
        findViewById(R.id.btnUp).setOnClickListener(v    -> sendKey(RemoteConnection.KEY_DPAD_UP));
        findViewById(R.id.btnDown).setOnClickListener(v  -> sendKey(RemoteConnection.KEY_DPAD_DOWN));
        findViewById(R.id.btnLeft).setOnClickListener(v  -> sendKey(RemoteConnection.KEY_DPAD_LEFT));
        findViewById(R.id.btnRight).setOnClickListener(v -> sendKey(RemoteConnection.KEY_DPAD_RIGHT));
        findViewById(R.id.btnOk).setOnClickListener(v    -> sendKey(RemoteConnection.KEY_DPAD_OK));

        // Volume
        findViewById(R.id.btnVolUp).setOnClickListener(v -> {
            vibrate(); volume = Math.min(100, volume + 5);
            prefs.setVolume(volume);
            sendKey(RemoteConnection.KEY_VOL_UP);
            showToast("صوت: " + volume);
        });
        findViewById(R.id.btnVolDown).setOnClickListener(v -> {
            vibrate(); volume = Math.max(0, volume - 5);
            prefs.setVolume(volume);
            sendKey(RemoteConnection.KEY_VOL_DOWN);
            showToast("صوت: " + volume);
        });
        findViewById(R.id.btnMute).setOnClickListener(v -> {
            vibrate(); muted = !muted;
            sendKey(RemoteConnection.KEY_MUTE);
            showToast(muted ? "صامت" : "صوت مفعّل");
            ((Button) v).setText(muted ? "🔇" : "🔊");
        });

        // Channel
        findViewById(R.id.btnChUp).setOnClickListener(v -> {
            vibrate(); channel = Math.min(999, channel + 1);
            prefs.setChannel(channel);
            sendKey(RemoteConnection.KEY_CH_UP);
            showToast("القناة: " + channel);
        });
        findViewById(R.id.btnChDown).setOnClickListener(v -> {
            vibrate(); channel = Math.max(1, channel - 1);
            prefs.setChannel(channel);
            sendKey(RemoteConnection.KEY_CH_DOWN);
            showToast("القناة: " + channel);
        });

        // Navigation
        findViewById(R.id.btnHome).setOnClickListener(v -> sendKey(RemoteConnection.KEY_HOME));
        findViewById(R.id.btnBack).setOnClickListener(v -> sendKey(RemoteConnection.KEY_BACK));
        findViewById(R.id.btnMenu).setOnClickListener(v -> sendKey(RemoteConnection.KEY_MENU));

        // Keyboard
        findViewById(R.id.btnKeyboard).setOnClickListener(v -> { vibrate(); showKeyboardDialog(); });
        // Mic
        findViewById(R.id.btnMic).setOnClickListener(v -> { vibrate(); showToast("جارٍ الاستماع..."); });

        // App shortcuts
        setupAppButton(R.id.btnNetflix, "https://www.netflix.com/title",       "Netflix");
        setupAppButton(R.id.btnYoutube, "https://www.youtube.com/",            "YouTube");
        setupAppButton(R.id.btnPrime,   "https://app.primevideo.com/",         "Prime Video");
        setupAppButton(R.id.btnDisney,  "https://www.disneyplus.com/",         "Disney+");
        setupAppButton(R.id.btnAppleTv, "https://tv.apple.com/",               "Apple TV");
        setupAppButton(R.id.btnHbo,     "https://play.hbomax.com/",            "HBO Max");

        // Media controls
        findViewById(R.id.btnPlayPause).setOnClickListener(v -> sendKey(RemoteConnection.KEY_PLAY_PAUSE));
        findViewById(R.id.btnRewind).setOnClickListener(v    -> sendKey(RemoteConnection.KEY_REWIND));
        findViewById(R.id.btnFastFwd).setOnClickListener(v   -> sendKey(RemoteConnection.KEY_FAST_FWD));
        findViewById(R.id.btnPrev).setOnClickListener(v      -> sendKey(RemoteConnection.KEY_PREV));
        findViewById(R.id.btnNext).setOnClickListener(v      -> sendKey(RemoteConnection.KEY_NEXT));

        // Color buttons
        findViewById(R.id.btnRed).setOnClickListener(v    -> { vibrate(); sendKey(RemoteConnection.KEY_RED); });
        findViewById(R.id.btnGreen).setOnClickListener(v  -> { vibrate(); sendKey(RemoteConnection.KEY_GREEN); });
        findViewById(R.id.btnYellow).setOnClickListener(v -> { vibrate(); sendKey(RemoteConnection.KEY_YELLOW); });
        findViewById(R.id.btnBlue).setOnClickListener(v   -> { vibrate(); sendKey(RemoteConnection.KEY_BLUE); });

        // Number pad
        int[] numIds = {R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                        R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9};
        int[] keyCodes = {
            RemoteConnection.KEY_0, RemoteConnection.KEY_1, RemoteConnection.KEY_2,
            RemoteConnection.KEY_3, RemoteConnection.KEY_4, RemoteConnection.KEY_5,
            RemoteConnection.KEY_6, RemoteConnection.KEY_7, RemoteConnection.KEY_8,
            RemoteConnection.KEY_9
        };
        for (int i = 0; i < numIds.length; i++) {
            final int kc = keyCodes[i];
            final int num = i;
            findViewById(numIds[i]).setOnClickListener(v -> {
                vibrate();
                sendKey(kc);
                showToast("رقم " + num);
            });
        }

        // Power-off screen turn on
        findViewById(R.id.btnTurnOn).setOnClickListener(v -> {
            powered = true;
            layoutPoweredOff.setVisibility(View.GONE);
            layoutRemote.setVisibility(View.VISIBLE);
            sendKey(RemoteConnection.KEY_POWER);
            showToast("جارٍ تشغيل التلفزيون...");
        });
    }

    private void setupAppButton(int viewId, String deepLink, String appName) {
        View btn = findViewById(viewId);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            vibrate();
            if (connection != null && connection.isConnected()) {
                // Send HOME first then the app will open via the TV's own launcher
                // For now send HOME key + show toast (deep link launch needs extra impl)
                showToast("جارٍ فتح " + appName);
            } else {
                showToast("غير متصل بالتليفزيون");
            }
        });
    }

    private void sendKey(int keyCode) {
        vibrate();
        if (connection == null || !connection.isConnected()) {
            showToast("غير متصل — تأكد من الربط");
            return;
        }
        connection.sendKey(keyCode);
    }

    private void vibrate() {
        if (!prefs.isHaptic() || vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(18);
        }
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void showPowerDialog() {
        new AlertDialog.Builder(this)
            .setTitle("تحكم في التلفزيون")
            .setItems(new String[]{"إيقاف التشغيل", "تشغيل"}, (d, which) -> {
                if (which == 0) {
                    powered = false;
                    sendKey(RemoteConnection.KEY_POWER);
                    layoutRemote.setVisibility(View.GONE);
                    layoutPoweredOff.setVisibility(View.VISIBLE);
                } else {
                    powered = true;
                    sendKey(RemoteConnection.KEY_POWER);
                }
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void showKeyboardDialog() {
        final EditText input = new EditText(this);
        input.setHint("اكتب للتليفزيون...");
        input.setPadding(40, 20, 40, 20);
        new AlertDialog.Builder(this)
            .setTitle("لوحة المفاتيح")
            .setView(input)
            .setPositiveButton("إرسال", (d, w) -> {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) showToast("أُرسل: " + text);
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (connection != null) connection.disconnect();
    }
}
