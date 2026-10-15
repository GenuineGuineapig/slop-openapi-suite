package dev.openapifn.schema;

/** Code point range tables. */
final class CodePoints {

    private CodePoints() {
    }

    /** Whether {@code cp} lies in one of the inclusive {@code [low, high]} pairs of {@code ranges}. */
    static boolean inRanges(int cp, int... ranges) {
        for (int i = 0; i < ranges.length; i += 2) {
            if (cp >= ranges[i] && cp <= ranges[i + 1]) {
                return true;
            }
        }
        return false;
    }
}
