package dev.openapifn.schema;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Host name validation per RFC 1123 and IDNA2008 (RFC 5890-5893): LDH labels, Punycode A-labels
 * and Unicode U-labels with the contextual rules, exception table and Bidi rule. The IDNA2008
 * derived property is approximated from Unicode general categories and NFKC case folding.
 */
final class Idna {

    private static final int BASE = 36;
    private static final int TMIN = 1;
    private static final int TMAX = 26;
    private static final int SKEW = 38;
    private static final int DAMP = 700;
    private static final int INITIAL_BIAS = 72;
    private static final int INITIAL_N = 128;

    private static final Set<Integer> PVALID_EXCEPTIONS = Set.of(0x00DF, 0x03C2, 0x06FD, 0x06FE, 0x0F0B, 0x3007);
    private static final Set<Integer> DISALLOWED_EXCEPTIONS = Set.of(0x0640, 0x07FA, 0x302E, 0x302F, 0x3031, 0x3032, 0x3033, 0x3034, 0x3035,
        0x303B);
    private static final Set<Integer> VIRAMAS = Set.of(0x094D, 0x09CD, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D3B, 0x0D3C, 0x0D4D,
        0x0DCA, 0x0E3A, 0x0EBA, 0x0F84, 0x1039, 0x103A, 0x1714, 0x1715, 0x1734, 0x17D2, 0x1A60, 0x1B44, 0x1BAA, 0x1BAB, 0x1BF2, 0x1BF3, 0x2D7F,
        0xA806, 0xA82C, 0xA8C4, 0xA953, 0xA9C0, 0xAAF6, 0xABED, 0x10A3F, 0x11046, 0x11070, 0x1107F, 0x110B9, 0x11133, 0x11134, 0x111C0,
        0x11235, 0x112EA, 0x1134D, 0x11442, 0x114C2, 0x115BF, 0x1163F, 0x116B6, 0x1172B, 0x11839, 0x1193D, 0x1193E, 0x119E0, 0x11A34,
        0x11A47, 0x11A99, 0x11C3F, 0x11D44, 0x11D45, 0x11D97);
    /** Right-joining Arabic letters (Joining_Type R); other Arabic-script letters are treated as dual-joining. */
    private static final Set<Integer> RIGHT_JOINING = Set.of(0x0622, 0x0623, 0x0624, 0x0625, 0x0627, 0x0629, 0x062F, 0x0630, 0x0631, 0x0632,
        0x0648, 0x0671, 0x0672, 0x0673, 0x0675, 0x0676, 0x0677, 0x0688, 0x0689, 0x068A, 0x068B, 0x068C, 0x068D, 0x068E, 0x068F, 0x0690,
        0x0691, 0x0692, 0x0693, 0x0694, 0x0695, 0x0696, 0x0697, 0x0698, 0x0699, 0x06C0, 0x06C3, 0x06C4, 0x06C5, 0x06C6, 0x06C7, 0x06C8,
        0x06C9, 0x06CA, 0x06CB, 0x06CD, 0x06CF, 0x06D2, 0x06D3, 0x06D5, 0x06EE, 0x06EF);

    /** Code points removed by the UTS #46 mapping. */
    private static final int[] IGNORED = {0x00AD, 0x00AD, 0x034F, 0x034F, 0x180B, 0x180F, 0x200B, 0x200B, 0x2060, 0x2060, 0x2064, 0x2064,
        0xFE00, 0xFE0F, 0xFEFF, 0xFEFF, 0x1BCA0, 0x1BCA3, 0xE0100, 0xE01EF};
    /** Old Hangul Jamo, which IDNA2008 disallows. */
    private static final int[] HANGUL_JAMO = {0x1100, 0x11FF, 0xA960, 0xA97F, 0xD7B0, 0xD7FF};
    private static final Set<Integer> LETTER_DIGIT_TYPES = Set.of((int) Character.LOWERCASE_LETTER, (int) Character.OTHER_LETTER,
        (int) Character.MODIFIER_LETTER, (int) Character.NON_SPACING_MARK, (int) Character.COMBINING_SPACING_MARK,
        (int) Character.DECIMAL_DIGIT_NUMBER);
    private static final Set<Integer> MARK_TYPES = Set.of((int) Character.NON_SPACING_MARK, (int) Character.COMBINING_SPACING_MARK,
        (int) Character.ENCLOSING_MARK);
    private static final Set<Character.UnicodeScript> JOINING_SCRIPTS = Set.of(Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC,
        Character.UnicodeScript.NKO, Character.UnicodeScript.MONGOLIAN);
    private static final String DIGITS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private Idna() {
    }

