package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.infrastructure.order.IncidentCheckpoint.Aborted;
import com.loopers.infrastructure.order.IncidentCheckpoint.Facts;
import com.loopers.infrastructure.order.IncidentCheckpoint.FaultFacts;
import com.loopers.infrastructure.order.IncidentCheckpoint.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("W10 AUTO 단계 진행 기록 검증")
class IncidentCheckpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-23T08:00:00Z");

    @TempDir
    Path root;

    private IncidentEvidenceWriter writer;
    private IncidentCheckpoint checkpoint;

    @BeforeEach
    void open() {
        writer = IncidentEvidenceWriter.openRun(root, new IncidentCodeReference("h", "d", 0, List.of()), IncidentMode.AUTO,
            "AUTO", NOW);
        checkpoint = new IncidentCheckpoint(writer, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @DisplayName("사람 명령 없이 accepted 에 AUTO_ADVANCE 와 사실 hash 만 남긴다.")
    @Test
    void recordsAutoAdvance() throws IOException {
        IncidentCheckpoint.Advance advance = checkpoint.await(IncidentStage.BASELINE,
            new Facts("baseline", Map.of("orders", 10), null));

        Path control = writer.runDir().resolve("control");
        JsonNode accepted = MAPPER.readTree(control.resolve("accepted/baseline.json").toFile());
        assertAll(
            () -> assertThat(advance.action()).isEqualTo("AUTO_ADVANCE"),
            () -> assertThat(accepted.get("runId").textValue()).isEqualTo(writer.runId()),
            () -> assertThat(accepted.get("factsSha256").textValue()).hasSize(64),
            () -> assertThat(accepted.get("acceptedAt").textValue()).isEqualTo(NOW.toString()),
            () -> assertThat(names(control)).containsExactly("accepted")
        );
    }

    @DisplayName("FAULT 자동 판정이 진행을 허용하지 않으면 accepted 없이 중단하고 실패를 기록한다.")
    @Test
    void abortsWhenAutoJudgmentFails() {
        Facts facts = new Facts("fault", Map.of(), new FaultFacts("fault/diagnosis.json", "sha", List.of(11L), false));

        assertThatThrownBy(() -> checkpoint.await(IncidentStage.FAULT, facts))
            .isInstanceOfSatisfying(Aborted.class, e -> assertThat(e.reason()).isEqualTo(Reason.AUTO_JUDGMENT_FAILED));
        assertAll(
            () -> assertThat(writer.runDir().resolve("control/accepted/fault.json")).doesNotExist(),
            () -> assertThat(names(writer.runDir().resolve("failures"))).containsExactly("001-fault.json")
        );
    }

    @DisplayName("같은 단계를 두 번 진행하지 않는다(accepted write-once).")
    @Test
    void refusesSecondAdvanceOfSameStage() {
        Facts facts = new Facts("recovery", Map.of(), null);
        checkpoint.await(IncidentStage.RECOVERY, facts);

        assertThatThrownBy(() -> checkpoint.await(IncidentStage.RECOVERY, facts)).isInstanceOf(UncheckedIOException.class);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }
}
