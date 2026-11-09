package local.urlcontroller;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.*;
import java.util.*;

/** Never resolves references into another file while a file-based index is being built. */
public final class SpringRoutes {
    private static final String SPRING = "org.springframework.web.bind.annotation.";
    private static final Map<String, String> VERBS = Map.of(
            "RequestMapping", "ANY", "GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");

    private SpringRoutes() {}

    public static List<Route> extract(PsiJavaFile file) {
        List<Route> routes = new ArrayList<>();
        for (PsiClass owner : file.getClasses()) extractClass(file, owner, routes);
        return List.copyOf(new LinkedHashSet<>(routes));
    }

    private static void extractClass(PsiJavaFile file, PsiClass owner, List<Route> output) {
        ProgressManager.checkCanceled();
        if (!owner.isInterface() && !owner.isAnnotationType()) {
            Mapping parent = mapping(file, owner.getModifierList(), owner);
            List<String> bases = parent == null ? List.of("") : parent.paths;
            for (PsiMethod method : owner.getMethods()) {
                ProgressManager.checkCanceled();
                Mapping child = mapping(file, method.getModifierList(), owner);
                if (child == null) continue;
                Set<String> verbs = combineVerbs(parent, child);
                for (String base : bases) {
                    for (String suffix : child.paths) {
                        String path = UrlPaths.join(base, suffix);
                        // Unresolved environment placeholders must not become invented endpoints.
                        if (path.contains("${") || path.contains("#{")) continue;
                        for (String verb : verbs) {
                            output.add(new Route(path, verb, Objects.toString(owner.getQualifiedName(), owner.getName()),
                                    method.getName(), method.getTextOffset()));
                        }
                    }
                }
            }
        }
        for (PsiClass nested : owner.getInnerClasses()) extractClass(file, nested, output);
    }

    private static Set<String> combineVerbs(Mapping parent, Mapping child) {
        if (parent == null || parent.verbs.contains("ANY")) return child.verbs;
        if (child.verbs.contains("ANY")) return parent.verbs;
        Set<String> intersection = new TreeSet<>(parent.verbs);
        intersection.retainAll(child.verbs);
        return intersection;
    }

    private static Mapping mapping(PsiJavaFile file, PsiModifierList modifiers, PsiClass owner) {
        if (modifiers == null) return null;
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
            if (reference == null) continue;
            String shortName = reference.getReferenceName();
            if (!VERBS.containsKey(shortName) || !isSpring(file, reference.getText(), shortName)) continue;
            PsiAnnotationMemberValue path = annotation.findDeclaredAttributeValue("path");
            if (path == null) path = annotation.findDeclaredAttributeValue("value");
            List<String> paths = path == null ? List.of("") : strings(path, owner, new HashSet<>());
            Set<String> verbs = new TreeSet<>();
            if (shortName.equals("RequestMapping")) {
                PsiAnnotationMemberValue methods = annotation.findDeclaredAttributeValue("method");
                if (methods == null) verbs.add("ANY");
                else collectVerbs(methods, verbs);
            } else verbs.add(VERBS.get(shortName));
            return new Mapping(paths, Set.copyOf(verbs));
        }
        return null;
    }

    private static boolean isSpring(PsiJavaFile file, String name, String shortName) {
        if (name.equals(SPRING + shortName)) return true;
        if (!name.equals(shortName)) return false;
        PsiImportList imports = file.getImportList();
        if (imports == null) return false;
        boolean wildcard = false;
        for (PsiImportStatement statement : imports.getImportStatements()) {
            String imported = statement.getQualifiedName();
            if (imported == null) continue;
            if (!statement.isOnDemand() && imported.endsWith("." + shortName)) return imported.equals(SPRING + shortName);
            if (statement.isOnDemand() && imported.equals(SPRING.substring(0, SPRING.length() - 1))) wildcard = true;
        }
        return wildcard || Objects.equals(file.getPackageName() + "." + shortName, SPRING + shortName);
    }

    private static void collectVerbs(PsiAnnotationMemberValue value, Set<String> result) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue item : array.getInitializers()) collectVerbs(item, result);
        } else {
            String text = value.getText();
            String name = text.substring(text.lastIndexOf('.') + 1);
            if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE").contains(name)) result.add(name);
        }
    }

    private static List<String> strings(PsiAnnotationMemberValue value, PsiClass owner, Set<PsiField> visiting) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            List<String> result = new ArrayList<>();
            for (PsiAnnotationMemberValue item : array.getInitializers()) result.addAll(strings(item, owner, visiting));
            return result;
        }
        String text = string(value, owner, visiting);
        return text == null ? List.of() : List.of(text);
    }

    private static String string(PsiElement value, PsiClass owner, Set<PsiField> visiting) {
        if (value instanceof PsiLiteralExpression literal) return literal.getValue() instanceof String text ? text : null;
        if (value instanceof PsiParenthesizedExpression parentheses) return string(parentheses.getExpression(), owner, visiting);
        if (value instanceof PsiPolyadicExpression expression && expression.getOperationTokenType() == JavaTokenType.PLUS) {
            StringBuilder result = new StringBuilder();
            for (PsiExpression operand : expression.getOperands()) {
                String part = string(operand, owner, visiting);
                if (part == null) return null;
                result.append(part);
            }
            return result.toString();
        }
        if (value instanceof PsiReferenceExpression reference) {
            PsiExpression qualifier = reference.getQualifierExpression();
            for (PsiClass current = owner; current != null; current = current.getContainingClass()) {
                if (qualifier != null && !qualifier.getText().equals(current.getName())
                        && !qualifier.getText().equals(current.getQualifiedName())) continue;
                for (PsiField field : current.getFields()) {
                    if (Objects.equals(field.getName(), reference.getReferenceName())
                            && field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) {
                        if (!visiting.add(field)) return null;
                        String result = string(field.getInitializer(), current, visiting);
                        visiting.remove(field);
                        return result;
                    }
                }
            }
        }
        return null;
    }

    private record Mapping(List<String> paths, Set<String> verbs) {}
}
