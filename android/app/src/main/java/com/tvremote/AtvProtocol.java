package com.tvremote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;

/**
 * Android TV Remote Protocol v2 - message framing + builders.
 *
 * Wire framing (from kunal52/AndroidTvRemote MessageManager + PacketParser):
 *   [1 byte length][protobuf bytes]
 *
 * PairingMessage (proto) fields:
 *   field 1 = status (varint)       STATUS_OK = 200
 *   field 2 = protocol_version (varint) = 2
 *   field 3 = pairing_request (embedded)
 *   field 4 = pairing_request_ack (embedded)
 *   field 5 = pairing_option (embedded)
 *   field 6 = pairing_option_ack (embedded)
 *   field 7 = pairing_configuration (embedded)
 *   field 8 = pairing_configuration_ack (embedded)
 *   field 9 = pairing_secret (embedded)
 *   field 10 = pairing_secret_ack (embedded)
 */
public class AtvProtocol {

    public static final int PORT_PAIRING = 6467;
    public static final int PORT_CONTROL = 6466;

    // PairingMessage.Status.STATUS_OK = 200
    private static final int STATUS_OK = 200;
    // protocol_version = 2
    private static final int PROTOCOL_VERSION = 2;

    // -----------------------------------------------------------------------
    // Wire framing — matches MessageManager.addLengthAndCreate() and
    // PacketParser.run(): single byte length prefix
    // -----------------------------------------------------------------------

    public static void writeMessage(DataOutputStream out, byte[] data) throws IOException {
        out.write(data.length & 0xFF);   // single byte length
        out.write(data);
        out.flush();
    }

