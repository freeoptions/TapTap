package local.urlcontroller;

import java.util.regex.Pattern;

/** Converts literal words and quoted phrases into a single-line AND expression. */
public final class MultiKeywordQuery {
    private MultiKeywordQuery() {}

    public static String regex(String input, boolean wholeWords) {
        StringBuilder expression = new StringBuilder("(?m)^");
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        boolean hasToken = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\r' || c == '\n') throw new IllegalArgumentException("多关键词查询不能包含换行");
            if (c == '"') {
                quoted = !quoted;
            } else if ((c == ' ' || c == '\t') && !quoted) {
                if (token.length() > 0) {
                    append(expression, token.toString(), wholeWords);
                    token.setLength(0);
                    hasToken = true;
                }
            } else {
                token.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("短语的双引号尚未闭合");
        if (token.length() > 0) {
            append(expression, token.toString(), wholeWords);
            hasToken = true;
        }
        return hasToken ? expression.append("[^\\r\\n]+").toString() : "(?!)";
    }

    private static void append(StringBuilder expression, String token, boolean wholeWords) {
        expression.append("(?=[^\\r\\n]*");
        if (wholeWords) expression.append("(?<![\\p{L}\\p{N}_])");
        expression.append(Pattern.quote(token));
        if (wholeWords) expression.append("(?![\\p{L}\\p{N}_])");
        expression.append(')');
    }
}
