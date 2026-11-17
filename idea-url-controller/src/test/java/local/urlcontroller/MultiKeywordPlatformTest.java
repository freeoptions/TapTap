package local.urlcontroller;

import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindResult;
import com.intellij.find.FindSettings;
import com.intellij.find.impl.FindPopupPanel;
import com.intellij.find.impl.FindUIHelper;
import com.intellij.find.impl.FindInProjectUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.usages.FindUsagesProcessPresentation;
import com.intellij.usages.UsageViewPresentation;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.UIUtil;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JTextArea;

/** Uses the real native popup, scope adapter and FindManager inside an isolated platform sandbox. */
public final class MultiKeywordPlatformTest {
    static void searchFiles(CodeInsightTestFixture fixture) {
        var includedFile = fixture.addFileToProject("keyword-search/one.txt", "扣减商品可用库存\n库存\n扣减\n校验库存后执行扣减");
        var excludedFile = fixture.addFileToProject("keyword-search/two.java", "// 库存 扣减");
        fixture.addFileToProject("other-keyword-search/three.txt", "库存 扣减");
        FindModel model = new FindModel();
        model.setStringToFind(MultiKeywordQuery.regex("库存 扣减", false));
        model.setRegularExpressions(true);
        model.setMultipleFiles(true);
        model.setCustomScope(true);
        model.setCustomScope(GlobalSearchScope.filesWithoutLibrariesScope(fixture.getProject(),
                List.of(includedFile.getVirtualFile(), excludedFile.getVirtualFile())));
        model.setFileFilter("*.txt");
        AtomicInteger count = new AtomicInteger();
        ProgressManager.getInstance().runProcess(() -> FindInProjectUtil.findUsages(model, fixture.getProject(),
                new FindUsagesProcessPresentation(new UsageViewPresentation()), Set.of(), usage -> {
                    require(usage.getFile().getVirtualFile().equals(includedFile.getVirtualFile()), "native file mask and scope");
                    count.incrementAndGet();
                    return true;
                }), new EmptyProgressIndicator());
        require(count.get() == 2, "native project search finds both matching lines: " + count.get());
        System.out.println("PASS: actual project content search, scope and file mask, unordered keywords, split-line exclusion");
    }

