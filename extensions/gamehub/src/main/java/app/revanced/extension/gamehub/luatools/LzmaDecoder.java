package app.revanced.extension.gamehub.luatools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Zero-dependency pure-Java LZMA1 raw decoder for Steam VZa depot chunks.
 * Accepts the 5-byte LZMA properties header (lclppb + little-endian dictSize)
 * and decodes the raw LZMA stream up to {@code outSize} bytes.
 */
final class LzmaDecoder {

    private static final int NUM_STATES = 12;
    private static final int NUM_POS_STATES_MAX = 16;
    private static final int NUM_LEN_TO_POS_STATES = 4;
    private static final int NUM_POS_SLOT_BITS = 6;
    private static final int START_POS_MODEL_INDEX = 4;
    private static final int END_POS_MODEL_INDEX = 14;
    private static final int NUM_FULL_DISTANCES = 1 << (END_POS_MODEL_INDEX >> 1);
    private static final int NUM_ALIGN_BITS = 4;
    private static final int ALIGN_TABLE_SIZE = 1 << NUM_ALIGN_BITS;
    private static final int MATCH_MIN_LEN = 2;
    private static final int NUM_LOW_LEN_BITS = 3;
    private static final int NUM_MID_LEN_BITS = 3;
    private static final int NUM_HIGH_LEN_BITS = 8;
    private static final int NUM_LOW_LEN_SYMBOLS = 1 << NUM_LOW_LEN_BITS;
    private static final int NUM_MID_LEN_SYMBOLS = 1 << NUM_MID_LEN_BITS;

    private static final int NUM_MOVE_BITS = 5;
    private static final int BIT_MODEL_TOTAL_BITS = 11;
    private static final int BIT_MODEL_TOTAL = 1 << BIT_MODEL_TOTAL_BITS;
    private static final int TOP_MASK = ~((1 << 24) - 1);

    private LzmaDecoder() {}

    static byte[] decompressRaw(byte[] props, InputStream in, long outSize) throws IOException {
        if (props == null || props.length != 5) {
            throw new IOException("Invalid LZMA properties");
        }
        int d = props[0] & 0xFF;
        if (d >= 9 * 5 * 5) {
            throw new IOException("Invalid LZMA lc/lp/pb");
        }
        int lc = d % 9;
        d /= 9;
        int lp = d % 5;
        int pb = d / 5;
        int dictSize = (props[1] & 0xFF)
                | ((props[2] & 0xFF) << 8)
                | ((props[3] & 0xFF) << 16)
                | ((props[4] & 0xFF) << 24);
        if (dictSize < 0) dictSize = Integer.MAX_VALUE;
        if (dictSize < 1) dictSize = 1;
        // Clamp huge dictionary allocations for safety while honoring real Steam chunks (<=16MB).
        if (dictSize > 32 * 1024 * 1024) dictSize = 32 * 1024 * 1024;

        RangeDecoder rc = new RangeDecoder(in);
        OutWindow out = new OutWindow(dictSize);
        LitDecoder lit = new LitDecoder(lc, lp);
        LenDecoder lenDec = new LenDecoder(1 << pb);
        LenDecoder repLenDec = new LenDecoder(1 << pb);

        short[] isMatch = new short[NUM_STATES << 4];
        short[] isRep = new short[NUM_STATES];
        short[] isRepG0 = new short[NUM_STATES];
        short[] isRepG1 = new short[NUM_STATES];
        short[] isRepG2 = new short[NUM_STATES];
        short[] isRep0Long = new short[NUM_STATES << 4];
        BitTreeDecoder[] posSlot = new BitTreeDecoder[NUM_LEN_TO_POS_STATES];
        for (int i = 0; i < NUM_LEN_TO_POS_STATES; i++) {
            posSlot[i] = new BitTreeDecoder(NUM_POS_SLOT_BITS);
        }
        short[] posDecoders = new short[NUM_FULL_DISTANCES - END_POS_MODEL_INDEX];
        BitTreeDecoder posAlign = new BitTreeDecoder(NUM_ALIGN_BITS);

        initProbs(isMatch);
        initProbs(isRep);
        initProbs(isRepG0);
        initProbs(isRepG1);
        initProbs(isRepG2);
        initProbs(isRep0Long);
        initProbs(posDecoders);

        int state = 0;
        int rep0 = 0, rep1 = 0, rep2 = 0, rep3 = 0;
        long nowPos = 0;
        int posStateMask = (1 << pb) - 1;

        while (outSize < 0 || nowPos < outSize) {
            int posState = (int) nowPos & posStateMask;
            if (rc.decodeBit(isMatch, (state << 4) + posState) == 0) {
                byte prevByte = nowPos == 0 ? 0 : out.getByte(0);
                byte b;
                if (state < 7) {
                    b = lit.decodeNormal(rc, (int) nowPos, prevByte);
                } else {
                    b = lit.decodeWithMatchByte(rc, (int) nowPos, prevByte, out.getByte(rep0));
                }
                out.putByte(b);
                state = stateUpdateChar(state);
                nowPos++;
            } else {
                int len;
                if (rc.decodeBit(isRep, state) == 1) {
                    len = 0;
                    if (rc.decodeBit(isRepG0, state) == 0) {
                        if (rc.decodeBit(isRep0Long, (state << 4) + posState) == 0) {
                            state = stateUpdateShortRep(state);
                            len = 1;
                        }
                    } else {
                        int dist;
                        if (rc.decodeBit(isRepG1, state) == 0) {
                            dist = rep1;
                        } else {
                            if (rc.decodeBit(isRepG2, state) == 0) {
                                dist = rep2;
                            } else {
                                dist = rep3;
                                rep3 = rep2;
                            }
                            rep2 = rep1;
                        }
                        rep1 = rep0;
                        rep0 = dist;
                    }
                    if (len == 0) {
                        len = repLenDec.decode(rc, posState) + MATCH_MIN_LEN;
                        state = stateUpdateRep(state);
                    }
                } else {
                    rep3 = rep2;
                    rep2 = rep1;
                    rep1 = rep0;
                    len = MATCH_MIN_LEN + lenDec.decode(rc, posState);
                    state = stateUpdateMatch(state);
                    int lenState = len - MATCH_MIN_LEN;
                    if (lenState >= NUM_LEN_TO_POS_STATES) lenState = NUM_LEN_TO_POS_STATES - 1;
                    int slot = posSlot[lenState].decode(rc);
                    if (slot >= START_POS_MODEL_INDEX) {
                        int numDirectBits = (slot >> 1) - 1;
                        rep0 = (2 | (slot & 1)) << numDirectBits;
                        if (slot < END_POS_MODEL_INDEX) {
                            rep0 += reverseDecode(posDecoders, rep0 - slot - 1, rc, numDirectBits);
                        } else {
                            rep0 += rc.decodeDirectBits(numDirectBits - NUM_ALIGN_BITS) << NUM_ALIGN_BITS;
                            rep0 += posAlign.reverseDecode(rc);
                            if (rep0 < 0) {
                                if (rep0 == -1) break;
                                throw new IOException("Corrupt LZMA stream");
                            }
                        }
                    } else {
                        rep0 = slot;
                    }
                }
                if (rep0 >= nowPos || rep0 >= dictSize) {
                    throw new IOException("Invalid LZMA match distance");
                }
                if (outSize >= 0 && nowPos + len > outSize) {
                    len = (int) (outSize - nowPos);
                }
                out.copyBlock(rep0, len);
                nowPos += len;
            }
        }
        return out.toByteArray();
    }

