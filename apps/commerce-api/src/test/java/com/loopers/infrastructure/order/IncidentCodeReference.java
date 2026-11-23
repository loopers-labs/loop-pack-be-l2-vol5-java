package com.loopers.infrastructure.order;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 실행 코드 기준(checkout-incident §9.1). HEAD 와 tracked diff 만으로는 아직 untracked 인 새 구현을 구분하지 못해
 * 실행에 영향을 주는 untracked 파일의 hash 도 함께 남긴다. git 실행 실패는 준비 실패다.
 */
record IncidentCodeReference(String head, String trackedDiffSha256, long trackedDiffBytes, List<FileHash> untracked) {

    private static final long GIT_TIMEOUT_SECONDS = 30;
    private static final Pattern SOURCE = Pattern.compile("(apps|modules|supports)/[^/]+/src/.+");

    IncidentCodeReference {
        untracked = List.copyOf(untracked);
    }

    record FileHash(String path, String sha256) {
    }

    static IncidentCodeReference capture(Path workingDirectory) {
        Path repo = Path.of(text(git(workingDirectory, "rev-parse", "--show-toplevel")));
        String head = text(git(repo, "rev-parse", "HEAD"));
        byte[] diff = git(repo, "diff", "HEAD", "--binary");
        List<FileHash> untracked = new ArrayList<>();
        String listing = new String(git(repo, "ls-files", "--others", "--exclude-standard", "-z"), StandardCharsets.UTF_8);
        for (String path : listing.split("\0")) {
            if (!path.isEmpty() && affectsExecution(path)) {
                untracked.add(new FileHash(path, sha256(read(repo.resolve(path)))));
            }
        }
        untracked.sort((a, b) -> a.path().compareTo(b.path()));
        return new IncidentCodeReference(head, sha256(diff), diff.length, untracked);
    }

    static boolean affectsExecution(String path) {
        if (path.startsWith("docs/")) {
            return false;
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return SOURCE.matcher(path).matches() || fileName.endsWith(".gradle.kts") || fileName.equals("gradle.properties")
            || path.startsWith("gradle/");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** stdout 은 임시 파일로 받아 git 이 멈춰도 유한 시간 뒤 실패한다. stderr 는 버린다. */
    private static byte[] git(Path directory, String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(Arrays.asList(args));
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("incident-git-", ".out");
            process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(output.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .start();
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("코드 기준 준비 실패: git " + args[0] + " 시간 초과");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("코드 기준 준비 실패: git " + args[0] + " 종료 코드 " + process.exitValue());
            }
            return Files.readAllBytes(output);
        } catch (IOException e) {
            throw new IllegalStateException("코드 기준 준비 실패: git " + args[0] + " 실행", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("코드 기준 준비 중단", e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
            deleteQuietly(output);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 임시 파일 정리 실패는 코드 기준 값에 영향이 없다.
        }
    }

    private static String text(byte[] output) {
        return new String(output, StandardCharsets.UTF_8).trim();
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("코드 기준 준비 실패: 파일 읽기", e);
        }
    }
}
