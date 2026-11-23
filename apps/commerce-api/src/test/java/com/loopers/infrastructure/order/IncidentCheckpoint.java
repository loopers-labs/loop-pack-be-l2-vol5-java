package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 단계 진행 checkpoint(checkout-incident §8.1.1·§8.2). 업무·증거 assertion 이 끝난 뒤에만 부른다.
 * AUTO 는 사람 명령 없이 accepted 만 남기고, OBSERVED 는 ready 발행 뒤 운영자의 명령 파일을 검증해 소비한다.
 * 시간 경과·취소·잘못된 파일은 진행으로 바꾸지 않고 {@link Aborted} 로 끝낸다.
 */
final class IncidentCheckpoint {

    static final Duration STAGE_LIMIT = Duration.ofMinutes(5);
    static final Duration POLL = Duration.ofMillis(200);
    static final int MAX_FILE_BYTES = 32 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> COMMAND_FIELDS =
        Set.of("schemaVersion", "runId", "stage", "nonce", "action", "operator", "createdAt");
    private static final Set<String> REVIEW_FIELDS = Set.of("schemaVersion", "runId", "stage", "nonce", "operator",
        "createdAt", "diagnosis", "unknownOrderIds", "judgments", "decision");
    private static final String REVIEW = "operator-review.json";

    private final IncidentMode mode;
    private final IncidentEvidenceWriter writer;
    private final String operator;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Path control;
    private final Map<Path, String> consumed = new LinkedHashMap<>();

    IncidentCheckpoint(IncidentMode mode, IncidentEvidenceWriter writer, String operator, Clock clock, Sleeper sleeper) {
        this.mode = mode;
        this.writer = writer;
        this.operator = operator;
        this.clock = clock;
        this.sleeper = sleeper;
        this.control = writer.runDir().resolve("control");
    }

    static IncidentCheckpoint create(IncidentMode mode, IncidentEvidenceWriter writer, String operator) {
        return new IncidentCheckpoint(mode, writer, operator, Clock.systemUTC(), duration -> Thread.sleep(duration));
    }

    /** ADVANCE 면 반환하고 그 밖의 모든 끝은 Aborted 다. */
    Advance await(IncidentStage stage, Facts facts, IncidentDeadline deadline) {
        String factsSha256 = IncidentCodeReference.sha256(json(facts));
        if (mode == IncidentMode.AUTO) {
            if (facts.fault() != null && !facts.fault().autoProceed()) {
                throw abort(stage, Reason.AUTO_JUDGMENT_FAILED, null);
            }
            Map<String, Object> accepted = base(stage);
            accepted.put("action", "AUTO_ADVANCE");
            accepted.put("factsSha256", factsSha256);
            accepted.put("acceptedAt", clock.instant().toString());
            write(control.resolve("accepted").resolve(stage.directoryName() + ".json"), accepted);
            return new Advance(stage, "AUTO_ADVANCE");
        }
        return awaitOperator(stage, facts, factsSha256, deadline);
    }

