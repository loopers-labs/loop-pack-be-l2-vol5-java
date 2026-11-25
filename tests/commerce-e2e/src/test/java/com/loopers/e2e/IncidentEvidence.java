package com.loopers.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 사고 실험 한 번의 증거 폴더. 실행마다 새 폴더({root}/{runId})를 만들고(이미 있으면 실패), 파일은 CREATE_NEW 로만 써서 덮어쓰지 않는다.
 * 쓴 파일마다 sha256 을 기록하고 manifest 에 남긴다. 다시 계산한 hash 로 파일이 바뀌지 않았는지 확인할 수 있다.
 */
final class IncidentEvidence {
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String runId;
    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> hashes = new LinkedHashMap<>();

    private IncidentEvidence(String runId, Path dir) {
        this.runId = runId;
        this.dir = dir;
    }

    static IncidentEvidence create(Path root) {
        String runId = RUN_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            Files.createDirectories(root);
            return new IncidentEvidence(runId, Files.createDirectory(root.resolve(runId)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String runId() {
        return runId;
    }

    Path dir() {
        return dir;
    }

    /** JSON 으로 쓰고 sha256 을 돌려준다. */
    String writeJson(String name, Object value) {
        try {
            return write(name, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 원문 그대로 쓴다 (예: Prometheus 응답). */
    String writeText(String name, String text) {
        return write(name, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 한 줄에 JSON 하나. */
    String writeJsonLines(String name, List<?> lines) {
        StringBuilder builder = new StringBuilder();
        try {
            for (Object line : lines) {
                builder.append(mapper.writeValueAsString(line)).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return writeText(name, builder.toString());
    }

    JsonNode readJson(String name) {
        try {
            return mapper.readTree(dir.resolve(name).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 쓸 때 기록한 hash. */
    String recordedHash(String name) {
        return hashes.get(name);
    }

    /** 지금 디스크의 파일로 다시 계산한 hash. */
    String currentHash(String name) {
        try {
            return sha256(Files.readAllBytes(dir.resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 지금까지 쓴 파일과 hash, 그리고 extra 를 manifest.json 으로 쓴다. manifest 자신은 목록에 없다. */
    void writeManifest(Map<String, Object> extra) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runId", runId);
        manifest.put("files", new LinkedHashMap<>(hashes));
        manifest.putAll(extra);
        writeJson("manifest.json", manifest);
    }

    private String write(String name, byte[] bytes) {
        try {
            Files.write(dir.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String hash = sha256(bytes);
        hashes.put(name, hash);
        return hash;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
