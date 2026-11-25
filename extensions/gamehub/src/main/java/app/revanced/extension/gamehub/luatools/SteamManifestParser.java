package app.revanced.extension.gamehub.luatools;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Zero-dependency binary Steam depot manifest parser & filename decryptor.
 * Mirrors the exact wire format used by SteamRE/DepotDownloader and GameNative's
 * Rust content_manifest parser without requiring protobuf-java or JavaSteam.
 */
public final class SteamManifestParser {

    private static final int PAYLOAD_MAGIC = 0x71F617D0;
    private static final int METADATA_MAGIC = 0x1F4812BE;
    private static final int SIGNATURE_MAGIC = 0x1B81B817;
    private static final int END_OF_MANIFEST_MAGIC = 0x32C415AB;

    public static final int FLAG_DIRECTORY = 64;

    public static final class ChunkData {
        public byte[] sha;
        public int crc;
        public long offset;
        public int cbOriginal;
        public int cbCompressed;
    }

    public static final class FileMapping {
        public String filename = "";
        public long size;
        public int flags;
        public byte[] shaFilename;
        public byte[] shaContent;
        public String linkTarget = "";
        public final List<ChunkData> chunks = new ArrayList<>();

        public boolean isDirectory() {
            return (flags & FLAG_DIRECTORY) != 0;
        }
    }

    public static final class Manifest {
        public int depotId;
        public long gidManifest;
        public boolean filenamesEncrypted;
        public long cbDiskOriginal;
        public long cbDiskCompressed;
        public int uniqueChunks;
        public final List<FileMapping> files = new ArrayList<>();
    }

    private SteamManifestParser() {}

    public static Manifest parse(byte[] raw, byte[] depotKey) throws Exception {
        if (raw == null || raw.length < 8) {
            throw new IllegalArgumentException("Empty or truncated manifest");
        }
        Manifest m = new Manifest();
        boolean havePayload = false;
        boolean haveMeta = false;
        int pos = 0;

        while (pos + 4 <= raw.length) {
            int magic = readIntLE(raw, pos);
            pos += 4;
            if (magic == END_OF_MANIFEST_MAGIC) break;
            if (pos + 4 > raw.length) throw new IllegalArgumentException("Truncated section header");
            int len = readIntLE(raw, pos);
            pos += 4;
            if (len < 0 || pos + len > raw.length) {
                throw new IllegalArgumentException("Invalid section length: " + len);
            }
            if (magic == PAYLOAD_MAGIC) {
                parsePayload(raw, pos, pos + len, m.files);
                havePayload = true;
            } else if (magic == METADATA_MAGIC) {
                parseMetadata(raw, pos, pos + len, m);
                haveMeta = true;
            } else if (magic == SIGNATURE_MAGIC) {
                // Skip signature bytes
            } else {
                throw new IllegalArgumentException(String.format("Unknown manifest magic: 0x%08X", magic));
            }
            pos += len;
        }
        if (!havePayload || !haveMeta) {
            throw new IllegalArgumentException("Manifest missing payload or metadata");
        }
        if (m.filenamesEncrypted) {
            if (depotKey == null || depotKey.length != 32) {
                throw new IllegalArgumentException("Manifest filenames encrypted but 32-byte depot key missing");
            }
            decryptFilenames(m, depotKey);
        }
        for (FileMapping f : m.files) {
            f.filename = f.filename.replace('\\', '/');
            f.linkTarget = f.linkTarget.replace('\\', '/');
        }
        return m;
    }

