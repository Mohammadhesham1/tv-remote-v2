package com.tvremote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
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

    private SSLSocket        sslSocket;
    private DataInputStream  in;
    private DataOutputStream out;

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
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                mainHandler.post(() -> callback.onError(msg));
                close();
            }
        });
    }

    private void connectTls() throws Exception {
        Log.d(TAG, "Step 1: KeyManagerFactory");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(certManager.getKeyStore(), certManager.getKeyStorePassword());

        Log.d(TAG, "Step 2: SSLContext");
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(),
                new TrustManager[]{new TrustAllManager()},
                new java.security.SecureRandom());

        Log.d(TAG, "Step 3: connect TCP to " + tvHost + ":" + AtvProtocol.PORT_PAIRING);
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(tvHost, AtvProtocol.PORT_PAIRING), 5000);
        plain.setSoTimeout(10000);

        Log.d(TAG, "Step 4: TLS handshake");
        SSLSocketFactory factory = sslCtx.getSocketFactory();
        sslSocket = (SSLSocket) factory.createSocket(plain, tvHost, AtvProtocol.PORT_PAIRING, true);
        sslSocket.setUseClientMode(true);
        sslSocket.startHandshake();

        in  = new DataInputStream(sslSocket.getInputStream());
        out = new DataOutputStream(sslSocket.getOutputStream());
        Log.d(TAG, "TLS connected OK");
    }

    private void doPairingHandshake() throws Exception {
        Log.d(TAG, "→ PairingRequest");
        AtvProtocol.writeMessage(out, AtvProtocol.buildPairingRequest(
                "TVRemote App", "androidtvremote2"));
        byte[] ack = AtvProtocol.readMessage(in);
        Log.d(TAG, "← PairingAck, field=" + AtvProtocol.parseMessageType(ack));

        Log.d(TAG, "→ OptionsRequest");
        AtvProtocol.writeMessage(out, AtvProtocol.buildOptionsRequest());
        byte[] optAck = AtvProtocol.readMessage(in);
        Log.d(TAG, "← OptionsAck, field=" + AtvProtocol.parseMessageType(optAck));

        Log.d(TAG, "→ ConfigRequest");
        AtvProtocol.writeMessage(out, AtvProtocol.buildConfigRequest());
        byte[] cfgAck = AtvProtocol.readMessage(in);
        Log.d(TAG, "← ConfigAck, field=" + AtvProtocol.parseMessageType(cfgAck));

        Log.d(TAG, "Handshake done — TV should show code");
        mainHandler.post(callback::onCodeRequired);
    }

    public void submitCode(String code) {
        executor.execute(() -> {
            try {
                // Get certs from TLS session — same as Utils.getLocalCert/getPeerCert
                SSLSession session = sslSocket.getSession();
                Certificate localCert  = session.getLocalCertificates()[0];
                Certificate remoteCert = session.getPeerCertificates()[0];

                Log.d(TAG, "→ SecretRequest for code=" + code);
                byte[] secretMsg = AtvProtocol.buildSecretRequest(localCert, remoteCert, code);
                AtvProtocol.writeMessage(out, secretMsg);
                byte[] response = AtvProtocol.readMessage(in);
                int status = AtvProtocol.parseStatus(response);
                Log.d(TAG, "← SecretAck status=" + status);

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
        try { if (sslSocket != null) sslSocket.close(); } catch (Exception ignored) {}
    }

    private static class TrustAllManager implements X509TrustManager {
        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }
}
