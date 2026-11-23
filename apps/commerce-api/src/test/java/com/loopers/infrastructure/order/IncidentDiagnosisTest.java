package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.fixture.pg.JournalEntry;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponseFailure;
import com.loopers.infrastructure.order.IncidentDiagnosis.Db;
import com.loopers.infrastructure.order.IncidentDiagnosis.Hypothesis;
import com.loopers.infrastructure.order.IncidentDiagnosis.Pg;
import com.loopers.infrastructure.order.IncidentDiagnosis.PgTransaction;
import com.loopers.infrastructure.order.IncidentDiagnosis.Result;
import com.loopers.infrastructure.order.IncidentDiagnosis.UnknownEvidence;
import com.loopers.infrastructure.order.IncidentDiagnosis.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("W10 진단 판정 규칙 검증")
class IncidentDiagnosisTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long ORDER_ID = 11L;
    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000011";
    private static final String KEY = "tx-u1";
    private static final Instant AT = Instant.parse("2026-11-23T01:00:00Z");

    @DisplayName("응답 유실 증거가 모두 맞으면 유실 지지·나머지 배제로 복구를 허용한다.")
    @Test
    void lostResponseAllowsRecovery() {
        Result result = IncidentDiagnosis.judge("sha", List.of(evidence(e -> e)));

        IncidentDiagnosis.UnknownDiagnosis u = result.unknowns().get(0);
        assertAll(
            () -> assertThat(result.proceed()).isTrue(),
            () -> assertThat(verdicts(u)).containsExactly(Verdict.EXCLUDED, Verdict.SUPPORTED, Verdict.EXCLUDED),
            () -> assertThat(u.callId()).isEqualTo("call-1"),
            () -> assertThat(u.observationId()).isEqualTo("obs-1"),
            () -> assertThat(u.diagnosticFailures()).isEmpty(),
            () -> assertThat(result.expectationsSha256()).isEqualTo("sha")
        );
    }

    @DisplayName("권위 조회가 PENDING 이면 처리 중 가설을 지지하고 진행하지 않는다.")
    @Test
    void pendingSupportsStillProcessing() {
        Result result = IncidentDiagnosis.judge("sha", List.of(evidence(f -> f.pg(
            new Pg(true, List.of(new PgTransaction(KEY, PROVIDER_ORDER_ID, 4000, "PENDING")))))));

        assertAll(
            () -> assertThat(result.proceed()).isFalse(),
            () -> assertThat(verdicts(result.unknowns().get(0)).get(0)).isEqualTo(Verdict.SUPPORTED),
            () -> assertThat(verdicts(result.unknowns().get(0)).get(1)).isEqualTo(Verdict.UNDETERMINED)
        );
    }

    @DisplayName("조회 실패·복수 거래·금액 불일치는 처리 중 가설을 미확정으로 둔다.")
    @Test
    void ambiguousLookupIsUndetermined() {
        List<Pg> cases = List.of(
            new Pg(false, List.of()),
            new Pg(true, List.of()),
            new Pg(true, List.of(new PgTransaction(KEY, PROVIDER_ORDER_ID, 4000, "SUCCESS"),
                new PgTransaction("tx-2", PROVIDER_ORDER_ID, 4000, "SUCCESS"))),
            new Pg(true, List.of(new PgTransaction(KEY, PROVIDER_ORDER_ID, 1, "SUCCESS"))));

        for (Pg pg : cases) {
            Result result = IncidentDiagnosis.judge("sha", List.of(evidence(f -> f.pg(pg))));
            assertThat(verdicts(result.unknowns().get(0)).get(0)).isEqualTo(Verdict.UNDETERMINED);
            assertThat(result.proceed()).isFalse();
        }
    }

    @DisplayName("유실 필수 증거 하나라도 빠지면 유실 가설은 미확정이고 진행하지 않는다.")
    @Test
    void missingLostEvidenceIsUndetermined() {
        List<UnaryOperator<Fields>> breaks = List.of(
            f -> f.reason("POOL_ACQUIRE_TIMEOUT"),
            f -> f.journalFailure(null),
            f -> f.journalKey("tx-other"),
            f -> f.outbox(1),
            f -> f.release(1),
            f -> f.paymentStatus("SUCCESS"));

        for (UnaryOperator<Fields> broken : breaks) {
            Result result = IncidentDiagnosis.judge("sha", List.of(evidence(broken)));
            assertThat(verdicts(result.unknowns().get(0)).get(1)).isEqualTo(Verdict.UNDETERMINED);
            assertThat(result.proceed()).isFalse();
        }
    }

    @DisplayName("CREATE_RESULT_UNAVAILABLE 이유도 유실로 지지한다.")
    @Test
    void decoderUnavailableAlsoSupportsLost() {
        Result result = IncidentDiagnosis.judge("sha", List.of(evidence(f -> f.reason("CREATE_RESULT_UNAVAILABLE"))));

        assertThat(result.proceed()).isTrue();
    }

    @DisplayName("SUCCESS 관찰 반영이 ROLLED_BACK 이면 적용 실패 가설을 지지한다.")
    @Test
    void rolledBackSuccessSupportsApplyFailure() {
        Result result = IncidentDiagnosis.judge("sha", List.of(evidence(f -> f.input("TransactionObserved", "SUCCESS")
            .effect("CONFIRM").outcome("ROLLED_BACK"))));

        assertAll(
            () -> assertThat(verdicts(result.unknowns().get(0)).get(2)).isEqualTo(Verdict.SUPPORTED),
            () -> assertThat(result.proceed()).isFalse()
        );
    }

    @DisplayName("진단 전 callback/reconcile 이나 추가 반영이 있으면 적용 실패를 배제하지 않는다.")
    @Test
    void extraCallsKeepApplyUndetermined() {
        Result callbacks = IncidentDiagnosis.judge("sha", List.of(evidence(f -> f.callsBefore(1))));
        Result extraApply = IncidentDiagnosis.judge("sha", List.of(evidence(f -> f.extraQueryApply(true))));

        assertAll(
            () -> assertThat(verdicts(callbacks.unknowns().get(0)).get(2)).isEqualTo(Verdict.UNDETERMINED),
            () -> assertThat(callbacks.proceed()).isFalse(),
            () -> assertThat(extraApply.unknowns().get(0).diagnosticFailures()).contains("UNEXPECTED_APPLY_COUNT_2"),
            () -> assertThat(extraApply.proceed()).isFalse()
        );
    }

    @DisplayName("기록 누락·중복·순서 역전·종료 불명·진단 실패는 진단 실패로 진행하지 않는다.")
    @Test
    void incompleteLogsBlockRecovery() {
        Map<String, UnaryOperator<Fields>> cases = new LinkedHashMap<>();
        cases.put("CREATE_START_COUNT_2", f -> f.duplicateCreate(true));
        cases.put("TX_COMPLETION_COUNT_0", f -> f.outcome(null));
        cases.put("TX_COMPLETION_UNKNOWN", f -> f.outcome("UNKNOWN"));
        cases.put("RESULT_AFTER_APPLY", f -> f.resultAfterApply(true));
        cases.put("DIAGNOSTIC_TX_OBSERVATION_UNAVAILABLE", f -> f.observationUnavailable(true));

        cases.forEach((failure, broken) -> {
            Result result = IncidentDiagnosis.judge("sha", List.of(evidence(broken)));
            assertThat(result.unknowns().get(0).diagnosticFailures()).as(failure).contains(failure);
            assertThat(result.proceed()).as(failure).isFalse();
        });
    }

    @DisplayName("U 하나라도 진행 조건을 못 채우면 전체를 진행하지 않고, U 가 없어도 진행하지 않는다.")
    @Test
    void allUnknownsMustPass() {
        Result mixed = IncidentDiagnosis.judge("sha", List.of(evidence(f -> f), evidence(f -> f.outbox(1))));

        assertAll(
            () -> assertThat(mixed.proceed()).isFalse(),
            () -> assertThat(IncidentDiagnosis.judge("sha", List.of()).proceed()).isFalse()
        );
    }

    @DisplayName("예상 파일은 세 가설의 규칙과 대응·진행 조건을 담는다.")
    @Test
    void expectationsDescribeRules() {
        Map<String, Object> expectations = IncidentDiagnosis.expectations();

        assertThat(MAPPER.valueToTree(expectations).get("hypotheses").fieldNames())
            .toIterable().containsExactly("PG_STILL_PROCESSING", "RESPONSE_LOST", "APPLY_TX_FAILED");
    }

    private static List<Verdict> verdicts(IncidentDiagnosis.UnknownDiagnosis u) {
        return List.of(u.judgments().get(Hypothesis.PG_STILL_PROCESSING).verdict(),
            u.judgments().get(Hypothesis.RESPONSE_LOST).verdict(),
            u.judgments().get(Hypothesis.APPLY_TX_FAILED).verdict());
    }

    private static UnknownEvidence evidence(UnaryOperator<Fields> change) {
        return change.apply(new Fields()).build();
    }

    /** 장애 U 하나의 정상 응답 유실 증거. 각 메서드가 한 가지씩 깨뜨린다. */
    private static final class Fields {

        private Pg pg = new Pg(true, List.of(new PgTransaction(KEY, PROVIDER_ORDER_ID, 4000, "SUCCESS")));
        private String reason = "TRANSPORT_UNAVAILABLE";
        private ResponseFailure journalFailure = ResponseFailure.CLOSED_EARLY;
        private String journalKey = KEY;
        private String inputClass = "Unavailable";
        private String inputStatus;
        private String effect = "UNAVAILABLE";
        private String outcome = "COMMITTED";
        private String paymentStatus = "UNKNOWN";
        private long release;
        private long outbox;
        private int callsBefore;
        private boolean duplicateCreate;
        private boolean resultAfterApply;
        private boolean observationUnavailable;
        private boolean extraQueryApply;

        Fields pg(Pg value) {
            pg = value;
            return this;
        }

        Fields reason(String value) {
            reason = value;
            return this;
        }

        Fields journalFailure(ResponseFailure value) {
            journalFailure = value;
            return this;
        }

        Fields journalKey(String value) {
            journalKey = value;
            return this;
        }

        Fields input(String classification, String status) {
            inputClass = classification;
            inputStatus = status;
            return this;
        }

        Fields effect(String value) {
            effect = value;
            return this;
        }

        Fields outcome(String value) {
            outcome = value;
            return this;
        }

        Fields paymentStatus(String value) {
            paymentStatus = value;
            return this;
        }

        Fields release(long value) {
            release = value;
            return this;
        }

        Fields outbox(long value) {
            outbox = value;
            return this;
        }

        Fields callsBefore(int value) {
            callsBefore = value;
            return this;
        }

        Fields duplicateCreate(boolean value) {
            duplicateCreate = value;
            return this;
        }

        Fields resultAfterApply(boolean value) {
            resultAfterApply = value;
            return this;
        }

        Fields observationUnavailable(boolean value) {
            observationUnavailable = value;
            return this;
        }

        Fields extraQueryApply(boolean value) {
            extraQueryApply = value;
            return this;
        }

        UnknownEvidence build() {
            List<JsonNode> pgEvents = new ArrayList<>();
            pgEvents.add(event("PAYMENT_PG_START", "callId", "call-1", "operation", "CREATE", "providerOrderId", PROVIDER_ORDER_ID));
            if (duplicateCreate) {
                pgEvents.add(event("PAYMENT_PG_START", "callId", "call-2", "operation", "CREATE", "providerOrderId",
                    PROVIDER_ORDER_ID));
            }
            JsonNode result = event("PAYMENT_PG_RESULT", "callId", "call-1", "operation", "CREATE",
                "classification", "Unavailable", "source", "CREATE", "reason", reason);
            List<JsonNode> txEvents = new ArrayList<>();
            txEvents.add(event("TX_METHOD_ENTER", "observationId", "obs-1", "operation", "APPLY_OBSERVATION",
                "orderId", ORDER_ID, "source", "CREATE"));
            txEvents.add(event("PAYMENT_APPLY_INPUT", "observationId", "obs-1", "classification", inputClass,
                "status", inputStatus));
            txEvents.add(event("PAYMENT_APPLY_DECISION", "observationId", "obs-1", "effect", effect));
            if (outcome != null) {
                txEvents.add(event("TX_COMPLETION", "observationId", "obs-1", "operation", "APPLY_OBSERVATION",
                    "outcome", outcome));
            }
            List<JsonNode> events = new ArrayList<>(pgEvents);
            if (resultAfterApply) {
                events.addAll(txEvents);
                events.add(result);
            } else {
                events.add(result);
                events.addAll(txEvents);
            }
            if (extraQueryApply) {
                events.add(event("TX_METHOD_ENTER", "observationId", "obs-2", "operation", "APPLY_OBSERVATION",
                    "orderId", ORDER_ID, "source", "QUERY"));
            }
            if (observationUnavailable) {
                events.add(event("TX_OBSERVATION_UNAVAILABLE", "observationId", "obs-3", "reason", "OBSERVATION_FAILED"));
            }
            JournalEntry journal = new JournalEntry(1, PgOperation.CREATE, PROVIDER_ORDER_ID, AT, AT, 200, journalKey,
                journalFailure);
            return new UnknownEvidence(ORDER_ID, PROVIDER_ORDER_ID, 4000, pg, List.of(journal), events,
                new Db(paymentStatus, "PAYMENT_PENDING", 2, release, outbox), callsBefore, List.of("fault/u-11.json"));
        }

        private static JsonNode event(String name, Object... fields) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("event", name);
            for (int i = 0; i < fields.length; i += 2) {
                map.put((String) fields[i], fields[i + 1]);
            }
            return MAPPER.valueToTree(map);
        }
    }
}
