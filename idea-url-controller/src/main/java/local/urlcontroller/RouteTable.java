package local.urlcontroller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Immutable route lookup, independent of PSI. Built only when index/project content changes. */
public final class RouteTable<T> {
    public record Entry<T>(Route route, T target) {}
    public record Match<T>(Entry<T> entry, int priority) {}
    private record Template<T>(Entry<T> entry, Pattern pattern, int priority) {}
    private final NavigableMap<String, List<Entry<T>>> exactMap = new TreeMap<>();
    private final Map<String, List<Entry<T>>> suffixMap = new HashMap<>();
    private final List<Template<T>> templateList = new ArrayList<>();
    private final Comparator<Match<T>> order = Comparator.<Match<T>>comparingInt(Match::priority).reversed()
            .thenComparing(m -> m.entry.route.path()).thenComparing(m -> m.entry.route.owner())
            .thenComparing(m -> m.entry.route.method()).thenComparing(m -> m.entry.route.verb());

    public RouteTable(List<Entry<T>> entryList, Runnable checkCanceled) {
        for (Entry<T> entry : entryList) {
            checkCanceled.run();
            String path = UrlPaths.key(entry.route.path());
            exactMap.computeIfAbsent(path, ignored -> new ArrayList<>()).add(entry);
            for (int slash = path.indexOf('/', 1); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                suffixMap.computeIfAbsent(path.substring(slash), ignored -> new ArrayList<>()).add(entry);
            }
            if (UrlPaths.dynamic(entry.route.path())) {
                addTemplate(entry, entry.route.path(), 800);
                // Allow searching orders/42 when the declaration is /api/orders/{id}.
                String original = entry.route.path();
                for (int slash = original.indexOf('/', 1); slash >= 0; slash = original.indexOf('/', slash + 1)) {
                    // A slash inside a regex is not a path boundary.
                    if (original.substring(0, slash).lastIndexOf('{') > original.substring(0, slash).lastIndexOf('}')) continue;
                    String suffix = original.substring(slash);
                    // A bare /{id} or /** suffix would match unrelated URLs everywhere.
                    String firstSegment = suffix.substring(1).split("/", 2)[0];
                    if (!UrlPaths.dynamic(firstSegment)) addTemplate(entry, suffix, 600);
                }
            }
        }
    }

    private void addTemplate(Entry<T> entry, String path, int priority) {
        Pattern pattern = UrlPaths.template(path);
        if (pattern != null) templateList.add(new Template<>(entry, pattern, priority));
    }

    public List<Match<T>> search(String query, int limit, Runnable checkCanceled) {
        if (query.isEmpty() || limit <= 0) return List.of();
        LinkedHashMap<Entry<T>, Match<T>> matchMap = new LinkedHashMap<>();
        add(matchMap, exactMap.get(query), 1000, checkCanceled);
        add(matchMap, suffixMap.get(query), 900, checkCanceled);
        for (Template<T> template : templateList) {
            checkCanceled.run();
            if (template.pattern.matcher(query).matches()) {
                matchMap.putIfAbsent(template.entry, new Match<>(template.entry, template.priority));
            }
        }
        // Prefix matching supports typing; stop after enough lower-priority results.
        for (var bucket : exactMap.tailMap(query, true).entrySet()) {
            checkCanceled.run();
            if (!bucket.getKey().startsWith(query) || matchMap.size() >= limit) break;
            add(matchMap, bucket.getValue(), 400, checkCanceled);
        }
        // The normalized leading slash must not force a path-segment boundary.
        String fragment = query.substring(1);
        for (var bucket : exactMap.entrySet()) {
            checkCanceled.run();
            if (matchMap.size() >= limit) break;
            if (bucket.getKey().contains(fragment)) add(matchMap, bucket.getValue(), 300, checkCanceled);
        }
        return matchMap.values().stream().sorted(order).limit(limit).toList();
    }

    private void add(Map<Entry<T>, Match<T>> matchMap, List<Entry<T>> entryList, int priority, Runnable checkCanceled) {
        if (entryList == null) return;
        for (Entry<T> entry : entryList) {
            checkCanceled.run();
            matchMap.putIfAbsent(entry, new Match<>(entry, priority));
        }
    }
}
