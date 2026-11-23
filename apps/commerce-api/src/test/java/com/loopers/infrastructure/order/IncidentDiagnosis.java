package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.fixture.pg.JournalEntry;
import com.loopers.fixture.pg.ResponseFailure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * W10 fault 단계의 자동 가설 판정(checkout-incident §8.1.1). 수집된 증거만 입력으로 받고 fixture plan 이름은 보지 않는다.
 * 각 판정은 이번 실행에서 포착한 범위에만 적용되며, 하나라도 완결되지 않으면 복구로 진행하지 않는다.
 */
final class IncidentDiagnosis {

    enum Hypothesis {
        PG_STILL_PROCESSING,
        RESPONSE_LOST,
        APPLY_TX_FAILED
    }

    enum Verdict {
        SUPPORTED,
        EXCLUDED,
        UNDETERMINED
    }

    private static final Set<String> LOST_REASONS = Set.of("TRANSPORT_UNAVAILABLE", "CREATE_RESULT_UNAVAILABLE");

    private IncidentDiagnosis() {
    }

    /** baseline 전에 CREATE_NEW 로 고정하는 가설·판정 규칙. 실제 관찰을 본 뒤 바꾸지 않는다. */
    static Map<String, Object> expectations() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put(Hypothesis.PG_STILL_PROCESSING.name(), Map.of(
            "supported", "권위 HTTP 조회의 동일 주문·금액 거래가 유일하고 PENDING",
            "excluded", "같은 조건의 유일한 거래가 SUCCESS",
            "undetermined", "조회 실패·0건·복수·주문/금액 불일치·그 밖의 상태"));
        rules.put(Hypothesis.RESPONSE_LOST.name(), Map.of(
            "supported", "권위 SUCCESS, 같은 CREATE 의 RESULT Unavailable(TRANSPORT_UNAVAILABLE 또는 CREATE_RESULT_UNAVAILABLE), "
                + "journal CLOSED_EARLY·같은 거래, APPLY_INPUT Unavailable·DECISION UNAVAILABLE·COMMITTED, "
                + "DB UNKNOWN·PAYMENT_PENDING·예약 유지·반환0·Outbox0",
            "undetermined", "필수 증거 누락·불일치"));
        rules.put(Hypothesis.APPLY_TX_FAILED.name(), Map.of(
            "supported", "SUCCESS 관찰의 APPLY_INPUT 과 ROLLED_BACK, DB 미반영",
            "excluded", "CREATE 반영이 Unavailable 입력·COMMITTED 로 완결, 진단 전 callback/reconcile 0, 다른 적용·진단 오류 없음",
            "undetermined", "그 밖의 경우. 로그 부재만으로 배제하지 않음"));
        Map<String, Object> expectations = new LinkedHashMap<>();
        expectations.put("schemaVersion", 1);
        expectations.put("hypotheses", rules);
        expectations.put("linking", "providerOrderId 의 CREATE START/RESULT 1쌍과 orderId 의 APPLY_OBSERVATION(source=CREATE) 1개, "
            + "로그 순서 RESULT→APPLY");
        expectations.put("proceed", "모든 U 에서 RESPONSE_LOST 지지·나머지 둘 배제·진단 실패 없음");
        return expectations;
    }

    static Result judge(String expectationsSha256, List<UnknownEvidence> evidence) {
        List<UnknownDiagnosis> diagnoses = evidence.stream().map(IncidentDiagnosis::judge).toList();
        boolean proceed = !diagnoses.isEmpty() && diagnoses.stream().allMatch(UnknownDiagnosis::allowsRecovery);
        return new Result(expectationsSha256, diagnoses, proceed);
    }

    private static UnknownDiagnosis judge(UnknownEvidence u) {
        List<String> failures = new ArrayList<>();
        Link link = link(u, failures);
        Map<Hypothesis, Judgment> judgments = new LinkedHashMap<>();
        judgments.put(Hypothesis.PG_STILL_PROCESSING, pgStillProcessing(u));
        judgments.put(Hypothesis.RESPONSE_LOST, responseLost(u, link));
        judgments.put(Hypothesis.APPLY_TX_FAILED, applyTxFailed(u, link, failures));
        return new UnknownDiagnosis(u.orderId(), u.providerOrderId(), link.callId(), link.observationId(),
            u.evidenceFiles(), judgments, failures);
    }

    /** 생성 호출과 그 반영 TX 를 찾는다. 대응이 유일하지 않거나 기록이 빠지면 진단 실패로 남긴다. */
    private static Link link(UnknownEvidence u, List<String> failures) {
        List<Integer> starts = indexes(u.events(), e -> is(e, "PAYMENT_PG_START") && text(e, "operation").equals("CREATE")
            && text(e, "providerOrderId").equals(u.providerOrderId()));
        if (starts.size() != 1) {
            failures.add("CREATE_START_COUNT_" + starts.size());
            return Link.NONE;
        }
        String callId = text(u.events().get(starts.get(0)), "callId");
        List<Integer> ends = indexes(u.events(), e -> (is(e, "PAYMENT_PG_RESULT") || is(e, "PAYMENT_PG_ERROR"))
            && text(e, "callId").equals(callId));
        if (ends.size() != 1 || !is(u.events().get(ends.get(0)), "PAYMENT_PG_RESULT")) {
            failures.add("CREATE_RESULT_INCOMPLETE");
            return new Link(callId, null, null, null, null, null);
        }
        List<Integer> applies = indexes(u.events(), e -> is(e, "TX_METHOD_ENTER")
            && text(e, "operation").equals("APPLY_OBSERVATION") && longValue(e, "orderId") == u.orderId());
        List<Integer> createApplies = applies.stream()
            .filter(i -> text(u.events().get(i), "source").equals("CREATE")).toList();
        if (createApplies.size() != 1) {
            failures.add("CREATE_APPLY_COUNT_" + createApplies.size());
            return new Link(callId, null, u.events().get(ends.get(0)), null, null, null);
        }
        if (applies.size() != 1) {
            failures.add("UNEXPECTED_APPLY_COUNT_" + applies.size());
        }
        int apply = createApplies.get(0);
        if (ends.get(0) > apply) {
            failures.add("RESULT_AFTER_APPLY");
            return new Link(callId, null, u.events().get(ends.get(0)), null, null, null);
        }
        String observationId = text(u.events().get(apply), "observationId");
        JsonNode input = single(u.events(), "PAYMENT_APPLY_INPUT", observationId, failures);
        JsonNode decision = single(u.events(), "PAYMENT_APPLY_DECISION", observationId, failures);
        JsonNode completion = single(u.events(), "TX_COMPLETION", observationId, failures);
        if (completion != null && text(completion, "outcome").equals("UNKNOWN")) {
            failures.add("TX_COMPLETION_UNKNOWN");
        }
        u.events().stream().filter(e -> is(e, "TX_OBSERVATION_UNAVAILABLE") || is(e, "PAYMENT_PG_ERROR"))
            .forEach(e -> failures.add("DIAGNOSTIC_" + text(e, "event")));
        return new Link(callId, observationId, u.events().get(ends.get(0)), input, decision, completion);
    }

    private static Judgment pgStillProcessing(UnknownEvidence u) {
        Pg pg = u.authoritative();
        if (pg == null || !pg.lookupSucceeded()) {
            return new Judgment(Verdict.UNDETERMINED, "LOOKUP_FAILED");
        }
        List<PgTransaction> same = sameOrder(u);
        if (same.size() != 1 || pg.transactions().size() != 1) {
            return new Judgment(Verdict.UNDETERMINED, "TRANSACTION_COUNT_" + pg.transactions().size());
        }
        return switch (same.get(0).status()) {
            case "PENDING" -> new Judgment(Verdict.SUPPORTED, "AUTHORITATIVE_PENDING");
            case "SUCCESS" -> new Judgment(Verdict.EXCLUDED, "AUTHORITATIVE_SUCCESS");
            default -> new Judgment(Verdict.UNDETERMINED, "AUTHORITATIVE_" + same.get(0).status());
        };
    }

    private static Judgment responseLost(UnknownEvidence u, Link link) {
        List<String> missing = new ArrayList<>();
        PgTransaction success = uniqueSuccess(u);
        if (success == null) {
            missing.add("AUTHORITATIVE_SUCCESS");
        }
        if (link.result() == null || !text(link.result(), "classification").equals("Unavailable")
            || !LOST_REASONS.contains(text(link.result(), "reason"))) {
            missing.add("PG_RESULT_UNAVAILABLE");
        }
        List<JournalEntry> creates = u.createJournal();
        if (creates.size() != 1 || creates.get(0).failure() != ResponseFailure.CLOSED_EARLY || success == null
            || !Objects.equals(creates.get(0).createdTransactionKey(), success.transactionKey())) {
            missing.add("JOURNAL_CLOSED_EARLY_SAME_TRANSACTION");
        }
        if (!appliedUnavailableCommitted(link)) {
            missing.add("APPLY_UNAVAILABLE_COMMITTED");
        }
        Db db = u.db();
        if (db == null || !db.paymentStatus().equals("UNKNOWN") || !db.orderStatus().equals("PAYMENT_PENDING")
            || db.reservationRows() == 0 || db.releaseRows() != 0 || db.outboxCount() != 0) {
            missing.add("DB_UNKNOWN_RESERVED_NO_OUTBOX");
        }
        return missing.isEmpty()
            ? new Judgment(Verdict.SUPPORTED, "ALL_REQUIRED_EVIDENCE")
            : new Judgment(Verdict.UNDETERMINED, "MISSING_" + String.join("+", missing));
    }

    private static Judgment applyTxFailed(UnknownEvidence u, Link link, List<String> failures) {
        boolean successInput = link.input() != null && text(link.input(), "classification").equals("TransactionObserved")
            && text(link.input(), "status").equals("SUCCESS");
        if (successInput && link.completion() != null && text(link.completion(), "outcome").equals("ROLLED_BACK")
            && u.db() != null && u.db().paymentStatus().equals("UNKNOWN")) {
            return new Judgment(Verdict.SUPPORTED, "SUCCESS_INPUT_ROLLED_BACK");
        }
        if (appliedUnavailableCommitted(link) && u.callbackOrReconcileBeforeDiagnosis() == 0 && failures.isEmpty()) {
            return new Judgment(Verdict.EXCLUDED, "CREATE_APPLY_UNAVAILABLE_COMMITTED");
        }
        return new Judgment(Verdict.UNDETERMINED, failures.isEmpty() ? "INCOMPLETE_APPLY_EVIDENCE" : "DIAGNOSTIC_FAILURE");
    }

    private static boolean appliedUnavailableCommitted(Link link) {
        return link.input() != null && text(link.input(), "classification").equals("Unavailable")
            && link.decision() != null && text(link.decision(), "effect").equals("UNAVAILABLE")
            && link.completion() != null && text(link.completion(), "outcome").equals("COMMITTED");
    }

    private static List<PgTransaction> sameOrder(UnknownEvidence u) {
        return u.authoritative().transactions().stream()
            .filter(t -> t.orderId().equals(u.providerOrderId()) && t.amount() == u.amount())
            .toList();
    }

    private static PgTransaction uniqueSuccess(UnknownEvidence u) {
        if (u.authoritative() == null || !u.authoritative().lookupSucceeded() || u.authoritative().transactions().size() != 1) {
            return null;
        }
        List<PgTransaction> same = sameOrder(u);
        return same.size() == 1 && same.get(0).status().equals("SUCCESS") ? same.get(0) : null;
    }

    private static JsonNode single(List<JsonNode> events, String event, String observationId, List<String> failures) {
        List<JsonNode> found = events.stream()
            .filter(e -> is(e, event) && text(e, "observationId").equals(observationId)).toList();
        if (found.size() != 1) {
            failures.add(event + "_COUNT_" + found.size());
            return null;
        }
        return found.get(0);
    }

    private static List<Integer> indexes(List<JsonNode> events, Predicate<JsonNode> match) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (match.test(events.get(i))) {
                found.add(i);
            }
        }
        return found;
    }

    private static boolean is(JsonNode event, String name) {
        return text(event, "event").equals(name);
    }

    private static String text(JsonNode event, String field) {
        JsonNode value = event.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static long longValue(JsonNode event, String field) {
        JsonNode value = event.get(field);
        return value == null || !value.canConvertToLong() ? Long.MIN_VALUE : value.longValue();
    }

    /** U 하나의 수집 증거. events 는 PG·TX 진단 로그를 실제 기록 순서대로 담는다. */
    record UnknownEvidence(
        long orderId,
        String providerOrderId,
        long amount,
        Pg authoritative,
        List<JournalEntry> createJournal,
        List<JsonNode> events,
        Db db,
        int callbackOrReconcileBeforeDiagnosis,
        List<String> evidenceFiles
    ) {

        UnknownEvidence {
            createJournal = List.copyOf(createJournal);
            events = List.copyOf(events);
            evidenceFiles = List.copyOf(evidenceFiles);
        }
    }

    /** proxy baseUrl 을 거친 권위 HTTP 조회 결과(합성 허용 필드). */
    record Pg(boolean lookupSucceeded, List<PgTransaction> transactions) {

        Pg {
            transactions = List.copyOf(transactions);
        }
    }

    record PgTransaction(String transactionKey, String orderId, long amount, String status) {
    }

    /** 진단 조회 뒤 새 DB 조회 값. */
    record Db(String paymentStatus, String orderStatus, long reservationRows, long releaseRows, long outboxCount) {
    }

    record Judgment(Verdict verdict, String reason) {
    }

    record UnknownDiagnosis(
        long orderId,
        String providerOrderId,
        String callId,
        String observationId,
        List<String> evidenceFiles,
        Map<Hypothesis, Judgment> judgments,
        List<String> diagnosticFailures
    ) {

        boolean allowsRecovery() {
            return diagnosticFailures.isEmpty()
                && judgments.get(Hypothesis.RESPONSE_LOST).verdict() == Verdict.SUPPORTED
                && judgments.get(Hypothesis.PG_STILL_PROCESSING).verdict() == Verdict.EXCLUDED
                && judgments.get(Hypothesis.APPLY_TX_FAILED).verdict() == Verdict.EXCLUDED;
        }
    }

    /** diagnosis.json 에 쓰는 자동 판정. 사람 판단은 여기 들어가지 않는다. */
    record Result(String expectationsSha256, List<UnknownDiagnosis> unknowns, boolean proceed) {
    }

    private record Link(String callId, String observationId, JsonNode result, JsonNode input, JsonNode decision,
                        JsonNode completion) {

        static final Link NONE = new Link(null, null, null, null, null, null);
    }
}
