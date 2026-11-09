package local.urlcontroller;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import java.util.*;

@Service(Service.Level.PROJECT)
public final class RouteCache {
    private final Project project;
    private Snapshot snapshot;
    private final Map<String, List<RouteTable.Match<VirtualFile>>> queries = new LinkedHashMap<>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<RouteTable.Match<VirtualFile>>> entry) {
            return size() > 128;
        }
    };

    public RouteCache(Project project) { this.project = project; }

    public List<RouteTable.Match<VirtualFile>> search(String input) {
        String query = UrlPaths.query(input);
        if (query.isEmpty()) return List.of();
        return ReadAction.compute(() -> {
            if (project.isDisposed() || DumbService.isDumb(project)) return List.of();
            synchronized (this) {
                ProgressManager.checkCanceled();
                FileBasedIndex index = FileBasedIndex.getInstance();
                long psi = PsiModificationTracker.getInstance(project).getModificationCount();
                long roots = ProjectRootModificationTracker.getInstance(project).getModificationCount();
                long stamp = index.getIndexModificationStamp(SpringRouteIndex.ID, project);
                if (snapshot == null || snapshot.stamp != stamp || snapshot.psi != psi || snapshot.roots != roots) {
                    List<RouteTable.Entry<VirtualFile>> entries = new ArrayList<>();
                    index.processValues(SpringRouteIndex.ID, SpringRouteIndex.KEY, null, (file, routes) -> {
                        ProgressManager.checkCanceled();
                        for (Route route : routes) entries.add(new RouteTable.Entry<>(route, file));
                        return true;
                    }, GlobalSearchScope.projectScope(project));
                    RouteTable<VirtualFile> table = new RouteTable<>(entries, ProgressManager::checkCanceled);
                    snapshot = new Snapshot(index.getIndexModificationStamp(SpringRouteIndex.ID, project), psi, roots, table);
                    queries.clear();
                }
                List<RouteTable.Match<VirtualFile>> cached = queries.get(query);
                if (cached != null) return cached;
                List<RouteTable.Match<VirtualFile>> results = snapshot.table.search(query, 100, ProgressManager::checkCanceled);
                queries.put(query, results);
                return results;
            }
        });
    }

    private record Snapshot(long stamp, long psi, long roots, RouteTable<VirtualFile> table) {}
}
