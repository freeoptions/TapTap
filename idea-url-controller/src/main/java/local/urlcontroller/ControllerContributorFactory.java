package local.urlcontroller;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.Processor;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import org.jetbrains.annotations.NotNull;

public final class ControllerContributorFactory implements SearchEverywhereContributorFactory<RouteTable.Entry<VirtualFile>> {
    @Override public boolean isAvailable(Project project) { return project != null && !project.isDisposed(); }

    @Override public @NotNull SearchEverywhereContributor<RouteTable.Entry<VirtualFile>> createContributor(@NotNull AnActionEvent event) {
        return new Contributor(event.getProject());
    }

    private static final class Contributor implements WeightedSearchEverywhereContributor<RouteTable.Entry<VirtualFile>> {
        // All results share one list: reserve a higher band than built-in class/file/symbol matches.
        private static final int URL_RESULT_PRIORITY = 1_000_000;
        private final Project project;
        private Contributor(Project project) { this.project = project; }
        @Override public @NotNull String getSearchProviderId() { return "UrlControllerSearch"; }
        @Override public @NotNull String getGroupName() { return "URL Controllers"; }
        @Override public int getSortWeight() { return 0; }
        @Override public boolean showInFindResults() { return false; }
        @Override public boolean isDumbAware() { return false; }
        @Override public Object getDataForItem(@NotNull RouteTable.Entry<VirtualFile> item, @NotNull String dataId) { return null; }
        @Override public String getAdvertisement() { return "Paste a Spring MVC URL · case-insensitive · Enter to navigate"; }

        @Override public void fetchWeightedElements(@NotNull String pattern, @NotNull ProgressIndicator indicator,
                @NotNull Processor<? super FoundItemDescriptor<RouteTable.Entry<VirtualFile>>> consumer) {
            if (project == null || project.isDisposed()) return;
            for (RouteTable.Match<VirtualFile> match : project.getService(RouteCache.class).search(pattern)) {
                indicator.checkCanceled();
                if (!consumer.process(new FoundItemDescriptor<>(match.entry(), URL_RESULT_PRIORITY + match.priority()))) return;
            }
        }

        @Override public boolean processSelectedItem(@NotNull RouteTable.Entry<VirtualFile> item, int modifiers, @NotNull String text) {
            if (project == null || project.isDisposed() || !item.target().isValid()) return false;
            PsiFile file = PsiManager.getInstance(project).findFile(item.target());
            if (file == null) return false;
            // Reparse this one file so edits made after displaying results cannot jump to a stale offset.
            if (file instanceof PsiJavaFile javaFile) {
                for (Route route : SpringRoutes.extract(javaFile)) {
                    if (route.path().equals(item.route().path()) && route.verb().equals(item.route().verb())
                            && route.owner().equals(item.route().owner()) && route.method().equals(item.route().method())) {
                        PsiMethod method = PsiTreeUtil.getParentOfType(file.findElementAt(route.offset()), PsiMethod.class, false);
                        if (method != null) { method.navigate(true); return true; }
                        new OpenFileDescriptor(project, item.target(), route.offset()).navigate(true);
                        return true;
                    }
                }
            }
            return false;
        }

        @Override public @NotNull ListCellRenderer<? super RouteTable.Entry<VirtualFile>> getElementsRenderer() {
            return new ColoredListCellRenderer<RouteTable.Entry<VirtualFile>>() {
                @Override protected void customizeCellRenderer(@NotNull JList<? extends RouteTable.Entry<VirtualFile>> list,
                        RouteTable.Entry<VirtualFile> value, int index, boolean selected, boolean focus) {
                    if (value == null) return;
                    setIcon(AllIcons.Nodes.Method);
                    Route route = value.route();
                    append(route.verb() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    append(route.path(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append("    " + route.owner() + "#" + route.method() + "()", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    append("  (" + value.target().getPresentableUrl() + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            };
        }

        @Override public String getItemDescription(@NotNull RouteTable.Entry<VirtualFile> item) {
            return item.route().verb() + " " + item.route().path() + " → " + item.route().owner() + "#" + item.route().method();
        }
    }
}
