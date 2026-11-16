package local.urlcontroller;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.editor.DiffRequestProcessorEditor;
import com.intellij.diff.editor.SimpleDiffVirtualFile;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffPlaces;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.changes.DiffPreviewProvider;
import com.intellij.openapi.vcs.changes.PreviewDiffVirtualFile;
import com.intellij.openapi.vcs.changes.ui.SimpleChangesBrowser;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
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
                require(CommitPreviewWindows.isSupported(processor.getContext(), processor), "native Changes/Unversioned preview qualifies");
                processor.putContextUserData(DiffUserDataKeys.MERGE_EDITOR_FLAG, true);
                require(!CommitPreviewWindows.isSupported(processor.getContext(), processor), "merge preview stays open");
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

    private static void verifyOtherSources(Project project) {
        var factory = DiffContentFactory.getInstance();
        var request = new SimpleDiffRequest("history", factory.createEditable(project, "before", null),
                factory.createEditable(project, "after", null), "before", "after");
        var file = new SimpleDiffVirtualFile(request);
        require(!CommitPreviewWindows.isNativePreview(file), "unrelated diff files are not observed");
        DiffRequestProcessor processor = file.createProcessor(project);
        try {
            require(!CommitPreviewWindows.isSupported(processor.getContext(), processor), "unrelated comparison stays open");
            processor.putContextUserData(DiffUserDataKeys.PLACE, DiffPlaces.CHANGES_VIEW);
            require(CommitPreviewWindows.isSupported(processor.getContext(), processor), "Changes context qualifies");
            processor.putContextUserData(DiffUserDataKeys.PLACE, DiffPlaces.COMMIT_DIALOG);
            require(CommitPreviewWindows.isSupported(processor.getContext(), processor), "Commit context qualifies");
            processor.putContextUserData(DiffUserDataKeys.PLACE, DiffPlaces.VCS_LOG_VIEW);
            require(!CommitPreviewWindows.isSupported(processor.getContext(), processor), "history preview stays open");
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
