package poker.evaluator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Generates HandRanks.dat, the "Two Plus Two" 7-card hand-rank lookup table read by
 * {@link poker.evaluator.Evaluator}.
 *
 * <p>The file is a 32,487,834 entry array of little-endian 32-bit ints (129,951,336 bytes).
 * It used to be checked in via Git LFS; LFS is disabled on the repository, so it is generated
 * locally instead. The output is byte-identical to the canonical table: it is verified against
 * the sha256 recorded in the old LFS pointer and against the table offsets that EvaluatorSpec
 * asserts.
 *
 * <p>Lookup protocol (see Evaluator.eval): start at p = 53, then p = HR[p + card] for each
 * card, where 1 = 2c, 2 = 2d, 3 = 2h, 4 = 2s, 5 = 3c, and so on up to 52 = as. After 7 cards
 * p is the hand value; with 5 or 6 cards take one extra step, p = HR[p]. Then p >>> 12 is the
 * hand category and p and 0xfff is the rank within that category.
 *
 * <p>Layout: entries 0..52 are a lead-in block, then each of the 612,977 reachable hand IDs
 * owns a block of 53 ints. Slot 0 of a block holds the evaluation of the cards of that block
 * itself, filled in only for 5- and 6-card hands; slots 1..52 point at the block for "these
 * cards plus that card", or, when that card would be the 7th, hold the final hand value.
 *
 * <p>A hand ID packs up to 7 cards into a long, one byte per card, sorted descending, each
 * byte holding (rank + 1) &lt;&lt; 4 | (suit + 1). The suit nibble is cleared once a flush is
 * out of reach: holding n of the 7 cards, a suit can still matter only if at least n - 2 cards
 * already share it. That collapse is what reduces the deal to 612,977 distinct states.
 *
 * <p>Run it directly, no build required (JDK 11+, a few seconds):
 * <pre>java -Xmx1g tools/HandRankTableGenerator.java src/main/resources/HandRanks.dat</pre>
 */
public final class HandRankTableGenerator {

    /** Number of int entries in the table. */
    public static final int HR_SIZE = 32_487_834;
    /** Size of the generated file in bytes. */
    public static final long EXPECTED_BYTES = (long) HR_SIZE * 4L;
    /** sha256 of the canonical table, taken from the old Git LFS pointer in this repository. */
    public static final String EXPECTED_SHA256 =
            "ad00f3976ad278f2cfd8c47b008cf4dbdefac642d70755a9f20707f8bbeb3c7e";
    /** Reachable hand IDs, counting the empty hand in slot 0. */
    public static final int EXPECTED_IDS = 612_977;

    private HandRankTableGenerator() {
    }

    // ---------------------------------------------------------------- hand IDs

    private static final int[] work = new int[8];
    private static final int[] suitCount = new int[16];
    private static final int[] rankCount = new int[16];

    /**
     * Adds newCard (1..52) to the hand encoded by idIn and returns the resulting hand ID, or 0
     * if the card cannot be added: a duplicate card, or a fifth card of some rank.
     */
    static long makeId(long idIn, int newCard) {
        final int[] w = work;
        final int[] sc = suitCount;
        final int[] rc = rankCount;
        Arrays.fill(w, 0);
        Arrays.fill(sc, 0);
        Arrays.fill(rc, 0);

        // A stored ID holds at most 6 cards, so 6 bytes is all there is to unpack.
        for (int i = 0; i < 6; i++) {
            w[i + 1] = (int) ((idIn >>> (8 * i)) & 0xff);
        }

        final int c = newCard - 1;
        w[0] = (((c >> 2) + 1) << 4) + (c & 3) + 1;

        int numCards = 0;
        boolean duplicate = false;
        while (numCards < 8 && w[numCards] != 0) {
            sc[w[numCards] & 0xf]++;
            rc[(w[numCards] >> 4) & 0xf]++;
            if (numCards > 0 && w[0] == w[numCards]) {
                duplicate = true;
            }
            numCards++;
        }
        if (duplicate) {
            return 0;
        }
        for (int rank = 1; rank < 14; rank++) {
            if (rc[rank] > 4) {
                return 0;
            }
        }

        // Drop the suit of every card that can no longer take part in a flush.
        final int needSuited = numCards - 2;
        if (needSuited > 1) {
            for (int i = 0; i < numCards; i++) {
                if (sc[w[i] & 0xf] < needSuited) {
                    w[i] &= 0xf0;
                }
            }
        }

        sortDescending(w, numCards);

        long id = 0;
        for (int i = 0; i < 7; i++) {
            id |= ((long) (w[i] & 0xff)) << (8 * i);
        }
        return id;
    }