    private static void initProbs(short[] p) {
        Arrays.fill(p, (short) (BIT_MODEL_TOTAL >>> 1));
    }

    private static int stateUpdateChar(int s) {
        if (s < 4) return 0;
        if (s < 10) return s - 3;
        return s - 6;
    }

    private static int stateUpdateMatch(int s) { return s < 7 ? 7 : 10; }
    private static int stateUpdateRep(int s) { return s < 7 ? 8 : 11; }
    private static int stateUpdateShortRep(int s) { return s < 7 ? 9 : 11; }

    private static int reverseDecode(short[] models, int startIdx, RangeDecoder rc, int numBits) throws IOException {
        int m = 1;
        int symbol = 0;
        for (int bitIndex = 0; bitIndex < numBits; bitIndex++) {
            int bit = rc.decodeBit(models, startIdx + m);
            m = (m << 1) + bit;
            symbol |= (bit << bitIndex);
        }
        return symbol;
    }

    private static final class RangeDecoder {
        private final InputStream in;
        private int range = -1;
        private int code = 0;

        RangeDecoder(InputStream in) throws IOException {
            this.in = in;
            for (int i = 0; i < 5; i++) {
                int b = in.read();
                if (b < 0) throw new IOException("Unexpected EOF in LZMA header");
                code = (code << 8) | b;
            }
        }

        int decodeBit(short[] probs, int index) throws IOException {
            int prob = probs[index] & 0xFFFF;
            int newBound = (range >>> BIT_MODEL_TOTAL_BITS) * prob;
            if ((code ^ 0x80000000) < (newBound ^ 0x80000000)) {
                range = newBound;
                probs[index] = (short) (prob + ((BIT_MODEL_TOTAL - prob) >>> NUM_MOVE_BITS));
                normalize();
                return 0;
            } else {
                range -= newBound;
                code -= newBound;
                probs[index] = (short) (prob - (prob >>> NUM_MOVE_BITS));
                normalize();
                return 1;
            }
        }

        int decodeDirectBits(int numTotalBits) throws IOException {
            int result = 0;
            for (int i = numTotalBits; i != 0; i--) {
                range >>>= 1;
                int t = ((code - range) >>> 31);
                code -= range & (t - 1);
                result = (result << 1) | (1 - t);
                normalize();
            }
            return result;
        }

