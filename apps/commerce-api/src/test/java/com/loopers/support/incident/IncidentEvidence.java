package com.loopers.support.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 사고 실험 한 번의 증거를 새 위치({@code build/incident-runs/{runId}/})에 남긴다. 이미 쓴 파일은 덮어쓰지 않고, 끝날 때
 * 모든 파일의 sha256 을 기록한다. 실패한 실행도 지우지 않는다.
 */
public final class IncidentEvidence {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String runId;
    private final Path directory;
    private final Map<String, Object> run = new LinkedHashMap<>();

    private IncidentEvidence(String runId, Path directory) {
        this.runId = runId;
        this.directory = directory;
    }

    public static IncidentEvidence create() {
        String runId = RUN_ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 6);
        Path directory = Path.of("build", "incident-runs", runId);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        IncidentEvidence evidence = new IncidentEvidence(runId, directory);
        evidence.run.put("runId", runId);
        evidence.run.put("startedAt", Instant.now());
        return evidence;
    }

    public String runId() {
        return runId;
    }

    public Path directory() {
        return directory;
    }

    /** run.json 에 실행 정보를 더한다. 끝날 때 한 번에 쓴다. */
    public void note(String key, Object value) {
        run.put(key, value);
    }

    public Path writeJson(String fileName, Object content) {
        try {
            return write(fileName, MAPPER.writeValueAsString(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Prometheus·health 응답처럼 받은 원문을 그대로 남긴다. */
    public Path writeText(String fileName, String content) {
        return write(fileName, content);
    }

    public String sha256(String fileName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(fileName)));
            return HexFormat.of().formatHex(digest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** run.json 과 SHA256SUMS 를 쓴다. 실패한 실행에서도 호출한다. */
    public void finish() {
        run.put("finishedAt", Instant.now());
        writeJson("run.json", run);
        StringBuilder sums = new StringBuilder();
        try (Stream<Path> files = Files.list(directory)) {
            files.map(path -> path.getFileName().toString())
                .filter(name -> !name.equals("SHA256SUMS"))
                .sorted()
                .forEach(name -> sums.append(sha256(name)).append("  ").append(name).append('\n'));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write("SHA256SUMS", sums.toString());
    }

    private Path write(String fileName, String content) {
        Path file = directory.resolve(fileName);
        // 같은 이름을 두 번 쓰는 것은 증거 덮어쓰기다. run.json 만 끝에서 한 번 쓴다.
        if (Files.exists(file)) {
            throw new IllegalStateException("증거 파일을 덮어쓰려 했습니다: " + file);
        }
        try {
            return Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
