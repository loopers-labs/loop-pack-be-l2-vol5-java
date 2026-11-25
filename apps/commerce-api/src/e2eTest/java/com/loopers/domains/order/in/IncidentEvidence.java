package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

// 사고 연결 테스트의 단계별 증거를 실행마다 새 폴더(build/incident-evidence/<runId>/)에 JSON 하나씩 남긴다.
// 같은 이름 파일은 만들지 않고(CREATE_NEW) 지우지도 않는다. 실패한 실행도 그때까지 쓴 파일이 남는다.
final class IncidentEvidence {
    private static final Path ROOT = Path.of("build", "incident-evidence");
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String runId;
    private final Path directory;

    private IncidentEvidence(String runId, Path directory) {
        this.runId = runId;
        this.directory = directory;
    }

    static IncidentEvidence open() throws IOException {
        String runId = RUN_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Files.createDirectories(ROOT);
        return new IncidentEvidence(runId, Files.createDirectory(ROOT.resolve(runId)));
    }

    String runId() {
        return runId;
    }

    Path directory() {
        return directory;
    }

    Path write(String stage, Map<String, Object> content) throws IOException {
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(content);
        return Files.write(file(stage), json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    JsonNode read(String stage) throws IOException {
        return objectMapper.readTree(file(stage).toFile());
    }

    String text(String stage) throws IOException {
        return Files.readString(file(stage));
    }

    String sha256(String stage) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file(stage))));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }

    List<Path> files() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.sorted().toList();
        }
    }

    private Path file(String stage) {
        return directory.resolve(stage + ".json");
    }
}
