package com.tvremote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.*;

/**
 * Handles the Android TV Remote Protocol v2 pairing flow.
 *
 * Flow:
 *   1. TLS connect to TV on port 6467
 *   2. Send PairingRequest  -> receive PairingRequestAck (get server cert)
 *   3. Send OptionsRequest  -> receive OptionsResponse
 *   4. Send ConfigRequest   -> receive ConfigResponse
 *   5. User enters 6-char code shown on TV screen
 *   6. Send SecretRequest   -> receive SecretResponse (status 200 = success)
 */
public class AtvPairing {

    private static final String TAG = "AtvPairing";

    public interface PairingCallback {
        void onCodeRequired();          // TV is showing a code, user needs to enter it
        void onSuccess();               // Pairing complete
        void onError(String message);   // Something went wrong
    }

    private final String tvHost;
    private final AtvCertManager certManager;
    private final PairingCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    // Server certificate data (for secret computation)
    private byte[] serverModulus;
    private byte[] serverExponent;

    public AtvPairing(String tvHost, AtvCertManager certManager, PairingCallback callback) {
        this.tvHost = tvHost;
        this.certManager = certManager;
        this.callback = callback;
    }

    public void startPairing() {
        executor.execute(() -> {
            try {
                connectTls();
                doPairingHandshake();
            } catch (Exception e) {
                Log.e(TAG, "Pairing error", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
                close();
            }
        });
    }

    private void connectTls() throws Exception {
        // Build SSLContext with our client cert, trust-all server (TV uses self-signed)
        SSLContext sslCtx = SSLContext.getInstance("TLS");

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(certManager.getKeyStore(), certManager.getKeyStorePassword());

        // Trust-all for TV's self-signed cert
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManagerAll()};

        sslCtx.init(kmf.getKeyManagers(), trustAll, new java.security.SecureRandom());

        SSLSocketFactory factory = sslCtx.getSocketFactory();
        SSLSocket ssl = (SSLSocket) factory.createSocket(tvHost, AtvProtocol.PORT_PAIRING);
        ssl.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        ssl.startHandshake();

        socket = ssl;
        in  = new DataInputStream(ssl.getInputStream());
        out = new DataOutputStream(ssl.getOutputStream());
        Log.d(TAG, "TLS pairing connected to " + tvHost);
    }

    private void doPairingHandshake() throws Exception {
        // Step 1: PairingRequest
        AtvProtocol.writeMessage(out, AtvProtocol.buildPairingRequest(
                "androidtvremote2", "TVRemote App"));
        byte[] ack = AtvProtocol.readMessage(in);
        int type = AtvProtocol.parseMessageType(ack);
        Log.d(TAG, "Got message type: " + type);

        // Parse server certificate from ack
        byte[][] serverCert = AtvProtocol.parseServerCertificate(ack);
        if (serverCert != null) {
            serverModulus  = serverCert[0];
            serverExponent = serverCert[1];
        }

        // Step 2: Options
        AtvProtocol.writeMessage(out, AtvProtocol.buildOptionsRequest());
        AtvProtocol.readMessage(in); // options response

        // Step 3: Configuration
        AtvProtocol.writeMessage(out, AtvProtocol.buildConfigRequest());
        AtvProtocol.readMessage(in); // config response

        // Now TV should show code on screen
        mainHandler.post(callback::onCodeRequired);
    }

    /** Call after user enters the 6-char code */
    public void submitCode(String code) {
        executor.execute(() -> {
            try {
                byte[] secretMsg = AtvProtocol.buildSecretRequest(
                        certManager.getCertModulus(),
                        certManager.getCertExponent(),
                        serverModulus != null ? serverModulus : new byte[1],
                        serverExponent != null ? serverExponent : new byte[1],
                        code);
                AtvProtocol.writeMessage(out, secretMsg);
                byte[] response = AtvProtocol.readMessage(in);
                int status = AtvProtocol.parseStatus(response);
                Log.d(TAG, "Secret response status: " + status);
                if (status == 200) {
                    mainHandler.post(callback::onSuccess);
                } else {
                    mainHandler.post(() -> callback.onError("كود خاطئ، حاول تاني"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Submit code error", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                close();
            }
        });
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    /** Trust-all TrustManager (safe for local LAN self-signed certs) */
    private static class X509TrustManagerAll implements javax.net.ssl.X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
    }
}