    static void run(Project project) {
        EdtTestUtil.runInEdtAndWait(() -> {
            PropertiesComponent preferences = PropertiesComponent.getInstance();
            String preferenceKey = "local.urlcontroller.multiKeyword.enabled";
            String previousPreference = preferences.getValue(preferenceKey);
            preferences.unsetValue(preferenceKey);
            FindModel model = new FindModel();
            model.setMultipleFiles(true);
            model.setProjectScope(true);
            model.setStringToFind("库存 扣减");
            FindUIHelper helper = null;
            try {
                Constructor<FindUIHelper> constructor = FindUIHelper.class.getDeclaredConstructor(Project.class, FindModel.class, Runnable.class);
                constructor.setAccessible(true);
                helper = constructor.newInstance(project, model, (Runnable) () -> {});
                Field uiField = FindUIHelper.class.getDeclaredField("myUI");
                uiField.setAccessible(true);
                FindPopupPanel panel = (FindPopupPanel) uiField.get(helper);
                FindManager findManager = FindManager.getInstance(project);
                Field helperField = findManager.getClass().getDeclaredField("myHelper");
                helperField.setAccessible(true);
                Object previousHelper = helperField.get(findManager);
                helperField.set(findManager, helper);
                try {
                    AnAction action = ActionManager.getInstance().getAction("FindInPath");
                    AnActionEvent event = AnActionEvent.createFromAnAction(action, null, "test",
                            SimpleDataContext.getProjectContext(project));
                    ApplicationManager.getApplication().getMessageBus().syncPublisher(AnActionListener.TOPIC)
                            .beforeActionPerformed(action, event);
                    UIUtil.dispatchAllInvocationEvents();
                } finally {
                    helperField.set(findManager, previousHelper);
                }
                JBCheckBox checkBox = (JBCheckBox) panel.getClientProperty("local.urlcontroller.multiKeyword");
                require(checkBox != null && !checkBox.isSelected(), "switch defaults to off");
                require(MultiKeywordFindListener.install(panel) == checkBox, "installation is idempotent");
                Method apply = FindPopupPanel.class.getDeclaredMethod("applyTo", FindModel.class);
                apply.setAccessible(true);
                FindModel literalModel = new FindModel();
                apply.invoke(panel, literalModel);
                require(!literalModel.isRegularExpressions(), "off preserves literal mode");
                require(literalModel.getStringToFind().equals("库存 扣减"), "off preserves real space");
                checkBox.doClick();
                helper.setModel(model.clone());
                require(checkBox.isSelected(), "reopening Find in Files remembers enabled multi-keyword switch");
                checkBox.setSelected(true);
                FindModel searchModel = new FindModel();
                apply.invoke(panel, searchModel);
                FindManager manager = FindManager.getInstance(project);
                String text = "扣减商品可用库存\n库存\n扣减\n校验库存后执行扣减";
                FindResult first = manager.findString(text, 0, searchModel);
                require(first.isStringFound() && first.getStartOffset() == 0 && first.getEndOffset() == 8,
                        "native engine finds reverse-order line");
                FindResult second = manager.findString(text, first.getEndOffset(), searchModel);
                require(second.isStringFound() && second.getStartOffset() == text.lastIndexOf("校验"),
                        "native engine excludes split lines and locates next match");
                require(!manager.findString(text, second.getEndOffset(), searchModel).isStringFound(), "no additional matches");
                require(panel.getStringToFind().equals("库存 扣减"), "input retains words rather than generated regex");
                FindModel persistentModel = manager.getFindInProjectModel();
                apply.invoke(panel, persistentModel);
                require(persistentModel.getStringToFind().equals("库存 扣减") && !persistentModel.isRegularExpressions(),
                        "persistent model retains literal input/options");
                // Exercise the native settings-save hook without opening a window.
                helper.getModel().copyFrom(searchModel);
                Field scopeField = FindPopupPanel.class.getDeclaredField("myScopeUI");
                scopeField.setAccessible(true);
                Object scope = scopeField.get(panel);
                Field selectedScopeField = FindPopupPanel.class.getDeclaredField("mySelectedScope");
                selectedScopeField.setAccessible(true);
                Class<?> scopeType = Class.forName("com.intellij.find.impl.FindPopupScopeUI$ScopeType");
                Method save = scopeField.getType().getMethod("applyTo", FindSettings.class, scopeType);
                save.invoke(scope, FindSettings.getInstance(), selectedScopeField.get(panel));
                require(helper.getModel().getStringToFind().equals("库存 扣减") && !helper.getModel().isRegularExpressions(),
                        "settings save retains raw query/options");
                require(searchModel.isRegularExpressions(), "settings save preserves captured search model");
                checkBox.setSelected(false);
                FindModel restoredModel = new FindModel();
                apply.invoke(panel, restoredModel);
                require(restoredModel.getStringToFind().equals("库存 扣减") && !restoredModel.isRegularExpressions(),
                        "turning off restores native search");
                Field textField = FindPopupPanel.class.getDeclaredField("mySearchComponent");
                textField.setAccessible(true);
                ((JTextArea) textField.get(panel)).setText("@Table _outbound\"");
                checkBox.setSelected(true);
                FindModel quoteModel = new FindModel();
                apply.invoke(panel, quoteModel);
                require(manager.findString("@Table(name = \"order_fulfillment_outbound\")", 0, quoteModel).isStringFound(),
                        "native multi-keyword search matches a literal trailing quote");
                require(!manager.findString("@Table(name = \"order_fulfillment_outbound_detail\")", 0, quoteModel).isStringFound(),
                        "literal trailing quote excludes longer table names");
                require(!manager.findString("@Table(name = order_fulfillment_outbound)", 0, quoteModel).isStringFound(),
                        "double quotes participate in matching");
                require(!checkBox.getToolTipText().contains("尚未闭合"), "literal quote does not trigger unclosed phrase feedback");
                ((JTextArea) textField.get(panel)).setText("public static");
                checkBox.setSelected(false);
                FindModel continuousModel = new FindModel();
                apply.invoke(panel, continuousModel);
                require(manager.findString("public static void", 0, continuousModel).isStringFound(), "native mode matches continuous text");
                require(!manager.findString("public final static void", 0, continuousModel).isStringFound(),
                        "native mode retains significant spaces");
                FindModel replaceModel = new FindModel();
                replaceModel.setReplaceState(true);
                replaceModel.setStringToFind("public static");
                helper.setModel(replaceModel);
                require(!checkBox.isSelected() && !checkBox.isVisible(), "replace mode hides and resets switch");
                FindModel replaceSearchModel = new FindModel();
                apply.invoke(panel, replaceSearchModel);
                require(!replaceSearchModel.isRegularExpressions() && replaceSearchModel.getStringToFind().equals("public static"),
                        "replace mode preserves native matching");
                FindModel reopenedModel = new FindModel();
                reopenedModel.setStringToFind("order list");
                helper.setModel(reopenedModel);
                require(checkBox.isVisible() && checkBox.isSelected(), "returning from replace restores remembered switch");
                checkBox.doClick();
                helper.setModel(reopenedModel.clone());
                require(!checkBox.isSelected(), "reopening also remembers disabled switch");
                Disposer.dispose(helper);
                helper = constructor.newInstance(project, reopenedModel.clone(), (Runnable) () -> {});
                JBCheckBox recreatedCheckBox = MultiKeywordFindListener.install((FindPopupPanel) uiField.get(helper));
                require(!recreatedCheckBox.isSelected(), "new popup restores disabled preference");
                recreatedCheckBox.doClick();
                Disposer.dispose(helper);
                helper = constructor.newInstance(project, reopenedModel.clone(), (Runnable) () -> {});
                FindPopupPanel recreatedPanel = (FindPopupPanel) uiField.get(helper);
                JBCheckBox rememberedCheckBox = MultiKeywordFindListener.install(recreatedPanel);
                require(rememberedCheckBox.isSelected(), "new popup restores enabled preference");
                FindModel rememberedModel = new FindModel();
                apply.invoke(recreatedPanel, rememberedModel);
                require(manager.findString("list of orders", 0, rememberedModel).isStringFound(),
                        "restored preference enables matching without another click");
                System.out.println("PASS: real native Find popup switch, AND search, result offsets, literal quotes, native continuous matching, settings isolation, replace isolation");
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                if (helper != null) Disposer.dispose(helper);
                if (previousPreference == null) preferences.unsetValue(preferenceKey);
                else preferences.setValue(preferenceKey, previousPreference);
            }
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
