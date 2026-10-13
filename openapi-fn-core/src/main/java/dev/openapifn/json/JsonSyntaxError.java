package dev.openapifn.json;

/**
 * A JSON syntax error with a 1-based line and column.
 *
 * @param message what went wrong
 * @param line 1-based line number
 * @param column 1-based column number
 * @param offset 0-based character offset
 */
public record JsonSyntaxError(String message, int line, int column, int offset) {

    @Override
    public String toString() {
        return message + " at line " + line + ", column " + column;
    }
}
