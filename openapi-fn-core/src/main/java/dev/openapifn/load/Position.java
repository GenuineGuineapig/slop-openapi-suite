package dev.openapifn.load;

/**
 * A 1-based line/column position in a source document.
 *
 * @param line 1-based line
 * @param column 1-based column
 */
public record Position(int line, int column) {

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
