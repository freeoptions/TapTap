package local.urlcontroller;

import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindSettings;
import com.intellij.find.impl.FindPopupPanel;
import com.intellij.find.impl.FindUIHelper;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionResult;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBCheckBox;
import java.awt.BorderLayout;
import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Narrow adapter for the native Find in Files popup, which has no toolbar extension point. */
public final class MultiKeywordFindListener implements AnActionListener {
    private static final Logger LOG = Logger.getInstance(MultiKeywordFindListener.class);
    private static final String STATE_KEY = "local.urlcontroller.multiKeyword";
    private static final String PREFERENCE_KEY = "local.urlcontroller.multiKeyword.enabled";
    private static final String HELP = "同一行包含全部关键词，顺序不限；空格或Tab分词；双引号等字符按原文匹配";

    @Override public void beforeActionPerformed(AnAction action, AnActionEvent event) {
        scheduleInstall(action, event);
    }

    @Override public void afterActionPerformed(AnAction action, AnActionEvent event, AnActionResult result) {
        scheduleInstall(action, event);
    }

    private static void scheduleInstall(AnAction action, AnActionEvent event) {
        String id = ActionManager.getInstance().getId(action);
        Project project = event.getProject();
        if (project == null) return;
        if (!("FindInPath".equals(id) || "ReplaceInPath".equals(id))) {
            Component context = event.getData(PlatformDataKeys.CONTEXT_COMPONENT);
            if (context == null || !(SwingUtilities.getAncestorOfClass(FindPopupPanel.class, context) instanceof FindPopupPanel)) return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                Object helper = field(FindManager.getInstance(project), "myHelper").get(FindManager.getInstance(project));
                if (helper == null) return;
                Object ui = field(helper, "myUI").get(helper);
                if (ui instanceof FindPopupPanel panel && !Disposer.isDisposed(panel.getDisposable())) install(panel);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                LOG.warn("Cannot attach multi-keyword switch to Find in Files", failure);
            }
        });
    }

    static JBCheckBox install(FindPopupPanel panel) throws ReflectiveOperationException {
        Object existing = panel.getClientProperty(STATE_KEY);
        if (existing instanceof JBCheckBox checkBox) return checkBox;
        FindUIHelper helper = (FindUIHelper) field(panel, "myHelper").get(panel);
        // Replace in Files must retain its original matching and replacement semantics.
        if (helper.isReplaceState()) return null;
        Object searchArea = field(panel, "mySearchTextArea").get(panel);
        JPanel actionsPanel = (JPanel) field(searchArea, "myExtraActionsPanel").get(searchArea);
        BorderLayout layout = (BorderLayout) actionsPanel.getLayout();
        Component nativeActions = layout.getLayoutComponent(BorderLayout.NORTH);
        Field scopeField = field(panel, "myScopeUI");
        Object delegate = scopeField.get(panel);
        PropertiesComponent preferences = PropertiesComponent.getInstance();
        JBCheckBox checkBox = new JBCheckBox("多关键词", preferences.getBoolean(PREFERENCE_KEY, false));
        checkBox.setOpaque(false);
        checkBox.setToolTipText(HELP);
        Object proxy = Proxy.newProxyInstance(scopeField.getType().getClassLoader(), new Class<?>[]{scopeField.getType()},
                (instance, method, args) -> {
                    Object value;
                    try {
                        value = method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if ("initByModel".equals(method.getName())) {
                        checkBox.setSelected(!helper.isReplaceState() && preferences.getBoolean(PREFERENCE_KEY, false));
                        checkBox.setVisible(!helper.isReplaceState());
                    }
                    if ("validate".equals(method.getName()) && checkBox.isSelected() && !helper.isReplaceState()) {
                        try {
                            MultiKeywordQuery.regex(panel.getStringToFind(), false);
                        } catch (IllegalArgumentException failure) {
                            return new ValidationInfo(failure.getMessage(), (JPanel) searchArea);
                        }
                    }
                    if ("applyTo".equals(method.getName()) && args[0] instanceof FindSettings && checkBox.isSelected()) {
                        // Save the user's query/options without mutating an already-running search model.
                        FindModel savedModel = helper.getModel().clone();
                        savedModel.setStringToFind(panel.getStringToFind());
                        savedModel.setRegularExpressions(((AtomicBoolean) field(panel, "myRegexState").get(panel)).get());
                        savedModel.setWholeWordsOnly(((AtomicBoolean) field(panel, "myWholeWordsState").get(panel)).get());
                        field(helper, "myModel").set(helper, savedModel);
                    }
                    if ("applyTo".equals(method.getName()) && args[0] instanceof FindModel model
                            && model != FindManager.getInstance(panel.getProject()).getFindInProjectModel()
                            && checkBox.isSelected() && !helper.isReplaceState()) {
                        try {
                            model.setStringToFind(MultiKeywordQuery.regex(model.getStringToFind(), model.isWholeWordsOnly()));
                            checkBox.setToolTipText(HELP);
                        } catch (IllegalArgumentException failure) {
                            model.setStringToFind("(?!)");
                            checkBox.setToolTipText(failure.getMessage());
                        }
                        model.setRegularExpressions(true);
                        model.setWholeWordsOnly(false);
                    }
                    return value;
                });
        scopeField.set(panel, proxy);
        if (nativeActions != null) {
            actionsPanel.remove(nativeActions);
            actionsPanel.add(nativeActions, BorderLayout.CENTER);
        }
        actionsPanel.add(checkBox, BorderLayout.WEST);
        checkBox.addActionListener(event -> {
            preferences.setValue(PREFERENCE_KEY, checkBox.isSelected(), false);
            checkBox.setToolTipText(HELP);
            panel.scheduleResultsUpdate();
        });
        panel.putClientProperty(STATE_KEY, checkBox);
        actionsPanel.revalidate();
        actionsPanel.repaint();
        if (checkBox.isSelected()) panel.scheduleResultsUpdate();
        return checkBox;
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
