package com.loopers.infrastructure.order;

import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.fixture.pg.FixtureState;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.RequestCounts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("W10 실패 시 상태 보존")
class CheckoutIncidentFailureSnapshotTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    @DisplayName("상태가 정리되기 전에 실제 수집값을 별도 실패 파일로 저장한다.")
    void preservesStateBeforeCleanup() throws Exception {
        IncidentEvidenceWriter writer = writer();
        Map<String, Supplier<?>> sources = new LinkedHashMap<>();
        Map<String, Object> liveState = new LinkedHashMap<>(Map.of("orderId", 11, "status", "UNKNOWN"));
        sources.put("orders", () -> Map.copyOf(liveState));
        AssertionError original = new AssertionError("UNKNOWN 기대 불일치");

        Throwable thrown = catchThrowable(() -> CheckoutIncidentScenario.runThenRelease(() -> {
            throw original;
        }, error -> writer.recordFailureSnapshotKeeping(error, IncidentStage.FAULT, Map.of(), sources), liveState::clear));

        assertThat(thrown).isSameAs(original);
        assertThat(liveState).isEmpty();
        assertThat(read(writer, "001-fault/orders.json").path("value").path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(read(writer, "001-fault.json").path("failureClass").asText()).isEqualTo(AssertionError.class.getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders", "pg-state"})
    @DisplayName("DB 또는 PG 수집 실패에도 독립 수집원과 원래 오류·정리를 유지한다.")
    void continuesAfterCollectionFailure(String unavailableSource) throws Exception {
        IncidentEvidenceWriter writer = writer();
        RuntimeException unavailable = new IllegalStateException("수집 불가");
        String availableSource = unavailableSource.equals("orders") ? "pg-state" : "orders";
        Map<String, Supplier<?>> sources = new LinkedHashMap<>();
        sources.put(unavailableSource, () -> {
            throw unavailable;
        });
        sources.put(availableSource, () -> Map.of("count", 20));
        AssertionError original = new AssertionError("최초 실패");
        boolean[] released = {false};

        Throwable thrown = catchThrowable(() -> CheckoutIncidentScenario.runThenRelease(() -> {
            throw original;
        }, error -> writer.recordFailureSnapshotKeeping(error, IncidentStage.FAULT, Map.of(), sources),
            () -> released[0] = true));

        assertThat(thrown).isSameAs(original);
        assertThat(original.getSuppressed()).containsExactly(unavailable);
        assertThat(released[0]).isTrue();
        JsonNode unavailableRecord = read(writer, "001-fault/" + unavailableSource + ".json");
        assertThat(unavailableRecord.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unavailableRecord.has("value")).isFalse();
        assertThat(read(writer, "001-fault/" + availableSource + ".json").path("value").path("count").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("수집원이 최초 오류와 같은 예외를 던져도 다음 수집을 계속한다.")
    void avoidsSelfSuppression() throws Exception {
        IncidentEvidenceWriter writer = writer();
        RuntimeException original = new IllegalStateException("동일 오류");
        Map<String, Supplier<?>> sources = new LinkedHashMap<>();
        sources.put("orders", () -> {
            throw original;
        });
        sources.put("pg-state", () -> List.of(11));

        writer.recordFailureSnapshotKeeping(original, IncidentStage.FAULT, Map.of(), sources);

        assertThat(original.getSuppressed()).isEmpty();
        assertThat(read(writer, "001-fault/pg-state.json").path("value").get(0).asInt()).isEqualTo(11);
    }

    @Test
    @DisplayName("실패 요약과 한 파일의 저장이 실패해도 나머지 상태 보존과 정리를 수행한다.")
    void continuesAfterWritingFailure() throws Exception {
        IncidentEvidenceWriter writer = writer();
        Path summary = writer.runDir().resolve("failures/001-fault.json");
        Path blocked = writer.runDir().resolve("failures/001-fault/orders.json");
        Files.createDirectories(blocked.getParent());
        Files.writeString(summary, "previous failure");
        Files.writeString(blocked, "previous orders");
        Map<String, Supplier<?>> sources = new LinkedHashMap<>();
        sources.put("orders", () -> List.of(11));
        sources.put("pg-state", () -> Map.of("createReceived", 20));
        AssertionError original = new AssertionError("최초 실패");
        boolean[] released = {false};

        Throwable thrown = catchThrowable(() -> CheckoutIncidentScenario.runThenRelease(() -> {
            throw original;
        }, error -> writer.recordFailureSnapshotKeeping(error, IncidentStage.FAULT, Map.of(), sources),
            () -> released[0] = true));

        assertThat(thrown).isSameAs(original);
        assertThat(original.getSuppressed()).hasSize(2);
        assertThat(released[0]).isTrue();
        assertThat(Files.readString(summary)).isEqualTo("previous failure");
        assertThat(Files.readString(blocked)).isEqualTo("previous orders");
        assertThat(read(writer, "001-fault/pg-state.json").path("status").asText()).isEqualTo("COLLECTED");
    }

    @Test
    @DisplayName("봉인된 fault와 과거 실패 원문을 바꾸지 않고 새 순번으로 추가한다.")
    void preservesSealedFaultAndEarlierFailure() throws Exception {
        IncidentEvidenceWriter writer = writer();
        writer.writeExpectations(Map.of("unknown", 3));
        writer.writeStage(IncidentStage.FAULT, IncidentEvidenceWriter.StageSnapshot.of(
            new IncidentEvidenceWriter.StageSnapshot.Json("summary", Map.of("unknown", 3))));
        writer.sealFault();
        writer.recordFailureSnapshotKeeping(new AssertionError("first"), IncidentStage.FAULT, Map.of(),
            Map.of("orders", () -> List.of(11)));
        byte[] previous = Files.readAllBytes(writer.runDir().resolve("failures/001-fault/orders.json"));

        writer.recordFailureSnapshotKeeping(new AssertionError("second"), IncidentStage.FAULT, Map.of(),
            Map.of("orders", () -> List.of(12)));

        writer.verifyFaultUnchanged();
        assertThat(Files.readAllBytes(writer.runDir().resolve("failures/001-fault/orders.json"))).isEqualTo(previous);
        assertThat(read(writer, "002-fault/orders.json").path("value").get(0).asInt()).isEqualTo(12);
    }

    @Test
    @DisplayName("DB 준비가 끝나지 않아도 PG 상태와 시작된 로그 수집기의 빈 결과를 보존한다.")
    void collectsAvailableScenarioSources() throws Exception {
        IncidentEvidenceWriter writer = writer();
        CheckoutIncidentScenario scenario = new CheckoutIncidentScenario();
        PgFixtureServer pg = mock(PgFixtureServer.class);
        when(pg.state()).thenReturn(new FixtureState(List.of(), new RequestCounts(20, 0, 0, 0), List.of(), 0, List.of(), 0));
        ReflectionTestUtils.setField(scenario, "writer", writer);
        ReflectionTestUtils.setField(scenario, "pg", pg);
        ((ListAppender<?>) ReflectionTestUtils.getField(scenario, "diagnostics")).start();
        AssertionError original = new AssertionError("준비 중 실패");

        scenario.recordFailureSnapshot(original, Map.of());

        assertThat(read(writer, "001-setup/orders.json").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(read(writer, "001-setup/roster.json").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(read(writer, "001-setup/pg-state.json").path("value").path("counts")
            .path("createReceived").asLong()).isEqualTo(20);
        assertThat(read(writer, "001-setup/diagnostic-log.json").path("status").asText()).isEqualTo("COLLECTED");
        assertThat(read(writer, "001-setup/diagnostic-log.json").path("value").isEmpty()).isTrue();
        assertThat(original.getSuppressed()).hasSize(5);
    }

    @Test
    @DisplayName("PG가 준비되지 않아도 빈 정상 상태로 합성하지 않는다.")
    void recordsUnavailablePg() throws Exception {
        IncidentEvidenceWriter writer = writer();
        CheckoutIncidentScenario scenario = new CheckoutIncidentScenario();
        ReflectionTestUtils.setField(scenario, "writer", writer);

        scenario.recordFailureSnapshot(new AssertionError("준비 실패"), Map.of());

        JsonNode pg = read(writer, "001-setup/pg-state.json");
        assertThat(pg.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(pg.has("value")).isFalse();
        JsonNode logs = read(writer, "001-setup/diagnostic-log.json");
        assertThat(logs.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(logs.has("value")).isFalse();
    }

    @Test
    @DisplayName("실패 수집의 DB 대기 한도를 줄여도 정상 조회 연결 설정은 바꾸지 않는다.")
    void boundsFailureDatabaseReads() {
        String url = "jdbc:mysql://localhost/test?connectTimeout=90000&socketTimeout=80000";
        JdbcTemplate normal = new JdbcTemplate(new DriverManagerDataSource(url, "user", "password"));

        JdbcTemplate failure = CheckoutIncidentScenario.failureJdbc(normal);

        assertThat(failure.getQueryTimeout()).isEqualTo(1);
        assertThat(((DriverManagerDataSource) failure.getDataSource()).getUrl())
            .contains("connectTimeout=1000", "socketTimeout=1000").doesNotContain("90000", "80000");
        assertThat(normal.getQueryTimeout()).isEqualTo(-1);
        assertThat(((DriverManagerDataSource) normal.getDataSource()).getUrl()).isEqualTo(url);
    }

    private IncidentEvidenceWriter writer() {
        return IncidentEvidenceWriter.openRun(root, new IncidentCodeReference("head", "diff", 0, List.of()),
            IncidentMode.AUTO, "AUTO", Instant.EPOCH);
    }

    private JsonNode read(IncidentEvidenceWriter writer, String relative) throws IOException {
        return JSON.readTree(Files.readAllBytes(writer.runDir().resolve("failures").resolve(relative)));
    }
}