    public static byte[] readMessage(DataInputStream in) throws IOException {
        int length = in.read() & 0xFF;   // single byte length
        if (length <= 0) throw new IOException("Zero length message");
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    // -----------------------------------------------------------------------
    // Pairing message builders
    // Field numbering from Pairingmessage.proto (kunal52 reference):
    //   PairingMessage:
    //     1 = status, 2 = protocol_version
    //     3 = pairing_request, 4 = pairing_request_ack
    //     5 = pairing_option,  6 = pairing_option_ack
    //     7 = pairing_configuration, 8 = pairing_configuration_ack
    //     9 = pairing_secret, 10 = pairing_secret_ack
    //
    //   PairingRequest:     1=service_name, 2=client_name
    //   PairingOption:      2=preferred_role, 3=input_encodings (repeated)
    //   PairingEncoding:    1=type(HEXADECIMAL=3), 2=symbol_length
    //   PairingConfiguration: 2=client_role, 3=encoding
    //   PairingSecret:      1=secret (bytes)
    // -----------------------------------------------------------------------

    public static byte[] buildPairingRequest(String clientName, String serviceName) {
        // PairingRequest inner message
        ProtoWriter req = new ProtoWriter();
        req.writeStringField(1, serviceName);
        req.writeStringField(2, clientName);

        // PairingMessage outer
        ProtoWriter msg = new ProtoWriter();
        msg.writeVarintField(1, STATUS_OK);
        msg.writeVarintField(2, PROTOCOL_VERSION);
        msg.writeBytesField(3, req.toBytes());
        return msg.toBytes();
    }

    public static byte[] buildOptionsRequest() {
        // PairingEncoding: HEXADECIMAL=3, symbol_length=6
        ProtoWriter enc = new ProtoWriter();
        enc.writeVarintField(1, 3);  // ENCODING_TYPE_HEXADECIMAL
        enc.writeVarintField(2, 6);  // symbol_length

        // PairingOption: preferred_role=ROLE_TYPE_INPUT=1, input_encodings
        ProtoWriter opt = new ProtoWriter();
        opt.writeVarintField(2, 1);              // ROLE_TYPE_INPUT
        opt.writeBytesField(3, enc.toBytes());   // input_encodings (repeated, one entry)

        ProtoWriter msg = new ProtoWriter();
        msg.writeVarintField(1, STATUS_OK);
        msg.writeVarintField(2, PROTOCOL_VERSION);
        msg.writeBytesField(5, opt.toBytes());
        return msg.toBytes();
    }

    public static byte[] buildConfigRequest() {
        // PairingEncoding
        ProtoWriter enc = new ProtoWriter();
        enc.writeVarintField(1, 3);
        enc.writeVarintField(2, 6);

        // PairingConfiguration: client_role=ROLE_TYPE_INPUT=1, encoding
        ProtoWriter cfg = new ProtoWriter();
        cfg.writeVarintField(2, 1);             // client_role
        cfg.writeBytesField(3, enc.toBytes());  // encoding

        ProtoWriter msg = new ProtoWriter();
        msg.writeVarintField(1, STATUS_OK);
        msg.writeVarintField(2, PROTOCOL_VERSION);
        msg.writeBytesField(7, cfg.toBytes());
        return msg.toBytes();
    }

    /**
     * Build secret request using Polo protocol (from PairingChallengeResponse.getAlpha):
     *   secret = SHA-256(
     *       removeLeadingNulls(clientModulus) +
     *       removeLeadingNulls(clientExponent) +
     *       removeLeadingNulls(serverModulus) +
     *       removeLeadingNulls(serverExponent) +
     *       nonce
     *   )
     *   nonce = last (symbolLength/2) bytes of the hex code = last 3 bytes (6 hex chars)
     *
     * Then gamma = alpha[0..2] + nonce[0..2] (6 bytes total, but we send alpha as secret)
     */
    public static byte[] buildSecretRequest(
            Certificate localCert, Certificate remoteCert, String hexCode) throws Exception {

        RSAPublicKey clientKey = (RSAPublicKey) localCert.getPublicKey();
        RSAPublicKey serverKey = (RSAPublicKey) remoteCert.getPublicKey();

        byte[] clientModulus  = removeLeadingNulls(clientKey.getModulus().abs().toByteArray());
        byte[] clientExponent = removeLeadingNulls(clientKey.getPublicExponent().abs().toByteArray());
        byte[] serverModulus  = removeLeadingNulls(serverKey.getModulus().abs().toByteArray());
        byte[] serverExponent = removeLeadingNulls(serverKey.getPublicExponent().abs().toByteArray());

        // nonce = hex code as bytes (6 hex chars = 3 bytes)
        byte[] nonce = hexStringToBytes(hexCode);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(clientModulus);
        digest.update(clientExponent);
        digest.update(serverModulus);
        digest.update(serverExponent);
        digest.update(nonce);
        byte[] alpha = digest.digest();

        // PairingSecret: field 1 = secret bytes (alpha)
        ProtoWriter secret = new ProtoWriter();
        secret.writeBytesField(1, alpha);

        ProtoWriter msg = new ProtoWriter();
        msg.writeVarintField(1, STATUS_OK);
        msg.writeVarintField(2, PROTOCOL_VERSION);
        msg.writeBytesField(9, secret.toBytes());
        return msg.toBytes();
    }

    private static byte[] removeLeadingNulls(byte[] in) {
        int offset = 0;
        while (offset < in.length && in[offset] == 0) offset++;
        if (offset == 0) return in;
        byte[] out = new byte[in.length - offset];
        System.arraycopy(in, offset, out, 0, out.length);
        return out;
    }

    private static byte[] hexStringToBytes(String hex) {
        if (hex.length() % 2 != 0) hex = "0" + hex;
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return result;
    }

    /** Parse the status field from a PairingMessage */
    public static int parseStatus(byte[] data) {
        ProtoReader pr = new ProtoReader(data);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fn = tag >>> 3, wt = tag & 7;
            if (fn == 1 && wt == 0) return pr.readVarint();
            pr.skip(wt);
        }
        return -1;
    }

    /** Returns field number of first embedded message field (to identify message type) */
    public static int parseMessageType(byte[] data) {
        ProtoReader pr = new ProtoReader(data);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fn = tag >>> 3, wt = tag & 7;
            if (wt == 2 && fn >= 3) return fn;
            pr.skip(wt);
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // Control (RemoteMessage) builders
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
    // Minimal protobuf helpers
    // -----------------------------------------------------------------------

    public static class ProtoWriter {
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        public void writeVarintField(int fn, int value) {
            writeTag(fn, 0); writeVarintRaw(value);
        }
        public void writeStringField(int fn, String value) {
            byte[] b;
            try { b = value.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
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
            int len = readVarint();
            byte[] out = new byte[len];
            System.arraycopy(data, pos, out, 0, len); pos += len;
            return out;
        }
        public void skip(int wt) {
            switch (wt) { case 0: readVarint(); break; case 2: readBytes(); break; }
        }
    }
}
