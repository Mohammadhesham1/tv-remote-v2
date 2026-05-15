package com.tvremote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;

/**
 * Android TV Remote Protocol v2
 *
 * Wire format: [1 byte length][protobuf payload]
 *
 * PairingMessage fields (from wire analysis in Aymkdn wiki):
 *   field 1 = protocol_version (varint) = 2    → tag byte = 8
 *   field 2 = status (varint) = 200             → tag byte = 16, then 200,1 (varint)
 *   field 10 = pairing_request                  → tag byte = 82
 *   field 11 = pairing_request_ack              → tag byte = 90
 *   field 20 = pairing_option                   → tag bytes = 162, 1
 *   field 21 = pairing_option_ack               → tag bytes = 170, 1
 *   field 30 = pairing_configuration            → tag bytes = 242, 1
 *   field 31 = pairing_configuration_ack        → tag bytes = 250, 1
 *   field 40 = pairing_secret                   → tag bytes = 194, 2 (then 34,10,32 for secret)
 *   field 41 = pairing_secret_ack               → tag bytes = 202, 2
 *
 * Verified byte sequences from wiki:
 *   Pairing:  [45][8,2, 16,200,1, 82,43, 10,21,...serviceName, 18,13,...clientName]
 *   Option:   [16][8,2, 16,200,1, 162,1,8, 10,4,8,3,16,6, 24,1]
 *   Config:   [16][8,2, 16,200,1, 242,1,8, 10,4,8,3,16,6, 16,1]
 *   Secret:   [42][8,2, 16,200,1, 194,2,34,10,32, ...32 bytes secret]
 */
public class AtvProtocol {

    public static final int PORT_PAIRING = 6467;
    public static final int PORT_CONTROL = 6466;

    // Wire framing: single byte length prefix (from MessageManager.addLengthAndCreate)
    public static void writeMessage(DataOutputStream out, byte[] data) throws IOException {
        out.write(data.length & 0xFF);
        out.write(data);
        out.flush();
    }

