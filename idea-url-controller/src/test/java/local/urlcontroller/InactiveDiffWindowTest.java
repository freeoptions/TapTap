package local.urlcontroller;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.editor.DiffRequestProcessorEditor;
import com.intellij.diff.editor.SimpleDiffVirtualFile;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffPlaces;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.DockableEditorTabbedContainer;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.fileEditor.impl.EditorsSplitters;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.changes.DiffPreviewProvider;
import com.intellij.openapi.vcs.changes.PreviewDiffVirtualFile;
import com.intellij.openapi.vcs.changes.ui.SimpleChangesBrowser;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.docking.DockManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Checks native preview observation and disposal without opening desktop windows. */
public final class InactiveDiffWindowTest {
    static void run(CodeInsightTestFixture fixture) {
        Project project = fixture.getProject();
        EdtTestUtil.runInEdtAndWait(() -> {
            DiffRequestProcessor processor = null;
            FileEditor editor = null;
            try {
                var tree = new SimpleChangesBrowser(project, false, false).getViewer();
                var mouse = tree.getDoubleClickHandler();
                var keyboard = tree.getEnterKeyHandler();
                Class<?> processorClass = Class.forName("com.intellij.openapi.vcs.changes.ChangesViewDiffPreviewProcessor");
                processor = (DiffRequestProcessor) processorClass.getConstructors()[0].newInstance(tree, true);
                require("Default".equals(processor.getContext().getUserData(DiffUserDataKeys.PLACE)),
                        "native Changes editor uses Default, which the old window extension missed");
                require(CommitPreviewWindows.isSupported(processor.getContext(), processor, false), "native Changes/Unversioned preview qualifies");
                processor.putContextUserData(DiffUserDataKeys.MERGE_EDITOR_FLAG, true);
                require(!CommitPreviewWindows.isSupported(processor.getContext(), processor, false), "merge preview stays open");
                processor.putContextUserData(DiffUserDataKeys.MERGE_EDITOR_FLAG, null);
                var providerConstructor = Class.forName("com.intellij.openapi.vcs.changes.EditorTabDiffPreviewProvider")
                        .getDeclaredConstructor(DiffRequestProcessor.class, kotlin.jvm.functions.Function0.class);
                providerConstructor.setAccessible(true);
                var provider = (DiffPreviewProvider) providerConstructor.newInstance(processor,
                        (kotlin.jvm.functions.Function0<String>) () -> "Native Commit preview");
                var file = new PreviewDiffVirtualFile(provider);
                require(CommitPreviewWindows.isNativePreview(file), "native preview file is recognized");
                editor = (FileEditor) DiffRequestProcessorEditor.class.getConstructors()[0].newInstance(file, processor);
                FileEditorManager manager = FileEditorManager.getInstance(project);
                var separateWindow = CommitPreviewWindows.class.getDeclaredMethod("isSeparateWindow", FileEditorManager.class,
                        com.intellij.openapi.vfs.VirtualFile.class);
                separateWindow.setAccessible(true);
                require(!(boolean) separateWindow.invoke(null, manager, file), "native window check excludes an embedded preview");
                var watch = CommitPreviewWindows.install(manager, file, editor);
                require(watch != null && watch.timer.isRunning(), "native preview starts its idle timer");
                require(CommitPreviewWindows.install(manager, file, editor) == watch, "reused preview keeps a single observer");
                require(tree.getDoubleClickHandler() == mouse && tree.getEnterKeyHandler() == keyboard,
                        "native double-click and Enter handlers remain unchanged");
                watch.timer.getActionListeners()[0].actionPerformed(null);
                require(editor.isValid(), "preview without a standalone window stays open");
                Disposer.dispose(editor);
                editor = null;
                require(!watch.timer.isRunning(), "native editor disposal stops the timer");
                verifyViewerEditor(project);
                verifyOtherSources(project);
                verifyInactivity();
                System.out.println("PASS: native Changes preview detection, unchanged open handlers, timer attachment/reuse/disposal, 60-second idle boundary");
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                if (editor != null) Disposer.dispose(editor);
                if (processor != null && !processor.isDisposed()) Disposer.dispose(processor);
            }
        });
    }

