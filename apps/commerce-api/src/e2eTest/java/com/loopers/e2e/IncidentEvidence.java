package com.loopers.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * W10 사고 실험의 증거를 실행마다 새 위치에 남긴다 (과제 7절).
 *
 * <ul>
 *   <li>위치: {@code build/incident-evidence/{runId}/}. runId는 시작 시각(UTC)과 무작위 접미어라 이전 실행을 덮어쓰지 않는다.</li>
 *   <li>같은 이름의 파일은 다시 쓰지 않는다 ({@link StandardOpenOption#CREATE_NEW}). 단계 기록을 고쳐 성공처럼 만드는 일을 막는다.</li>
 *   <li>파일마다 sha256을 {@code hashes.txt}에 남긴다. hash는 파일이 그 뒤 바뀌지 않았는지만 확인할 뿐, 내용이 참이라는 증거는 아니다.
 *       참·거짓은 테스트 assertion이 같은 식별자·상태·효과 수로 확인한다.</li>
 *   <li>중간에 실패해도 {@link #recordFailure}로 실패 단계와 예외를 남긴다.</li>
 * </ul>
 */
final class IncidentEvidence {

    private static final Path ROOT = Path.of("build", "incident-evidence");
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final String runId;
    private final Path dir;
    private final Map<String, String> hashes = new LinkedHashMap<>();
    private String stage = "init";

    private IncidentEvidence(String runId, Path dir) {
        this.runId = runId;
        this.dir = dir;
    }

    static IncidentEvidence newRun() {
        String runId = RUN_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path dir = ROOT.resolve(runId);
        try {
            Files.createDirectories(ROOT);
            Files.createDirectory(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("증거 위치를 만들지 못했다: " + dir, e);
        }
        return new IncidentEvidence(runId, dir);
    }

    String runId() {
        return runId;
    }

    Path dir() {
        return dir;
    }

    /** 지금 진행 중인 단계. 실패 기록에 남긴다. */
    void stage(String stage) {
        this.stage = stage;
    }

    /** 객체를 JSON으로 쓰고 hash를 기록한다. 같은 이름의 파일이 있으면 실패한다. */
    String writeJson(String fileName, Object content) {
        try {
            return write(fileName, JSON.writeValueAsBytes(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 받은 원문(health JSON, Prometheus 텍스트, 로그 줄)을 그대로 쓴다. */
    String writeText(String fileName, String content) {
        return write(fileName, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 기록한 파일을 지금 다시 읽어 계산한 sha256. 저장 시 hash와 비교해 그 뒤 바뀌지 않았는지 본다. */
    String currentHash(String fileName) {
        try {
            return sha256(Files.readAllBytes(dir.resolve(fileName)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String recordedHash(String fileName) {
        return hashes.get(fileName);
    }

    /** 실패한 단계·예외를 남긴다. 기록 자체가 실패해도 원래 실패를 가리지 않는다. */
    void recordFailure(Throwable failure) {
        try {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            Path failures = Files.createDirectories(dir.resolve("failures"));
            Files.writeString(failures.resolve(stage + "-" + System.nanoTime() + ".txt"),
                "stage=" + stage + "\nat=" + Instant.now() + "\n\n" + trace, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    private String write(String fileName, byte[] bytes) {
        try {
            Files.write(dir.resolve(fileName), bytes, StandardOpenOption.CREATE_NEW);
            String hash = sha256(bytes);
            hashes.put(fileName, hash);
            Files.writeString(dir.resolve("hashes.txt"), hash + "  " + fileName + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return hash;
        } catch (IOException e) {
            throw new UncheckedIOException("증거를 쓰지 못했다: " + fileName, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
