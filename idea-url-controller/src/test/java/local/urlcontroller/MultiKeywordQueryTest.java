package local.urlcontroller;

import java.util.regex.Pattern;

public final class MultiKeywordQueryTest {
    public static void main(String[] args) {
        matches("库存 扣减", "校验库存后执行扣减", true);
        matches("库存 扣减", "扣减商品可用库存", true);
        matches("库存 扣减", "库存\n扣减", false);
        matches("库存 扣减", "库存\r\n扣减", false);
        matches("库存 扣减", "库存\r扣减", false);
        matches("库存 扣减", "只有库存", false);
        matches("  order   list  ", "/outboundorder/query/list", true);
        matches("list order", "/outboundorder/query/list", true);
        matches("order_fulfillment_outbound\"", "@Table(name = \"order_fulfillment_outbound\")", true);
        matches("order_fulfillment_outbound\"", "@Table(name = \"order_fulfillment_outbound_detail\")", false);
        matches("_outbound\" @Table", "@Table(name = \"order_fulfillment_outbound\")", true);
        matches("_outbound\" @Table", "@Table(name = \"order_fulfillment_outbound_detail\")", false);
        matches("\"public static\" void", "void \"public final static\"", true);
        matches("\"public static\" void", "void public static", false);
        matches("\"", "\"order_fulfillment_outbound\"", true);
        matches("\"", "order_fulfillment_outbound", false);
        matches("a.b [x]", "[x] a.b", true);
        matches("a.b [x]", "x aZb", false);
        matches("C:\\temp a", "a C:\\temp", true);
        matches("\\E foo", "foo \\E", true);
        matches("", "anything", false);
        matches("   \t", "anything", false);
        matches("\"\"", "anything", false);
        matches("\"\"", "String value = \"\";", true);
        Pattern wholeWords = Pattern.compile(MultiKeywordQuery.regex("order list", true));
        require(wholeWords.matcher("list order").find(), "whole words match");
        require(!wholeWords.matcher("list outboundorder").find(), "whole words reject fragments");
        require(Pattern.compile(MultiKeywordQuery.regex("库存 扣减", false)).matcher("库存 扣减\n扣减 库存").results().count() == 2,
                "one result per matching line");
        for (String invalid : new String[]{"a\nb", "a\rb"}) {
            try {
                MultiKeywordQuery.regex(invalid, false);
                throw new AssertionError("invalid query accepted");
            } catch (IllegalArgumentException expected) {
                require(expected.getMessage() != null, "invalid query explains error");
            }
        }
        System.out.println("PASS: multi-keyword AND, unordered fragments, literal quotes/symbols, line boundaries, whole words, invalid input");
    }

    private static void matches(String query, String text, boolean expected) {
        require(Pattern.compile(MultiKeywordQuery.regex(query, false)).matcher(text).find() == expected, query + ": " + text);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
