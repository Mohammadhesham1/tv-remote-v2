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
                Log.e(TAG, "Pairing error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                mainHandler.post(() -> callback.onError(msg));
                close();
            }
        });
    }

    private void connectTls() throws Exception {
        Log.d(TAG, "Step 1: init KeyManagerFactory for " + tvHost);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(certManager.getKeyStore(), certManager.getKeyStorePassword());

        Log.d(TAG, "Step 2: init SSLContext");
        TrustManager[] trustAll = new TrustManager[]{new TrustAllManager()};
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), trustAll, new java.security.SecureRandom());

        Log.d(TAG, "Step 3: TCP connect to " + tvHost + ":" + AtvProtocol.PORT_PAIRING);
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(tvHost, AtvProtocol.PORT_PAIRING), 5000);
        plain.setSoTimeout(10000);

        Log.d(TAG, "Step 4: TLS handshake...");
        SSLSocketFactory factory = sslCtx.getSocketFactory();
        SSLSocket ssl = (SSLSocket) factory.createSocket(plain, tvHost, AtvProtocol.PORT_PAIRING, true);
        ssl.setUseClientMode(true);
        ssl.startHandshake();

        socket = ssl;
        in  = new DataInputStream(ssl.getInputStream());
        out = new DataOutputStream(ssl.getOutputStream());
        Log.d(TAG, "TLS connected OK");
    }

    private void doPairingHandshake() throws Exception {
        Log.d(TAG, "Sending PairingRequest...");
        AtvProtocol.writeMessage(out, AtvProtocol.buildPairingRequest(
                "androidtvremote2", "TVRemote App"));
        byte[] ack = AtvProtocol.readMessage(in);
        Log.d(TAG, "Got PairingAck type=" + AtvProtocol.parseMessageType(ack));

        byte[][] serverCert = AtvProtocol.parseServerCertificate(ack);
        if (serverCert != null) {
            serverModulus  = serverCert[0];
            serverExponent = serverCert[1];
        }

        Log.d(TAG, "Sending OptionsRequest...");
        AtvProtocol.writeMessage(out, AtvProtocol.buildOptionsRequest());
        AtvProtocol.readMessage(in);

        Log.d(TAG, "Sending ConfigRequest...");
        AtvProtocol.writeMessage(out, AtvProtocol.buildConfigRequest());
        AtvProtocol.readMessage(in);

        Log.d(TAG, "Handshake done — TV should show code now");
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
                    mainHandler.post(() -> callback.onError("كود خاطئ (status=" + status + ")"));
                }
            } catch (Exception e) {
                Log.e(TAG, "submitCode error", e);
                mainHandler.post(() -> callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage()));
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
