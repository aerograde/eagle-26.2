import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.teavm.tooling.ConsoleTeaVMToolLog;
import org.teavm.tooling.TeaVMProblemRenderer;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.builder.InProcessBuildStrategy;
import org.teavm.vm.TeaVMOptimizationLevel;
import org.teavm.vm.TeaVMPhase;
import org.teavm.vm.TeaVMProgressFeedback;
import org.teavm.vm.TeaVMProgressListener;

/**
 * Minimal standalone equivalent of the Gradle TeaVM WasmGC task.
 *
 * <p>The linker graph and optimizer are identical; only Gradle's resident model,
 * daemon registry and caches are absent from the linker JVM. This is important
 * for the 13 GiB hard process-tree build limit.</p>
 */
public final class StandaloneTeaVMLinker {

    private StandaloneTeaVMLinker() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6 && args.length != 7) {
            throw new IllegalArgumentException(
                    "usage: StandaloneTeaVMLinker <classpath-file> <main-class> <output-dir> <output-file> "
                            + "<optimization> <obfuscated> [fast-dependency-analysis]");
        }
        boolean fastDependencyAnalysis = args.length == 7 && Boolean.parseBoolean(args[6]);
        List<String> classPath = Files.readAllLines(Path.of(args[0])).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        Files.createDirectories(Path.of(args[2]));

        ConsoleTeaVMToolLog log = new ConsoleTeaVMToolLog(false);
        InProcessBuildStrategy builder = new InProcessBuildStrategy();
        builder.init();
        builder.setLog(log);
        builder.setProgressListener(new TeaVMProgressListener() {
            private int total;
            private int bucket = -1;

            @Override
            public TeaVMProgressFeedback phaseStarted(TeaVMPhase phase, int count) {
                total = count;
                bucket = -1;
                System.out.println("[standalone-teavm] phase=" + phase + " workItems=" + count);
                return TeaVMProgressFeedback.CONTINUE;
            }

            @Override
            public TeaVMProgressFeedback progressReached(int progress) {
                int nextBucket = total > 0 ? progress * 10 / total : 0;
                if (nextBucket > bucket) {
                    bucket = nextBucket;
                    System.out.println("[standalone-teavm] progress=" + progress + "/" + total);
                }
                return TeaVMProgressFeedback.CONTINUE;
            }
        });
        builder.setClassPathEntries(classPath);
        builder.setTargetType(TeaVMTargetType.WEBASSEMBLY_GC);
        builder.setMainClass(args[1]);
        builder.setTargetDirectory(Path.of(args[2]).toAbsolutePath().toString());
        builder.setTargetFileName(args[3]);
        builder.setDebugInformationGenerated(false);
        builder.setSourceMapsFileGenerated(false);
        builder.setFastDependencyAnalysis(fastDependencyAnalysis);
        builder.setOptimizationLevel(TeaVMOptimizationLevel.valueOf(args[4]));
        builder.setObfuscated(Boolean.parseBoolean(args[5]));
        builder.setStrict(true);
        builder.setClassesToPreserve(new String[0]);
        builder.setMinDirectBuffersSize(2 * 1024 * 1024);
        builder.setMaxDirectBuffersSize(32 * 1024 * 1024);
        builder.setImportedWasmMemory(false);
        builder.setProperties(new Properties());
        builder.setIncremental(false);

        System.out.println("[standalone-teavm] requestedOptimization=" + args[4]
                + " effectiveOptimization=" + (fastDependencyAnalysis ? "SIMPLE" : args[4])
                + " dependencyAnalysis=" + (fastDependencyAnalysis ? "fast" : "precise")
                + " strict=true obfuscated=" + args[5]);

        var result = builder.build();
        TeaVMProblemRenderer.describeProblems(result.getCallGraph(), result.getProblems(), log);
        if (!result.getProblems().getSevereProblems().isEmpty()) {
            throw new IllegalStateException("TeaVM reported severe problems");
        }
    }
}