    public static byte[] readMessage(DataInputStream in) throws IOException {
        int length = in.read() & 0xFF;
        if (length <= 0) throw new IOException("Zero length message");
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    /**
     * Pairing request — verified bytes from wiki:
     * [8,2, 16,200,1, 82,LENGTH, 10,LEN,serviceName, 18,LEN,clientName]
     */
    public static byte[] buildPairingRequest(String clientName, String serviceName) {
        byte[] svcBytes = utf8(serviceName);
        byte[] cliBytes = utf8(clientName);

        // inner = field10(service_name) + field18(client_name) in PairingRequest proto
        // PairingRequest: field 1=service_name, field 2=client_name
        // field1 tag=10, field2 tag=18
        byte[] inner = concat(
            new byte[]{10}, varint(svcBytes.length), svcBytes,
            new byte[]{18}, varint(cliBytes.length), cliBytes
        );

        // outer: [8,2][16,200,1][82,innerLen][inner]
        return concat(
            new byte[]{8, 2},           // protocol_version=2
            new byte[]{16, (byte)200, 1}, // status=200 (varint 200 = 0xC8 0x01)
            new byte[]{82},             // field 10, wire type 2 = (10<<3)|2 = 82
            varint(inner.length),
            inner
        );
    }

    /**
     * Option request — verified bytes from wiki:
     * [8,2, 16,200,1, 162,1,8, 10,4,8,3,16,6, 24,1]
     */
    public static byte[] buildOptionsRequest() {
        return new byte[]{
            8, 2,                       // protocol_version=2
            16, (byte)200, 1,           // status=200
            (byte)162, 1, 8,            // field 20, wire type 2 = (20<<3)|2=162 → [162,1]; length=8
            10, 4, 8, 3, 16, 6,         // encoding: field1=tag10,len4,[type=3,symlen=6]
            24, 1                       // preferred_role=1
        };
    }

    /**
     * Config request — verified bytes from wiki:
     * [8,2, 16,200,1, 242,1,8, 10,4,8,3,16,6, 16,1]
     */
    public static byte[] buildConfigRequest() {
        return new byte[]{
            8, 2,                       // protocol_version=2
            16, (byte)200, 1,           // status=200
            (byte)242, 1, 8,            // field 30, wire type 2 = (30<<3)|2=242 → [242,1]; length=8
            10, 4, 8, 3, 16, 6,         // encoding
            16, 1                       // client_role=1
        };
    }

    /**
     * Secret request — verified bytes from wiki:
     * [8,2, 16,200,1, 194,2,34,10,32, ...32bytes]
     * Secret = SHA-256(clientMod+clientExp+serverMod+serverExp+nonce)
     * nonce = last 4 hex chars of code = 2 bytes
     */
    public static byte[] buildSecretRequest(
            Certificate localCert, Certificate remoteCert, String hexCode) throws Exception {

        RSAPublicKey clientKey = (RSAPublicKey) localCert.getPublicKey();
        RSAPublicKey serverKey = (RSAPublicKey) remoteCert.getPublicKey();

        byte[] clientMod = removeLeadingNulls(clientKey.getModulus().abs().toByteArray());
        byte[] clientExp = removeLeadingNulls(clientKey.getPublicExponent().abs().toByteArray());
        byte[] serverMod = removeLeadingNulls(serverKey.getModulus().abs().toByteArray());
        byte[] serverExp = removeLeadingNulls(serverKey.getPublicExponent().abs().toByteArray());

        // nonce = last 4 hex chars of 6-char code = last 2 bytes
        String last4 = hexCode.substring(hexCode.length() - 4);
        byte[] nonce = hexToBytes(last4);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(clientMod);
        md.update(clientExp);
        md.update(serverMod);
        md.update(serverExp);
        md.update(nonce);
        byte[] alpha = md.digest(); // 32 bytes

        // [8,2][16,200,1][194,2,34,10,32][32 bytes]
        // field 40 tag = (40<<3)|2 = 322 → varint [194,2]; inner=[34,10,32,alpha...]
        // inner: 34=length of sub-msg, 10=field1 tag, 32=length, alpha
        return concat(
            new byte[]{8, 2},
            new byte[]{16, (byte)200, 1},
            new byte[]{(byte)194, 2, 34, 10, 32},
            alpha
        );
    }

    public static int parseStatus(byte[] data) {
        // status is field 2, but after reading wiki: field2 tag=16, value=200 (varint C8 01)
        for (int i = 0; i < data.length - 1; i++) {
            if ((data[i] & 0xFF) == 16) {
                int val = 0, shift = 0, j = i + 1;
                int b;
                do {
                    b = data[j++] & 0xFF;
                    val |= (b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0 && j < data.length);
                return val;
            }
        }
        return -1;
    }

    public static int parseMessageType(byte[] data) {
        // Return first multi-byte tag value as identifier
        if (data.length > 2 && (data[0] & 0xFF) == 8) return data[5] & 0xFF;
        return -1;
    }

    // -----------------------------------------------------------------------
    // Control (RemoteMessage) — field 10=remote_configure, field 4=key_inject
    // -----------------------------------------------------------------------

    public static byte[] buildKeyCommand(int keyCode, int action) {
        ProtoWriter key = new ProtoWriter();
        key.writeVarintField(1, keyCode);
        key.writeVarintField(2, action);
        ProtoWriter msg = new ProtoWriter();
        msg.writeBytesField(4, key.toBytes());
        return msg.toBytes();
    }

    public static byte[] buildRemoteConfiguration() {
        ProtoWriter devInfo = new ProtoWriter();
        devInfo.writeStringField(1, "UnknownVendor");
        devInfo.writeStringField(2, "TVRemote");
        devInfo.writeStringField(3, "com.tvremote");
        devInfo.writeStringField(4, "1.0");
        devInfo.writeVarintField(5, 1);
        ProtoWriter config = new ProtoWriter();
        config.writeVarintField(1, 622);
        config.writeBytesField(2, devInfo.toBytes());
        ProtoWriter msg = new ProtoWriter();
        msg.writeBytesField(1, config.toBytes());
        return msg.toBytes();
    }

    public static byte[] buildPong(int val1, int val2) {
        ProtoWriter pong = new ProtoWriter();
        pong.writeVarintField(1, val1);
        pong.writeVarintField(2, val2);
        ProtoWriter msg = new ProtoWriter();
        msg.writeBytesField(8, pong.toBytes());
        return msg.toBytes();
    }

    public static byte[] buildSetActive(int active) {
        ProtoWriter sa = new ProtoWriter();
        sa.writeVarintField(1, active);
        ProtoWriter msg = new ProtoWriter();
        msg.writeBytesField(6, sa.toBytes());
        return msg.toBytes();
    }

    public static int parseRemoteMessageField(byte[] data) {
        ProtoReader pr = new ProtoReader(data);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fn = tag >>> 3, wt = tag & 7;
            if (wt == 2) { pr.readBytes(); return fn; }
            pr.skip(wt);
        }
        return -1;
    }

    public static int[] parsePing(byte[] msg) {
        ProtoReader pr = new ProtoReader(msg);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fn = tag >>> 3, wt = tag & 7;
            if (fn == 7 && wt == 2) {
                byte[] pingData = pr.readBytes();
                ProtoReader pi = new ProtoReader(pingData);
                int v1 = 0, v2 = 0;
                while (pi.hasMore()) {
                    int t2 = pi.readTag(); int f2 = t2>>>3; int w2 = t2&7;
                    if (f2 == 1 && w2 == 0) v1 = pi.readVarint();
                    else if (f2 == 2 && w2 == 0) v2 = pi.readVarint();
                    else pi.skip(w2);
                }
                return new int[]{v1, v2};
            }
            pr.skip(wt);
        }
        return new int[]{0, 0};
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static byte[] removeLeadingNulls(byte[] in) {
        int offset = 0;
        while (offset < in.length && in[offset] == 0) offset++;
        if (offset == 0) return in;
        byte[] out = new byte[in.length - offset];
        System.arraycopy(in, offset, out, 0, out.length);
        return out;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) hex = "0" + hex;
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++)
            result[i] = (byte) Integer.parseInt(hex.substring(2*i, 2*i+2), 16);
        return result;
    }

    private static byte[] utf8(String s) {
        try { return s.getBytes("UTF-8"); } catch (Exception e) { return new byte[0]; }
    }

    private static byte[] varint(int value) {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        while ((value & ~0x7F) != 0) { buf.write((value & 0x7F) | 0x80); value >>>= 7; }
        buf.write(value);
        return buf.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, result, pos, a.length); pos += a.length; }
        return result;
    }

    // -----------------------------------------------------------------------
    // ProtoWriter/Reader for control messages
    // -----------------------------------------------------------------------

    public static class ProtoWriter {
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        public void writeVarintField(int fn, int value) { writeTag(fn, 0); writeVarintRaw(value); }
        public void writeStringField(int fn, String value) {
            byte[] b; try { b = value.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
            writeBytesField(fn, b);
        }
        public void writeBytesField(int fn, byte[] value) {
            writeTag(fn, 2); writeVarintRaw(value.length);
            try { buf.write(value); } catch (IOException ignored) {}
        }
        private void writeTag(int fn, int wt) { writeVarintRaw((fn << 3) | wt); }
        private void writeVarintRaw(int v) {
            while ((v & ~0x7F) != 0) { buf.write((v & 0x7F) | 0x80); v >>>= 7; }
            buf.write(v);
        }
        public byte[] toBytes() { return buf.toByteArray(); }
    }

    public static class ProtoReader {
        private final byte[] data; private int pos;
        public ProtoReader(byte[] data) { this.data = data; }
        public boolean hasMore() { return pos < data.length; }
        public int readTag() { return readVarint(); }
        public int readVarint() {
            int r = 0, s = 0, b;
            do { b = data[pos++] & 0xFF; r |= (b & 0x7F) << s; s += 7; }
            while ((b & 0x80) != 0 && pos < data.length);
            return r;
        }
        public byte[] readBytes() {
            int len = readVarint(); byte[] out = new byte[len];
            System.arraycopy(data, pos, out, 0, len); pos += len; return out;
        }
        public void skip(int wt) { switch(wt) { case 0: readVarint(); break; case 2: readBytes(); break; } }
    }
}
