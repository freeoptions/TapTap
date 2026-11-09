package local.urlcontroller;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

public final class UrlPaths {
    private UrlPaths() {}

    public static String query(String input) {
        String value = input.strip();
        if (value.isEmpty()) return "";
        if (value.matches("(?i)^https?://.*")) {
            try {
                value = URI.create(value).getRawPath();
                if (value == null) return "";
            } catch (IllegalArgumentException ignored) {
                return "";
            }
        }
        int end = value.length();
        int question = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (question >= 0) end = Math.min(end, question);
        if (fragment >= 0) end = Math.min(end, fragment);
        value = value.substring(0, end).strip();
        return value.isEmpty() ? "" : key(path(value));
    }

    public static String path(String value) {
        String normalized = value.strip();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static String join(String parent, String child) {
        if (parent.isEmpty() || parent.equals("/")) return path(child);
        if (child.isEmpty() || child.equals("/")) return path(parent);
        return path(path(parent) + (child.startsWith("/") ? child : "/" + child));
    }

    public static String key(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    public static boolean dynamic(String path) {
        return path.indexOf('{') >= 0 || path.indexOf('*') >= 0 || path.indexOf('?') >= 0;
    }

    /** Spring-style path variables and Ant wildcards, compiled once per cached route. */
    public static Pattern template(String path) {
        StringBuilder expression = new StringBuilder("^");
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '{') {
                int depth = 1;
                int end = i + 1;
                for (; end < path.length() && depth > 0; end++) {
                    if (path.charAt(end) == '{') depth++;
                    if (path.charAt(end) == '}') depth--;
                }
                if (depth != 0) return null;
                String variable = path.substring(i + 1, end - 1);
                int colon = variable.indexOf(':');
                expression.append(colon >= 0 ? "(?:" + variable.substring(colon + 1) + ")"
                        : variable.startsWith("*") ? ".*" : "[^/]+");
                i = end - 1;
            } else if (c == '*') {
                boolean doubleStar = i + 1 < path.length() && path.charAt(i + 1) == '*';
                // Ant's /a/**/b also matches /a/b.
                if (doubleStar && i + 2 < path.length() && path.charAt(i + 2) == '/') {
                    expression.append("(?:.*/)?");
                    i += 2;
                } else {
                    expression.append(doubleStar ? ".*" : "[^/]*");
                    if (doubleStar) i++;
                }
            } else if (c == '?') {
                expression.append("[^/]");
            } else {
                expression.append(Pattern.quote(String.valueOf(c)));
            }
        }
        try {
            return Pattern.compile(expression.append('$').toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (java.util.regex.PatternSyntaxException ignored) {
            return null;
        }
    }
}
