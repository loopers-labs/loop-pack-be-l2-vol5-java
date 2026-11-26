package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

// 사고 연결 테스트의 단계별 증거를 실행마다 새 폴더(build/incident-evidence/<runId>/)에 JSON 하나씩 남긴다.
// 같은 이름 파일은 만들지 않고(CREATE_NEW) 지우지도 않는다. 실패한 실행도 그때까지 쓴 파일과 failure.json이 남는다.
final class IncidentEvidence {
    private static final Path ROOT = Path.of("build", "incident-evidence");
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);

    // 커밋하는 증거에 사용자명이 든 로컬 경로를 남기지 않는다. 저장소 루트가 홈 아래에 있으므로 긴 경로부터 바꾼다.
    private static final Map<String, String> LOCAL_PATHS = Map.of(
        repositoryRoot(), "$REPO",
        Path.of(System.getProperty("user.home")).toAbsolutePath().normalize().toString(), "~");

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
        String json = maskLocalPaths(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content));
        return Files.write(file(stage), json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
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

    private static String maskLocalPaths(String text) {
        String masked = text;
        List<String> paths = LOCAL_PATHS.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for (String path : paths) {
            masked = masked.replace(path, LOCAL_PATHS.get(path));
        }
        return masked;
    }

    // 테스트는 apps/commerce-api에서 돈다. settings.gradle.kts가 있는 가장 가까운 상위 폴더가 저장소 루트다.
    private static String repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("settings.gradle.kts"))) {
                return candidate.toString();
            }
        }
        return current.toString();
    }

    private Path file(String stage) {
        return directory.resolve(stage + ".json");
    }
}
