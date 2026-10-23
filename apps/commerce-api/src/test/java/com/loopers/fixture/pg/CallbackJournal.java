package com.loopers.fixture.pg;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 실제 전송을 시작한 알림만 기록한다. 그래서 entry 수가 callbackSent 이고 접수·대기 중 작업과 구분된다. */
final class CallbackJournal {

    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();
    private long nextSequence = 1;

    CallbackJournal(Clock clock) {
        this.clock = clock;
    }

    synchronized Entry begin(TransactionSnapshot snapshot) {
        Entry entry = new Entry(nextSequence++, snapshot, clock.instant());
        entries.add(entry);
        return entry;
    }

    synchronized void complete(Entry entry, Integer httpStatus, CallbackFailure failure) {
        entry.completedAt = clock.instant();
        entry.httpStatus = httpStatus;
        entry.failure = failure;
    }

    synchronized List<CallbackRecord> snapshot() {
        return entries.stream().map(Entry::toRecord).toList();
    }

    synchronized void clear() {
        entries.clear();
    }

    static final class Entry {

        private final long sequence;
        private final TransactionSnapshot snapshot;
        private final Instant startedAt;
        private Instant completedAt;
        private Integer httpStatus;
        private CallbackFailure failure;

        private Entry(long sequence, TransactionSnapshot snapshot, Instant startedAt) {
            this.sequence = sequence;
            this.snapshot = snapshot;
            this.startedAt = startedAt;
        }

        private CallbackRecord toRecord() {
            return new CallbackRecord(sequence, snapshot.transactionKey(), snapshot.orderId(), snapshot.status(),
                startedAt, completedAt, httpStatus, failure);
        }
    }
}
