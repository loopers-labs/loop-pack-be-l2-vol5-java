package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.infrastructure.order.IncidentEvidenceWriter.GaugeValue;
import com.loopers.infrastructure.order.IncidentEvidenceWriter.StageSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("W10 증거 writer 검증")
class IncidentEvidenceWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final IncidentCodeReference CODE = new IncidentCodeReference("abc123", "d1ff", 0, List.of());
    private static final Instant CLOCK = Instant.parse("2026-11-23T01:00:00Z");

    @TempDir
    Path root;

    @DisplayName("실행마다 새 runDir 에 metadata 를 한 번 쓴다.")
    @Test
    void opensNewRun() throws IOException {
        IncidentEvidenceWriter first = open();
        IncidentEvidenceWriter second = open();

        JsonNode metadata = json(first.runDir().resolve("metadata.json"));
        assertAll(
            () -> assertThat(first.runDir()).isNotEqualTo(second.runDir()),
            () -> assertThat(first.runDir().getParent()).isEqualTo(root.toAbsolutePath()),
            () -> assertThat(metadata.get("runId").textValue()).isEqualTo(first.runId()),
            () -> assertThat(metadata.get("mode").textValue()).isEqualTo("AUTO"),
            () -> assertThat(metadata.get("operator").textValue()).isEqualTo("claude"),
            () -> assertThat(metadata.get("syntheticClock").textValue()).isEqualTo("2026-11-23T01:00:00Z"),
            () -> assertThat(metadata.get("code").get("head").textValue()).isEqualTo("abc123")
        );
    }

    @DisplayName("예상 파일과 그 hash 를 따로 남기고 다시 쓰지 않는다.")
    @Test
    void writesExpectationsOnce() throws IOException {
        IncidentEvidenceWriter writer = open();

        String sha = writer.writeExpectations(Map.of("hypotheses", List.of("PG_PENDING", "RESPONSE_LOST", "APPLY_FAILED")));

        byte[] bytes = Files.readAllBytes(writer.runDir().resolve("diagnosis-expectations.json"));
        assertAll(
            () -> assertThat(sha).isEqualTo(IncidentCodeReference.sha256(bytes)),
            () -> assertThat(json(writer.runDir().resolve("metadata-expectations.json")).get("sha256").textValue())
                .isEqualTo(sha),
            () -> assertThatThrownBy(() -> writer.writeExpectations(Map.of())).isInstanceOf(UncheckedIOException.class),
            () -> assertThat(Files.readAllBytes(writer.runDir().resolve("diagnosis-expectations.json"))).isEqualTo(bytes)
        );
    }

    @DisplayName("단계 원문은 JSON·바이트 그대로 쓰고, 같은 이름·잘못된 이름·비유한 숫자는 거절한다.")
    @Test
    void writesStageEntries() throws IOException {
        IncidentEvidenceWriter writer = open();
        byte[] prometheus = "commerce_payment_unknown{application=\"commerce-api\"} NaN\n".getBytes(StandardCharsets.UTF_8);

        writer.writeStage(IncidentStage.BASELINE, StageSnapshot.of(
            new StageSnapshot.Json("orders", Map.of("count", 10)),
            new StageSnapshot.Json("gauge", Map.of("unknown", GaugeValue.of("NaN"))),
            new StageSnapshot.Raw("prometheus.txt", prometheus)));

        Path baseline = writer.runDir().resolve("baseline");
        JsonNode gauge = json(baseline.resolve("gauge.json")).get("unknown");
        assertAll(
            () -> assertThat(Files.readAllBytes(baseline.resolve("prometheus.txt"))).isEqualTo(prometheus),
            () -> assertThat(json(baseline.resolve("orders.json")).get("count").intValue()).isEqualTo(10),
            () -> assertThat(gauge.get("raw").textValue()).isEqualTo("NaN"),
            () -> assertThat(gauge.get("finite").booleanValue()).isFalse(),
            () -> assertThat(gauge.get("value").isNull()).isTrue(),
            () -> assertThat(GaugeValue.of("3.0")).isEqualTo(new GaugeValue("3.0", true, 3.0)),
            () -> assertThatThrownBy(() -> writer.writeStage(IncidentStage.BASELINE,
                StageSnapshot.of(new StageSnapshot.Json("orders", Map.of())))).isInstanceOf(UncheckedIOException.class),
            () -> assertThatThrownBy(() -> new StageSnapshot.Raw("../escape", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> new StageSnapshot.Json("a/b", Map.of()))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> writer.writeStage(IncidentStage.BASELINE,
                StageSnapshot.of(new StageSnapshot.Json("bad", Map.of("v", Double.NaN)))))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThat(baseline.resolve("bad.json")).doesNotExist()
        );
    }

    @DisplayName("fault 봉인 뒤 변경·추가를 찾아내고 fault 쓰기를 막는다.")
    @Test
    void sealsAndVerifiesFault() throws IOException {
        IncidentEvidenceWriter writer = open();
        writer.writeExpectations(Map.of("rule", "fixed"));
        writer.writeStage(IncidentStage.FAULT, StageSnapshot.of(new StageSnapshot.Json("diagnosis", Map.of("u", 3))));

        writer.sealFault();
        assertThatCode(writer::verifyFaultUnchanged).doesNotThrowAnyException();
        writer.writeStage(IncidentStage.RECOVERY, StageSnapshot.of(new StageSnapshot.Json("orders", Map.of("count", 10))));
        assertThatCode(writer::verifyFaultUnchanged).as("recovery 기록은 봉인과 무관").doesNotThrowAnyException();

        Path diagnosis = writer.runDir().resolve("fault").resolve("diagnosis.json");
        byte[] original = Files.readAllBytes(diagnosis);
        Files.write(diagnosis, "{\"u\":0}".getBytes(StandardCharsets.UTF_8));
        Throwable changed = catchFailure(writer);
        Files.write(diagnosis, original);
        Files.writeString(writer.runDir().resolve("fault").resolve("extra.json"), "{}");
        Throwable added = catchFailure(writer);

        assertAll(
            () -> assertThat(changed).isInstanceOf(IllegalStateException.class),
            () -> assertThat(added).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> writer.writeStage(IncidentStage.FAULT,
                StageSnapshot.of(new StageSnapshot.Json("late", Map.of())))).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(writer::sealFault).isInstanceOf(IllegalStateException.class)
        );
    }

    @DisplayName("예상 파일이나 fault 원문 없이는 봉인하지 않는다.")
    @Test
    void refusesEmptySeal() {
        IncidentEvidenceWriter noExpectations = open();
        noExpectations.writeStage(IncidentStage.FAULT, StageSnapshot.of(new StageSnapshot.Json("diagnosis", Map.of())));
        IncidentEvidenceWriter noFault = open();
        noFault.writeExpectations(Map.of());

        assertAll(
            () -> assertThatThrownBy(noExpectations::sealFault).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(noFault::sealFault).isInstanceOf(IllegalStateException.class),
            () -> assertThat(noFault.runDir().resolve("fault-seal.json")).doesNotExist()
        );
    }

    @DisplayName("실패·자원 기록은 순번 파일로 덧붙이고, 실패 기록이 실패하면 원래 오류에 suppressed 로 남긴다.")
    @Test
    void appendsFailuresAndResources() throws IOException {
        IncidentEvidenceWriter writer = open();
        writer.recordResource("pg", Map.of("port", 18080));
        writer.recordResource("topic", Map.of("name", "order-events-run"));
        writer.recordFailure(IncidentStage.FAULT, "java.lang.AssertionError", Map.of("orderId", 1));
        writer.recordFailure(null, "java.lang.IllegalStateException", Map.of());

        Files.delete(writer.runDir().resolve("failures").resolve("001-fault.json"));
        Files.delete(writer.runDir().resolve("failures").resolve("002-setup.json"));
        Files.delete(writer.runDir().resolve("failures"));
        Files.writeString(writer.runDir().resolve("failures"), "not a directory");
        IllegalStateException original = new IllegalStateException("원래 오류");
        writer.recordFailureKeeping(original, IncidentStage.RECOVERY, Map.of());

        assertAll(
            () -> assertThat(names(writer.runDir().resolve("resources"))).containsExactly("001-pg.json", "002-topic.json"),
            () -> assertThat(original.getSuppressed()).singleElement().isInstanceOf(UncheckedIOException.class)
        );
    }

    @DisplayName("코드 기준은 HEAD·tracked diff hash·실행 영향 untracked 파일만 담고 git 이 없으면 실패한다.")
    @Test
    void capturesCodeReference(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("tracked.txt"), "one\n");
        git(repo, "add", "tracked.txt");
        git(repo, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-q", "-m", "init");
        Files.writeString(repo.resolve("tracked.txt"), "two\n");
        write(repo.resolve("apps/commerce-api/src/test/java/New.java"), "class New {}");
        write(repo.resolve("apps/commerce-api/build.gradle.kts"), "// build");
        write(repo.resolve("docs/week10/note.md"), "local");
        write(repo.resolve("scratch.txt"), "ignored by rule");

        IncidentCodeReference code = IncidentCodeReference.capture(repo);

        assertAll(
            () -> assertThat(code.head()).hasSize(40),
            () -> assertThat(code.trackedDiffSha256()).isEqualTo(IncidentCodeReference.sha256(gitOutput(repo, "diff", "HEAD", "--binary"))),
            () -> assertThat(code.trackedDiffBytes()).isPositive(),
            () -> assertThat(code.untracked()).extracting(IncidentCodeReference.FileHash::path)
                .containsExactly("apps/commerce-api/build.gradle.kts", "apps/commerce-api/src/test/java/New.java"),
            () -> assertThatThrownBy(() -> IncidentCodeReference.capture(root)).isInstanceOf(IllegalStateException.class),
            () -> assertThat(IncidentCodeReference.capture(Path.of("")).head()).as("상대 경로 작업 디렉터리").hasSize(40)
        );
    }

    private IncidentEvidenceWriter open() {
        return IncidentEvidenceWriter.openRun(root, CODE, IncidentMode.AUTO, "claude", CLOCK);
    }

    private static Throwable catchFailure(IncidentEvidenceWriter writer) {
        try {
            writer.verifyFaultUnchanged();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static JsonNode json(Path file) throws IOException {
        return MAPPER.readTree(file.toFile());
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void git(Path repo, String... args) throws Exception {
        gitOutput(repo, args);
    }

    private static byte[] gitOutput(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Path out = Files.createTempFile("git-test-", ".out");
        try {
            Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            return Files.readAllBytes(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
