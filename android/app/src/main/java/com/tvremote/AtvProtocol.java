package com.tvremote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Android TV Remote Protocol v2 - low-level message framing.
 *
 * Each message on the wire is:
 *   [varint length][protobuf bytes]
 *
 * Pairing port : 6467
 * Control port : 6466
 */
public class AtvProtocol {

    public static final int PORT_PAIRING = 6467;
    public static final int PORT_CONTROL = 6466;

    /** Write a length-prefixed message to the stream */
    public static void writeMessage(DataOutputStream out, byte[] data) throws IOException {
        writeVarint(out, data.length);
        out.write(data);
        out.flush();
    }

    /** Read a length-prefixed message from the stream */
    public static byte[] readMessage(DataInputStream in) throws IOException {
        int length = readVarint(in);
        if (length <= 0 || length > 1024 * 1024) {
            throw new IOException("Invalid message length: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    /** Encode a positive int as a protobuf varint */
    public static void writeVarint(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** Decode a protobuf varint */
    public static int readVarint(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = in.read();
            if (b < 0) throw new IOException("Stream closed while reading varint");
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    // ---------------------------------------------------------------------------
    // Pairing message builders (hand-crafted protobuf binary, no .proto needed)
    // Based on: https://github.com/tronikos/androidtvremote2
    // ---------------------------------------------------------------------------

    // OuterMessage types
    public static final int PAIRING_REQUEST      = 10;
    public static final int PAIRING_REQUEST_ACK  = 11;
    public static final int OPTIONS_REQUEST       = 20;
    public static final int OPTIONS_RESPONSE      = 21; // not used client side
    public static final int CONFIGURATION_REQUEST = 30;
    public static final int CONFIG_RESPONSE       = 31; // not used client side
    public static final int SECRET_REQUEST        = 40;
    public static final int SECRET_RESPONSE       = 41; // not used client side

    /** Build pairing request message: {status:200, type:10, PairingRequest:{service_name, client_name}} */
    public static byte[] buildPairingRequest(String serviceName, String clientName) {
        // Field 1 (status) = 200  -> varint field
        // Field 2 (type)   = 10   -> varint field
        // Field 3 (payload)        -> embedded message
        //   PairingRequest field 1 = service_name (string)
        //   PairingRequest field 2 = client_name  (string)
        ProtoWriter pw = new ProtoWriter();
        pw.writeVarintField(1, 200);       // status = STATUS_OK
        pw.writeVarintField(2, PAIRING_REQUEST);
        ProtoWriter inner = new ProtoWriter();
        inner.writeStringField(1, serviceName);
        inner.writeStringField(2, clientName);
        pw.writeBytesField(3, inner.toBytes());
        return pw.toBytes();
    }

    /** Build options request */
    public static byte[] buildOptionsRequest() {
        // PairingOption: preferred_role=1, input_encodings=[{type=3, symbol_length=6}]
        ProtoWriter enc = new ProtoWriter();
        enc.writeVarintField(1, 3);   // type = HEXADECIMAL
        enc.writeVarintField(2, 6);   // symbol_length
        ProtoWriter option = new ProtoWriter();
        option.writeVarintField(2, 1);                 // preferred_role = ROLE_INPUT
        option.writeBytesField(3, enc.toBytes());      // input_encodings
        ProtoWriter outer = new ProtoWriter();
        outer.writeVarintField(1, 200);
        outer.writeVarintField(2, OPTIONS_REQUEST);
        outer.writeBytesField(3, option.toBytes());
        return outer.toBytes();
    }

    /** Build configuration request */
    public static byte[] buildConfigRequest() {
        ProtoWriter enc = new ProtoWriter();
        enc.writeVarintField(1, 3);   // type = HEXADECIMAL
        enc.writeVarintField(2, 6);
        ProtoWriter config = new ProtoWriter();
        config.writeVarintField(2, 1);                 // client_role = ROLE_INPUT
        config.writeBytesField(3, enc.toBytes());
        ProtoWriter outer = new ProtoWriter();
        outer.writeVarintField(1, 200);
        outer.writeVarintField(2, CONFIGURATION_REQUEST);
        outer.writeBytesField(3, config.toBytes());
        return outer.toBytes();
    }

    /** Build secret request given the 6-char hex code entered by user */
    public static byte[] buildSecretRequest(
            byte[] clientModulus, byte[] clientExponent,
            byte[] serverModulus, byte[] serverExponent,
            String hexCode) throws Exception {
        // secret = SHA-256( clientModulus + clientExponent + serverModulus + serverExponent + codeBytes )
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(clientModulus);
        md.update(clientExponent);
        md.update(serverModulus);
        md.update(serverExponent);
        // last two hex chars of code -> 1 byte
        String last2 = hexCode.substring(hexCode.length() - 2);
        md.update((byte) Integer.parseInt(last2, 16));
        byte[] secret = md.digest();

        ProtoWriter inner = new ProtoWriter();
        inner.writeBytesField(1, secret);
        ProtoWriter outer = new ProtoWriter();
        outer.writeVarintField(1, 200);
        outer.writeVarintField(2, SECRET_REQUEST);
        outer.writeBytesField(3, inner.toBytes());
        return outer.toBytes();
    }

    /** Parse the type field (field 2) from an outer message */
    public static int parseMessageType(byte[] data) {
        ProtoReader pr = new ProtoReader(data);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            if (fieldNumber == 2 && wireType == 0) {
                return pr.readVarint();
            }
            pr.skip(wireType);
        }
        return -1;
    }

    /** Parse status field (field 1) from an outer message */
    public static int parseStatus(byte[] data) {
        ProtoReader pr = new ProtoReader(data);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            if (fieldNumber == 1 && wireType == 0) {
                return pr.readVarint();
            }
            pr.skip(wireType);
        }
        return -1;
    }

    /** Parse server certificate modulus from PairingRequestAck payload (field 3) */
    public static byte[][] parseServerCertificate(byte[] data) {
        // Returns [modulus, exponent] or null
        ProtoReader pr = new ProtoReader(data);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fn = tag >>> 3;
            int wt = tag & 0x7;
            if (fn == 3 && wt == 2) {
                byte[] payload = pr.readBytes();
                // payload is PairingRequestAck, parse its certificate field
                return parseCertFromAck(payload);
            }
            pr.skip(wt);
        }
        return null;
    }

    private static byte[][] parseCertFromAck(byte[] ack) {
        // PairingRequestAck has field 2 = certificate (bytes - DER encoded X509)
        ProtoReader pr = new ProtoReader(ack);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fn = tag >>> 3;
            int wt = tag & 0x7;
            if (fn == 2 && wt == 2) {
                byte[] certDer = pr.readBytes();
                try {
                    java.security.cert.CertificateFactory cf =
                        java.security.cert.CertificateFactory.getInstance("X.509");
                    java.security.cert.X509Certificate cert =
                        (java.security.cert.X509Certificate) cf.generateCertificate(
                            new java.io.ByteArrayInputStream(certDer));
                    java.security.interfaces.RSAPublicKey pub =
                        (java.security.interfaces.RSAPublicKey) cert.getPublicKey();
                    byte[] modBytes = pub.getModulus().toByteArray();
                    byte[] expBytes = pub.getPublicExponent().toByteArray();
                    return new byte[][]{modBytes, expBytes};
                } catch (Exception e) {
                    return null;
                }
            }
            pr.skip(wt);
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // RemoteMessage builders for control
    // ---------------------------------------------------------------------------

    /** Build a key command RemoteMessage */
    public static byte[] buildKeyCommand(int keyCode, int action) {
        // RemoteMessage { remote_key_inject { key_code, action } }
        ProtoWriter key = new ProtoWriter();
        key.writeVarintField(1, keyCode);   // key_code
        key.writeVarintField(2, action);    // action: 1=down, 2=up
        ProtoWriter msg = new ProtoWriter();
        msg.writeBytesField(4, key.toBytes()); // field 4 = remote_key_inject
        return msg.toBytes();
    }

    /** Build a RemoteMessage configuration (sent on connect) */
    public static byte[] buildRemoteConfiguration() {
        // RemoteMessage { remote_configure { code1=622, device_info { ... } } }
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
        msg.writeBytesField(1, config.toBytes()); // field 1 = remote_configure
        return msg.toBytes();
    }

    /** Build a pong response to a ping */
    public static byte[] buildPong(int val1, int val2) {
        ProtoWriter pong = new ProtoWriter();
        pong.writeVarintField(1, val1);
        pong.writeVarintField(2, val2);
        ProtoWriter msg = new ProtoWriter();
        msg.writeBytesField(8, pong.toBytes()); // field 8 = remote_pong_request
        return msg.toBytes();
    }

    /** Build a set-active message */
    public static byte[] buildSetActive(int active) {
        ProtoWriter sa = new ProtoWriter();
        sa.writeVarintField(1, active);
        ProtoWriter msg = new ProtoWriter();
        msg.writeBytesField(6, sa.toBytes()); // field 6 = remote_set_active
        return msg.toBytes();
    }

    /** Parse incoming RemoteMessage - returns field number of the set field, or -1 */
    public static int parseRemoteMessageField(byte[] data) {
        ProtoReader pr = new ProtoReader(data);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fn = tag >>> 3;
            int wt = tag & 0x7;
            if (wt == 2) { pr.readBytes(); return fn; }
            pr.skip(wt);
        }
        return -1;
    }

    /** Parse ping val1/val2 from RemoteMessage ping field */
    public static int[] parsePing(byte[] msg) {
        ProtoReader pr = new ProtoReader(msg);
        while (pr.hasMore()) {
            int tag = pr.readTag();
            int fn = tag >>> 3;
            int wt = tag & 0x7;
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

    // ---------------------------------------------------------------------------
    // Minimal protobuf writer/reader helpers
    // ---------------------------------------------------------------------------

    public static class ProtoWriter {
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        public void writeVarintField(int fieldNumber, int value) {
            writeTag(fieldNumber, 0);
            writeVarintRaw(value);
        }

        public void writeStringField(int fieldNumber, String value) {
            byte[] bytes;
            try { bytes = value.getBytes("UTF-8"); } catch (Exception e) { bytes = new byte[0]; }
            writeBytesField(fieldNumber, bytes);
        }

        public void writeBytesField(int fieldNumber, byte[] value) {
            writeTag(fieldNumber, 2);
            writeVarintRaw(value.length);
            try { buf.write(value); } catch (IOException ignored) {}
        }

        private void writeTag(int fieldNumber, int wireType) {
            writeVarintRaw((fieldNumber << 3) | wireType);
        }

        private void writeVarintRaw(int value) {
            while ((value & ~0x7F) != 0) {
                buf.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buf.write(value);
        }

        public byte[] toBytes() { return buf.toByteArray(); }
    }

    public static class ProtoReader {
        private final byte[] data;
        private int pos;

        public ProtoReader(byte[] data) { this.data = data; this.pos = 0; }

        public boolean hasMore() { return pos < data.length; }

        public int readTag() { return readVarint(); }

        public int readVarint() {
            int result = 0, shift = 0, b;
            do {
                b = data[pos++] & 0xFF;
                result |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && pos < data.length);
            return result;
        }

        public byte[] readBytes() {
            int len = readVarint();
            byte[] out = new byte[len];
            System.arraycopy(data, pos, out, 0, len);
            pos += len;
            return out;
        }

        public void skip(int wireType) {
            switch (wireType) {
                case 0: readVarint(); break;
                case 2: readBytes(); break;
                default: break;
            }
        }
    }
}
