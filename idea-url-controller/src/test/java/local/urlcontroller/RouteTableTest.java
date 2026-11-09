package local.urlcontroller;

import cn.hutool.core.collection.CollUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RouteTableTest {
    private static final Runnable CHECK = () -> {};
    private static int assertions;

    public static void main(String[] args) {
        equal("/outboundorder/list", UrlPaths.query("  HTTPS://example.com/OutboundOrder/List/?page=1#top  "));
        equal("/outboundorder/list", UrlPaths.query("OutboundOrder/List"));
        equal("", UrlPaths.query("  "));
        equal("", UrlPaths.query("https://[bad/url"));
        equal("/api/list", UrlPaths.join("/api/", "/list/"));
        equal("/api", UrlPaths.join("/api", ""));
        List<RouteTable.Entry<String>> entryList = List.of(
                entry("/OutboundOrder/List", "POST", "exact"),
                entry("/api/outboundorder/list", "GET", "suffix"),
                entry("/outboundorder/{id}", "GET", "variable"),
                entry("/api/orders/{id:[0-9]{2,4}}", "GET", "regex"),
                entry("/files/**/detail", "GET", "wildcard"),
                entry("/OutboundOrder/List", "GET", "duplicate"));
        RouteTable<String> table = new RouteTable<>(entryList, CHECK);
        List<RouteTable.Match<String>> hitList = table.search(UrlPaths.query("OUTBOUNDORDER/LIST"), 100, CHECK);
        equal(4, hitList.size());
        equal(1000, hitList.get(0).priority());
        equal(1000, hitList.get(1).priority());
        equal(900, hitList.get(2).priority());
        equal(800, hitList.get(3).priority());
        equal(3, table.search(UrlPaths.query("tboundorder/list"), 100, CHECK).size());
        equal(3, table.search(UrlPaths.query("TBOUNDORDER/LI"), 100, CHECK).size());
        equal(3, table.search(UrlPaths.query("https://example.com/tboundorder/list?page=1"), 100, CHECK).size());
        check(CollUtil.isEmpty(table.search(UrlPaths.query("tboundorder/missing"), 100, CHECK)));
        equal(1, table.search(UrlPaths.query("tboundorder/list"), 1, CHECK).size());
        equal("variable", table.search("/outboundorder/123", 100, CHECK).get(0).entry().target());
        equal("regex", table.search("/orders/123", 100, CHECK).get(0).entry().target());
        check(CollUtil.isEmpty(table.search("/orders/abc", 100, CHECK)));
        equal("wildcard", table.search("/files/detail", 100, CHECK).get(0).entry().target());
        equal("wildcard", table.search("/files/a/b/detail", 100, CHECK).get(0).entry().target());
        check(CollUtil.isEmpty(table.search("/missing", 100, CHECK)));
        equal(1, table.search("/outboundorder/list", 1, CHECK).size());
        check(CollUtil.isEmpty(table.search("", 100, CHECK)));
        equal(400, table.search("/outb", 100, CHECK).get(0).priority());
        // Replacing the snapshot must drop removed/renamed routes.
        RouteTable<String> updated = new RouteTable<>(List.of(entry("/new/list", "GET", "updated")), CHECK);
        check(CollUtil.isEmpty(updated.search("/outboundorder/list", 100, CHECK)));
        equal("updated", updated.search("/new/list", 100, CHECK).get(0).entry().target());
        boolean canceled = false;
        try { table.search("/outboundorder/list", 100, () -> { throw new IllegalStateException("canceled"); }); }
        catch (IllegalStateException expected) { canceled = true; }
        check(canceled);
        RouteTable<String> ranked = new RouteTable<>(List.of(
                entry("/tboundorder/list", "GET", "exact"),
                entry("/api/tboundorder/list", "GET", "suffix"),
                entry("/tboundorder/{id}", "GET", "template"),
                entry("/tboundorder/listAll", "GET", "prefix"),
                entry("/OutboundOrder/List", "POST", "substring")), CHECK);
        equal(List.of(1000, 900, 800, 400, 300), ranked.search("/tboundorder/list", 100, CHECK)
                .stream().map(RouteTable.Match::priority).toList());
        check(CollUtil.isEmpty(ranked.search("/tboundorder/list", 0, CHECK)));
        performance();
        System.out.println("PASS: " + assertions + " route assertions");
    }

    private static void performance() {
        List<RouteTable.Entry<String>> entryList = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) entryList.add(entry("/api/controller" + i + "/list", "GET", "target"));
        long start = System.nanoTime();
        RouteTable<String> table = new RouteTable<>(entryList, CHECK);
        long built = System.nanoTime();
        for (int i = 0; i < 1000; i++) table.search("/controller1234/list", 100, CHECK);
        long[] times = new long[10_000];
        for (int i = 0; i < times.length; i++) {
            long before = System.nanoTime();
            equal(1, table.search("/controller" + i + "/list", 100, CHECK).size());
            times[i] = System.nanoTime() - before;
        }
        Arrays.sort(times);
        System.out.printf(Locale.ROOT, "50,000 static routes: table build %.1f ms; lookup p50 %.3f ms, p95 %.3f ms, p99 %.3f ms%n",
                (built - start) / 1e6, times[5000] / 1e6, times[9500] / 1e6, times[9900] / 1e6);
        long[] fragmentTimes = new long[200];
        for (int i = 0; i < fragmentTimes.length; i++) {
            long before = System.nanoTime();
            equal(1, table.search("/roller" + i + "/li", 100, CHECK).size());
            fragmentTimes[i] = System.nanoTime() - before;
        }
        Arrays.sort(fragmentTimes);
        System.out.printf(Locale.ROOT, "50,000 static routes: substring lookup p95 %.3f ms%n", fragmentTimes[190] / 1e6);
    }

    private static RouteTable.Entry<String> entry(String path, String verb, String target) {
        return new RouteTable.Entry<>(new Route(path, verb, "example.Controller", "list", 10), target);
    }
    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + "; actual " + actual);
    }
    private static void check(boolean value) { equal(true, value); }
}