    /** 소비한 명령·판단 파일이 그 뒤 바뀌지 않았는지 확인한다. 실행 종료 확인에서도 부른다. */
    void verifyConsumedUnchanged(IncidentStage stage) {
        for (Map.Entry<Path, String> entry : consumed.entrySet()) {
            Path file = entry.getKey();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !IncidentCodeReference.sha256(readBytes(file)).equals(entry.getValue())) {
                throw abort(stage, Reason.CONSUMED_FILE_CHANGED, file.getFileName().toString());
            }
        }
    }

    private Advance awaitOperator(IncidentStage stage, Facts facts, String factsSha256, IncidentDeadline deadline) {
        verifyConsumedUnchanged(stage);
        Duration limit = deadline.remaining().compareTo(STAGE_LIMIT) < 0 ? deadline.remaining() : STAGE_LIMIT;
        long endNanos = deadline.nowNanos() + limit.toNanos();
        String nonce = UUID.randomUUID().toString();
        Instant createdAt = clock.instant();
        Ready ready = new Ready(stage, nonce, createdAt, createdAt.plus(limit));
        Map<String, Object> readyFile = base(stage);
        readyFile.put("nonce", nonce);
        readyFile.put("createdAt", createdAt.toString());
        readyFile.put("expiresAt", ready.expiresAt().toString());
        readyFile.put("evidence", facts.evidencePath());
        readyFile.put("factsSha256", factsSha256);
        readyFile.put("nextAllowedActions", List.of("ADVANCE", "CANCEL"));
        Path readyPath = control.resolve("ready").resolve(stage.directoryName() + ".json");
        write(readyPath, readyFile);
        createDirectories(control.resolve("commands"));
        createDirectories(control.resolve("tmp"));
        System.out.println("W10_LAB stage=" + stage + " ready=" + readyPath + " expiresAt=" + ready.expiresAt());

        Path expected = control.resolve("commands").resolve(stage.directoryName() + "-" + nonce + ".json");
        while (true) {
            rejectUnexpectedCommands(stage, expected);
            verifyConsumedUnchanged(stage);
            if (Files.exists(expected, LinkOption.NOFOLLOW_LINKS)) {
                return consume(stage, facts, ready, expected);
            }
            if (deadline.nowNanos() >= endNanos) {
                throw abort(stage, Reason.TIMEOUT, null);
            }
            try {
                sleeper.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw abort(stage, Reason.INTERRUPTED, null);
            }
        }
    }

    private Advance consume(IncidentStage stage, Facts facts, Ready ready, Path commandFile) {
        byte[] commandBytes = readControl(stage, commandFile);
        JsonNode command = parse(stage, commandBytes, commandFile);
        String action = requireCommand(stage, command, ready);
        Map<String, Object> accepted = base(stage);
        accepted.put("nonce", ready.nonce());
        accepted.put("action", action);
        accepted.put("operator", operator);
        accepted.put("commandFile", writer.runDir().relativize(commandFile).toString());
        accepted.put("commandSha256", IncidentCodeReference.sha256(commandBytes));
        Path reviewFile = control.resolve(REVIEW);
        if (action.equals("CANCEL")) {
            if (Files.exists(reviewFile, LinkOption.NOFOLLOW_LINKS)) {
                byte[] review = readControl(stage, reviewFile);
                requireReview(stage, parse(stage, review, reviewFile), ready, facts, "STOP");
                accepted.put("reviewSha256", IncidentCodeReference.sha256(review));
                consumed.put(reviewFile, IncidentCodeReference.sha256(review));
            }
            recordAccepted(stage, accepted, commandFile, commandBytes);
            throw abort(stage, Reason.CANCELLED, null);
        }
        if (stage == IncidentStage.FAULT) {
            FaultFacts fault = facts.fault();
            JsonNode diagnosis = command.get("diagnosis");
            JsonNode review = command.get("review");
            if (fault == null || !fault.autoProceed() || diagnosis == null || review == null
                || !text(diagnosis, "path").equals(fault.diagnosisPath())
                || !text(diagnosis, "sha256").equals(fault.diagnosisSha256())
                || !IncidentCodeReference.sha256(readReferenced(stage, fault.diagnosisPath())).equals(fault.diagnosisSha256())
                || !text(review, "path").equals("control/" + REVIEW)) {
                throw abort(stage, Reason.INVALID_COMMAND, commandFile.getFileName().toString());
            }
            byte[] reviewBytes = readControl(stage, reviewFile);
            if (!IncidentCodeReference.sha256(reviewBytes).equals(text(review, "sha256"))) {
                throw abort(stage, Reason.INVALID_COMMAND, REVIEW);
            }
            requireReview(stage, parse(stage, reviewBytes, reviewFile), ready, facts, "PROCEED");
            accepted.put("diagnosisSha256", fault.diagnosisSha256());
            accepted.put("reviewSha256", text(review, "sha256"));
            consumed.put(reviewFile, text(review, "sha256"));
        }
        recordAccepted(stage, accepted, commandFile, commandBytes);
        return new Advance(stage, action);
    }

    private void recordAccepted(IncidentStage stage, Map<String, Object> accepted, Path commandFile, byte[] commandBytes) {
        accepted.put("acceptedAt", clock.instant().toString());
        write(control.resolve("accepted").resolve(stage.directoryName() + ".json"), accepted);
        consumed.put(commandFile, IncidentCodeReference.sha256(commandBytes));
    }

    /** schema·runId·단계·nonce·operator·시각을 확인하고 action 을 돌려준다. FAULT ADVANCE 만 근거 참조를 더 가진다. */
    private String requireCommand(IncidentStage stage, JsonNode command, Ready ready) {
        String action = text(command, "action");
        Set<String> fields = new LinkedHashSet<>(COMMAND_FIELDS);
        if (stage == IncidentStage.FAULT && action.equals("ADVANCE")) {
            fields.add("diagnosis");
            fields.add("review");
        }
        if (!fieldNames(command).equals(fields) || !(action.equals("ADVANCE") || action.equals("CANCEL"))
            || !common(command, stage, ready)) {
            throw abort(stage, Reason.INVALID_COMMAND, "command");
        }
        return action;
    }

    /** 사람 판단은 자동 판정을 덮어쓰지 않는다. PROCEED 는 모든 U 가 진행 조건과 일치할 때만 허용한다. */
    private void requireReview(IncidentStage stage, JsonNode review, Ready ready, Facts facts, String expectedDecision) {
        FaultFacts fault = facts.fault();
        boolean valid = stage == IncidentStage.FAULT && fault != null && fieldNames(review).equals(REVIEW_FIELDS)
            && common(review, stage, ready)
            && text(review, "decision").equals(expectedDecision)
            && text(review.get("diagnosis"), "path").equals(fault.diagnosisPath())
            && text(review.get("diagnosis"), "sha256").equals(fault.diagnosisSha256())
            && orderIds(review.get("unknownOrderIds")).equals(fault.unknownOrderIds())
            && judgmentsValid(stage, review.get("judgments"), fault, expectedDecision.equals("PROCEED"));
        if (!valid) {
            throw abort(stage, Reason.INVALID_REVIEW, REVIEW);
        }
    }

    private boolean judgmentsValid(IncidentStage stage, JsonNode judgments, FaultFacts fault, boolean proceed) {
        if (judgments == null || !judgments.isArray() || judgments.size() != fault.unknownOrderIds().size()) {
            return false;
        }
        for (int i = 0; i < judgments.size(); i++) {
            JsonNode judgment = judgments.get(i);
            if (!judgment.path("orderId").canConvertToLong()
                || judgment.path("orderId").longValue() != fault.unknownOrderIds().get(i)) {
                return false;
            }
            for (IncidentDiagnosis.Hypothesis hypothesis : IncidentDiagnosis.Hypothesis.values()) {
                JsonNode h = judgment.path("hypotheses").path(hypothesis.name());
                String verdict = text(h, "verdict");
                if (text(h, "reason").isBlank() || !h.path("evidence").isArray() || h.path("evidence").isEmpty()
                    || !Set.of("SUPPORTED", "EXCLUDED", "UNDETERMINED").contains(verdict)) {
                    return false;
                }
                for (JsonNode path : h.path("evidence")) {
                    readReferenced(stage, path.asText());
                }
                String required = hypothesis == IncidentDiagnosis.Hypothesis.RESPONSE_LOST ? "SUPPORTED" : "EXCLUDED";
                if (proceed && !verdict.equals(required)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean common(JsonNode node, IncidentStage stage, Ready ready) {
        if (!node.path("schemaVersion").isInt() || node.path("schemaVersion").intValue() != 1
            || !text(node, "runId").equals(writer.runId()) || !text(node, "stage").equals(stage.name())
            || !text(node, "nonce").equals(ready.nonce()) || !text(node, "operator").equals(operator)) {
            return false;
        }
        try {
            Instant createdAt = Instant.parse(text(node, "createdAt"));
            return !createdAt.isBefore(ready.createdAt()) && !createdAt.isAfter(ready.expiresAt());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** commands/ 에는 현재 기대 파일과 이미 소비한 파일만 있어야 한다. 그 밖의 파일은 읽지 않고 이름만 남긴다. */
    private void rejectUnexpectedCommands(IncidentStage stage, Path expected) {
        try (Stream<Path> files = Files.list(control.resolve("commands"))) {
            for (Iterator<Path> it = files.iterator(); it.hasNext();) {
                Path file = it.next();
                if (!file.equals(expected) && !consumed.containsKey(file)) {
                    throw abort(stage, Reason.UNEXPECTED_COMMAND, file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw abort(stage, Reason.INVALID_COMMAND, "commands");
        }
    }

    /** runDir 밖·symlink·32KiB 초과는 읽지 않는다. */
    private byte[] readControl(IncidentStage stage, Path file) {
        try {
            Path real = file.toRealPath();
            if (Files.isSymbolicLink(file) || !real.startsWith(writer.runDir().toRealPath())
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) {
                throw abort(stage, Reason.INVALID_COMMAND, file.getFileName().toString());
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_FILE_BYTES) {
                throw abort(stage, Reason.INVALID_COMMAND, file.getFileName().toString());
            }
            return bytes;
        } catch (IOException e) {
            throw abort(stage, Reason.INVALID_COMMAND, file.getFileName().toString());
        }
    }

    private byte[] readReferenced(IncidentStage stage, String relative) {
        Path file = writer.runDir().resolve(relative).normalize();
        if (relative.isBlank() || !file.startsWith(writer.runDir())) {
            throw abort(stage, Reason.INVALID_REVIEW, "reference");
        }
        return readControl(stage, file);
    }

    private JsonNode parse(IncidentStage stage, byte[] bytes, Path file) {
        try {
            JsonNode node = MAPPER.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw abort(stage, Reason.INVALID_COMMAND, file.getFileName().toString());
            }
            return node;
        } catch (IOException e) {
            throw abort(stage, Reason.INVALID_COMMAND, file.getFileName().toString());
        }
    }

    /** 실패를 증거에 남기고 Aborted 를 만든다. 기록 실패는 suppressed 로 붙인다. */
    private Aborted abort(IncidentStage stage, Reason reason, String fileName) {
        Aborted aborted = new Aborted(stage, reason);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("reason", reason.name());
        context.put("file", fileName);
        writer.recordFailureKeeping(aborted, stage, context);
        return aborted;
    }

    private Map<String, Object> base(IncidentStage stage) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", 1);
        fields.put("runId", writer.runId());
        fields.put("stage", stage.name());
        return fields;
    }

    private static List<Long> orderIds(JsonNode node) {
        List<Long> ids = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(id -> ids.add(id.canConvertToLong() ? id.longValue() : Long.MIN_VALUE));
        }
        return ids;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? "" : value.textValue();
    }

    private static byte[] json(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 OutputStream out = Channels.newOutputStream(channel)) {
                out.write(json(value));
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("checkpoint 파일 쓰기 실패: " + file.getFileName(), e);
        }
    }

    enum Reason {
        CANCELLED,
        TIMEOUT,
        INTERRUPTED,
        INVALID_COMMAND,
        INVALID_REVIEW,
        UNEXPECTED_COMMAND,
        CONSUMED_FILE_CHANGED,
        AUTO_JUDGMENT_FAILED
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** ready 에 쓴 사실. evidencePath 는 이 단계 원문의 runDir 상대경로다. */
    record Facts(String evidencePath, Map<String, Object> summary, FaultFacts fault) {
    }

    /** FAULT 진행 근거. 봉인한 diagnosis 의 경로·hash 와 현재 U 목록, 자동 판정의 진행 가능 여부. */
    record FaultFacts(String diagnosisPath, String diagnosisSha256, List<Long> unknownOrderIds, boolean autoProceed) {

        FaultFacts {
            unknownOrderIds = List.copyOf(unknownOrderIds);
        }
    }

    record Advance(IncidentStage stage, String action) {
    }

    private record Ready(IncidentStage stage, String nonce, Instant createdAt, Instant expiresAt) {
    }

    /** 진행하지 않고 끝난 checkpoint. 정상 반환·skip 으로 바꾸지 않는다. */
    static final class Aborted extends RuntimeException {

        private final IncidentStage stage;
        private final Reason reason;

        Aborted(IncidentStage stage, Reason reason) {
            super("checkpoint 중단: " + stage + " " + reason);
            this.stage = stage;
            this.reason = reason;
        }

        IncidentStage stage() {
            return stage;
        }

        Reason reason() {
            return reason;
        }
    }
}