    /**
     * Validates a host name; {@code unicode} permits U-labels and IDNA full stops and applies the
     * UTS #46 mapping (case and width folding, removal of ignorable code points) first.
     */
    static boolean isValid(String host, boolean unicode) {
        var mapped = unicode ? map(host) : host;
        if (mapped.isEmpty()) {
            return false;
        }
        var labels = unicode ? mapped.split("[.\\u3002\\uFF0E\\uFF61]", -1) : mapped.split("\\.", -1);
        var unicodeLabels = new ArrayList<String>();
        int length = labels.length - 1;
        for (var label : labels) {
            var checked = label(label, unicode);
            if (checked.isEmpty()) {
                return false;
            }
            unicodeLabels.add(checked.get().unicode());
            length += checked.get().asciiLength();
        }
        return length <= 253 && bidiDomain(unicodeLabels);
    }

    private record Label(String unicode, int asciiLength) {
    }

    /** UTS #46 style mapping: drop ignorable code points, then NFKC with lower casing, then NFC. */
    private static String map(String host) {
        var kept = new StringBuilder();
        host.codePoints().filter(cp -> !ignored(cp)).forEach(kept::appendCodePoint);
        var folded = Normalizer.normalize(kept, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(folded, Normalizer.Form.NFC);
    }

    private static boolean ignored(int cp) {
        return CodePoints.inRanges(cp, IGNORED);
    }

    private static Optional<Label> label(String label, boolean unicode) {
        if (label.isEmpty()) {
            return Optional.empty();
        }
        var ascii = label.chars().allMatch(c -> c < 0x80);
        if (!ascii) {
            if (!unicode) {
                return Optional.empty();
            }
            var encoded = encode(label);
            return encoded.filter(a -> a.length() + 4 <= 63).filter(a -> validULabel(label)).map(a -> new Label(label, a.length() + 4));
        }
        if (label.length() > 63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) {
            return Optional.empty();
        }
        var reserved = label.length() >= 4 && label.charAt(2) == '-' && label.charAt(3) == '-';
        if (!reserved) {
            return Optional.of(new Label(label, label.length()));
        }
        if (!label.regionMatches(true, 0, "xn--", 0, 4)) {
            return Optional.empty();
        }
        var punycode = label.substring(4).toLowerCase(Locale.ROOT);
        return decode(punycode)
            .filter(decoded -> decoded.chars().anyMatch(c -> c >= 0x80))
            .filter(decoded -> encode(decoded).filter(punycode::equals).isPresent())
            .filter(Idna::validULabel)
            .map(decoded -> new Label(decoded, label.length()));
    }

    /** RFC 5891 section 5.4 and RFC 5892: the code points and context of a U-label. */
    private static boolean validULabel(String label) {
        if (!Normalizer.isNormalized(label, Normalizer.Form.NFC) || label.startsWith("-") || label.endsWith("-")
            || (label.length() >= 4 && label.charAt(2) == '-' && label.charAt(3) == '-')) {
            return false;
        }
        var cps = label.codePoints().toArray();
        if (isMark(cps[0])) {
            return false;
        }
        for (int i = 0; i < cps.length; i++) {
            if (!validCodePoint(cps, i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validCodePoint(int[] cps, int i) {
        int cp = cps[i];
        return switch (cp) {
            case 0x00B7 -> i > 0 && i + 1 < cps.length && cps[i - 1] == 'l' && cps[i + 1] == 'l';
            case 0x0375 -> i + 1 < cps.length && script(cps[i + 1]) == Character.UnicodeScript.GREEK;
            case 0x05F3, 0x05F4 -> i > 0 && script(cps[i - 1]) == Character.UnicodeScript.HEBREW;
            case 0x30FB -> java.util.Arrays.stream(cps).anyMatch(c -> c != 0x30FB && (script(c) == Character.UnicodeScript.HIRAGANA
                || script(c) == Character.UnicodeScript.KATAKANA || script(c) == Character.UnicodeScript.HAN));
            case 0x200D -> i > 0 && VIRAMAS.contains(cps[i - 1]);
            case 0x200C -> i > 0 && (VIRAMAS.contains(cps[i - 1]) || joinsAround(cps, i));
            default -> {
                if (cp >= 0x0660 && cp <= 0x0669) {
                    yield java.util.Arrays.stream(cps).noneMatch(c -> c >= 0x06F0 && c <= 0x06F9);
                }
                if (cp >= 0x06F0 && cp <= 0x06F9) {
                    yield java.util.Arrays.stream(cps).noneMatch(c -> c >= 0x0660 && c <= 0x0669);
                }
                yield pvalid(cp);
            }
        };
    }

    /** The ZERO WIDTH NON-JOINER regular expression rule of RFC 5892 appendix A.1. */
    private static boolean joinsAround(int[] cps, int index) {
        var before = IntStream.range(0, index).map(k -> joiningType(cps[index - 1 - k])).filter(type -> type != 'T').findFirst();
        var after = IntStream.range(index + 1, cps.length).map(j -> joiningType(cps[j])).filter(type -> type != 'T').findFirst();
        return "LD".indexOf(before.orElse('U')) >= 0 && "RD".indexOf(after.orElse('U')) >= 0;
    }

    private static char joiningType(int cp) {
        if (Character.getType(cp) == Character.NON_SPACING_MARK) {
            return 'T';
        }
        if (!JOINING_SCRIPTS.contains(script(cp)) || Character.getType(cp) != Character.OTHER_LETTER) {
            return 'U';
        }
        return RIGHT_JOINING.contains(cp) ? 'R' : 'D';
    }

    private static boolean pvalid(int cp) {
        if (PVALID_EXCEPTIONS.contains(cp) || cp == '-') {
            return true;
        }
        if (DISALLOWED_EXCEPTIONS.contains(cp) || CodePoints.inRanges(cp, HANGUL_JAMO) || !LETTER_DIGIT_TYPES.contains(Character.getType(cp))) {
            return false;
        }
        var text = Character.toString(cp);
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).equals(text);
    }

    private static boolean isMark(int cp) {
        return MARK_TYPES.contains(Character.getType(cp));
    }

    private static Character.UnicodeScript script(int cp) {
        return Character.UnicodeScript.of(cp);
    }

    /** RFC 5893: applies when any label contains right-to-left characters. */
    private static boolean bidiDomain(List<String> labels) {
        var rtl = labels.stream().anyMatch(label -> label.codePoints().anyMatch(cp -> {
            var d = Character.getDirectionality(cp);
            return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                || d == Character.DIRECTIONALITY_ARABIC_NUMBER;
        }));
        return !rtl || labels.stream().allMatch(Idna::bidiLabel);
    }

    private static boolean bidiLabel(String label) {
        var dirs = label.codePoints().map(cp -> Character.getDirectionality(cp)).toArray();
        var first = dirs[0];
        int last = dirs.length - 1;
        while (last > 0 && dirs[last] == Character.DIRECTIONALITY_NONSPACING_MARK) {
            last--;
        }
        if (first == Character.DIRECTIONALITY_RIGHT_TO_LEFT || first == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
            var hasEn = false;
            var hasAn = false;
            for (var d : dirs) {
                if (!in(d, Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC, Character.DIRECTIONALITY_ARABIC_NUMBER,
                    Character.DIRECTIONALITY_EUROPEAN_NUMBER, Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
                    Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR, Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR,
                    Character.DIRECTIONALITY_OTHER_NEUTRALS, Character.DIRECTIONALITY_BOUNDARY_NEUTRAL, Character.DIRECTIONALITY_NONSPACING_MARK)) {
                    return false;
                }
                hasEn |= d == Character.DIRECTIONALITY_EUROPEAN_NUMBER;
                hasAn |= d == Character.DIRECTIONALITY_ARABIC_NUMBER;
            }
            return !(hasEn && hasAn) && in(dirs[last], Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER, Character.DIRECTIONALITY_ARABIC_NUMBER);
        }
        if (first != Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            return false;
        }
        for (var d : dirs) {
            if (!in(d, Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.DIRECTIONALITY_EUROPEAN_NUMBER,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR, Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
                Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR, Character.DIRECTIONALITY_OTHER_NEUTRALS,
                Character.DIRECTIONALITY_BOUNDARY_NEUTRAL, Character.DIRECTIONALITY_NONSPACING_MARK)) {
                return false;
            }
        }
        return in(dirs[last], Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.DIRECTIONALITY_EUROPEAN_NUMBER);
    }

    private static boolean in(int value, int... candidates) {
        for (var candidate : candidates) {
            if (value == candidate) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // RFC 3492 Punycode

    static Optional<String> decode(String input) {
        int delimiter = input.lastIndexOf('-');
        var output = new ArrayList<Integer>();
        for (int j = 0; j < Math.max(delimiter, 0); j++) {
            output.add((int) input.charAt(j));
        }
        long n = INITIAL_N;
        long i = 0;
        int bias = INITIAL_BIAS;
        int in = delimiter >= 0 ? delimiter + 1 : 0;
        while (in < input.length()) {
            long oldi = i;
            long w = 1;
            for (int k = BASE; ; k += BASE) {
                if (in >= input.length()) {
                    return Optional.empty();
                }
                int digit = digit(input.charAt(in++));
                if (digit < 0) {
                    return Optional.empty();
                }
                i += digit * w;
                if (i > Integer.MAX_VALUE) {
                    // w <= i always holds here, so checking i also bounds w
                    return Optional.empty();
                }
                int t = k <= bias ? TMIN : Math.min(k - bias, TMAX);
                if (digit < t) {
                    break;
                }
                w *= BASE - t;
            }
            bias = adapt((int) (i - oldi), output.size() + 1, oldi == 0);
            n += i / (output.size() + 1);
            i %= output.size() + 1;
            if (n > Character.MAX_CODE_POINT) {
                return Optional.empty();
            }
            output.add((int) i, (int) n);
            i++;
        }
        var result = new StringBuilder();
        output.forEach(result::appendCodePoint);
        return Optional.of(result.toString());
    }

    static Optional<String> encode(String input) {
        var cps = input.codePoints().toArray();
        var output = new StringBuilder();
        for (int cp : cps) {
            if (cp < 0x80) {
                output.append((char) Character.toLowerCase(cp));
            }
        }
        int basic = output.length();
        int handled = basic;
        if (basic > 0) {
            output.append('-');
        }
        int n = INITIAL_N;
        long delta = 0;
        int bias = INITIAL_BIAS;
        while (handled < cps.length) {
            int m = Integer.MAX_VALUE;
            for (int cp : cps) {
                if (cp >= n && cp < m) {
                    m = cp;
                }
            }
            delta += (long) (m - n) * (handled + 1);
            n = m;
            for (int cp : cps) {
                if (cp < n) {
                    delta++;
                }
                if (cp == n) {
                    long q = delta;
                    for (int k = BASE; ; k += BASE) {
                        int t = k <= bias ? TMIN : Math.min(k - bias, TMAX);
                        if (q < t) {
                            break;
                        }
                        output.append(encodeDigit((int) (t + (q - t) % (BASE - t))));
                        q = (q - t) / (BASE - t);
                    }
                    output.append(encodeDigit((int) q));
                    bias = adapt((int) delta, handled + 1, handled == basic);
                    delta = 0;
                    handled++;
                }
            }
            delta++;
            n++;
        }
        return Optional.of(output.toString());
    }

    private static int adapt(int delta, int numPoints, boolean first) {
        delta = first ? delta / DAMP : delta / 2;
        delta += delta / numPoints;
        int k = 0;
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN;
            k += BASE;
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW);
    }

    private static int digit(char c) {
        return DIGITS.indexOf(c);
    }

    private static char encodeDigit(int d) {
        return DIGITS.charAt(d);
    }
}
