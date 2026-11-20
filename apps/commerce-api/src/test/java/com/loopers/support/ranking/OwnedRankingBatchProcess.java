package com.loopers.support.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Test-only bridge to a separate Batch JVM; API and Batch classes never share one application context. */
public final class OwnedRankingBatchProcess {
    private static final String PROBE = "com.loopers.support.ranking.WeeklyRankingProcessProbe";
    private static final String PROBE_DIRECTORY = "com/loopers/support/ranking";

    private OwnedRankingBatchProcess() {
    }

    public static void initializeSchema(String jdbcUrl, String username, String password) throws Exception {
        Output result = execute("initialize-schema", jdbcUrl, username, password, null, 0, null);
        if (result.exitCode() != 0 || !"INITIALIZED".equals(result.json().path("status").asText())) {
            throw new IllegalStateException("Owned Batch schema initialization failed: " + result.log());
        }
    }

    public static Result run(String jdbcUrl, String username, String password, LocalDate week,
                             int revision, Long failBeforeProduct) throws Exception {
        if (week == null || revision < 1 || (failBeforeProduct != null && failBeforeProduct < 1)) {
            throw new IllegalArgumentException("Owned Batch execution requires a period and positive revision/product");
        }
        Output output = execute("run", jdbcUrl, username, password, week, revision, failBeforeProduct);
        JsonNode row = output.json();
        String status = row.path("status").asText();
        if (!(output.exitCode() == 0 && status.equals("COMPLETED"))
            && !(output.exitCode() == 2 && status.equals("FAILED"))) {
            throw new IllegalStateException("Unexpected owned Batch status/exit code: " + output.log());
        }
        List<String> steps = new ArrayList<>();
        row.path("stepNames").forEach(step -> steps.add(step.textValue()));
        List<Long> products = new ArrayList<>();
        row.path("visitedProducts").forEach(product -> products.add(product.longValue()));
        List<String> failures = new ArrayList<>();
        row.path("failureTypes").forEach(failure -> failures.add(failure.textValue()));
        return new Result(row.path("jobInstanceId").longValue(), row.path("jobExecutionId").longValue(), status,
            row.path("snapshotId").textValue(), List.copyOf(steps), row.path("readerPosition").longValue(),
            List.copyOf(products), row.path("inputChecksum").textValue(), row.path("resultChecksum").textValue(),
            List.copyOf(failures), output.log());
    }

    private static Output execute(String mode, String jdbcUrl, String username, String password,
                                  LocalDate week, int revision, Long failBeforeProduct) throws Exception {
        requireValue(jdbcUrl, "owned JDBC URL");
        requireValue(username, "owned JDBC username");
        requireValue(password, "owned JDBC password");
        String mainClasspath = requireValue(System.getProperty("w9.batch.main-classpath"), "Batch main classpath");
        String testClasses = requireValue(System.getProperty("w9.batch.test-classes"), "Batch probe classes");
        Path temporary = Files.createTempDirectory("w9-owned-ranking-batch-");
        Process child = null;
        boolean interrupted = false;
        try {
            Path isolatedClasses = temporary.resolve("probe-classes");
            copyOnlyProbeClasses(testClasses, isolatedClasses);
            Path log = temporary.resolve("process.log");
            Path result = temporary.resolve("result.json");
            Path argumentFile = temporary.resolve("arguments.txt");
            List<String> arguments = List.of("-Xms64m", "-Xmx256m", "-Xshare:off", "-Duser.timezone=Asia/Seoul",
                "-cp", mainClasspath + File.pathSeparator + isolatedClasses, PROBE, mode, result.toString());
            Files.writeString(argumentFile, arguments.stream().map(OwnedRankingBatchProcess::quoteArgument)
                .reduce("", (all, argument) -> all + argument + "\n"), StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + argumentFile).redirectErrorStream(true).redirectOutput(log.toFile());
            Map<String, String> environment = builder.environment();
            environment.keySet().removeIf(name -> name.startsWith("SPRING_") || name.startsWith("MYSQL_")
                || name.startsWith("REDIS_") || name.startsWith("W9_OWNED_") || name.equals("CLASSPATH")
                || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS") || name.equals("_JAVA_OPTIONS"));
            environment.put("W9_OWNED_JDBC_URL", jdbcUrl);
            environment.put("W9_OWNED_USERNAME", username);
            environment.put("W9_OWNED_PASSWORD", password);
            if (week != null) {
                environment.put("W9_OWNED_WEEK", week.toString());
                environment.put("W9_OWNED_REVISION", Integer.toString(revision));
            }
            if (failBeforeProduct != null) {
                environment.put("W9_OWNED_FAIL_PRODUCT", failBeforeProduct.toString());
            }
            child = builder.start();
            child.getOutputStream().close();
            if (!child.waitFor(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Owned Batch exceeded 60 seconds: " + readTail(log, password));
            }
            String diagnostic = readTail(log, password);
            if (!Files.isRegularFile(result) || Files.size(result) > 1_000_000) {
                throw new IllegalStateException("Owned Batch produced no bounded evidence file; exit=" + child.exitValue()
                    + "; log=" + diagnostic);
            }
            JsonNode json = new ObjectMapper().readTree(result.toFile());
            return new Output(child.exitValue(), json, diagnostic);
        } catch (InterruptedException cancellation) {
            interrupted = true;
            throw cancellation;
        } finally {
            interrupted |= Thread.interrupted();
            try {
                interrupted |= stopOwnedChild(child);
            } finally {
                try (var paths = Files.walk(temporary)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private static void copyOnlyProbeClasses(String roots, Path isolatedClasses) throws IOException {
        Path destination = isolatedClasses.resolve(PROBE_DIRECTORY);
        Files.createDirectories(destination);
        for (String root : roots.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path directory = Path.of(root).resolve(PROBE_DIRECTORY);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var files = Files.newDirectoryStream(directory, "WeeklyRankingProcessProbe*.class")) {
                for (Path file : files) {
                    Files.copy(file, destination.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (!Files.isRegularFile(destination.resolve("WeeklyRankingProcessProbe.class"))) {
            throw new IllegalStateException("Batch probe has not been compiled");
        }
    }

    private static boolean stopOwnedChild(Process child) {
        boolean interrupted = false;
        if (child == null || !child.isAlive()) {
            return false;
        }
        child.destroy();
        for (int phase = 0; phase < 2; phase++) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (child.isAlive() && System.nanoTime() < deadline) {
                try {
                    child.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException cancellation) {
                    interrupted = true;
                }
            }
            if (!child.isAlive()) {
                return interrupted;
            }
            child.destroyForcibly();
        }
        throw new IllegalStateException("Owned Batch child did not terminate");
    }

    private static String quoteArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String readTail(Path log, String password) throws IOException {
        try (var channel = Files.newByteChannel(log, StandardOpenOption.READ)) {
            long start = Math.max(0, channel.size() - 64_000);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) (channel.size() - start));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Read only the bounded diagnostic tail.
            }
            buffer.flip();
            return StandardCharsets.UTF_8.decode(buffer).toString().replace(password, "[REDACTED]");
        }
    }

    private static String requireValue(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + label);
        }
        return value;
    }

    private record Output(int exitCode, JsonNode json, String log) {
    }

    public record Result(long jobInstanceId, long jobExecutionId, String status, String snapshotId,
                         List<String> stepNames, long readerPosition, List<Long> visitedProducts,
                         String inputChecksum, String resultChecksum, List<String> failureTypes, String log) {
    }
}
