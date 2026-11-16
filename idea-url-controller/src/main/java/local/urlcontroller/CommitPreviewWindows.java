package local.urlcontroller;

import com.intellij.diff.DiffContext;
import com.intellij.diff.editor.DiffEditorTabFilesManager;
import com.intellij.diff.editor.DiffRequestProcessorEditor;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.util.DiffPlaces;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.changes.PreviewDiffVirtualFile;
import com.intellij.openapi.vfs.VirtualFile;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Observes native Commit previews without changing how IDEA opens or reuses them. */
public final class CommitPreviewWindows implements FileEditorManagerListener {
    private static final Logger LOG = Logger.getInstance(CommitPreviewWindows.class);
    private static final String WATCH_KEY = "local.urlcontroller.commitPreviewWatch";

    @Override public void fileOpened(FileEditorManager manager, VirtualFile file) {
        if (!isNativePreview(file)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (manager.getProject().isDisposed()) return;
            for (FileEditor editor : manager.getAllEditors(file)) install(manager, file, editor);
        });
    }

    static boolean isNativePreview(VirtualFile file) {
        return file instanceof PreviewDiffVirtualFile || file.getClass().getName().equals(
                "com.intellij.openapi.vcs.changes.EditorTabDiffPreview$TabPreviewDiffVirtualFile");
    }

    static Watch install(FileEditorManager manager, VirtualFile file, FileEditor editor) {
        if (!isNativePreview(file) || !editor.isValid()) return null;
        JComponent component = editor.getComponent();
        if (component.getClientProperty(WATCH_KEY) instanceof Watch watch) return watch;
        try {
            DiffRequestProcessor processor = editor instanceof DiffRequestProcessorEditor diffEditor ? diffEditor.getProcessor() : null;
            DiffContext context;
            if (processor != null) {
                context = processor.getContext();
            } else {
                // New native combined previews expose their shared context through DiffEditorViewer.
                Object viewer = editor.getClass().getMethod("getEditorViewer").invoke(editor);
                context = (DiffContext) Class.forName("com.intellij.diff.impl.DiffEditorViewer").getMethod("getContext").invoke(viewer);
            }
            Watch watch = new Watch(manager, file, editor, context, processor);
            component.putClientProperty(WATCH_KEY, watch);
            Disposer.register(editor, watch::stop);
            watch.timer.start();
            return watch;
        } catch (ReflectiveOperationException failure) {
            LOG.warn("Cannot observe native Commit preview", failure);
            return null;
        }
    }

    static boolean isSupported(DiffContext context, DiffRequestProcessor processor) {
        DiffRequest request = processor == null ? null : processor.getActiveRequest();
        if (Boolean.TRUE.equals(context.getUserData(DiffUserDataKeys.MERGE_EDITOR_FLAG))
                || request != null && Boolean.TRUE.equals(request.getUserData(DiffUserDataKeys.MERGE_EDITOR_FLAG))) return false;
        String place = context.getUserData(DiffUserDataKeys.PLACE);
        // IDEA's own ChangesView editor processor uses the Default place, not ChangesView.
        return DiffPlaces.CHANGES_VIEW.equals(place) || DiffPlaces.COMMIT_DIALOG.equals(place)
                || processor != null && processor.getClass().getName().equals(
                        "com.intellij.openapi.vcs.changes.ChangesViewDiffPreviewProcessor");
    }

    private static boolean isSeparateWindow(FileEditorManager manager, VirtualFile file) throws ReflectiveOperationException {
        try {
            return (boolean) DiffEditorTabFilesManager.class.getMethod("isDiffOpenedInWindow", VirtualFile.class)
                    .invoke(DiffEditorTabFilesManager.getInstance(manager.getProject()), file);
        } catch (NoSuchMethodException olderPlatform) {
            return (boolean) DiffEditorTabFilesManager.class.getMethod("isDiffOpenedInNewWindow", VirtualFile.class).invoke(null, file);
        }
    }

    static final class Inactivity {
        private long lastActiveAt;

        Inactivity(long now) { lastActiveAt = now; }

        void active(long now) { lastActiveAt = now; }

        boolean expired(long now) { return now - lastActiveAt >= TimeUnit.MINUTES.toNanos(1); }
    }

    static final class Watch {
        final Timer timer;
        private final FileEditorManager manager;
        private final VirtualFile file;
        private final FileEditor editor;
        private final DiffContext context;
        private final DiffRequestProcessor processor;
        private final Inactivity inactivity = new Inactivity(System.nanoTime());
        private final WindowAdapter focusListener = new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent event) { inactivity.active(System.nanoTime()); }
            @Override public void windowLostFocus(WindowEvent event) { inactivity.active(System.nanoTime()); }
        };
        private Window window;

        private Watch(FileEditorManager manager, VirtualFile file, FileEditor editor, DiffContext context, DiffRequestProcessor processor) {
            this.manager = manager;
            this.file = file;
            this.editor = editor;
            this.context = context;
            this.processor = processor;
            timer = new Timer(5000, event -> tick());
        }

        private void tick() {
            if (manager.getProject().isDisposed() || !editor.isValid()) {
                stop();
                return;
            }
            try {
                JComponent component = editor.getComponent();
                Window current = component.isShowing() && isSupported(context, processor) && isSeparateWindow(manager, file)
                        ? SwingUtilities.getWindowAncestor(component) : null;
                if (current != window) {
                    if (window != null) window.removeWindowFocusListener(focusListener);
                    window = current;
                    if (window != null) window.addWindowFocusListener(focusListener);
                    inactivity.active(System.nanoTime());
                }
                if (window == null) {
                    inactivity.active(System.nanoTime());
                    return;
                }
                for (Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
                        focused != null; focused = focused.getOwner()) {
                    if (focused == window) {
                        inactivity.active(System.nanoTime());
                        return;
                    }
                }
                if (inactivity.expired(System.nanoTime())) {
                    LOG.info("Closing inactive native Commit preview");
                    // Close only the native preview file; IDEA owns the window and disposal lifecycle.
                    manager.closeFile(file);
                }
            } catch (ReflectiveOperationException failure) {
                stop();
                LOG.warn("Cannot determine native Commit preview window; auto-close disabled for this preview", failure);
            }
        }

        void stop() {
            timer.stop();
            if (window != null) window.removeWindowFocusListener(focusListener);
            window = null;
        }
    }
}