        private void normalize() throws IOException {
            if ((range & TOP_MASK) == 0) {
                int b = in.read();
                code = (code << 8) | (b < 0 ? 0 : b);
                range <<= 8;
            }
        }
    }

    private static final class BitTreeDecoder {
        private final short[] models;
        private final int numBitLevels;

        BitTreeDecoder(int numBitLevels) {
            this.numBitLevels = numBitLevels;
            this.models = new short[1 << numBitLevels];
            initProbs(models);
        }

        int decode(RangeDecoder rc) throws IOException {
            int m = 1;
            for (int i = numBitLevels; i != 0; i--) {
                m = (m << 1) + rc.decodeBit(models, m);
            }
            return m - (1 << numBitLevels);
        }

        int reverseDecode(RangeDecoder rc) throws IOException {
            int m = 1;
            int symbol = 0;
            for (int i = 0; i < numBitLevels; i++) {
                int bit = rc.decodeBit(models, m);
                m = (m << 1) + bit;
                symbol |= (bit << i);
            }
            return symbol;
        }
    }

    private static final class LenDecoder {
        private final short[] choice = new short[2];
        private final BitTreeDecoder[] lowCoder = new BitTreeDecoder[NUM_POS_STATES_MAX];
        private final BitTreeDecoder[] midCoder = new BitTreeDecoder[NUM_POS_STATES_MAX];
        private final BitTreeDecoder highCoder = new BitTreeDecoder(NUM_HIGH_LEN_BITS);

        LenDecoder(int numPosStates) {
            initProbs(choice);
            for (int i = 0; i < numPosStates; i++) {
                lowCoder[i] = new BitTreeDecoder(NUM_LOW_LEN_BITS);
                midCoder[i] = new BitTreeDecoder(NUM_MID_LEN_BITS);
            }
        }

        int decode(RangeDecoder rc, int posState) throws IOException {
            if (rc.decodeBit(choice, 0) == 0) {
                return lowCoder[posState].decode(rc);
            }
            int symbol = NUM_LOW_LEN_SYMBOLS;
            if (rc.decodeBit(choice, 1) == 0) {
                symbol += midCoder[posState].decode(rc);
            } else {
                symbol += NUM_MID_LEN_SYMBOLS + highCoder.decode(rc);
            }
            return symbol;
        }
    }

    private static final class LitDecoder {
        private final int numPrevBits;
        private final int posMask;
        private final short[][] decoders;

        LitDecoder(int lc, int lp) {
            this.numPrevBits = lc;
            this.posMask = (1 << lp) - 1;
            int states = 1 << (lc + lp);
            this.decoders = new short[states][0x300];
            for (int i = 0; i < states; i++) initProbs(decoders[i]);
        }

        private short[] state(int pos, byte prevByte) {
            int idx = ((pos & posMask) << numPrevBits) + ((prevByte & 0xFF) >>> (8 - numPrevBits));
            return decoders[idx];
        }

        byte decodeNormal(RangeDecoder rc, int pos, byte prevByte) throws IOException {
            short[] probs = state(pos, prevByte);
            int symbol = 1;
            do {
                symbol = (symbol << 1) | rc.decodeBit(probs, symbol);
            } while (symbol < 0x100);
            return (byte) symbol;
        }

        byte decodeWithMatchByte(RangeDecoder rc, int pos, byte prevByte, byte matchByte) throws IOException {
            short[] probs = state(pos, prevByte);
            int symbol = 1;
            int m = matchByte & 0xFF;
            do {
                int matchBit = (m >> 7) & 1;
                m <<= 1;
                int bit = rc.decodeBit(probs, ((1 + matchBit) << 8) + symbol);
                symbol = (symbol << 1) | bit;
                if (matchBit != bit) {
                    while (symbol < 0x100) {
                        symbol = (symbol << 1) | rc.decodeBit(probs, symbol);
                    }
                    break;
                }
            } while (symbol < 0x100);
            return (byte) symbol;
        }
    }

    private static final class OutWindow {
        private final byte[] buf;
        private int pos = 0;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        OutWindow(int dictSize) {
            this.buf = new byte[dictSize];
        }

        void putByte(byte b) {
            buf[pos++] = b;
            if (pos >= buf.length) pos = 0;
            out.write(b & 0xFF);
        }

        byte getByte(int distance) {
            int idx = pos - distance - 1;
            if (idx < 0) idx += buf.length;
            return buf[idx];
        }

        void copyBlock(int distance, int len) {
            int p = pos - distance - 1;
            if (p < 0) p += buf.length;
            for (; len != 0; len--) {
                if (p >= buf.length) p = 0;
                byte b = buf[p++];
                buf[pos++] = b;
                if (pos >= buf.length) pos = 0;
                out.write(b & 0xFF);
            }
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
