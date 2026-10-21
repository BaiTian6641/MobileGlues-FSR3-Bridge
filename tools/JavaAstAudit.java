import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Syntax parses bridge Java with javac's real AST and inventories risky seams. */
public final class JavaAstAudit {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none", "--release", "17"), null, manager.getJavaFileObjectsFromPaths(files));
            int[] counts = new int[6];
            for (CompilationUnitTree unit : task.parse()) {
                counts[0]++;
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        counts[1]++;
                        if (node.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.NATIVE)) counts[2]++;
                        return super.visitMethod(node, unused);
                    }
                    @Override public Void visitCatch(CatchTree node, Void unused) {
                        counts[3]++;
                        if (node.getParameter().getType().toString().equals("Throwable")) counts[4]++;
                        if (node.getBlock().getStatements().isEmpty()) {
                            counts[5]++;
                            System.out.println("EMPTY_CATCH " + unit.getSourceFile().getName() + " " + node.getParameter());
                        }
                        return super.visitCatch(node, unused);
                    }
                }.scan(unit, null);
            }
            boolean error = false;
            for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                System.out.println(diagnostic);
                error |= diagnostic.getKind() == Diagnostic.Kind.ERROR;
            }
            System.out.printf(Locale.ROOT, "AST files=%d methods=%d native=%d catches=%d broad_catches=%d empty_catches=%d syntax_errors=%s%n",
                counts[0],counts[1],counts[2],counts[3],counts[4],counts[5],error);
            if (error || counts[4] != 0 || counts[5] != 0) System.exit(1);
        }
    }
}
