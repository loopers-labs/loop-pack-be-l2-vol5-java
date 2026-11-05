package com.loopers.support.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring Boot 실행 jar를 테스트 JVM 밖의 별도 프로세스로 띄운다(docs/week7/decisions.md D-10). 운영처럼 두 서버가 따로 뜬다 —
 * api 테스트 클래스패스에 다른 앱을 넣지 않는다(넣으면 {@code @EntityScan("com.loopers")}로 그 앱의 listener·엔티티가 모든 api
 * 테스트에 끼어든다).
 *
 * - 실행: 지금 테스트를 돌리는 JVM의 java로 {@code java [jvmArgs] -jar <jar> [args]}. 설정은 명령줄 인자({@code --key=value})와
 *   환경 변수로만 준다. jar 안의 코드는 바꾸지 않는다.
 * - 출력: stdout·stderr를 합쳐 build/process-logs/ 아래 파일로 남긴다. 실패 메시지에는 끝부분을 붙인다.
 * - 준비: 출력에 준비 줄(예: "Started CommerceStreamerApplication")이 나올 때까지 상한을 두고 기다린다. 그 전에 프로세스가 끝나거나
 *   상한이 지나면 로그 끝부분과 함께 실패한다.
 * - 정리: {@link #close()}가 자손 프로세스까지 끝내고 기다린다. 테스트가 중간에 죽어도 고아가 남지 않게 JVM 종료 hook도 건다.
 */
public final class BootJarProcess implements AutoCloseable {

    private static final Duration STOP_WAIT = Duration.ofSeconds(20);
    private static final int TAIL_LINES = 80;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final String name;
    private final Process process;
    private final Path logFile;
    private final Thread shutdownHook;

    private BootJarProcess(String name, Process process, Path logFile) {
        this.name = name;
        this.process = process;
        this.logFile = logFile;
        this.shutdownHook = new Thread(() -> destroyTree(process), "boot-jar-stop-" + name);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static Builder builder(String name, Path jar) {
        return new Builder(name, jar);
    }

    public String name() {
        return name;
    }

    public Path logFile() {
        return logFile;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** 로그 파일의 마지막 lines줄. 실패 메시지와 진단 출력에 붙인다. */
    public String tail(int lines) {
        List<String> all = readLines();
        return String.join(System.lineSeparator(), all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    public String tail() {
        return tail(TAIL_LINES);
    }

    /** 자손 프로세스까지 끝내고 기다린다. 여러 번 불러도 된다. */
    @Override
    public void close() {
        destroyTree(process);
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM이 이미 종료 중이다 — hook이 같은 일을 한다.
        }
    }

    private static void destroyTree(Process process) {
        List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
        tree.add(process.toHandle());
        tree.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + STOP_WAIT.toNanos();
        for (ProcessHandle handle : tree) {
            long remaining = deadline - System.nanoTime();
            try {
                handle.onExit().get(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 기한 안에 끝나지 않았다 — 아래에서 강제로 끝낸다.
            }
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        tree.forEach(handle -> {
            try {
                handle.onExit().get(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 강제 종료도 기다리지 못했다. 더 할 수 있는 일이 없다.
            }
        });
    }

    /**
     * 출력에 pattern이 나올 때까지 상한을 두고 기다리고, 처음 맞은 자리를 돌려준다(예: 실제 포트 줄, 미발행 로그 줄).
     * 그 전에 프로세스가 끝나거나 상한이 지나면 로그 끝부분과 함께 실패한다. 프로세스는 끄지 않는다 — 정리는 호출자가 한다.
     */
    public MatchResult awaitLog(Pattern pattern, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Matcher matcher = pattern.matcher(readLog());
            if (matcher.find()) {
                return matcher.toMatchResult();
            }
            if (!process.isAlive()) {
                throw new IllegalStateException("[" + name + "] /" + pattern + "/ 이 나오기 전에 프로세스가 끝났다 (exit="
                    + process.exitValue() + ", log=" + logFile + ")\n" + tail());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("[" + name + "] 로그를 기다리다 중단됐다", e);
            }
        }
        throw new IllegalStateException("[" + name + "] " + timeout + " 안에 /" + pattern + "/ 이 나오지 않았다 (log=" + logFile + ")\n" + tail());
    }

    private void awaitReady(Pattern readyLine, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (readyLine.matcher(readLog()).find()) {
                return;
            }
            if (!process.isAlive()) {
                String message = "[" + name + "] 준비 전에 프로세스가 끝났다 (exit=" + process.exitValue() + ", log=" + logFile + ")\n" + tail();
                close();
                throw new IllegalStateException(message);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                throw new IllegalStateException("[" + name + "] 준비를 기다리다 중단됐다", e);
            }
        }
        String message = "[" + name + "] " + timeout + " 안에 준비 줄 /" + readyLine + "/ 이 나오지 않았다 (log=" + logFile + ")\n" + tail();
        close();
        throw new IllegalStateException(message);
    }

    private String readLog() {
        try {
            // 깨진 바이트는 대체 문자로 바꾼다 — 로그 읽기가 테스트를 실패시키지 않게 한다.
            return new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private List<String> readLines() {
        return Arrays.asList(readLog().split("\\R", -1));
    }

    public static final class Builder {

        private final String name;
        private final Path jar;
        private final List<String> jvmArgs = new ArrayList<>();
        private final Map<String, String> args = new LinkedHashMap<>();
        private final Map<String, String> env = new LinkedHashMap<>();
        private Pattern readyLine;
        private Duration readyTimeout = Duration.ofSeconds(90);

        private Builder(String name, Path jar) {
            this.name = name;
            this.jar = jar;
        }

        public Builder jvmArg(String jvmArg) {
            jvmArgs.add(jvmArg);
            return this;
        }

        /** 명령줄 인자 {@code --key=value}. Spring Boot에서 설정 파일·프로필보다 앞선다. */
        public Builder arg(String key, String value) {
            args.put(key, value);
            return this;
        }

        public Builder env(String key, String value) {
            env.put(key, value);
            return this;
        }

        public Builder readyWhen(Pattern readyLine, Duration timeout) {
            this.readyLine = readyLine;
            this.readyTimeout = timeout;
            return this;
        }

        /** 띄우고 준비 줄이 나올 때까지 기다린다. 실패하면 프로세스를 끝내고 로그 끝부분과 함께 예외를 던진다. */
        public BootJarProcess start() {
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("[" + name + "] 실행 jar가 없다: " + jar);
            }
            if (readyLine == null) {
                throw new IllegalStateException("[" + name + "] 준비 줄을 정하지 않았다");
            }
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(jvmArgs);
            command.add("-jar");
            command.add(jar.toAbsolutePath().toString());
            args.forEach((key, value) -> command.add("--" + key + "=" + value));

            Path logFile = Path.of("build", "process-logs", name + "-" + LocalDateTime.now().format(LOG_TIME) + ".log").toAbsolutePath();
            Process process;
            try {
                Files.createDirectories(logFile.getParent());
                ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
                builder.environment().putAll(env);
                process = builder.start();
            } catch (IOException e) {
                throw new IllegalStateException("[" + name + "] 프로세스를 띄우지 못했다: " + command, e);
            }
            BootJarProcess started = new BootJarProcess(name, process, logFile);
            started.awaitReady(readyLine, readyTimeout);
            return started;
        }
    }
}
