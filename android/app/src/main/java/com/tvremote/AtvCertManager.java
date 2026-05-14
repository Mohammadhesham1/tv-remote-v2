package com.tvremote;

import android.content.Context;
import android.util.Log;

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

public class AtvCertManager {

    private static final String TAG = "AtvCertManager";
    private static final String TLS_KS_FILE = "atv_tls.p12";
    private static final String KEY_ALIAS   = "atv_remote";
    private static final char[] TLS_PASS    = "tvremote2024".toCharArray();

    private final Context ctx;
    private KeyStore tlsKeyStore;
    private X509Certificate certificate;
    private PrivateKey privateKey;

    public AtvCertManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void init() throws Exception {
        File ksFile = new File(ctx.getFilesDir(), TLS_KS_FILE);

        if (ksFile.exists()) {
            try {
                tlsKeyStore = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(ksFile)) {
                    tlsKeyStore.load(fis, TLS_PASS);
                }
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
                    tlsKeyStore.getEntry(KEY_ALIAS, new KeyStore.PasswordProtection(TLS_PASS));
                if (entry != null) {
                    privateKey  = entry.getPrivateKey();
                    certificate = (X509Certificate) entry.getCertificateChain()[0];
                    Log.d(TAG, "Loaded existing cert: " + certificate.getSubjectDN());
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load keystore, regenerating: " + e.getMessage());
                ksFile.delete();
            }
        }

        generateAndSave(ksFile);
    }

    private void generateAndSave(File ksFile) throws Exception {
        Log.d(TAG, "Generating new RSA 2048 key pair...");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        privateKey = kp.getPrivate();

        certificate = buildCert(kp);
        Log.d(TAG, "Generated cert: " + certificate.getSubjectDN());

        tlsKeyStore = KeyStore.getInstance("PKCS12");
        tlsKeyStore.load(null, TLS_PASS);
        tlsKeyStore.setKeyEntry(KEY_ALIAS, privateKey, TLS_PASS,
            new java.security.cert.Certificate[]{certificate});

        try (FileOutputStream fos = new FileOutputStream(ksFile)) {
            tlsKeyStore.store(fos, TLS_PASS);
        }
        Log.d(TAG, "Saved keystore to " + ksFile.getAbsolutePath());
    }

    private X509Certificate buildCert(KeyPair kp) throws Exception {
        try {
            return buildWithBouncyCastle(kp);
        } catch (Exception e1) {
            Log.w(TAG, "BouncyCastle failed: " + e1.getMessage());
            try {
                return buildWithSunX509(kp);
            } catch (Exception e2) {
                Log.w(TAG, "sun.security failed: " + e2.getMessage());
                return buildWithAndroidKeyStore();
            }
        }
    }

    private X509Certificate buildWithBouncyCastle(KeyPair kp) throws Exception {
        Class<?> x500Class    = Class.forName("org.bouncycastle.asn1.x500.X500Name");
        Class<?> builderClass = Class.forName("org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder");
        Class<?> contentClass = Class.forName("org.bouncycastle.cert.jcajce.JcaX509CertificateConverter");
        Class<?> signerClass  = Class.forName("org.bouncycastle.operator.jcajce.JcaContentSignerBuilder");

        Object x500Name = x500Class.getConstructor(String.class).newInstance("CN=tvremote,O=TVRemote");

        Date notBefore = new Date(System.currentTimeMillis() - 86400000L);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.YEAR, 10);
        Date notAfter = cal.getTime();

        Object builder = builderClass.getConstructor(
            x500Class, BigInteger.class, Date.class, Date.class,
            x500Class, java.security.PublicKey.class
        ).newInstance(x500Name, BigInteger.valueOf(System.currentTimeMillis()),
            notBefore, notAfter, x500Name, kp.getPublic());

        Object signerBuilder = signerClass.getConstructor(String.class).newInstance("SHA256withRSA");
        Object contentSigner = signerClass.getMethod("build", java.security.PrivateKey.class)
            .invoke(signerBuilder, kp.getPrivate());

        Object holder = builderClass.getMethod("build",
            Class.forName("org.bouncycastle.operator.ContentSigner"))
            .invoke(builder, contentSigner);