    static void runNativeClosure() {
        var projectFixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("inactive-preview-close").getFixture();
        EdtTestUtil.runInEdtAndWait(() -> {
            try {
                projectFixture.setUp();
                try {
                    verifyCloseInInactiveSplitter(projectFixture.getProject());
                } finally {
                    projectFixture.tearDown();
                }
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private static void verifyViewerEditor(Project project) throws ReflectiveOperationException {
        Class<?> editorClass;
        try {
            editorClass = Class.forName("com.intellij.diff.editor.DiffEditorViewerFileEditor");
        } catch (ClassNotFoundException olderPlatform) {
            return;
        }
        var tree = new SimpleChangesBrowser(project, false, false).getViewer();
        var processor = (DiffRequestProcessor) Class.forName("com.intellij.openapi.vcs.changes.ChangesViewDiffPreviewProcessor")
                .getConstructors()[0].newInstance(tree, true);
        FileEditor editor = null;
        try {
            var providerConstructor = Class.forName("com.intellij.openapi.vcs.changes.EditorTabDiffPreviewProvider")
                    .getDeclaredConstructor(DiffRequestProcessor.class, kotlin.jvm.functions.Function0.class);
            providerConstructor.setAccessible(true);
            var provider = (DiffPreviewProvider) providerConstructor.newInstance(processor,
                    (kotlin.jvm.functions.Function0<String>) () -> "Native viewer Commit preview");
            var file = new PreviewDiffVirtualFile(provider);
            editor = (FileEditor) editorClass.getConstructor(com.intellij.openapi.vfs.VirtualFile.class,
                    Class.forName("com.intellij.diff.impl.DiffEditorViewer")).newInstance(file, processor);
            require(!(editor instanceof DiffRequestProcessorEditor), "new native editor uses the viewer branch");
            var watch = CommitPreviewWindows.install(FileEditorManager.getInstance(project), file, editor);
            require(watch != null && watch.timer.isRunning(), "native viewer editor starts its timer");
            var processorField = CommitPreviewWindows.Watch.class.getDeclaredField("processor");
            processorField.setAccessible(true);
            var observedProcessor = (DiffRequestProcessor) processorField.get(watch);
            require(CommitPreviewWindows.isSupported(processor.getContext(), observedProcessor, false),
                    "native viewer with Default place must reach the inactivity/close path");
            require(observedProcessor == processor, "native viewer retains its real request processor");
            require(CommitPreviewWindows.install(FileEditorManager.getInstance(project), file, editor) == watch,
                    "native viewer reuse keeps one observer");
            processor.putContextUserData(DiffUserDataKeys.MERGE_EDITOR_FLAG, true);
            require(!CommitPreviewWindows.isSupported(processor.getContext(), observedProcessor, false), "native viewer merge stays open");
            Disposer.dispose(editor);
            editor = null;
            require(!watch.timer.isRunning(), "native viewer disposal stops its timer");
            System.out.println("PASS: real native viewer editor, Default-place timeout eligibility, observer reuse, merge exclusion, disposal");
        } finally {
            if (editor != null) Disposer.dispose(editor);
            if (!processor.isDisposed()) Disposer.dispose(processor);
        }
    }

    private static void verifyCloseInInactiveSplitter(Project project) throws ReflectiveOperationException {
        // Native splitters and docking containers exercise closure without creating desktop windows.
        var parent = Disposer.newDisposable("inactive-preview-close-test");
        var scope = kotlinx.coroutines.CoroutineScopeKt.MainScope();
        var manager = new FileEditorManagerImpl(project, scope);
        Disposer.register(parent, manager);
        ServiceContainerUtil.replaceService(project, FileEditorManager.class, manager, parent);
        var mainSplitters = manager.getSplitters();
        var factory = DiffContentFactory.getInstance();
        var mainFile = new SimpleDiffVirtualFile(new SimpleDiffRequest("Active main comparison",
                factory.create("before"), factory.create("after"), "before", "after"));
        manager.openFile(mainFile, false);
        var splitters = new EditorsSplitters(manager, scope);
        splitters.createCurrentWindow$intellij_platform_ide_impl();
        EditorWindow window = splitters.getWindows()[0];
        var container = new DockableEditorTabbedContainer(splitters, false, scope);
        Disposer.register(parent, container);
        DockManager.getInstance(project).register(container, parent);
        var tree = new SimpleChangesBrowser(project, false, false).getViewer();
        var processor = (DiffRequestProcessor) Class.forName("com.intellij.openapi.vcs.changes.ChangesViewDiffPreviewProcessor")
                .getConstructors()[0].newInstance(tree, true);
        try {
            var providerConstructor = Class.forName("com.intellij.openapi.vcs.changes.EditorTabDiffPreviewProvider")
                    .getDeclaredConstructor(DiffRequestProcessor.class, kotlin.jvm.functions.Function0.class);
            providerConstructor.setAccessible(true);
            var provider = (DiffPreviewProvider) providerConstructor.newInstance(processor,
                    (kotlin.jvm.functions.Function0<String>) () -> "Inactive splitter Commit preview");
            var file = new PreviewDiffVirtualFile(provider);
            var composite = manager.openFile(file, window, new FileEditorOpenOptions()
                    .withRequestFocus(false).withSelectAsCurrent(false).withReuseOpen(false));
            FileEditor editor = composite.getAllEditors().get(0);
            var watch = CommitPreviewWindows.install(manager, file, editor);
            require(watch != null && watch.timer.isRunning(), "real inactive preview starts its observer");
            require(window.isFileOpen(file), "preview is open in its own splitter");
            require(java.util.Arrays.stream(mainSplitters.getWindows()).noneMatch(mainWindow -> mainWindow.isFileOpen(file)),
                    "preview is absent from the active main splitter");
            CommitPreviewWindows.closePreview(manager, file, editor);
            require(!window.isFileOpen(file), "inactive preview closes even while the main splitter is active");
            require(!editor.isValid(), "closing the inactive preview disposes its editor");
            require(!watch.timer.isRunning(), "closing the inactive preview releases its idle timer");
            require(manager.getSplitters() == mainSplitters, "closing a preview preserves the active main splitter");
            require(manager.isFileOpen(mainFile), "closing the inactive preview retains unrelated main editors");
            System.out.println("PASS: native inactive splitter preview closure, editor disposal, idle timer release, main splitter retained");
            verifyGitLogPreview(project, manager, window, parent);
        } finally {
            Disposer.dispose(parent);
            kotlinx.coroutines.CoroutineScopeKt.cancel(scope, null);
            if (!processor.isDisposed()) Disposer.dispose(processor);
        }
    }

    private static void verifyGitLogPreview(Project project, FileEditorManagerImpl manager, EditorWindow window,
                                            Disposable parent) throws ReflectiveOperationException {
        Class<?> modelClass = Class.forName("com.intellij.vcs.log.ui.frame.VcsLogAsyncChangesTreeModel");
        Class<?> propertiesClass = Class.forName("com.intellij.vcs.log.impl.VcsLogProjectTabsProperties");
        var properties = propertiesClass.getConstructor().newInstance();
        var scope = kotlinx.coroutines.CoroutineScopeKt.MainScope();
        var originalCombinedDiff = AdvancedSettings.getBoolean("enable.combined.diff");
        try {
            Class<?> logManagerClass = Class.forName("com.intellij.vcs.log.impl.VcsLogManager");
            var logManager = logManagerClass.getConstructors()[0].newInstance(project, scope, properties,
                    Map.of(), "inactive-git-log-preview", false,
                    (kotlin.jvm.functions.Function2<Object, Throwable, kotlin.Unit>) (source, error) -> {
                        throw new AssertionError(error);
                    });
            var logData = logManagerClass.getMethod("getDataManager").invoke(logManager);
            var tabProperties = propertiesClass.getMethod("createProperties", String.class).invoke(properties, "test");
            var model = modelClass.getConstructors()[0].newInstance(logData, tabProperties, parent);
            Class<?> browserClass = Class.forName("com.intellij.vcs.log.ui.frame.VcsLogChangesBrowser");
            var browser = browserClass.getConstructors()[0].newInstance(project, model, parent);
            Class<?> previewClass = Class.forName("com.intellij.vcs.log.ui.frame.VcsLogEditorDiffPreview");
            var preview = (Disposable) previewClass.getConstructor(browserClass).newInstance(browser);
            Disposer.register(parent, preview);
            var file = (VirtualFile) previewClass.getMethod("getPreviewFile").invoke(preview);
            for (boolean combinedDiff : new boolean[]{false, true}) {
                AdvancedSettings.setBoolean("enable.combined.diff", combinedDiff);
                var composite = manager.openFile(file, window, new FileEditorOpenOptions()
                        .withRequestFocus(false).withSelectAsCurrent(false).withReuseOpen(false));
                FileEditor editor = composite.getAllEditors().get(0);
                Object viewer = editor.getClass().getMethod("getEditorViewer").invoke(editor);
                require((viewer instanceof DiffRequestProcessor) != combinedDiff, "Git Log uses the selected diff mode");
                var watch = CommitPreviewWindows.install(manager, file, editor);
                require(watch != null && watch.supportsAutoClose(),
                        "native Git Log preview qualifies for auto-close, Combined Diff=" + combinedDiff);
                CommitPreviewWindows.closePreview(manager, file, editor);
                require(!window.isFileOpen(file), "native Git Log preview closes in its own splitter");
                require(!watch.timer.isRunning(), "closing a Git Log preview releases its timer");
            }
            System.out.println("PASS: native Git Log preview eligibility and closure in standard and Combined Diff modes");
        } finally {
            AdvancedSettings.setBoolean("enable.combined.diff", originalCombinedDiff);
            kotlinx.coroutines.CoroutineScopeKt.cancel(scope, null);
        }
    }

    private static void verifyOtherSources(Project project) {
        var factory = DiffContentFactory.getInstance();
        var request = new SimpleDiffRequest("history", factory.createEditable(project, "before", null),
                factory.createEditable(project, "after", null), "before", "after");
        var file = new SimpleDiffVirtualFile(request);
        require(!CommitPreviewWindows.isNativePreview(file), "unrelated diff files are not observed");
        DiffRequestProcessor processor = file.createProcessor(project);
        try {
            require(!CommitPreviewWindows.isSupported(processor.getContext(), processor, false), "unrelated comparison stays open");
            processor.putContextUserData(DiffUserDataKeys.PLACE, DiffPlaces.CHANGES_VIEW);
            require(CommitPreviewWindows.isSupported(processor.getContext(), processor, false), "Changes context qualifies");
            processor.putContextUserData(DiffUserDataKeys.PLACE, DiffPlaces.COMMIT_DIALOG);
            require(CommitPreviewWindows.isSupported(processor.getContext(), processor, false), "Commit context qualifies");
            processor.putContextUserData(DiffUserDataKeys.PLACE, DiffPlaces.VCS_LOG_VIEW);
            require(!CommitPreviewWindows.isSupported(processor.getContext(), processor, false), "history preview stays open");
        } finally {
            Disposer.dispose(processor);
        }
    }

    private static void verifyInactivity() {
        long minute = TimeUnit.MINUTES.toNanos(1);
        var inactivity = new CommitPreviewWindows.Inactivity(0);
        require(!inactivity.expired(minute - 1), "preview remains open before 60 seconds");
        require(inactivity.expired(minute), "preview expires at 60 seconds without focus");
        inactivity.active(minute);
        require(!inactivity.expired(2 * minute - 1), "returning focus restarts idle period");
        inactivity.active(2 * minute);
        require(!inactivity.expired(3 * minute - 1), "losing focus starts a full idle minute");
        require(inactivity.expired(3 * minute), "preview expires after a full idle minute");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
