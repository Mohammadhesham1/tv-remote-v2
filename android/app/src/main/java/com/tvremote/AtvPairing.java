package com.tvremote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.*;

/**
 * Handles ATV Remote Protocol v2 pairing flow.
 * SSLContext mirrors KeyStoreManager.getKeyManagers() from reference lib:
 *   KeyManagerFactory.getDefaultAlgorithm() + empty password.
 */
public class AtvPairing {

    private static final String TAG = "AtvPairing";

    public interface PairingCallback {
        void onCodeRequired();
        void onSuccess();
        void onError(String message);
    }

    private final String          tvHost;
    private final AtvCertManager  certManager;
    private final PairingCallback callback;
    private final Handler          mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService  executor    = Executors.newSingleThreadExecutor();

    private Socket           socket;
    private DataInputStream  in;
    private DataOutputStream out;

    private byte[] serverModulus;
    private byte[] serverExponent;

    public AtvPairing(String tvHost, AtvCertManager certManager, PairingCallback callback) {
        this.tvHost      = tvHost;
        this.certManager = certManager;
        this.callback    = callback;
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
        // Mirrors KeyStoreManager.getKeyManagers()
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(certManager.getKeyStore(), certManager.getKeyStorePassword());

        // Trust-all for TV self-signed cert
        TrustManager[] trustAll = new TrustManager[]{new TrustAllManager()};

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), trustAll, new java.security.SecureRandom());

        SSLSocketFactory factory = sslCtx.getSocketFactory();
        SSLSocket ssl = (SSLSocket) factory.createSocket(tvHost, AtvProtocol.PORT_PAIRING);
        ssl.setUseClientMode(true);
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
        Log.d(TAG, "PairingAck type=" + AtvProtocol.parseMessageType(ack));

        byte[][] serverCert = AtvProtocol.parseServerCertificate(ack);
        if (serverCert != null) {
            serverModulus  = serverCert[0];
            serverExponent = serverCert[1];
        }

        // Step 2: Options
        AtvProtocol.writeMessage(out, AtvProtocol.buildOptionsRequest());
        AtvProtocol.readMessage(in);

        // Step 3: Config
        AtvProtocol.writeMessage(out, AtvProtocol.buildConfigRequest());
        AtvProtocol.readMessage(in);

        // TV now shows code
        mainHandler.post(callback::onCodeRequired);
    }

    public void submitCode(String code) {
        executor.execute(() -> {
            try {
                byte[] secretMsg = AtvProtocol.buildSecretRequest(
                        certManager.getCertModulus(),
                        certManager.getCertExponent(),
                        serverModulus  != null ? serverModulus  : new byte[1],
                        serverExponent != null ? serverExponent : new byte[1],
                        code);
                AtvProtocol.writeMessage(out, secretMsg);
                byte[] response = AtvProtocol.readMessage(in);
                int status = AtvProtocol.parseStatus(response);
                Log.d(TAG, "Secret status: " + status);
                if (status == 200) {
                    mainHandler.post(callback::onSuccess);
                } else {
                    mainHandler.post(() -> callback.onError("كود خاطئ، حاول تاني"));
                }
            } catch (Exception e) {
                Log.e(TAG, "submitCode error", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                close();
            }
        });
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    private static class TrustAllManager implements X509TrustManager {
        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }
}
