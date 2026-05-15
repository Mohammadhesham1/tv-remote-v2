package com.tvremote;

import android.content.Context;
import android.util.Log;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V1CertificateGenerator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Calendar;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * Generates and persists a self-signed RSA cert for ATV Remote v2.
 * Implementation mirrors SslUtil + KeyStoreManager from kunal52/AndroidTvRemote exactly:
 *   - X509V1CertificateGenerator + generate(key, "BC")
 *   - KeyStore.getDefaultType() (not PKCS12/BC)
 *   - setKeyEntry(alias, privateKey, null, chain)  ← null password for private key
 *   - KeyManagerFactory.getDefaultAlgorithm()
 */
@SuppressWarnings("deprecation")
public class AtvCertManager {

    private static final String TAG       = "AtvCertManager";
    private static final String KS_FILE   = "atv_identity.keystore";
    private static final String KEY_ALIAS = "androidtv-local";   // same as SERVER_IDENTITY_ALIAS
    private static final char[] KS_PASS   = "".toCharArray();    // empty password like reference lib

    private final Context ctx;
    private KeyStore        mKeyStore;
    private X509Certificate certificate;
    private PrivateKey       privateKey;

    public AtvCertManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        // Android has an old built-in BC that conflicts — remove it first,
        // then insert our full BC at position 1 (highest priority)
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    public void init() throws Exception {
        File ksFile = new File(ctx.getFilesDir(), KS_FILE);

        if (ksFile.exists()) {
            try {
                mKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                try (FileInputStream fis = new FileInputStream(ksFile)) {
                    mKeyStore.load(fis, KS_PASS);
                }
                // Private key stored with null password (matching createIdentity())
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
                    mKeyStore.getEntry(KEY_ALIAS,
                        new KeyStore.PasswordProtection(null));
                if (entry != null) {
                    privateKey  = entry.getPrivateKey();
                    certificate = (X509Certificate) entry.getCertificateChain()[0];
                    Log.d(TAG, "Loaded existing cert: " + certificate.getSubjectDN());
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Load failed, regenerating: " + e.getMessage());
                ksFile.delete();
            }
        }

        generateAndSave(ksFile);
    }

    private void generateAndSave(File ksFile) throws Exception {
        // Step 1: Generate RSA key pair — same as KeyPairGenerator.getInstance("RSA").generateKeyPair()
        KeyPair kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        privateKey = kp.getPrivate();

        // Step 2: Generate cert — mirrors SslUtil.generateX509V1Certificate(pair, "CN=androidtv/livingTV")
        Calendar calendar = Calendar.getInstance();
        calendar.set(2009, 0, 1);
        Date startDate = new Date(calendar.getTimeInMillis());
        calendar.set(2029, 0, 1);
        Date expiryDate = new Date(calendar.getTimeInMillis());

        BigInteger serialNumber = BigInteger.valueOf(Math.abs(System.currentTimeMillis()));
        X500Principal dnName = new X500Principal("CN=androidtv/livingTV");

        X509V1CertificateGenerator certGen = new X509V1CertificateGenerator();
        certGen.setSerialNumber(serialNumber);
        certGen.setIssuerDN(dnName);
        certGen.setNotBefore(startDate);
        certGen.setNotAfter(expiryDate);
        certGen.setSubjectDN(dnName);
        certGen.setPublicKey(kp.getPublic());
        certGen.setSignatureAlgorithm("SHA256withRSA");

        // generate() with "BC" provider — exact same call as SslUtil
        certificate = certGen.generate(kp.getPrivate(), "BC");
        Log.d(TAG, "Generated cert: " + certificate.getSubjectDN());

        // Step 3: Save to keystore — mirrors createIdentity() + store()
        mKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        mKeyStore.load(null, KS_PASS);
        // null password for private key entry — same as KeyStoreManager.createIdentity()
        mKeyStore.setKeyEntry(KEY_ALIAS, kp.getPrivate(), null,
                new java.security.cert.Certificate[]{certificate});

        try (FileOutputStream fos = new FileOutputStream(ksFile)) {
            mKeyStore.store(fos, KS_PASS);
        }
        Log.d(TAG, "Cert saved to: " + ksFile.getAbsolutePath());
    }

    // --- Getters ---

    public X509Certificate getCertificate() { return certificate; }
    public PrivateKey       getPrivateKey()  { return privateKey; }

    public byte[] getCertModulus() {
        return ((RSAPublicKey) certificate.getPublicKey()).getModulus().toByteArray();
    }

    public byte[] getCertExponent() {
        return ((RSAPublicKey) certificate.getPublicKey()).getPublicExponent().toByteArray();
    }

    /** KeyStore to pass to KeyManagerFactory — mirrors KeyStoreManager.getKeyManagers() */
    public KeyStore getKeyStore()         { return mKeyStore; }
    /** Empty password — same as reference lib */
    public char[]   getKeyStorePassword() { return KS_PASS; }

    public void reset() {
        new File(ctx.getFilesDir(), KS_FILE).delete();
    }
}
