package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * W10 실행별 write-once 증거(checkout-incident §9.1). 모든 파일은 CREATE_NEW 로 한 번만 쓰고 덮어쓰지 않는다.
 * fault 봉인 뒤에는 fault 디렉터리에 쓰지 않으며, 봉인 목록과 바이트 hash 로 변경 여부를 확인한다.
 */
final class IncidentEvidenceWriter {

    static final Path DEFAULT_ROOT = Path.of("build", "checkout-incident-runs");

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");
    private static final DateTimeFormatter RUN_ID_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String SEAL = "fault-seal.json";
    private static final String EXPECTATIONS = "diagnosis-expectations.json";

    private final Path runDir;
    private final String runId;
    private int resourceSequence;
    private int failureSequence;
    private boolean faultSealed;

    private IncidentEvidenceWriter(Path runDir, String runId) {
        this.runDir = runDir;
        this.runId = runId;
    }

    /** root 아래 새 runDir 를 만들고 metadata.json 을 쓴다. 같은 이름 디렉터리가 있으면 실패한다. */
    static IncidentEvidenceWriter openRun(Path root, IncidentCodeReference code, IncidentMode mode, String operator,
                                          Instant syntheticClock) {
        Instant startedAt = Instant.now();
        String runId = RUN_ID_TIME.format(startedAt) + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            Files.createDirectories(root);
            Path runDir = Files.createDirectory(root.resolve(runId));
            IncidentEvidenceWriter writer = new IncidentEvidenceWriter(runDir.toAbsolutePath(), runId);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("schemaVersion", 1);
            metadata.put("runId", runId);
            metadata.put("mode", mode.name());
            metadata.put("operator", operator);
            metadata.put("startedAt", startedAt.toString());
            metadata.put("syntheticClock", syntheticClock.toString());
            metadata.put("code", code);
            writer.writeJson(runDir.resolve("metadata.json"), metadata);
            return writer;
        } catch (IOException e) {
            throw new UncheckedIOException("증거 실행 디렉터리 준비 실패", e);
        }
    }

    Path runDir() {
        return runDir;
    }

    String runId() {
        return runId;
    }

    /** baseline 전 고정하는 가설·예상 규칙. 그 바이트 hash 를 metadata-expectations.json 에 따로 남긴다. */
    synchronized String writeExpectations(Object expectations) {
        byte[] bytes = toJson(expectations);
        write(runDir.resolve(EXPECTATIONS), bytes);
        String sha256 = IncidentCodeReference.sha256(bytes);
        writeJson(runDir.resolve("metadata-expectations.json"), Map.of("file", EXPECTATIONS, "sha256", sha256));
        return sha256;
    }

    /** 준비 중에 알게 된 소유 자원(실제 port·topic/group·자식·collector)을 순번 파일로 덧붙인다. */
    synchronized void recordResource(String kind, Map<String, ?> fields) {
        requireName(kind);
        resourceSequence++;
        Path file = runDir.resolve("resources").resolve("%03d-%s.json".formatted(resourceSequence, kind));
        writeJson(file, fields);
    }

    /** 단계 원문을 이름별 파일로 쓴다. 이미 있는 파일·봉인된 fault 에는 쓰지 않는다. */
    synchronized void writeStage(IncidentStage stage, StageSnapshot snapshot) {
        if (stage == IncidentStage.FAULT && faultSealed) {
            throw new IllegalStateException("봉인된 fault 에는 쓸 수 없음");
        }
        Path dir = runDir.resolve(stage.directoryName());
        for (StageSnapshot.Entry entry : snapshot.entries()) {
            switch (entry) {
                case StageSnapshot.Json json -> writeJson(dir.resolve(json.name() + ".json"), json.value());
                case StageSnapshot.Raw raw -> write(dir.resolve(raw.fileName()), raw.bytes());
            }
        }
    }

    /** fault 필수 원문과 예상 파일의 hash 를 봉인한다. 한 번만 가능하다. */
    synchronized void sealFault() {
        if (faultSealed) {
            throw new IllegalStateException("fault 는 이미 봉인됨");
        }
        Path expectations = runDir.resolve(EXPECTATIONS);
        List<Map<String, String>> files = hashes(runDir.resolve(IncidentStage.FAULT.directoryName()));
        if (!Files.isRegularFile(expectations) || files.isEmpty()) {
            throw new IllegalStateException("봉인할 fault 원문 또는 예상 파일 없음");
        }
        Map<String, Object> seal = new LinkedHashMap<>();
        seal.put("sealedAt", Instant.now().toString());
        seal.put("expectationsSha256", IncidentCodeReference.sha256(readBytes(expectations)));
        seal.put("files", files);
        writeJson(runDir.resolve(SEAL), seal);
        faultSealed = true;
    }

    /** 봉인 목록과 현재 fault 파일·예상 파일 바이트를 비교한다. 추가·삭제·변경이 있으면 실패한다. */
    synchronized void verifyFaultUnchanged() {
        JsonNode seal;
        try {
            seal = MAPPER.readTree(readBytes(runDir.resolve(SEAL)));
        } catch (IOException e) {
            throw new UncheckedIOException("fault 봉인 읽기 실패", e);
        }
        List<Map<String, String>> sealed = new ArrayList<>();
        seal.get("files").forEach(file -> sealed.add(Map.of("path", file.get("path").textValue(),
            "sha256", file.get("sha256").textValue())));
        List<Map<String, String>> current = hashes(runDir.resolve(IncidentStage.FAULT.directoryName()));
        String expectations = IncidentCodeReference.sha256(readBytes(runDir.resolve(EXPECTATIONS)));
        if (!sealed.equals(current) || !expectations.equals(seal.get("expectationsSha256").textValue())) {
            throw new IllegalStateException("봉인된 fault 원문이 바뀜");
        }
    }

    /** 실패를 새 순번 파일로 남긴다. 기존 실패 기록은 바꾸지 않는다. */
    synchronized void recordFailure(IncidentStage stage, String failureClass, Map<String, ?> allowedContext) {
        failureSequence++;
        String stageName = stage == null ? "setup" : stage.directoryName();
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("recordedAt", Instant.now().toString());
        failure.put("stage", stageName);
        failure.put("failureClass", failureClass);
        failure.put("context", allowedContext);
        writeJson(runDir.resolve("failures").resolve("%03d-%s.json".formatted(failureSequence, stageName)), failure);
    }

    /** 실패 기록이 실패해도 원래 오류를 가리지 않고 suppressed 로 붙인다. */
    void recordFailureKeeping(Throwable original, IncidentStage stage, Map<String, ?> allowedContext) {
        try {
            recordFailure(stage, original.getClass().getName(), allowedContext);
        } catch (RuntimeException e) {
            original.addSuppressed(e);
        }
    }

    private List<Map<String, String>> hashes(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                .sorted()
                .map(file -> Map.of("path", runDir.relativize(file).toString(),
                    "sha256", IncidentCodeReference.sha256(readBytes(file))))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("fault 원문 목록 읽기 실패", e);
        }
    }

    private void writeJson(Path file, Object value) {
        write(file, toJson(value));
    }

    /** 비유한 숫자는 JSON 숫자로 쓰지 않는다. gauge 는 {@link GaugeValue} 로 원문 문자열을 보존한다. */
    private static byte[] toJson(Object value) {
        JsonNode tree = MAPPER.valueToTree(value);
        requireFinite(tree);
        try {
            return MAPPER.writeValueAsBytes(tree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void requireFinite(JsonNode node) {
        if (node.isNumber() && (node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("비유한 숫자는 GaugeValue 로 기록해야 함");
        }
        node.forEach(IncidentEvidenceWriter::requireFinite);
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 OutputStream out = Channels.newOutputStream(channel)) {
                out.write(bytes);
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("증거 파일 쓰기 실패: " + file.getFileName(), e);
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("증거 파일 읽기 실패: " + file.getFileName(), e);
        }
    }

    static String requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("증거 이름 형식 오류");
        }
        return name;
    }

    /** gauge 원문 값. NaN 등 비유한 값은 0 이나 비표준 숫자로 바꾸지 않고 raw 와 finite=false 로 남긴다. */
    record GaugeValue(String raw, boolean finite, Double value) {

        static GaugeValue of(String raw) {
            double parsed = Double.parseDouble(raw);
            return Double.isFinite(parsed) ? new GaugeValue(raw, true, parsed) : new GaugeValue(raw, false, null);
        }
    }

    /** 한 단계에 쓸 허용 원문 목록. 구조 데이터는 JSON, HTTP 본문 등은 바이트 그대로다. */
    record StageSnapshot(List<Entry> entries) {

        StageSnapshot {
            entries = List.copyOf(entries);
        }

        static StageSnapshot of(Entry... entries) {
            return new StageSnapshot(List.of(entries));
        }

        sealed interface Entry permits Json, Raw {
        }

        record Json(String name, Object value) implements Entry {

            Json {
                requireName(name);
            }
        }

        record Raw(String fileName, byte[] bytes) implements Entry {

            Raw {
                requireName(fileName);
                bytes = bytes.clone();
            }
        }
    }
}
