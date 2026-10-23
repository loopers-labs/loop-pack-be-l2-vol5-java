package com.loopers.fixture.pg;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class RequestJournal {

    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();
    private long nextSequence = 1;

    RequestJournal(Clock clock) {
        this.clock = clock;
    }

    synchronized Entry begin(PgOperation operation) {
        Entry entry = new Entry(nextSequence++, operation, clock.instant());
        entries.add(entry);
        return entry;
    }

    synchronized void target(Entry entry, String target) {
        entry.target = target;
    }

    synchronized void transactionCreated(Entry entry, String transactionKey) {
        entry.createdTransactionKey = transactionKey;
    }

    synchronized void complete(Entry entry, Integer responseStatus, ResponseFailure failure) {
        entry.completedAt = clock.instant();
        entry.responseStatus = responseStatus;
        entry.failure = failure;
    }

    synchronized List<JournalEntry> snapshot() {
        return entries.stream().map(Entry::toSnapshot).toList();
    }

    synchronized void clear() {
        entries.clear();
    }

    static final class Entry {

        private final long sequence;
        private final PgOperation operation;
        private final Instant receivedAt;
        private String target;
        private Instant completedAt;
        private Integer responseStatus;
        private String createdTransactionKey;
        private ResponseFailure failure;

        private Entry(long sequence, PgOperation operation, Instant receivedAt) {
            this.sequence = sequence;
            this.operation = operation;
            this.receivedAt = receivedAt;
        }

        private JournalEntry toSnapshot() {
            return new JournalEntry(sequence, operation, target, receivedAt, completedAt, responseStatus,
                createdTransactionKey, failure);
        }
    }
}
