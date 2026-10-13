package dev.openapifn.schema;

import java.util.regex.Pattern;

/** Strict RFC 3986 (URI) and RFC 3987 (IRI) syntax validation. */
final class UriSyntax {

    private static final Pattern COMPONENTS = Pattern.compile("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$", Pattern.DOTALL);
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*");
    private static final Pattern PORT = Pattern.compile("[0-9]*");
    private static final Pattern IPV_FUTURE = Pattern.compile("[vV][0-9A-Fa-f]+\\.[A-Za-z0-9._~!$&'()*+,;=:-]+");
    private static final String UNRESERVED = "-._~";
    private static final String SUB_DELIMS = "!$&'()*+,;=";

    private static final int[] UCSCHAR_BMP = {0xA0, 0xD7FF, 0xF900, 0xFDCF, 0xFDF0, 0xFFEF};
    private static final int[] UCSCHAR_PLANES = {0x10000, 0xDFFFF, 0xE1000, 0xEFFFF};
    private static final int[] IPRIVATE = {0xE000, 0xF8FF, 0xF0000, 0xFFFFD, 0x100000, 0x10FFFD};

    private UriSyntax() {
    }

    /**
     * Validates {@code value}.
     *
     * @param absolute whether a scheme is required (URI/IRI rather than reference)
     * @param international whether IRI characters are permitted
     */
    static boolean isValid(String value, boolean absolute, boolean international) {
        var m = COMPONENTS.matcher(value);
        m.matches(); // always matches: every component of the RFC 3986 appendix B expression is optional
        var scheme = m.group(2);
        var authority = m.group(4);
        var path = m.group(5);
        if (scheme == null ? absolute : !SCHEME.matcher(scheme).matches()) {
            return false;
        }
        if (authority != null && !authority(authority, international)) {
            return false;
        }
        if (scheme == null && authority == null && !path.startsWith("/")) {
            var firstSegment = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            if (firstSegment.contains(":")) {
                return false;
            }
        }
        return chars(path, ":@/", international, false)
            && (m.group(7) == null || chars(m.group(7), ":@/?", international, true))
            && (m.group(9) == null || chars(m.group(9), ":@/?", international, false));
    }

    private static boolean authority(String authority, boolean international) {
        var at = authority.lastIndexOf('@');
        var userinfo = at >= 0 ? authority.substring(0, at) : "";
        var hostPort = authority.substring(at + 1);
        if (!chars(userinfo, ":", international, false)) {
            return false;
        }
        String host;
        String port;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                return false;
            }
            host = hostPort.substring(0, close + 1);
            var rest = hostPort.substring(close + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) {
                return false;
            }
            port = rest.isEmpty() ? "" : rest.substring(1);
            var literal = host.substring(1, host.length() - 1);
            if (!Formats.isIpv6(literal) && !IPV_FUTURE.matcher(literal).matches()) {
                return false;
            }
        } else {
            int colon = hostPort.lastIndexOf(':');
            host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
            port = colon >= 0 ? hostPort.substring(colon + 1) : "";
            if (!chars(host, "", international, false)) {
                return false;
            }
        }
        return PORT.matcher(port).matches();
    }

    /** Whether {@code text} consists of unreserved, sub-delims, percent-encodings and {@code extra}. */
    private static boolean chars(String text, String extra, boolean international, boolean privateUse) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == '%') {
                if (i + 2 >= text.length() || Character.digit(text.charAt(i + 1), 16) < 0 || Character.digit(text.charAt(i + 2), 16) < 0) {
                    return false;
                }
                i += 3;
                continue;
            }
            var allowed = (cp < 0x80 && (Character.isLetterOrDigit(cp) || UNRESERVED.indexOf(cp) >= 0 || SUB_DELIMS.indexOf(cp) >= 0
                || extra.indexOf(cp) >= 0))
                || (international && (ucschar(cp) || (privateUse && iprivate(cp))));
            if (!allowed) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /** RFC 3987 {@code ucschar}: the BMP ranges, then planes 1 to 13 and 14 (from U+E1000) without the plane-final noncharacters. */
    static boolean ucschar(int cp) {
        return CodePoints.inRanges(cp, UCSCHAR_BMP) || (CodePoints.inRanges(cp, UCSCHAR_PLANES) && (cp & 0xFFFF) <= 0xFFFD);
    }

    private static boolean iprivate(int cp) {
        return CodePoints.inRanges(cp, IPRIVATE);
    }
}
