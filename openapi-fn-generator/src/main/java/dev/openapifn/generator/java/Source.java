package dev.openapifn.generator.java;

/** Builds indented Java source text. */
public final class Source {

    private static final String INDENT = "    ";

    private final StringBuilder out = new StringBuilder();
    private int depth;

    /** Appends {@code text} indented; embedded line breaks continue at the same indentation. */
    public Source line(String text) {
        for (var piece : text.split("\n", -1)) {
            out.append(INDENT.repeat(depth)).append(piece).append('\n');
        }
        return this;
    }

    public Source blank() {
        out.append('\n');
        return this;
    }

    /** Appends <code>header {</code> and indents. */
    public Source open(String header) {
        line(header + " {");
        depth++;
        return this;
    }

    /** Outdents and appends {@code }} followed by {@code suffix}. */
    public Source close(String suffix) {
        depth--;
        return line("}" + suffix);
    }

    public Source close() {
        return close("");
    }

    /** Appends a Javadoc comment unless {@code text} is blank. */
    public Source javadoc(String text) {
        if (text.isBlank()) {
            return this;
        }
        line("/**");
        Names.javadoc(text).lines().forEach(l -> line(l.isBlank() ? " *" : " * " + l));
        return line(" */");
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
