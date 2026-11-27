package com.loopers.support.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 사고 실험 증거를 실행마다 새 폴더에 씀 (10주차 D-210). 같은 실행 안에서도 이미 쓴 파일은 덮지 않음.
 * 통과 여부는 테스트 assertion 이 정하고, 이 파일들은 사람이 같은 식별자로 다시 읽기 위한 기록임
 */
public final class IncidentEvidence {

    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    public static final String CHECKSUMS = "SHA256SUMS";

    private final Path directory;
    private final String runId;
    private final ObjectMapper mapper;

    private IncidentEvidence(Path directory, String runId, ObjectMapper mapper) {
        this.directory = directory;
        this.runId = runId;
        this.mapper = mapper;
    }

    /** runId 는 실제 시각 + 임의 접미사라 이전 실행을 덮지 않음. 테스트 시계와 무관함 */
    public static IncidentEvidence start(Path root, ObjectMapper mapper) {
        String runId = ZonedDateTime.now(ZoneOffset.UTC).format(RUN_ID_TIME) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = root.resolve(runId);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new IncidentEvidence(directory, runId, mapper.copy().enable(SerializationFeature.INDENT_OUTPUT));
    }

    public String runId() {
        return runId;
    }

    public Path directory() {
        return directory;
    }

    public void writeJson(String fileName, Object content) {
        try {
            write(fileName, mapper.writeValueAsBytes(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeLines(String fileName, List<String> lines) {
        write(fileName, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** 실패한 실행의 단계 기록처럼 마지막 상태로 바꿔야 하는 파일만 다시 씀 */
    public void overwriteJson(String fileName, Object content) {
        try {
            Files.write(directory.resolve(fileName), mapper.writeValueAsBytes(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String sha256(String fileName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(directory.resolve(fileName))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** sha256sum 과 같은 형식. 자기 자신은 빼고 이름순 */
    public void writeChecksums() {
        try (Stream<Path> files = Files.list(directory)) {
            List<String> lines = files.map(path -> path.getFileName().toString())
                .filter(name -> !name.equals(CHECKSUMS))
                .sorted()
                .map(name -> sha256(name) + "  " + name)
                .toList();
            Files.write(directory.resolve(CHECKSUMS), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String fileName, byte[] bytes) {
        Path path = directory.resolve(fileName);
        if (Files.exists(path)) {
            throw new IllegalStateException("이미 기록한 증거를 덮지 않음: " + path);
        }
        try {
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
