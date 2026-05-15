package com.tvremote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.*;

/**
 * Control connection to Android TV on port 6466.
 *
 * Sequence (from Aymkdn wiki):
 *   1. TLS connect
 *   2. Receive server's config message
 *   3. Send our config message (1st)
 *   4. Receive server ack [10,3,8,255,4] then [18,0]
 *   5. Send 2nd config [18,3,8,238,4]
 *   6. Ready — start read loop for pings
 */
public class RemoteConnection {

    private static final String TAG = "RemoteConnection";

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
    public static final int ACTION_SHORT = 3; // for single-press keys like CH_UP/DOWN

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String message);
    }

    private String tvHost;
    private final AtvCertManager    certManager;
    private final ConnectionCallback callback;
    private final Handler            mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService    executor    = Executors.newCachedThreadPool();

    private SSLSocket        sslSocket;
    private DataInputStream  in;
    private DataOutputStream out;
    private volatile boolean connected = false;

    public RemoteConnection(String tvHost, AtvCertManager certManager, ConnectionCallback callback) {
        this.tvHost      = tvHost;
        this.certManager = certManager;
        this.callback    = callback;
    }

    public void setTvHost(String host) { this.tvHost = host; }
    public String getTvHost()          { return tvHost; }
    public boolean isConnected()       { return connected; }

    public void connect() {
        executor.execute(() -> {
            try {
                connectInternal();
            } catch (Exception e) {
                Log.e(TAG, "Connect error: " + e.getMessage(), e);
                connected = false;
                mainHandler.post(() -> callback.onError("فشل الاتصال: " + e.getMessage()));
            }
        });
    }

    private void connectInternal() throws Exception {
        Log.d(TAG, "Connecting to " + tvHost + ":" + AtvProtocol.PORT_CONTROL);

        // TLS setup
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(certManager.getKeyStore(), certManager.getKeyStorePassword());

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(),
                new TrustManager[]{new TrustAllManager()},
                new java.security.SecureRandom());

        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(tvHost, AtvProtocol.PORT_CONTROL), 5000);
        plain.setSoTimeout(30000);

        sslSocket = (SSLSocket) sslCtx.getSocketFactory()
                .createSocket(plain, tvHost, AtvProtocol.PORT_CONTROL, true);
        sslSocket.setUseClientMode(true);
        sslSocket.startHandshake();

        in  = new DataInputStream(sslSocket.getInputStream());
        out = new DataOutputStream(sslSocket.getOutputStream());
        Log.d(TAG, "TLS control connected");

        // Step 1: Receive server's config message
        byte[] serverConfig = AtvProtocol.readMessage(in);
        Log.d(TAG, "← server config (" + serverConfig.length + " bytes)");

        // Step 2: Send our 1st config message
        // [10,34,8,238,4,18,29,24,1,34,1,49,42,15,androidtv-remote,50,5,1.0.0]
        byte[] config1 = buildControlConfig1();
        AtvProtocol.writeMessage(out, config1);
        Log.d(TAG, "→ config1");

        // Step 3: Read server ack [10,3,8,255,4]
        byte[] ack1 = AtvProtocol.readMessage(in);
        Log.d(TAG, "← ack1 (" + ack1.length + " bytes)");

        // Step 4: Read [18,0]
        byte[] ack2 = AtvProtocol.readMessage(in);
        Log.d(TAG, "← ack2 (" + ack2.length + " bytes)");

        // Step 5: Send 2nd config [18,3,8,238,4]
        AtvProtocol.writeMessage(out, new byte[]{18, 3, 8, (byte)238, 4});
        Log.d(TAG, "→ config2");

        // Step 6: Drain any initial server messages with short timeout
        sslSocket.setSoTimeout(2000);
        for (int i = 0; i < 10; i++) {
            try {
                byte[] info = AtvProtocol.readMessage(in);
                Log.d(TAG, "u2190 server info " + i + " (" + info.length + " bytes)");
            } catch (java.net.SocketTimeoutException e) {
                Log.d(TAG, "No more initial messages after " + i);
                break;
            }
        }
        sslSocket.setSoTimeout(0); // infinite for read loop

        connected = true;
        Log.d(TAG, "Control ready");
        mainHandler.post(callback::onConnected);

        startReadLoop();
    }

    /**
     * 1st config message — from wiki example:
     * [36][10,34,8,238,4,18,29,24,1,34,1,49,42,15,androidtv-remote,50,5,1.0.0]
     */
    private byte[] buildControlConfig1() {
        byte[] appName    = "androidtv-remote".getBytes();  // 15 bytes
        byte[] appVersion = "1.0.0".getBytes();             // 5 bytes

        // inner = [8,238,4] = protocol 622
        // then [18, subLen, 24,1, 34,1,49, 42,appNameLen,..., 50,appVerLen,...]
        byte[] sub = concat(
            new byte[]{24, 1},                              // field3=1
            new byte[]{34, 1, 49},                          // field4="1" (version char)
            new byte[]{42, (byte)appName.length}, appName,  // field5=package
            new byte[]{50, (byte)appVersion.length}, appVersion // field6=version
        );
        byte[] inner = concat(
            new byte[]{8, (byte)238, 4},                    // field1=622
            new byte[]{18, (byte)sub.length}, sub
        );
        return concat(new byte[]{10, (byte)inner.length}, inner);
    }

    private void startReadLoop() {
        executor.execute(() -> {
            while (connected) {
                try {
                    byte[] msg = AtvProtocol.readMessage(in);
                    // Check for ping: starts with [66,6,...] per wiki
                    if (msg.length >= 2 && (msg[0] & 0xFF) == 66) {
                        // Respond with pong [74,2,8,25]
                        synchronized (this) {
                            out.write(new byte[]{74, 2, 8, 25});
                            out.flush();
                        }
                        Log.d(TAG, "ping → pong");
                    }
                } catch (Exception e) {
                    if (connected) {
                        Log.e(TAG, "Read loop: " + e.getMessage());
                        connected = false;
                        mainHandler.post(callback::onDisconnected);
                    }
                    break;
                }
            }
        });
    }

    public void sendKey(int keyCode) {
        if (!connected) return;
        executor.execute(() -> {
            try {
                synchronized (this) {
                    // From wiki: [82,4,8,keyCode,16,1] then [82,4,8,keyCode,16,2]
                    AtvProtocol.writeMessage(out, buildKeyMsg(keyCode, ACTION_DOWN));
                    AtvProtocol.writeMessage(out, buildKeyMsg(keyCode, ACTION_UP));
                }
            } catch (Exception e) {
                Log.e(TAG, "sendKey error: " + e.getMessage());
                connected = false;
                mainHandler.post(callback::onDisconnected);
            }
        });
    }

    /**
     * Key command from wiki: [82,4,8,keyCode,16,action]
     * field 10 (remote_key_inject) tag=82
     */
    private byte[] buildKeyMsg(int keyCode, int action) {
        return new byte[]{
            82, 4,                    // field10, length=4
            8, (byte)keyCode,         // field1=keyCode
            16, (byte)action          // field2=action
        };
    }

    private byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, result, pos, a.length); pos += a.length; }
        return result;
    }

    public void disconnect() {
        connected = false;
        try { if (sslSocket != null) sslSocket.close(); } catch (Exception ignored) {}
        executor.shutdown();
    }

    private static class TrustAllManager implements javax.net.ssl.X509TrustManager {
        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }
}
