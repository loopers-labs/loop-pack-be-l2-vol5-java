package com.loopers.support.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
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
import java.util.stream.Stream;

// 실행마다 새 폴더에 사고 실험의 증거를 남긴다.
// 파일은 새로 만들기만 하고, 쓴 즉시 sha256을 hashes.sha256에 덧붙인다. manifest는 끝날 때 한 번만 쓴다.
public final class IncidentEvidence {
    public static final String HASHES = "hashes.sha256";
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
    private final String runId;
    private final Path directory;

    private IncidentEvidence(String runId, Path directory) {
        this.runId = runId;
        this.directory = directory;
    }

    public static IncidentEvidence create(Path root, Instant startedAt) {
        String runId = RUN_TIME.format(startedAt) + "-" + UUID.randomUUID().toString().substring(0, 6);
        try {
            Files.createDirectories(root);
            Path directory = Files.createDirectory(root.resolve(runId));
            Files.createDirectory(directory.resolve("raw"));
            Files.createDirectory(directory.resolve("logs"));
            return new IncidentEvidence(runId, directory);
        } catch (IOException exception) {
            throw new IllegalStateException("증거 폴더를 만들지 못했습니다: " + root, exception);
        }
    }

    public String runId() {
        return runId;
    }

    public Path path(String relative) {
        return directory.resolve(relative);
    }

    public String writeJson(String relative, Object value) {
        try {
            return writeText(relative, json.writeValueAsString(value));
        } catch (IOException exception) {
            throw new IllegalStateException("증거를 JSON으로 바꾸지 못했습니다: " + relative, exception);
        }
    }

    public String writeText(String relative, String text) {
        try {
            Files.writeString(directory.resolve(relative), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw new IllegalStateException("증거 파일을 새로 만들지 못했습니다: " + relative, exception);
        }
        return recordHash(relative);
    }

    // 자식 프로세스가 직접 쓴 로그처럼 이미 있는 파일의 hash를 남긴다.
    public String recordHash(String relative) {
        String hash = sha256(relative);
        try {
            Files.writeString(directory.resolve(HASHES), hash + "  " + relative + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("hash를 남기지 못했습니다: " + relative, exception);
        }
        return hash;
    }

    public Map<String, String> recordedHashes() {
        Map<String, String> hashes = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(directory.resolve(HASHES))) {
                String[] parts = line.split(" {2}", 2);
                hashes.put(parts[1], parts[0]);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("hash 기록을 읽지 못했습니다.", exception);
        }
        return hashes;
    }

    public String sha256(String relative) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(directory.resolve(relative))));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("hash를 계산하지 못했습니다: " + relative, exception);
        }
    }

    public void writeManifest(Map<String, Object> manifest) {
        writeJson("manifest.json", manifest);
    }

    public List<Path> filesContaining(String text) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).filter(file -> {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8).contains(text);
                } catch (IOException exception) {
                    throw new IllegalStateException("증거 파일을 읽지 못했습니다: " + file, exception);
                }
            }).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("증거 폴더를 읽지 못했습니다.", exception);
        }
    }
}