    private static void decryptFilenames(Manifest m, byte[] depotKey) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(depotKey, "AES");
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.DECRYPT_MODE, keySpec);
        Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");

        for (FileMapping f : m.files) {
            if (!f.filename.isEmpty()) {
                f.filename = decryptName(ecb, cbc, keySpec, f.filename);
            }
            if (!f.linkTarget.isEmpty()) {
                f.linkTarget = decryptName(ecb, cbc, keySpec, f.linkTarget);
            }
        }
        m.filenamesEncrypted = false;
    }

    private static String decryptName(Cipher ecb, Cipher cbc, SecretKeySpec keySpec, String b64) throws Exception {
        byte[] blob = Base64.decode(b64.trim(), Base64.DEFAULT);
        if (blob.length < 32) {
            throw new IllegalArgumentException("Encrypted filename blob too short: " + blob.length);
        }
        byte[] iv = ecb.doFinal(blob, 0, 16);
        cbc.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
        byte[] plain = cbc.doFinal(blob, 16, blob.length - 16);
        int end = plain.length;
        while (end > 0 && plain[end - 1] == 0) end--;
        return new String(plain, 0, end, StandardCharsets.UTF_8);
    }

    private static void parsePayload(byte[] buf, int start, int end, List<FileMapping> out) {
        ProtoReader r = new ProtoReader(buf, start, end);
        while (!r.eof()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3;
            int wt = tag & 7;
            if (field == 1 && wt == 2) {
                int len = (int) r.readVarint();
                int subEnd = r.pos + len;
                out.add(parseFileMapping(buf, r.pos, subEnd));
                r.pos = subEnd;
            } else {
                r.skip(wt);
            }
        }
    }

    private static FileMapping parseFileMapping(byte[] buf, int start, int end) {
        FileMapping f = new FileMapping();
        ProtoReader r = new ProtoReader(buf, start, end);
        while (!r.eof()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3;
            int wt = tag & 7;
            switch (field) {
                case 1:
                    f.filename = r.readString();
                    break;
                case 2:
                    f.size = r.readVarint();
                    break;
                case 3:
                    f.flags = (int) r.readVarint();
                    break;
                case 4:
                    f.shaFilename = r.readBytes();
                    break;
                case 5:
                    f.shaContent = r.readBytes();
                    break;
                case 6: {
                    int len = (int) r.readVarint();
                    int subEnd = r.pos + len;
                    f.chunks.add(parseChunk(buf, r.pos, subEnd));
                    r.pos = subEnd;
                    break;
                }
                case 7:
                    f.linkTarget = r.readString();
                    break;
                default:
                    r.skip(wt);
            }
        }
        return f;
    }

    private static ChunkData parseChunk(byte[] buf, int start, int end) {
        ChunkData c = new ChunkData();
        ProtoReader r = new ProtoReader(buf, start, end);
        while (!r.eof()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3;
            int wt = tag & 7;
            switch (field) {
                case 1:
                    c.sha = r.readBytes();
                    break;
                case 2:
                    c.crc = r.readFixed32();
                    break;
                case 3:
                    c.offset = r.readVarint();
                    break;
                case 4:
                    c.cbOriginal = (int) r.readVarint();
                    break;
                case 5:
                    c.cbCompressed = (int) r.readVarint();
                    break;
                default:
                    r.skip(wt);
            }
        }
        return c;
    }

    private static void parseMetadata(byte[] buf, int start, int end, Manifest m) {
        ProtoReader r = new ProtoReader(buf, start, end);
        while (!r.eof()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3;
            int wt = tag & 7;
            switch (field) {
                case 1:
                    m.depotId = (int) r.readVarint();
                    break;
                case 2:
                    m.gidManifest = r.readVarint();
                    break;
                case 4:
                    m.filenamesEncrypted = r.readVarint() != 0;
                    break;
                case 5:
                    m.cbDiskOriginal = r.readVarint();
                    break;
                case 6:
                    m.cbDiskCompressed = r.readVarint();
                    break;
                case 7:
                    m.uniqueChunks = (int) r.readVarint();
                    break;
                default:
                    r.skip(wt);
            }
        }
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static final class ProtoReader {
        final byte[] buf;
        final int end;
        int pos;

        ProtoReader(byte[] buf, int start, int end) {
            this.buf = buf;
            this.pos = start;
            this.end = Math.min(end, buf.length);
        }

        boolean eof() {
            return pos >= end;
        }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (pos < end) {
                int b = buf[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift > 63) break;
            }
            return result;
        }

        int readFixed32() {
            if (pos + 4 > end) {
                pos = end;
                return 0;
            }
            int v = readIntLE(buf, pos);
            pos += 4;
            return v;
        }

        byte[] readBytes() {
            int len = (int) readVarint();
            if (len <= 0 || pos + len > end) {
                pos = Math.min(end, pos + Math.max(0, len));
                return new byte[0];
            }
            byte[] out = Arrays.copyOfRange(buf, pos, pos + len);
            pos += len;
            return out;
        }

        String readString() {
            byte[] b = readBytes();
            return new String(b, StandardCharsets.UTF_8);
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0:
                    readVarint();
                    break;
                case 1:
                    pos = Math.min(end, pos + 8);
                    break;
                case 2: {
                    int len = (int) readVarint();
                    pos = Math.min(end, pos + Math.max(0, len));
                    break;
                }
                case 5:
                    pos = Math.min(end, pos + 4);
                    break;
                default:
                    pos = end;
            }
        }
    }
}
