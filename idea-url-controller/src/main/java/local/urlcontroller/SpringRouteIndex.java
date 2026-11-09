package local.urlcontroller;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.psi.PsiJavaFile;
import com.intellij.util.indexing.*;
import com.intellij.util.io.*;
import java.io.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class SpringRouteIndex extends FileBasedIndexExtension<String, List<Route>> {
    public static final ID<String, List<Route>> ID = com.intellij.util.indexing.ID.create("local.urlcontroller.SpringRoutes");
    public static final String KEY = "routes";

    @Override public @NotNull ID<String, List<Route>> getName() { return ID; }
    @Override public int getVersion() { return 1; }
    @Override public boolean dependsOnFileContent() { return true; }
    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(JavaFileType.INSTANCE);
    }
    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor() { return EnumeratorStringDescriptor.INSTANCE; }

    @Override public @NotNull DataIndexer<String, List<Route>, FileContent> getIndexer() {
        return content -> {
            // Cheap filter before loading a Java AST. No project resolve, no nested index access.
            if (!content.getContentAsText().toString().contains("Mapping")) return Map.of();
            if (!(content.getPsiFile() instanceof PsiJavaFile file)) return Map.of();
            List<Route> routes = SpringRoutes.extract(file);
            return routes.isEmpty() ? Map.of() : Map.of(KEY, routes);
        };
    }

    @Override public @NotNull DataExternalizer<List<Route>> getValueExternalizer() {
        return new DataExternalizer<>() {
            @Override public void save(@NotNull DataOutput out, List<Route> routes) throws IOException {
                out.writeInt(routes.size());
                for (Route route : routes) {
                    IOUtil.writeUTF(out, route.path());
                    IOUtil.writeUTF(out, route.verb());
                    IOUtil.writeUTF(out, route.owner());
                    IOUtil.writeUTF(out, route.method());
                    out.writeInt(route.offset());
                }
            }
            @Override public List<Route> read(@NotNull DataInput in) throws IOException {
                int size = in.readInt();
                if (size < 0 || size > 1_000_000) throw new IOException("Invalid route count: " + size);
                List<Route> result = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    result.add(new Route(IOUtil.readUTF(in), IOUtil.readUTF(in), IOUtil.readUTF(in), IOUtil.readUTF(in), in.readInt()));
                }
                return List.copyOf(result);
            }
        };
    }
}
