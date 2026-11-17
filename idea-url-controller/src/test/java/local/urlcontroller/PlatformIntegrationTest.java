package local.urlcontroller;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import cn.hutool.core.collection.CollUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiJavaFile;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Runs against the actual headless IDEA test application; no mocked PSI/index/cache. */
public final class PlatformIntegrationTest {
    public static void main(String[] args) throws Exception {
        int exit = 0;
        try {
            IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
            IdeaProjectTestFixture projectFixture = factory.createLightFixtureBuilder("url-controller-search").getFixture();
            CodeInsightTestFixture fixture = factory.createCodeInsightFixture(projectFixture);
            fixture.setUp();
            try { run(fixture); } finally { fixture.tearDown(); }
            InactiveDiffWindowTest.runNativeClosure();
        } catch (Throwable failure) {
            failure.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private static void run(CodeInsightTestFixture fixture) throws Exception {
        Project project = fixture.getProject();
        InactiveDiffWindowTest.run(fixture);
        MultiKeywordPlatformTest.run(project);
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() ->
                com.intellij.testFramework.PsiTestUtil.addSourceContentToRoots(fixture.getModule(), fixture.getTempDirFixture().findOrCreateDir("")));
        MultiKeywordPlatformTest.searchFiles(fixture);
        PsiJavaFile file = (PsiJavaFile) fixture.addFileToProject("src/example/Orders.java", """
                package example;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping(path={"/api/OutboundOrder", "/v2/OutboundOrder"})
                public class Orders {
                    private static final String LIST = "/" + "List";
                    @PostMapping(LIST) public void list() {}
                    @RequestMapping(path="/{id:[0-9]{2,4}}", method={RequestMethod.GET, RequestMethod.HEAD})
                    public void detail() {}
                    @GetMapping public void root() {}
                    @GetMapping(Other.EXTERNAL) public void unresolved() {}
                    @GetMapping("${base}/x") public void environment() {}
                }
                """);
        List<Route> routeList = ReadAction.compute(() -> SpringRoutes.extract(file));
        require(routeList.size() == 8, "Expected 8 routes, found " + routeList);
        require(routeList.stream().anyMatch(r -> r.path().equals("/api/OutboundOrder/List") && r.verb().equals("POST")), "class + local constant");
        require(routeList.stream().filter(r -> r.method().equals("detail")).count() == 4, "multi verbs and class paths");
        require(routeList.stream().noneMatch(r -> r.method().equals("unresolved") || r.method().equals("environment")), "unresolved paths excluded");
        ReadAction.run(() -> {
            for (Route route : routeList) require(file.findElementAt(route.offset()).getText().equals(route.method()), "navigation offset");
        });
        var externalizer = new SpringRouteIndex().getValueExternalizer();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        externalizer.save(new DataOutputStream(bytes), routeList);
        require(routeList.equals(externalizer.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))), "disk serialization roundtrip");
        waitForIndexes(project);
        RouteCache cache = project.getService(RouteCache.class);
        var firstList = cache.search("OUTBOUNDORDER/LIST");
        require(firstList.size() == 2, "persistent index search, found " + firstList);
        require(firstList == cache.search("outboundorder/list"), "case-folded query cache reused");
        require(cache.search("tboundorder/list").size() == 2, "word-internal query finds both indexed routes");
        require(cache.search("TBOUNDORDER/LI").size() == 2, "case-insensitive internal partial path finds both indexed routes");
        var context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project);
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext("UrlControllerTest", null, context);
        var contributor = new ControllerContributorFactory().createContributor(event);
        List<RouteTable.Entry<com.intellij.openapi.vfs.VirtualFile>> contributedList = new ArrayList<>();
        contributor.fetchElements("OUTBOUNDORDER/LIST", new com.intellij.openapi.progress.EmptyProgressIndicator(),
                item -> { contributedList.add(item); return true; });
        require(contributedList.size() == 2, "Search Everywhere contributor supplies both results");
        for (String pattern : List.of("tboundorder/list", "TBOUNDORDER/LI")) {
            List<RouteTable.Entry<com.intellij.openapi.vfs.VirtualFile>> partialList = new ArrayList<>();
            contributor.fetchElements(pattern, new com.intellij.openapi.progress.EmptyProgressIndicator(),
                    item -> { partialList.add(item); return true; });
            require(partialList.size() == 2, "Search Everywhere supplies internal partial path results: " + pattern);
        }
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> {
            require(contributor.processSelectedItem(contributedList.get(0), 0, "outboundorder/list"), "selected result navigates");
            var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
            require(editor != null, "navigation opens Java editor");
            require(file.findElementAt(editor.getCaretModel().getOffset()).getText().equals("list"), "caret is at controller method");
        });
        verifyResultOrdering(fixture, event, contributor);
        WriteCommandAction.runWriteCommandAction(project, () -> {
            var document = PsiDocumentManager.getInstance(project).getDocument(file);
            require(document != null, "document exists");
            document.setText(document.getText().replace("\"List\"", "\"Changed\""));
            PsiDocumentManager.getInstance(project).commitAllDocuments();
        });
        require(CollUtil.isEmpty(cache.search("outboundorder/list")), "unsaved route edit invalidates old query");
        require(CollUtil.isEmpty(cache.search("tboundorder/list")), "unsaved edit invalidates cached internal query");
        require(CollUtil.isEmpty(cache.search("TBOUNDORDER/LI")), "unsaved edit invalidates cached internal partial query");
        List<RouteTable.Entry<com.intellij.openapi.vfs.VirtualFile>> editedList = new ArrayList<>();
        contributor.fetchElements("TBOUNDORDER/LI", new com.intellij.openapi.progress.EmptyProgressIndicator(),
                item -> { editedList.add(item); return true; });
        require(CollUtil.isEmpty(editedList), "contributor excludes stale internal partial results after edit");
        require(cache.search("tboundorder/changed").size() == 2, "internal query finds updated route");
        require(cache.search("OUTBOUNDORDER/CHANGED").size() == 2, "unsaved route edit is indexed");
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() ->
                require(!contributor.processSelectedItem(contributedList.get(0), 0, "outboundorder/list"), "stale result does not jump to wrong method"));
        WriteCommandAction.runWriteCommandAction(project, () -> FileDocumentManager.getInstance().saveAllDocuments());
        WriteCommandAction.runWriteCommandAction(project, file::delete);
        waitForIndexes(project);
        require(CollUtil.isEmpty(cache.search("outboundorder/changed")), "deleted file invalidates route cache");
        require(CollUtil.isEmpty(cache.search("tboundorder/changed")), "deleted file invalidates cached internal query");
        List<RouteTable.Entry<com.intellij.openapi.vfs.VirtualFile>> deletedList = new ArrayList<>();
        contributor.fetchElements("tboundorder/changed", new com.intellij.openapi.progress.EmptyProgressIndicator(),
                item -> { deletedList.add(item); return true; });
        require(CollUtil.isEmpty(deletedList), "contributor excludes deleted internal path results");
        PsiJavaFile fake = (PsiJavaFile) fixture.addFileToProject("src/example/Fake.java", """
                package example;
                import example.GetMapping;
                class Fake { @GetMapping("/fake") void fake() {} }
                """);
        require(CollUtil.isEmpty(ReadAction.compute(() -> SpringRoutes.extract(fake))), "non-Spring mapping ignored");
        contributor.dispose();
        System.out.println("PASS: real IDEA PSI/index/cache, unsaved edit/delete invalidation, navigation, URL results before built-in class/file/symbol results");
    }

    private static void verifyResultOrdering(CodeInsightTestFixture fixture,
            com.intellij.openapi.actionSystem.AnActionEvent event,
            com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor<RouteTable.Entry<com.intellij.openapi.vfs.VirtualFile>> contributor) {
        fixture.addFileToProject("src/example/ListRequest.java", "package example; public class ListRequest { public void list() {} }");
        fixture.addFileToProject("ListDetails.txt", "A matching file");
        fixture.addFileToProject("src/example/ListController.java", """
                package example;
                import org.springframework.web.bind.annotation.*;
                @RestController public class ListController { @GetMapping("/List") public void list() {} }
                """);
        waitForIndexes(fixture.getProject());
        var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
        var providerList = ReadAction.compute(() -> List.of(
                new com.intellij.ide.actions.searcheverywhere.ClassSearchEverywhereContributor(event),
                new com.intellij.ide.actions.searcheverywhere.FileSearchEverywhereContributor(event),
                new com.intellij.ide.actions.searcheverywhere.SymbolSearchEverywhereContributor(event)));
        List<com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo> ordinaryList = new ArrayList<>();
        try {
            for (var provider : providerList) {
                int before = ordinaryList.size();
                provider.fetchWeightedElements("list", indicator, hit -> {
                    ordinaryList.add(new com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo(
                            hit.getItem(), hit.getWeight(), provider));
                    return ordinaryList.size() - before < 20;
                });
                require(ordinaryList.size() > before, "real built-in provider returns results: " + provider.getSearchProviderId());
            }
            List<com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo> urlList = new ArrayList<>();
            var weighted = (com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor<RouteTable.Entry<com.intellij.openapi.vfs.VirtualFile>>) contributor;
            weighted.fetchWeightedElements("LIST", indicator, hit -> {
                urlList.add(new com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo(hit.getItem(), hit.getWeight(), contributor));
                return true;
            });
            require(urlList.size() == 3, "partial URL matches multiple controller routes");
            require(((RouteTable.Entry<?>) urlList.get(0).getElement()).route().path().equals("/List"), "exact URL remains first");
            com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> {
                try {
                    // The real mixed model is package-private in IDEA 2023.1.
                    Class<?> modelClass = Class.forName("com.intellij.ide.actions.searcheverywhere.MixedSearchListModel");
                    var constructor = modelClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    Object model = constructor.newInstance();
                    var addElements = modelClass.getMethod("addElements", List.class);
                    var clear = modelClass.getMethod("clear");
                    var getRawFoundElementAt = modelClass.getMethod("getRawFoundElementAt", int.class);
                    addElements.setAccessible(true);
                    clear.setAccessible(true);
                    getRawFoundElementAt.setAccessible(true);
                    javax.swing.ListModel<?> listModel = (javax.swing.ListModel<?>) model;
                    addElements.invoke(model, ordinaryList); // Built-in results arrive before the plugin.
                    addElements.invoke(model, urlList);
                    require(listModel.getSize() == ordinaryList.size() + urlList.size(), "ordinary results are retained");
                    for (int i = 0; i < urlList.size(); i++) {
                        var hit = (com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo) getRawFoundElementAt.invoke(model, i);
                        require(hit.getContributor() == contributor, "all URL matches rank before built-ins");
                    }
                    require(((RouteTable.Entry<?>) listModel.getElementAt(0)).route().path().equals("/List"), "mixed list preserves exact-before-partial ranking");
                    clear.invoke(model);
                    addElements.invoke(model, urlList); // Later built-in batches must not displace URL matches either.
                    addElements.invoke(model, ordinaryList);
                    for (int i = 0; i < urlList.size(); i++) {
                        var hit = (com.intellij.ide.actions.searcheverywhere.SearchEverywhereFoundElementInfo) getRawFoundElementAt.invoke(model, i);
                        require(hit.getContributor() == contributor, "late built-in results stay below URLs");
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("Cannot verify the real IDEA mixed search model", failure);
                }
            });
        } finally {
            for (var provider : providerList) provider.dispose();
        }
    }

    private static void waitForIndexes(Project project) {
        DumbService.getInstance(project).waitForSmartMode();
        ReadAction.run(() -> FileBasedIndex.getInstance().ensureUpToDate(
                SpringRouteIndex.ID, project, GlobalSearchScope.projectScope(project)));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
