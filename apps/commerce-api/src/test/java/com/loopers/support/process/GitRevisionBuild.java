package com.loopers.support.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 과거 revision 을 git worktree 로 꺼내 bootJar 를 빌드함. 자기가 만든 worktree 만 지움 (7주차 설계 6.1).
 * 빌드는 현재 저장소의 wrapper 로 함. 이 PC 에서 동작이 확인된 Gradle 을 쓰기 위함
 */
public final class GitRevisionBuild implements AutoCloseable {

    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(15);

    private final Path repositoryRoot;
    private final Path worktree;

    private GitRevisionBuild(Path repositoryRoot, Path worktree) {
        this.repositoryRoot = repositoryRoot;
        this.worktree = worktree;
    }

    public static GitRevisionBuild checkout(Path repositoryRoot, String revision, Path worktree) {
        GitRevisionBuild build = new GitRevisionBuild(repositoryRoot, worktree);
        // 이전 실행이 중간에 끊겨 남은 같은 경로의 worktree 만 치움
        build.removeWorktree();
        run(repositoryRoot, GIT_TIMEOUT, "git", "worktree", "add", "--detach", worktree.toString(), revision);
        return build;
    }

    public void buildBootJars(String... projectPaths) {
        List<String> command = new ArrayList<>(gradleWrapper());
        command.add("-p");
        command.add(worktree.toString());
        command.add("--console=plain");
        command.add("-x");
        command.add("test");
        for (String projectPath : projectPaths) {
            command.add(projectPath + ":bootJar");
        }
        run(repositoryRoot, BUILD_TIMEOUT, command.toArray(String[]::new));
    }

    /** 예: "apps/commerce-api". 루트 빌드가 plain jar 를 끄므로 libs 의 jar 는 bootJar 하나임 */
    public Path bootJar(String projectDirectory) {
        Path libs = worktree.resolve(projectDirectory).resolve("build").resolve("libs");
        try (Stream<Path> jars = Files.list(libs)) {
            List<Path> found = jars.filter(path -> path.toString().endsWith(".jar") && !path.toString().endsWith("-plain.jar")).toList();
            if (found.size() != 1) {
                throw new IllegalStateException(libs + " 의 bootJar 를 하나로 정하지 못함: " + found);
            }
            return found.get(0);
        } catch (IOException e) {
            throw new IllegalStateException(libs + " 를 읽지 못함", e);
        }
    }

    @Override
    public void close() {
        removeWorktree();
    }

    /** 등록이 지워지고 디렉터리만 남는 경우가 있어(Windows 파일 잠금) 이 경로를 직접 지운 뒤 등록을 정리함 */
    private void removeWorktree() {
        deleteRecursively(worktree);
        run(repositoryRoot, GIT_TIMEOUT, "git", "worktree", "prune");
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException(directory + " 를 지우지 못함", e);
        }
    }

    private List<String> gradleWrapper() {
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            return List.of("cmd", "/c", repositoryRoot.resolve("gradlew.bat").toString());
        }
        return List.of(repositoryRoot.resolve("gradlew").toString());
    }

    private static void run(Path directory, Duration timeout, String... command) {
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            Thread printer = new Thread(() -> print(process), "git-revision-build-output");
            printer.setDaemon(true);
            printer.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(String.join(" ", command) + " 가 " + timeout + " 안에 끝나지 않음");
            }
            printer.join(TimeUnit.SECONDS.toMillis(5));
            if (process.exitValue() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " 실패. exit=" + process.exitValue());
            }
        } catch (IOException e) {
            throw new IllegalStateException(String.join(" ", command) + " 를 실행하지 못함", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 interrupt", e);
        }
    }

    private static void print(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[git-revision-build] " + line);
            }
        } catch (IOException ignored) {
            // 프로세스가 끝나면 stream 이 닫힘
        }
    }
}
