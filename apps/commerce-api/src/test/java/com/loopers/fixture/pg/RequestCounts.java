package com.loopers.fixture.pg;

import java.util.List;

/**
 * 거절·응답 유실된 요청도 세므로 거래 수와 다를 수 있다. 계약 밖 경로·메서드와 제어·관측 호출은 세지 않는다.
 * callbackSent 는 실제 전송을 시작한 알림 수이며 접수·대기 중 작업은 포함하지 않는다.
 */
public record RequestCounts(long createReceived, long lookupByKeyReceived, long lookupByOrderReceived, long callbackSent) {

    /** journal 과 같은 snapshot 에서 계산해 둘이 어긋나지 않게 한다. */
    static RequestCounts from(List<JournalEntry> journal, List<CallbackRecord> callbacks) {
        return new RequestCounts(
            count(journal, PgOperation.CREATE), count(journal, PgOperation.LOOKUP_TRANSACTION),
            count(journal, PgOperation.LOOKUP_ORDER), callbacks.size());
    }

    private static long count(List<JournalEntry> journal, PgOperation operation) {
        return journal.stream().filter(entry -> entry.operation() == operation).count();
    }
}
