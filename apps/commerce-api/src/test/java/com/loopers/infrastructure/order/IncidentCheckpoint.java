package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTO 단계 진행 기록(checkout-incident §8.2). 업무·증거 assertion 이 끝난 뒤에만 부르며 사람 명령을 합성하지 않는다.
 * FAULT 는 자동 판정이 복구 진행을 허용하지 않으면 {@link Aborted} 로 끝낸다.
 * OBSERVED 의 ready·명령·판단 파일 진행은 2026-11-23 사용자 결정으로 범위에서 빠져 코드도 두지 않는다.
 */
final class IncidentCheckpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IncidentEvidenceWriter writer;
    private final Clock clock;
    private final Path accepted;

    IncidentCheckpoint(IncidentEvidenceWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
        this.accepted = writer.runDir().resolve("control").resolve("accepted");
    }

    static IncidentCheckpoint create(IncidentEvidenceWriter writer) {
        return new IncidentCheckpoint(writer, Clock.systemUTC());
    }

    /** 진행하면 accepted/<stage>.json 에 AUTO_ADVANCE 와 입력 사실 hash 를 남긴다. */
    Advance await(IncidentStage stage, Facts facts) {
        if (facts.fault() != null && !facts.fault().autoProceed()) {
            Aborted aborted = new Aborted(stage, Reason.AUTO_JUDGMENT_FAILED);
            writer.recordFailureKeeping(aborted, stage, Map.of("reason", Reason.AUTO_JUDGMENT_FAILED.name()));
            throw aborted;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", 1);
        record.put("runId", writer.runId());
        record.put("stage", stage.name());
        record.put("action", "AUTO_ADVANCE");
        record.put("factsSha256", IncidentCodeReference.sha256(json(facts)));
        record.put("acceptedAt", clock.instant().toString());
        write(accepted.resolve(stage.directoryName() + ".json"), record);
        return new Advance(stage, "AUTO_ADVANCE");
    }

    private static byte[] json(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
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
        AUTO_JUDGMENT_FAILED
    }

    /** accepted 에 hash 로 남기는 단계 사실. evidencePath 는 이 단계 원문의 runDir 상대경로다. */
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
