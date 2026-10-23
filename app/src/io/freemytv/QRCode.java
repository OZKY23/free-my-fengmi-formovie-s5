package io.freemytv;

/**
 * A small QR code encoder: byte mode, error correction L, versions 1 to 4 (the web remote's address and pin fit in
 * one). Pure Java, no Android: the home screen draws the boolean matrix itself. Algorithm after the public-domain
 * reference implementation (Nayuki): Reed-Solomon on GF(256), one data block at these versions, all 8 masks tried.
 */
final class QRCode {
    private QRCode() {}

    /** The modules, row by row (true = dark), for the smallest version that fits the text. */
    static boolean[][] encode(String text) {
        byte[] textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int version = 1;
        while (version < 4 && textBytes.length > byteCapacity(version)) version++;
        if (textBytes.length > byteCapacity(version)) throw new IllegalArgumentException("text too long for the QR");
        int size = version * 4 + 17;

        boolean[][] isFunction = new boolean[size][size];
        boolean[][] modules = new boolean[size][size];
        drawPatterns(modules, isFunction, size, version);

        // The data bitstream: mode byte (0100), the byte count, the bytes, the terminator, the pad bytes.
        int dataCodewords = dataCodewords(version);
        int capacityBits = dataCodewords * 8;
        int[] bits = new int[capacityBits];
        int bit = 0;
        bits[bit++] = 0; bits[bit++] = 1; bits[bit++] = 0; bits[bit++] = 0;   // 0100 = byte mode
        for (int i = 7; i >= 0; i--) bits[bit++] = (textBytes.length >>> i) & 1;
        for (byte b : textBytes) for (int i = 7; i >= 0; i--) bits[bit++] = (b >>> i) & 1;
        for (int i = 0; i < 4 && bit < capacityBits; i++) bits[bit++] = 0;    // terminator
        int padStart = (bit + 7) / 8 * 8;   // the pad bytes start at the next byte boundary
        for (; bit < capacityBits; bit++) {
            bits[bit] = bit < padStart ? 0 : (0xEC11 >>> (15 - (bit - padStart) % 16)) & 1;   // 11101100 00010001
        }
        byte[] codewords = new byte[dataCodewords];
        for (int i = 0; i < dataCodewords; i++) {
            int v = 0;
            for (int j = 0; j < 8; j++) v = v << 1 | bits[i * 8 + j];
            codewords[i] = (byte) v;
        }

        byte[] ecc = reedSolomon(codewords, eccCodewords(version));
        byte[] all = new byte[codewords.length + ecc.length];
        System.arraycopy(codewords, 0, all, 0, codewords.length);
        System.arraycopy(ecc, 0, all, codewords.length, ecc.length);

        // The best of the 8 masks, tried on the real placement.
        boolean[][] best = null;
        int bestPenalty = 0;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] m = new boolean[size][size];
            for (int y = 0; y < size; y++) System.arraycopy(modules[y], 0, m[y], 0, size);
            drawFormat(m, isFunction, size, mask);
            placeData(m, isFunction, size, all, mask);
            int p = penalty(m, size);
            if (best == null || p < bestPenalty) {
                best = m;
                bestPenalty = p;
            }
        }
        return best;
    }

    private static int byteCapacity(int version) {
        return dataCodewords(version) - 2;   // minus the mode (4 bits) and the byte count (8 bits), rounded down
    }

    private static int dataCodewords(int version) {
        switch (version) {
            case 1: return 19;
            case 2: return 34;
            case 3: return 55;
            default: return 80;   // version 4
        }
    }

    private static int eccCodewords(int version) {
        switch (version) {
            case 1: return 7;
            case 2: return 10;
            case 3: return 15;
            default: return 20;   // version 4
        }
    }

    /** The finders with separators, the timing patterns, the alignment pattern and the dark module. */
    private static void drawPatterns(boolean[][] modules, boolean[][] isFunction, int size, int version) {
        for (int[] c : new int[][] {{0, 0}, {size - 7, 0}, {0, size - 7}}) {
            for (int dy = -1; dy <= 7; dy++) {
                for (int dx = -1; dx <= 7; dx++) {
                    int x = c[0] + dx, y = c[1] + dy;
                    if (x < 0 || x >= size || y < 0 || y >= size) continue;
                    isFunction[y][x] = true;
                    boolean inFinder = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6;
                    modules[y][x] = inFinder
                            && (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
                }
            }
        }
        for (int i = 8; i < size - 8; i++) {
            boolean dark = i % 2 == 0;
            modules[6][i] = dark;
            isFunction[6][i] = true;
            modules[i][6] = dark;
            isFunction[i][6] = true;
        }
        if (version >= 2) {   // one alignment pattern, away from the finders, from version 2 up
            int a = alignmentPositions(version)[1];
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    isFunction[a + dy][a + dx] = true;
                    modules[a + dy][a + dx] = Math.max(Math.abs(dx), Math.abs(dy)) != 1;
                }
            }
        }
        modules[size - 8][8] = true;
        isFunction[size - 8][8] = true;
    }

    private static int[] alignmentPositions(int version) {
        switch (version) {
            case 1: return new int[] {-1, -1};
            case 2: return new int[] {6, 18};
            case 3: return new int[] {6, 22};
            default: return new int[] {6, 26};   // version 4
        }
    }

    /** The format information (ECC L = 01, then the mask) with its BCH code, drawn twice, plus the dark module. */
    private static void drawFormat(boolean[][] modules, boolean[][] isFunction, int size, int mask) {
        int data = (1 << 3) | mask;   // the format bits for error correction L
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        // First copy: the column at x=8 and the row at y=8 (the timing pattern is skipped).
        for (int i = 0; i <= 5; i++) {
            modules[i][8] = ((bits >> i) & 1) != 0;
            isFunction[i][8] = true;
        }
        modules[7][8] = ((bits >> 6) & 1) != 0;
        isFunction[7][8] = true;
        modules[8][8] = ((bits >> 7) & 1) != 0;
        isFunction[8][8] = true;
        modules[8][7] = ((bits >> 8) & 1) != 0;
        isFunction[8][7] = true;
        for (int i = 9; i < 15; i++) {
            modules[8][14 - i] = ((bits >> i) & 1) != 0;
            isFunction[8][14 - i] = true;
        }
        // Second copy, by the other two finders.
        for (int i = 0; i < 8; i++) {
            modules[8][size - 1 - i] = ((bits >> i) & 1) != 0;
            isFunction[8][size - 1 - i] = true;
        }
        for (int i = 8; i < 15; i++) {
            modules[size - 15 + i][8] = ((bits >> i) & 1) != 0;
            isFunction[size - 15 + i][8] = true;
        }
    }

    private static void placeData(boolean[][] modules, boolean[][] isFunction, int size, byte[] all, int mask) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (isFunction[y][x]) continue;
                    if (i < all.length * 8) {
                        boolean dark = ((all[i >> 3] >> (7 - (i & 7))) & 1) != 0;
                        modules[y][x] = dark ^ maskBit(mask, x, y);
                        i++;
                    } else {
                        modules[y][x] = false;   // the remainder bits: zero, unmasked
                    }
                }
            }
        }
    }

    private static boolean maskBit(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (y / 2 + x / 3) % 2 == 0;
            case 5: return x * y % 2 + x * y % 3 == 0;
            case 6: return (x * y % 2 + x * y % 3) % 2 == 0;
            default: return (x + y) % 2 + x * y % 3 == 0;   // mask 7
        }
    }

    /** The remainder of the data by the generator polynomial (one block: no interleaving at these versions). */
    private static byte[] reedSolomon(byte[] data, int degree) {
        byte[] divisor = new byte[degree];
        divisor[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < divisor.length; j++) {
                divisor[j] = (byte) multiply(divisor[j] & 0xFF, root);
                if (j + 1 < divisor.length) divisor[j] ^= divisor[j + 1];
            }
            root = multiply(root, 2);
        }
        byte[] result = new byte[degree];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ (result[0] & 0xFF);
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < divisor.length; i++) result[i] ^= multiply(factor, divisor[i] & 0xFF);
        }
        return result;
    }

    /** Multiplication in GF(2^8), the Russian-peasant way (the field's polynomial is 0x11D). */
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    /** A simplified penalty: the runs, the 2×2 blocks and the finder-like pattern (rule 4 skipped). */
    private static int penalty(boolean[][] m, int size) {
        int result = 0;
        for (int y = 0; y < size; y++) {
            boolean color = m[y][0];
            int run = 1;
            for (int x = 1; x < size; x++) {
                if (m[y][x] == color) {
                    if (++run == 5) result += 3;
                    else if (run > 5) result++;
                } else {
                    color = m[y][x];
                    run = 1;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            boolean color = m[0][x];
            int run = 1;
            for (int y = 1; y < size; y++) {
                if (m[y][x] == color) {
                    if (++run == 5) result += 3;
                    else if (run > 5) result++;
                } else {
                    color = m[y][x];
                    run = 1;
                }
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (m[y][x + 1] == c && m[y + 1][x] == c && m[y + 1][x + 1] == c) result += 3;
            }
        }
        return result;
    }
}
