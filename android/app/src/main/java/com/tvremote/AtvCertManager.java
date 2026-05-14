package com.tvremote;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Calendar;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * Generates and persists a self-signed RSA certificate used for
 * Android TV Remote Protocol v2 TLS authentication.
 * Uses Android Keystore for secure key storage.
 */
public class AtvCertManager {

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "atv_remote_key";

    private final Context ctx;
    private KeyStore androidKeyStore;
    private X509Certificate certificate;
    private PrivateKey privateKey;

    // Separate PKCS12 keystore for TLS (SSLContext needs it)
    private KeyStore tlsKeyStore;
    private static final char[] TLS_PASS = "tvremote".toCharArray();
    private static final String TLS_KS_FILE = "atv_tls.p12";

    public AtvCertManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void init() throws Exception {
        File tlsFile = new File(ctx.getFilesDir(), TLS_KS_FILE);

        tlsKeyStore = KeyStore.getInstance("PKCS12");

        if (tlsFile.exists()) {
            try (FileInputStream fis = new FileInputStream(tlsFile)) {
                tlsKeyStore.load(fis, TLS_PASS);
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
                    tlsKeyStore.getEntry(KEY_ALIAS, new KeyStore.PasswordProtection(TLS_PASS));
                if (entry != null) {
                    privateKey  = entry.getPrivateKey();
                    certificate = (X509Certificate) entry.getCertificateChain()[0];
                    return;
                }
            } catch (Exception ignored) {}
        }

        generateAndSave(tlsFile);
    }

    private void generateAndSave(File tlsFile) throws Exception {
        // Generate RSA 2048 key pair using Android's built-in provider
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        privateKey = kp.getPrivate();

        // Generate self-signed cert using BouncyCastle-compatible approach
        // Android 26+ has android.security.keystore, but for a portable self-signed cert
        // we use the KeyPairGenerator approach with reflection on X509V3CertificateGenerator
        // OR we use the simpler approach: generate via KeyPairGenerator with AndroidKeyStore
        certificate = buildSelfSignedCert(kp);

        // Save to PKCS12
        tlsKeyStore.load(null, TLS_PASS);
        tlsKeyStore.setKeyEntry(KEY_ALIAS, privateKey, TLS_PASS,
            new java.security.cert.Certificate[]{certificate});
        try (FileOutputStream fos = new FileOutputStream(tlsFile)) {
            tlsKeyStore.store(fos, TLS_PASS);
        }
    }

    private X509Certificate buildSelfSignedCert(KeyPair kp) throws Exception {
        // Use android.net.http.X509TrustManagerExtensions? No.
        // Best approach on Android without BouncyCastle:
        // Use KeyPairGenerator with AndroidKeyStore which auto-creates a cert
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER);

        Calendar start = Calendar.getInstance();
        Calendar end   = Calendar.getInstance();
        end.add(Calendar.YEAR, 10);

        kpg.initialize(
            new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setCertificateSubject(new X500Principal("CN=tvremote"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(start.getTime())
                .setCertificateNotAfter(end.getTime())
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build());

        KeyPair akp = kpg.generateKeyPair();

        // Get cert from AndroidKeyStore
        androidKeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        androidKeyStore.load(null);
        X509Certificate aksCert = (X509Certificate)
            androidKeyStore.getCertificate(KEY_ALIAS);

        // Use the AndroidKeyStore private key (backed by hardware if available)
        privateKey = (PrivateKey) androidKeyStore.getKey(KEY_ALIAS, null);

        return aksCert;
    }

    /** Reset: re-init tlsKeyStore from AndroidKeyStore cert */
    public void initFromAndroidKeyStore() throws Exception {
        androidKeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        androidKeyStore.load(null);

        if (!androidKeyStore.containsAlias(KEY_ALIAS)) {
            buildSelfSignedCert(null);
            return;
        }

        certificate = (X509Certificate) androidKeyStore.getCertificate(KEY_ALIAS);
        privateKey  = (PrivateKey) androidKeyStore.getKey(KEY_ALIAS, null);

        // Rebuild TLS keystore in memory for SSLContext
        tlsKeyStore = KeyStore.getInstance("PKCS12");
        tlsKeyStore.load(null, TLS_PASS);
        tlsKeyStore.setKeyEntry(KEY_ALIAS, privateKey, TLS_PASS,
            new java.security.cert.Certificate[]{certificate});
    }

    public X509Certificate getCertificate() { return certificate; }
    public PrivateKey getPrivateKey()        { return privateKey; }

    public byte[] getCertModulus() {
        RSAPublicKey pub = (RSAPublicKey) certificate.getPublicKey();
        return pub.getModulus().toByteArray();
    }

    public byte[] getCertExponent() {
        RSAPublicKey pub = (RSAPublicKey) certificate.getPublicKey();
        return pub.getPublicExponent().toByteArray();
    }

    public KeyStore getTlsKeyStore()        { return tlsKeyStore; }
    public char[]   getTlsKeyStorePass()    { return TLS_PASS; }

    // Keep backward-compat names used in AtvPairing / RemoteConnection
    public KeyStore getKeyStore()           { return tlsKeyStore; }
    public char[]   getKeyStorePassword()   { return TLS_PASS; }
}
