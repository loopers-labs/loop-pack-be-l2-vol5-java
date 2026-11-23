package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.infrastructure.order.IncidentCheckpoint.Aborted;
import com.loopers.infrastructure.order.IncidentCheckpoint.Facts;
import com.loopers.infrastructure.order.IncidentCheckpoint.FaultFacts;
import com.loopers.infrastructure.order.IncidentCheckpoint.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("W10 진행 checkpoint 검증")
class IncidentCheckpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-23T08:00:00Z");
    private static final String OPERATOR = "operator-a";
    private static final List<Long> U = List.of(11L, 12L, 13L);

    @TempDir
    Path root;

    private final AtomicLong nanos = new AtomicLong();
    private final AtomicInteger sleeps = new AtomicInteger();
    private IncidentEvidenceWriter writer;

    @DisplayName("AUTO 는 사람 명령 없이 accepted 에 AUTO_ADVANCE 만 남긴다.")
    @Test
    void autoAdvances() throws IOException {
        IncidentCheckpoint checkpoint = checkpoint(IncidentMode.AUTO, path -> {
        });

        IncidentCheckpoint.Advance advance = checkpoint.await(IncidentStage.BASELINE, facts(null), deadline());

        Path control = writer.runDir().resolve("control");
        assertAll(
            () -> assertThat(advance.action()).isEqualTo("AUTO_ADVANCE"),
            () -> assertThat(json(control.resolve("accepted/baseline.json")).get("factsSha256").textValue()).hasSize(64),
            () -> assertThat(control.resolve("ready")).doesNotExist(),
            () -> assertThat(control.resolve("commands")).doesNotExist()
        );
    }

    @DisplayName("AUTO FAULT 는 자동 판정이 진행을 허용하지 않으면 중단한다.")
    @Test
    void autoStopsWhenJudgmentFails() {
        IncidentCheckpoint checkpoint = checkpoint(IncidentMode.AUTO, path -> {
        });
        Facts facts = facts(new FaultFacts("fault/diagnosis.json", "sha", U, false));

        assertThatThrownBy(() -> checkpoint.await(IncidentStage.FAULT, facts, deadline()))
            .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isEqualTo(Reason.AUTO_JUDGMENT_FAILED));
    }

    @DisplayName("ready 뒤 올바른 ADVANCE 명령을 소비하고 accepted 에 명령 hash 를 남긴다.")
    @Test
    void consumesAdvance() throws IOException {
        IncidentCheckpoint checkpoint = checkpoint(IncidentMode.OBSERVED,
            control -> publishCommand(control, "baseline", command(control, "BASELINE", "ADVANCE")));

        IncidentCheckpoint.Advance advance = checkpoint.await(IncidentStage.BASELINE, facts(null), deadline());

        JsonNode ready = json(writer.runDir().resolve("control/ready/baseline.json"));
        JsonNode accepted = json(writer.runDir().resolve("control/accepted/baseline.json"));
        assertAll(
            () -> assertThat(advance.action()).isEqualTo("ADVANCE"),
            () -> assertThat(ready.get("expiresAt").textValue()).isEqualTo(NOW.plus(Duration.ofMinutes(5)).toString()),
            () -> assertThat(accepted.get("nonce")).isEqualTo(ready.get("nonce")),
            () -> assertThat(accepted.get("commandSha256").textValue()).hasSize(64)
        );
    }

    @DisplayName("명령이 없으면 5분 뒤 TIMEOUT 으로 실패하고 진행하지 않는다.")
    @Test
    void timesOut() {
        IncidentCheckpoint checkpoint = checkpoint(IncidentMode.OBSERVED, control -> {
        });

        assertThatThrownBy(() -> checkpoint.await(IncidentStage.BASELINE, facts(null), deadline()))
            .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isEqualTo(Reason.TIMEOUT));
        assertAll(
            () -> assertThat(sleeps.get()).isEqualTo(1500),
            () -> assertThat(Files.list(writer.runDir().resolve("failures"))).hasSize(1)
        );
    }

    @DisplayName("전체 남은 시간이 5분보다 짧으면 그 시간이 ready 만료다.")
    @Test
    void usesShorterOverallDeadline() throws IOException {
        IncidentCheckpoint checkpoint = checkpoint(IncidentMode.OBSERVED, control -> {
        });

        assertThatThrownBy(() -> checkpoint.await(IncidentStage.BASELINE, facts(null),
            IncidentDeadline.after(Duration.ofSeconds(1), nanos::get))).isInstanceOf(Aborted.class);

        assertThat(json(writer.runDir().resolve("control/ready/baseline.json")).get("expiresAt").textValue())
            .isEqualTo(NOW.plusSeconds(1).toString());
    }

    @DisplayName("CANCEL 은 accepted 를 남기고 CANCELLED 로 중단한다. STOP 판단 파일이 있으면 hash 도 남긴다.")
    @Test
    void cancels() throws IOException {
        IncidentCheckpoint checkpoint = checkpoint(IncidentMode.OBSERVED, control -> {
            publishReview(control, review(control, "STOP", r -> r));
            publishCommand(control, "fault", command(control, "FAULT", "CANCEL"));
        });
        writeDiagnosis();

        assertThatThrownBy(() -> checkpoint.await(IncidentStage.FAULT, faultFacts(true), deadline()))
            .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isEqualTo(Reason.CANCELLED));
        JsonNode accepted = json(writer.runDir().resolve("control/accepted/fault.json"));
        assertAll(
            () -> assertThat(accepted.get("action").textValue()).isEqualTo("CANCEL"),
            () -> assertThat(accepted.get("reviewSha256").textValue()).hasSize(64)
        );
    }

    @DisplayName("잘못된 명령은 진행 신호가 아니라 INVALID_COMMAND 로 중단한다.")
    @Test
    void rejectsInvalidCommands() {
        Map<String, UnaryOperator<Map<String, Object>>> cases = new LinkedHashMap<>();
        cases.put("nonce", c -> put(c, "nonce", "other"));
        cases.put("runId", c -> put(c, "runId", "other-run"));
        cases.put("operator", c -> put(c, "operator", "someone-else"));
        cases.put("stage", c -> put(c, "stage", "RECOVERY"));
        cases.put("action", c -> put(c, "action", "SKIP"));
        cases.put("createdAt", c -> put(c, "createdAt", NOW.minusSeconds(1).toString()));
        cases.put("extra field", c -> put(c, "note", "x"));
        cases.put("too large", c -> put(c, "operator", "x".repeat(IncidentCheckpoint.MAX_FILE_BYTES)));

        cases.forEach((name, broken) -> {
            root = freshRoot(name);
            IncidentCheckpoint checkpoint = checkpoint(IncidentMode.OBSERVED,
                control -> publishCommand(control, "baseline", broken.apply(command(control, "BASELINE", "ADVANCE"))));
            assertThatThrownBy(() -> checkpoint.await(IncidentStage.BASELINE, facts(null), deadline())).as(name)
                .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isIn(Reason.INVALID_COMMAND));
        });
    }

    @DisplayName("손상된 JSON 과 symlink 명령은 읽지 않고 중단한다.")
    @Test
    void rejectsCorruptAndSymlink() {
        root = freshRoot("corrupt");
        IncidentCheckpoint corrupt = checkpoint(IncidentMode.OBSERVED, control -> writeBytes(
            control.resolve("commands/baseline-" + nonce(control, "baseline") + ".json"), "{\"schemaVersion\":".getBytes()));
        assertThatThrownBy(() -> corrupt.await(IncidentStage.BASELINE, facts(null), deadline()))
            .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_COMMAND));

        root = freshRoot("symlink");
        IncidentCheckpoint symlink = checkpoint(IncidentMode.OBSERVED, control -> {
            Path outside = root.resolve("outside.json");
            writeBytes(outside, toJson(command(control, "BASELINE", "ADVANCE")));
            try {
                Files.createSymbolicLink(control.resolve("commands/baseline-" + nonce(control, "baseline") + ".json"), outside);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        assertThatThrownBy(() -> symlink.await(IncidentStage.BASELINE, facts(null), deadline()))
            .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_COMMAND));
    }

    @DisplayName("기대 파일이 아닌 명령이 commands/ 에 있으면 읽지 않고 UNEXPECTED_COMMAND 로 중단한다.")
    @Test
    void rejectsUnexpectedFiles() {
        IncidentCheckpoint checkpoint = checkpoint(IncidentMode.OBSERVED,
            control -> writeBytes(control.resolve("commands/baseline-guess.json"), "{}".getBytes()));

        assertThatThrownBy(() -> checkpoint.await(IncidentStage.BASELINE, facts(null), deadline()))
            .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNEXPECTED_COMMAND));
    }

    @DisplayName("FAULT ADVANCE 는 봉인 diagnosis·PROCEED 판단이 자동 판정과 일치할 때만 진행한다.")
    @Test
    void faultAdvanceRequiresMatchingReview() throws IOException {
        IncidentCheckpoint checkpoint = checkpoint(IncidentMode.OBSERVED, control -> {
            byte[] review = publishReview(control, review(control, "PROCEED", r -> r));
            publishCommand(control, "fault", faultAdvance(control, review));
        });
        writeDiagnosis();

        IncidentCheckpoint.Advance advance = checkpoint.await(IncidentStage.FAULT, faultFacts(true), deadline());

        JsonNode accepted = json(writer.runDir().resolve("control/accepted/fault.json"));
        assertAll(
            () -> assertThat(advance.action()).isEqualTo("ADVANCE"),
            () -> assertThat(accepted.get("diagnosisSha256").textValue()).isEqualTo(diagnosisSha()),
            () -> assertThat(accepted.get("reviewSha256").textValue()).hasSize(64)
        );
    }

    @DisplayName("판단 불일치·STOP·자동 판정 불허·diagnosis hash 불일치면 FAULT 를 진행하지 않는다.")
    @Test
    void faultAdvanceRejectsMismatch() {
        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put("verdict", new Object[] {"PROCEED", (UnaryOperator<Map<String, Object>>) r -> withVerdict(r, "UNDETERMINED"), true, true});
        cases.put("stop", new Object[] {"STOP", (UnaryOperator<Map<String, Object>>) r -> r, true, true});
        cases.put("auto", new Object[] {"PROCEED", (UnaryOperator<Map<String, Object>>) r -> r, false, true});
        cases.put("hash", new Object[] {"PROCEED", (UnaryOperator<Map<String, Object>>) r -> r, true, false});
        cases.put("orders", new Object[] {"PROCEED", (UnaryOperator<Map<String, Object>>) r -> put(r, "unknownOrderIds", List.of(11, 12)), true, true});

        cases.forEach((name, c) -> {
            root = freshRoot(name);
            @SuppressWarnings("unchecked")
            UnaryOperator<Map<String, Object>> change = (UnaryOperator<Map<String, Object>>) c[1];
            boolean hashMatches = (boolean) c[3];
            IncidentCheckpoint checkpoint = checkpoint(IncidentMode.OBSERVED, control -> {
                byte[] review = publishReview(control, review(control, (String) c[0], change));
                Map<String, Object> command = faultAdvance(control, review);
                if (!hashMatches) {
                    put(command, "diagnosis", Map.of("path", "fault/diagnosis.json", "sha256", "0".repeat(64)));
                }
                publishCommand(control, "fault", command);
            });
            writeDiagnosis();
            assertThatThrownBy(() -> checkpoint.await(IncidentStage.FAULT, faultFacts((boolean) c[2]), deadline())).as(name)
                .isInstanceOf(Aborted.class);
        });
    }

    @DisplayName("소비한 명령이 뒤에 바뀌면 다음 단계에서 CONSUMED_FILE_CHANGED 로 중단한다.")
    @Test
    void detectsConsumedChange() {
        AtomicInteger stage = new AtomicInteger();
        IncidentCheckpoint checkpoint = checkpoint(IncidentMode.OBSERVED, control -> {
            if (stage.get() == 0) {
                publishCommand(control, "baseline", command(control, "BASELINE", "ADVANCE"));
            }
        });
        checkpoint.await(IncidentStage.BASELINE, facts(null), deadline());
        stage.set(1);
        Path consumedCommand = commandFile(writer.runDir().resolve("control"), "baseline");
        writeOver(consumedCommand, "{}");

        assertThatThrownBy(() -> checkpoint.await(IncidentStage.RECOVERY, facts(null), deadline()))
            .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isEqualTo(Reason.CONSUMED_FILE_CHANGED));
    }

    @DisplayName("대기 중 interrupt 는 flag 를 복원하고 INTERRUPTED 로 중단한다.")
    @Test
    void restoresInterrupt() {
        writer = IncidentEvidenceWriter.openRun(root, new IncidentCodeReference("h", "d", 0, List.of()),
            IncidentMode.OBSERVED, OPERATOR, NOW);
        IncidentCheckpoint checkpoint = new IncidentCheckpoint(IncidentMode.OBSERVED, writer, OPERATOR,
            Clock.fixed(NOW, ZoneOffset.UTC), duration -> {
                throw new InterruptedException();
            });

        try {
            assertThatThrownBy(() -> checkpoint.await(IncidentStage.BASELINE, facts(null), deadline()))
                .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isEqualTo(Reason.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private IncidentCheckpoint checkpoint(IncidentMode mode, Consumer<Path> onFirstSleep) {
        writer = IncidentEvidenceWriter.openRun(root, new IncidentCodeReference("h", "d", 0, List.of()), mode, OPERATOR, NOW);
        Path control = writer.runDir().resolve("control");
        AtomicInteger calls = new AtomicInteger();
        sleeps.set(0);
        return new IncidentCheckpoint(mode, writer, OPERATOR, Clock.fixed(NOW, ZoneOffset.UTC), duration -> {
            sleeps.incrementAndGet();
            nanos.addAndGet(duration.toNanos());
            if (calls.getAndIncrement() == 0) {
                onFirstSleep.accept(control);
            }
        });
    }

    private IncidentDeadline deadline() {
        return IncidentDeadline.after(Duration.ofMinutes(30), nanos::get);
    }

    private static Facts facts(FaultFacts fault) {
        return new Facts("baseline", Map.of("orders", 10), fault);
    }

    private Facts faultFacts(boolean autoProceed) {
        return facts(new FaultFacts("fault/diagnosis.json", diagnosisSha(), U, autoProceed));
    }

    private void writeDiagnosis() {
        writer.writeStage(IncidentStage.FAULT, IncidentEvidenceWriter.StageSnapshot.of(
            new IncidentEvidenceWriter.StageSnapshot.Json("diagnosis", Map.of("proceed", true))));
    }

    private String diagnosisSha() {
        Path file = writer.runDir().resolve("fault/diagnosis.json");
        try {
            return Files.exists(file) ? IncidentCodeReference.sha256(Files.readAllBytes(file)) : "none";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> command(Path control, String stage, String action) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("schemaVersion", 1);
        command.put("runId", writer.runId());
        command.put("stage", stage);
        command.put("nonce", nonce(control, stage.toLowerCase()));
        command.put("action", action);
        command.put("operator", OPERATOR);
        command.put("createdAt", NOW.toString());
        return command;
    }

    private Map<String, Object> faultAdvance(Path control, byte[] review) {
        Map<String, Object> command = command(control, "FAULT", "ADVANCE");
        command.put("diagnosis", Map.of("path", "fault/diagnosis.json", "sha256", diagnosisSha()));
        command.put("review", Map.of("path", "control/operator-review.json", "sha256", IncidentCodeReference.sha256(review)));
        return command;
    }

    private Map<String, Object> review(Path control, String decision, UnaryOperator<Map<String, Object>> change) {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("schemaVersion", 1);
        review.put("runId", writer.runId());
        review.put("stage", "FAULT");
        review.put("nonce", nonce(control, "fault"));
        review.put("operator", OPERATOR);
        review.put("createdAt", NOW.toString());
        review.put("diagnosis", Map.of("path", "fault/diagnosis.json", "sha256", diagnosisSha()));
        review.put("unknownOrderIds", U);
        review.put("judgments", U.stream().map(id -> Map.of("orderId", id, "hypotheses", Map.of(
            "PG_STILL_PROCESSING", hypothesis("EXCLUDED"),
            "RESPONSE_LOST", hypothesis("SUPPORTED"),
            "APPLY_TX_FAILED", hypothesis("EXCLUDED")))).toList());
        review.put("decision", decision);
        return change.apply(review);
    }

    private static Map<String, Object> hypothesis(String verdict) {
        return Map.of("verdict", verdict, "evidence", List.of("fault/diagnosis.json"), "reason", "근거 확인");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withVerdict(Map<String, Object> review, String verdict) {
        List<Map<String, Object>> judgments = (List<Map<String, Object>>) review.get("judgments");
        Map<String, Object> first = new LinkedHashMap<>(judgments.get(0));
        first.put("hypotheses", Map.of("PG_STILL_PROCESSING", hypothesis("EXCLUDED"),
            "RESPONSE_LOST", hypothesis(verdict), "APPLY_TX_FAILED", hypothesis("EXCLUDED")));
        review.put("judgments", List.of(first, judgments.get(1), judgments.get(2)));
        return review;
    }

    private static Map<String, Object> put(Map<String, Object> map, String key, Object value) {
        map.put(key, value);
        return map;
    }

    private static String nonce(Path control, String stage) {
        try {
            return MAPPER.readTree(control.resolve("ready/" + stage + ".json").toFile()).get("nonce").textValue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path commandFile(Path control, String stage) {
        return control.resolve("commands/" + stage + "-" + nonce(control, stage) + ".json");
    }

    /** 운영자처럼 control/tmp 에서 완성한 뒤 atomic move 로 공개한다. */
    private static void publishCommand(Path control, String stage, Map<String, Object> command) {
        publish(control, commandFile(control, stage), toJson(command));
    }

    private static byte[] publishReview(Path control, Map<String, Object> review) {
        byte[] bytes = toJson(review);
        publish(control, control.resolve("operator-review.json"), bytes);
        return bytes;
    }

    private static void publish(Path control, Path target, byte[] bytes) {
        try {
            Path tmp = Files.createTempFile(control.resolve("tmp"), "op-", ".json");
            Files.write(tmp, bytes);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] toJson(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeBytes(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeOver(Path file, String content) {
        writeBytes(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private Path freshRoot(String name) {
        try {
            return Files.createDirectories(root.resolve("case-" + name.replace(' ', '-')));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode json(Path file) throws IOException {
        return MAPPER.readTree(file.toFile());
    }
}