        Object converter = contentClass.newInstance();
        return (X509Certificate) contentClass.getMethod("getCertificate",
            Class.forName("org.bouncycastle.cert.X509CertificateHolder"))
            .invoke(converter, holder);
    }

    private X509Certificate buildWithSunX509(KeyPair kp) throws Exception {
        Class<?> certInfoClass = Class.forName("sun.security.x509.X509CertInfo");
        Class<?> x500NameClass = Class.forName("sun.security.x509.X500Name");
        Class<?> certImplClass = Class.forName("sun.security.x509.X509CertImpl");
        Class<?> algIdClass    = Class.forName("sun.security.x509.AlgorithmId");
        Class<?> validityClass = Class.forName("sun.security.x509.CertificateValidity");
        Class<?> snClass       = Class.forName("sun.security.x509.CertificateSerialNumber");
        Class<?> algFieldClass = Class.forName("sun.security.x509.CertificateAlgorithmId");
        Class<?> subjectClass  = Class.forName("sun.security.x509.CertificateSubjectName");
        Class<?> issuerClass   = Class.forName("sun.security.x509.CertificateIssuerName");
        Class<?> keyClass      = Class.forName("sun.security.x509.CertificateX509Key");
        Class<?> versionClass  = Class.forName("sun.security.x509.CertificateVersion");

        Date notBefore = new Date(System.currentTimeMillis() - 86400000L);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.YEAR, 10);
        Date notAfter = cal.getTime();

        Object x500Name = x500NameClass.getConstructor(String.class).newInstance("CN=tvremote");
        Object validity = validityClass.getConstructor(Date.class, Date.class).newInstance(notBefore, notAfter);
        Object sn       = snClass.getConstructor(BigInteger.class).newInstance(BigInteger.valueOf(System.currentTimeMillis()));
        Object algId    = algIdClass.getMethod("get", String.class).invoke(null, "SHA256withRSA");
        Object algField = algFieldClass.getConstructor(algIdClass).newInstance(algId);

        Object info = certInfoClass.newInstance();
        java.lang.reflect.Method set = certInfoClass.getMethod("set", String.class, Object.class);
        set.invoke(info, "version",      versionClass.getConstructor(int.class).newInstance(2));
        set.invoke(info, "serialNumber", sn);
        set.invoke(info, "algorithmID",  algField);
        set.invoke(info, "subject",      subjectClass.getConstructor(x500NameClass).newInstance(x500Name));
        set.invoke(info, "issuer",       issuerClass.getConstructor(x500NameClass).newInstance(x500Name));
        set.invoke(info, "key",          keyClass.getConstructor(java.security.PublicKey.class).newInstance(kp.getPublic()));
        set.invoke(info, "validity",     validity);

        Object cert = certImplClass.getConstructor(certInfoClass).newInstance(info);
        certImplClass.getMethod("sign", PrivateKey.class, String.class)
            .invoke(cert, kp.getPrivate(), "SHA256withRSA");
        return (X509Certificate) cert;
    }

    private X509Certificate buildWithAndroidKeyStore() throws Exception {
        String aksAlias = KEY_ALIAS + "_aks";
        android.security.keystore.KeyGenParameterSpec spec =
            new android.security.keystore.KeyGenParameterSpec.Builder(aksAlias,
                android.security.keystore.KeyProperties.PURPOSE_SIGN |
                android.security.keystore.KeyProperties.PURPOSE_VERIFY)
            .setKeySize(2048)
            .setCertificateSubject(new X500Principal("CN=tvremote"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(new Date(System.currentTimeMillis() - 86400000L))
            .setCertificateNotAfter(new Date(System.currentTimeMillis() + 10L * 365 * 86400000L))
            .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build();

        KeyPairGenerator kpg2 = KeyPairGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        kpg2.initialize(spec);
        kpg2.generateKeyPair();

        KeyStore aks = KeyStore.getInstance("AndroidKeyStore");
        aks.load(null);
        privateKey = (PrivateKey) aks.getKey(aksAlias, null);
        return (X509Certificate) aks.getCertificate(aksAlias);
    }

    public X509Certificate getCertificate() { return certificate; }
    public PrivateKey       getPrivateKey()  { return privateKey; }

    public byte[] getCertModulus() {
        return ((RSAPublicKey) certificate.getPublicKey()).getModulus().toByteArray();
    }

    public byte[] getCertExponent() {
        return ((RSAPublicKey) certificate.getPublicKey()).getPublicExponent().toByteArray();
    }

    public KeyStore getKeyStore()         { return tlsKeyStore; }
    public char[]   getKeyStorePassword() { return TLS_PASS; }

    public void reset() {
        new File(ctx.getFilesDir(), TLS_KS_FILE).delete();
    }
}
