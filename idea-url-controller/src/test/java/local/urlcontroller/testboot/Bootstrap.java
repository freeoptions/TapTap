package local.urlcontroller.testboot;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.testFramework.TestApplicationManager;

/** Keep the plugin under its real plugin class loader, just as in an installed IDE. */
public final class Bootstrap {
    public static void main(String[] args) {
        try {
            TestApplicationManager.getInstance();
            var plugin = PluginManagerCore.getPlugin(PluginId.getId("local.urlcontroller"));
            if (plugin == null || !plugin.isEnabled()) throw new AssertionError("Plugin did not load");
            Class<?> test = plugin.getPluginClassLoader().loadClass("local.urlcontroller.PlatformIntegrationTest");
            test.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