    private static void sortDescending(int[] a, int n) {
        for (int i = 1; i < n; i++) {
            final int v = a[i];
            int j = i - 1;
            while (j >= 0 && a[j] < v) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
    }

    /** Number of cards packed into a hand ID. */
    static int cardCount(long id) {
        int n = 0;
        while (n < 7 && ((id >>> (8 * n)) & 0xff) != 0) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------- 5-card ranks

    /** Straight index by 13-bit rank mask: 1 = 5-high (the wheel) to 10 = ace-high, 0 = none. */
    private static final int[] STRAIGHT = new int[1 << 13];
    /** Index 1..1277 of a rank mask with no pair and no straight, ascending by strength. */
    private static final int[] NO_PAIR = new int[1 << 13];
    private static final int[] C2 = new int[14];
    private static final int[] C3 = new int[14];

    static {
        STRAIGHT[(1 << 12) | 0b1111] = 1; // A5432
        for (int high = 4; high <= 12; high++) {
            int mask = 0;
            for (int r = high - 4; r <= high; r++) {
                mask |= 1 << r;
            }
            STRAIGHT[mask] = high - 2;
        }
        int index = 0;
        for (int mask = 0; mask < (1 << 13); mask++) {
            if (Integer.bitCount(mask) == 5 && STRAIGHT[mask] == 0) {
                NO_PAIR[mask] = ++index;
            }
        }
        if (index != 1277) {
            throw new AssertionError("expected 1277 no-pair rank masks, got " + index);
        }
        for (int n = 0; n < 14; n++) {
            C2[n] = n * (n - 1) / 2;
            C3[n] = n * (n - 1) * (n - 2) / 6;
        }
    }

    /** 0-based position of rank r among the 12 ranks other than x. */
    private static int without(int r, int x) {
        return r > x ? r - 1 : r;
    }

    /** 1-based position of rank r among the 11 ranks other than x and y. */
    private static int without(int r, int x, int y) {
        return r - (r > x ? 1 : 0) - (r > y ? 1 : 0) + 1;
    }

    /**
     * Evaluates the five cards at the given indices and returns category &lt;&lt; 12 | rank,
     * a value that orders hands by strength.
     */
    private static int eval5(int[] ranks, int[] suits, int i0, int i1, int i2, int i3, int i4) {
        final int r0 = ranks[i0], r1 = ranks[i1], r2 = ranks[i2], r3 = ranks[i3], r4 = ranks[i4];
        final int mask = (1 << r0) | (1 << r1) | (1 << r2) | (1 << r3) | (1 << r4);
        final int suit = suits[i0];
        final boolean flush = suits[i1] == suit && suits[i2] == suit
                && suits[i3] == suit && suits[i4] == suit;

        if (Integer.bitCount(mask) == 5) {
            final int straight = STRAIGHT[mask];
            if (straight != 0) {
                return flush ? (9 << 12) | straight : (5 << 12) | straight;
            }
            final int high = NO_PAIR[mask];
            return flush ? (6 << 12) | high : (1 << 12) | high;
        }

        // Paired hand. Counts go in four bits per rank so the hot loop clears no arrays.
        long counts = 0;
        counts += 1L << (r0 << 2);
        counts += 1L << (r1 << 2);
        counts += 1L << (r2 << 2);
        counts += 1L << (r3 << 2);
        counts += 1L << (r4 << 2);

        int quad = -1, trips = -1, highPair = -1, lowPair = -1;
        int k0 = -1, k1 = -1, k2 = -1, kickers = 0;
        int m = mask;
        while (m != 0) {
            final int r = 31 - Integer.numberOfLeadingZeros(m);
            m &= ~(1 << r);
            switch ((int) ((counts >>> (r << 2)) & 0xf)) {
                case 4:
                    quad = r;
                    break;
                case 3:
                    trips = r;
                    break;
                case 2:
                    if (highPair < 0) {
                        highPair = r;
                    } else {
                        lowPair = r;
                    }
                    break;
                default:
                    if (kickers == 0) {
                        k0 = r;
                    } else if (kickers == 1) {
                        k1 = r;
                    } else {
                        k2 = r;
                    }
                    kickers++;
            }
        }

        if (quad >= 0) {
            return (8 << 12) | (quad * 12 + without(k0, quad) + 1);
        }
        if (trips >= 0 && highPair >= 0) {
            return (7 << 12) | (trips * 12 + without(highPair, trips) + 1);
        }
        if (trips >= 0) {
            return (4 << 12) | (trips * 66 + C2[without(k0, trips)] + without(k1, trips) + 1);
        }
        if (lowPair >= 0) {
            final int pairIndex = C2[highPair] + lowPair + 1;
            return (3 << 12) | ((pairIndex - 1) * 11 + without(k0, highPair, lowPair));
        }
        return (2 << 12) | (highPair * 220 + C3[without(k0, highPair)]
                + C2[without(k1, highPair)] + without(k2, highPair) + 1);
    }

    // --------------------------------------------------------- hand evaluation

    private static final int[][] COMBINATIONS = new int[8][];

    static {
        for (int n = 5; n <= 7; n++) {
            final int[] flat = new int[choose5(n) * 5];
            int at = 0;
            for (int a = 0; a < n; a++) {
                for (int b = a + 1; b < n; b++) {
                    for (int c = b + 1; c < n; c++) {
                        for (int d = c + 1; d < n; d++) {
                            for (int e = d + 1; e < n; e++) {
                                flat[at++] = a;
                                flat[at++] = b;
                                flat[at++] = c;
                                flat[at++] = d;
                                flat[at++] = e;
                            }
                        }
                    }
                }
            }
            COMBINATIONS[n] = flat;
        }
    }

    private static int choose5(int n) {
        return n == 5 ? 1 : n == 6 ? 6 : 21;
    }

    /** The three suits other than mainSuit; index 0 covers "no significant suit at all". */
    private static final int[][] OTHER_SUITS = {
            {1, 2, 3}, {2, 3, 4}, {1, 3, 4}, {1, 2, 4}, {1, 2, 3},
    };

    private static final int[] evalRanks = new int[7];
    private static final int[] evalSuits = new int[7];

    /**
     * Evaluates the best 5-card hand held in a hand ID and returns category &lt;&lt; 12 | rank.
     * Cards whose suit nibble was cleared get a filler suit that cannot complete a flush: at
     * most 7 such cards spread over 3 suits leaves at most 3 of any one suit.
     */
    static int evaluate(long id) {
        if (id == 0) {
            return 0;
        }
        int n = 0;
        int mainSuit = 0;
        for (int i = 0; i < 7; i++) {
            final int b = (int) ((id >>> (8 * i)) & 0xff);
            if (b == 0) {
                break;
            }
            evalRanks[n] = ((b >> 4) & 0xf) - 1;
            final int suit = b & 0xf;
            evalSuits[n] = suit;
            if (suit != 0) {
                mainSuit = suit;
            }
            n++;
        }

        final int[] filler = OTHER_SUITS[mainSuit];
        int next = 0;
        for (int i = 0; i < n; i++) {
            if (evalSuits[i] == 0) {
                evalSuits[i] = filler[next % 3];
                next++;
            }
        }

        final int[] combos = COMBINATIONS[n];
        int best = 0;
        for (int i = 0; i < combos.length; i += 5) {
            final int value = eval5(evalRanks, evalSuits,
                    combos[i], combos[i + 1], combos[i + 2], combos[i + 3], combos[i + 4]);
            if (value > best) {
                best = value;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- the table

    /** Builds the lookup table in memory. */
    public static int[] buildTable() {
        final long[] ids = reachableIds();
        if (ids.length != EXPECTED_IDS) {
            throw new IllegalStateException(
                    "expected " + EXPECTED_IDS + " hand IDs, found " + ids.length);
        }

        final int[] hr = new int[HR_SIZE];
        for (int i = 0; i < ids.length; i++) {
            final long base = ids[i];
            final int held = cardCount(base);
            final int block = i * 53 + 53;
            for (int card = 1; card <= 52; card++) {
                final long id = makeId(base, card);
                if (held + 1 != 7) {
                    int slot = 0;
                    if (id != 0) {
                        slot = Arrays.binarySearch(ids, id);
                        if (slot < 0) {
                            throw new IllegalStateException("hand ID missing from index: " + id);
                        }
                    }
                    hr[block + card] = slot * 53 + 53;
                } else {
                    hr[block + card] = evaluate(id);
                }
            }
            // Slot 0 answers "what is this hand worth", the extra lookup Evaluator makes when
            // it holds fewer than 7 cards.
            if (held >= 5) {
                hr[block] = evaluate(base);
            }
        }
        return hr;
    }

    /**
     * Every hand ID reachable by dealing 1 to 6 cards. The order in which they are discovered
     * does not matter: the canonical table numbers the IDs by ascending value, which is what
     * the sorted insert of the original generator leaves behind.
     */
    private static long[] reachableIds() {
        final LongSet seen = new LongSet(1 << 21);
        long[] queue = new long[1 << 20];
        int head = 0;
        int tail = 0;
        queue[tail++] = 0L; // the empty hand
        while (head < tail) {
            final long base = queue[head++];
            if (cardCount(base) >= 6) {
                continue; // a 7th card yields a hand value, not a stored ID
            }
            for (int card = 1; card <= 52; card++) {
                final long id = makeId(base, card);
                if (id != 0 && seen.add(id)) {
                    if (tail == queue.length) {
                        queue = Arrays.copyOf(queue, queue.length * 2);
                    }
                    queue[tail++] = id;
                }
            }
        }
        final long[] ids = new long[seen.size() + 1];
        seen.copyInto(ids, 1);
        Arrays.sort(ids); // ids[0] stays 0, the empty hand, which sorts first
        return ids;
    }

    /** Minimal open-addressed set of non-zero longs, to keep the closure allocation-free. */
    private static final class LongSet {
        private final long[] keys;
        private final int mask;
        private int size;

        LongSet(int capacity) {
            this.keys = new long[capacity];
            this.mask = capacity - 1;
        }

        boolean add(long key) {
            int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & mask;
            while (true) {
                final long current = keys[i];
                if (current == 0) {
                    keys[i] = key;
                    size++;
                    return true;
                }
                if (current == key) {
                    return false;
                }
                i = (i + 1) & mask;
            }
        }

        int size() {
            return size;
        }

        void copyInto(long[] target, int offset) {
            int at = offset;
            for (final long key : keys) {
                if (key != 0) {
                    target[at++] = key;
                }
            }
        }
    }

    // ---------------------------------------------------------------- file I/O

    /** Writes the table as little-endian ints and returns its sha256. */
    public static String write(int[] hr, File target) throws IOException {
        final File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create directory " + parent);
        }
        final File temp = new File(parent, target.getName() + ".tmp");
        final MessageDigest digest = sha256();
        final byte[] bytes = new byte[1 << 16];
        final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp), 1 << 16)) {
            for (final int value : hr) {
                if (!buffer.hasRemaining()) {
                    out.write(bytes);
                    digest.update(bytes);
                    buffer.clear();
                }
                buffer.putInt(value);
            }
            final int remaining = buffer.position();
            if (remaining > 0) {
                out.write(bytes, 0, remaining);
                digest.update(bytes, 0, remaining);
            }
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("cannot replace " + target);
        }
        if (!temp.renameTo(target)) {
            throw new IOException("cannot move " + temp + " to " + target);
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- self-checks

    /** Table offsets and hand values that EvaluatorSpec asserts, checked before writing. */
    private static void selfCheck(int[] hr) {
        // Walking 8c Td 6s Kh 9d 3s 5h one card at a time, the way Evaluator does.
        check(hr, 53 + 25, 1378);
        check(hr, 1378 + 34, 53530);
        check(hr, 53530 + 20, 961950);
        check(hr, 961950 + 47, 4806782);
        check(hr, 4806782 + 30, 12819958);
        check(hr, 12819958 + 8, 22802402);
        check(hr, 22802402 + 15, 4676);

        // Known hand values: royal flush, king-high straight flush, quad kings with a ten,
        // kings full of aces, ace-high heart flush, seven-high straight, trip threes, kings up,
        // pair of threes, ace-high no pair, then the same full house held as 5 and as 6 cards.
        checkHand(hr, 36874, 52, 48, 44, 40, 36, 6, 15);
        checkHand(hr, 36873, 31, 47, 43, 39, 35, 7, 15);
        checkHand(hr, 32909, 47, 46, 45, 48, 35, 7, 15);
        checkHand(hr, 28816, 47, 46, 45, 52, 51, 7, 15);
        checkHand(hr, 25489, 7, 11, 15, 51, 39, 8, 12);
        checkHand(hr, 20483, 7, 10, 16, 19, 23, 8, 12);
        checkHand(hr, 16513, 7, 6, 8, 19, 27, 36, 52);
        checkHand(hr, 12915, 7, 6, 48, 47, 27, 36, 52);
        checkHand(hr, 8630, 7, 6, 24, 47, 27, 36, 52);
        checkHand(hr, 5286, 7, 10, 24, 47, 27, 36, 52);
        checkHand(hr, 4973, 2, 13, 27, 36, 52);
        checkHand(hr, 4645, 2, 13, 27, 36, 48);
        checkHand(hr, 28816, 46, 45, 51, 52, 48);
        checkHand(hr, 28816, 46, 45, 51, 52, 48, 8);
    }

    private static void check(int[] hr, int index, int expected) {
        if (hr[index] != expected) {
            throw new IllegalStateException(
                    "HR[" + index + "] = " + hr[index] + ", expected " + expected);
        }
    }

    private static void checkHand(int[] hr, int expected, int... cards) {
        int p = 53;
        for (final int card : cards) {
            p = hr[p + card];
        }
        if (cards.length < 7) {
            p = hr[p];
        }
        if (p != expected) {
            throw new IllegalStateException(
                    "hand " + Arrays.toString(cards) + " = " + p + ", expected " + expected);
        }
    }

    // --------------------------------------------------------------------- main

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: HandRankTableGenerator <output-file>");
            System.exit(2);
        }
        final File target = new File(args[0]);
        final long started = System.currentTimeMillis();

        System.err.println("Building hand-rank table (" + HR_SIZE + " entries)...");
        final int[] hr = buildTable();
        selfCheck(hr);
        System.err.println("Self-checks passed, writing " + target + " ...");

        final String sha256 = write(hr, target);
        final long seconds = (System.currentTimeMillis() - started) / 1000;
        System.err.println("Wrote " + target.length() + " bytes in " + seconds + "s");
        System.err.println("sha256 " + sha256);

        if (target.length() != EXPECTED_BYTES) {
            System.err.println("FAILED: expected " + EXPECTED_BYTES + " bytes");
            System.exit(1);
        }
        if (!EXPECTED_SHA256.equals(sha256)) {
            System.err.println("FAILED: expected sha256 " + EXPECTED_SHA256);
            System.exit(1);
        }
        System.err.println("OK: byte-identical to the canonical HandRanks.dat");
    }
}
