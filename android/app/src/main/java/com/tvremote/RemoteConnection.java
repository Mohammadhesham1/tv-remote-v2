package com.tvremote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.*;

/**
 * Manages the ongoing control connection to Android TV (port 6466).
 * Uses the Android TV Remote Protocol v2 over TLS with Protobuf messages.
 */
public class RemoteConnection {

    private static final String TAG = "RemoteConnection";

    // Key codes from Android TV Remote Protocol v2
    public static final int KEY_DPAD_UP    = 19;
    public static final int KEY_DPAD_DOWN  = 20;
    public static final int KEY_DPAD_LEFT  = 21;
    public static final int KEY_DPAD_RIGHT = 22;
    public static final int KEY_DPAD_OK    = 23;
    public static final int KEY_VOL_UP     = 24;
    public static final int KEY_VOL_DOWN   = 25;
    public static final int KEY_POWER      = 26;
    public static final int KEY_MUTE       = 164;
    public static final int KEY_HOME       = 3;
    public static final int KEY_BACK       = 4;
    public static final int KEY_MENU       = 82;
    public static final int KEY_CH_UP      = 166;
    public static final int KEY_CH_DOWN    = 167;
    public static final int KEY_PLAY_PAUSE = 85;
    public static final int KEY_STOP       = 86;
    public static final int KEY_REWIND     = 89;
    public static final int KEY_FAST_FWD   = 90;
    public static final int KEY_NEXT       = 87;
    public static final int KEY_PREV       = 88;
    public static final int KEY_RED        = 183;
    public static final int KEY_GREEN      = 184;
    public static final int KEY_YELLOW     = 185;
    public static final int KEY_BLUE       = 186;
    public static final int KEY_0          = 7;
    public static final int KEY_1          = 8;
    public static final int KEY_2          = 9;
    public static final int KEY_3          = 10;
    public static final int KEY_4          = 11;
    public static final int KEY_5          = 12;
    public static final int KEY_6          = 13;
    public static final int KEY_7          = 14;
    public static final int KEY_8          = 15;
    public static final int KEY_9          = 16;

    public static final int ACTION_DOWN = 1;
    public static final int ACTION_UP   = 2;

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String message);
    }

    private String tvHost;
    private int tvPort = AtvProtocol.PORT_CONTROL;
    private final AtvCertManager certManager;
    private final ConnectionCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private boolean connected = false;
    private ScheduledFuture<?> readLoop;

    public RemoteConnection(String tvHost, AtvCertManager certManager, ConnectionCallback callback) {
        this.tvHost = tvHost;
        this.certManager = certManager;
        this.callback = callback;
    }

    public void setTvHost(String host) { this.tvHost = host; }
    public String getTvHost() { return tvHost; }
    public boolean isConnected() { return connected; }

    public void connect() {
        executor.execute(() -> {
            try {
                connectInternal();
            } catch (Exception e) {
                Log.e(TAG, "Connect error", e);
                connected = false;
                mainHandler.post(() -> callback.onError("فشل الاتصال: " + e.getMessage()));
            }
        });
    }

    private void connectInternal() throws Exception {
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(certManager.getKeyStore(), certManager.getKeyStorePassword());
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManagerAll()};
        sslCtx.init(kmf.getKeyManagers(), trustAll, new java.security.SecureRandom());

        SSLSocketFactory factory = sslCtx.getSocketFactory();
        SSLSocket ssl = (SSLSocket) factory.createSocket(tvHost, tvPort);
        ssl.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        ssl.setSoTimeout(20000);
        ssl.startHandshake();

        socket = ssl;
        in  = new DataInputStream(ssl.getInputStream());
        out = new DataOutputStream(ssl.getOutputStream());

        // Send configuration
        AtvProtocol.writeMessage(out, AtvProtocol.buildRemoteConfiguration());
        // Send set-active
        AtvProtocol.writeMessage(out, AtvProtocol.buildSetActive(622));

        connected = true;
        Log.d(TAG, "Control connected to " + tvHost);
        mainHandler.post(callback::onConnected);

        // Start read loop to handle pings
        startReadLoop();
    }

    private void startReadLoop() {
        executor.execute(() -> {
            while (connected) {
                try {
                    byte[] msg = AtvProtocol.readMessage(in);
                    int field = AtvProtocol.parseRemoteMessageField(msg);
                    if (field == 7) {
                        // ping -> respond with pong
                        int[] pingVals = AtvProtocol.parsePing(msg);
                        synchronized (this) {
                            AtvProtocol.writeMessage(out, AtvProtocol.buildPong(pingVals[0], pingVals[1]));
                        }
                    }
                } catch (Exception e) {
                    if (connected) {
                        Log.e(TAG, "Read loop error", e);
                        connected = false;
                        mainHandler.post(callback::onDisconnected);
                    }
                    break;
                }
            }
        });
    }

    /** Send a key press (down + up) */
    public void sendKey(int keyCode) {
        if (!connected) return;
        executor.execute(() -> {
            try {
                synchronized (this) {
                    AtvProtocol.writeMessage(out, AtvProtocol.buildKeyCommand(keyCode, ACTION_DOWN));
                    AtvProtocol.writeMessage(out, AtvProtocol.buildKeyCommand(keyCode, ACTION_UP));
                }
            } catch (Exception e) {
                Log.e(TAG, "sendKey error", e);
                connected = false;
                mainHandler.post(callback::onDisconnected);
            }
        });
    }

    public void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        executor.shutdown();
        scheduler.shutdown();
    }

    private static class X509TrustManagerAll implements javax.net.ssl.X509TrustManager {
        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
    }
}
