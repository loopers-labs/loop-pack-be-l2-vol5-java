package com.loopers.testpg;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TransactionStore {

    private final ConcurrentMap<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Submission> submissionsByIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> transactionsByPaymentKey = new ConcurrentHashMap<>();
    private final Path persistenceFile;
    private final ObjectMapper objectMapper;

    public TransactionStore() {
        this(null, null);
    }

    public TransactionStore(Path persistenceFile, ObjectMapper objectMapper) {
        this.persistenceFile = persistenceFile;
        this.objectMapper = objectMapper;
        if (persistenceFile != null && Files.exists(persistenceFile)) {
            load();
            return;
        }
        seed("local-txn-success-1", "test-payment-success-1", "LP-ORD-LOCAL-SUCCESS-001", 7_000,
            TransactionStatus.SUCCESS);
        seed("local-txn-success-2", "test-payment-success-2", "LP-ORD-LOCAL-SUCCESS-002", 12_000,
            TransactionStatus.SUCCESS);
        seed("local-txn-failed-1", "test-payment-failed-1", "LP-ORD-LOCAL-FAILED-001", 7_000,
            TransactionStatus.FAILED);
        seed("local-txn-failed-2", "test-payment-failed-2", "LP-ORD-LOCAL-FAILED-002", 12_000,
            TransactionStatus.FAILED);
        persist();
    }

    private void seed(String transactionKey, String paymentKey, String orderId, long amount,
                      TransactionStatus status) {
        Transaction transaction = new Transaction(transactionKey, paymentKey, orderId, amount, status);
        transactions.put(transactionKey, transaction);
        transactionsByPaymentKey.put(paymentKey, transactionKey);
    }

    public synchronized Submission confirm(String idempotencyKey, String paymentKey, String orderId, long amount) {
        Submission previousSubmission = previousSubmission(idempotencyKey);
        if (previousSubmission != null) {
            return previousSubmission;
        }

        if (transactionsByPaymentKey.containsKey(paymentKey)) {
            Submission alreadyProcessed = new Submission(null, true, null, null, 400,
                "ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
            cache(idempotencyKey, alreadyProcessed);
            persist();
            return alreadyProcessed;
        }

        String transactionKey = "test-txn-" + UUID.randomUUID();
        OffsetDateTime approvedAt = OffsetDateTime.now(ZoneOffset.UTC);
        Transaction transaction = new Transaction(transactionKey, paymentKey, orderId, amount,
            TransactionStatus.SUCCESS);
        transactions.put(transactionKey, transaction);
        transactionsByPaymentKey.put(paymentKey, transactionKey);
        Submission approved = new Submission(transaction, false, paymentKey, approvedAt, 200, null, null);
        cache(idempotencyKey, approved);
        persist();
        return approved;
    }

    public synchronized Submission error(String idempotencyKey, int status, String code, String message) {
        Submission previousSubmission = previousSubmission(idempotencyKey);
        if (previousSubmission != null) {
            return previousSubmission;
        }
        Submission failure = new Submission(null, false, null, null, status, code, message);
        cache(idempotencyKey, failure);
        persist();
        return failure;
    }

    public synchronized Submission cancel(String idempotencyKey, String paymentKey, String cancelReason) {
        Submission previousSubmission = previousSubmission(idempotencyKey);
        if (previousSubmission != null) {
            return previousSubmission;
        }

        Optional<Transaction> matched = findByPaymentKey(paymentKey);
        if (matched.isEmpty()) {
            Submission notFound = cache(idempotencyKey, new Submission(null, false, paymentKey, null, 404,
                "NOT_FOUND_PAYMENT", "존재하지 않는 결제입니다."));
            persist();
            return notFound;
        }
        Transaction transaction = matched.get();
        if (transaction.status() == TransactionStatus.CANCELED) {
            Submission alreadyCanceled = cache(idempotencyKey, new Submission(null, false, paymentKey, null, 400,
                "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제입니다."));
            persist();
            return alreadyCanceled;
        }
        if (transaction.status() != TransactionStatus.SUCCESS) {
            Submission notCancelable = cache(idempotencyKey, new Submission(null, false, paymentKey, null, 400,
                "NOT_CANCELABLE_PAYMENT", "취소할 수 없는 결제입니다."));
            persist();
            return notCancelable;
        }

        Cancellation cancellation = new Cancellation(transaction.amount(), cancelReason,
            OffsetDateTime.now(ZoneOffset.UTC).toString(), "test-cancel-" + UUID.randomUUID(), "DONE");
        Transaction canceled = transaction.withCancellation(cancellation);
        transactions.put(canceled.transactionKey(), canceled);
        Submission result = new Submission(canceled, false, paymentKey, null, 200, null, null);
        cache(idempotencyKey, result);
        persist();
        return result;
    }

    private Submission previousSubmission(String idempotencyKey) {
        return idempotencyKey == null ? null : submissionsByIdempotencyKey.get(idempotencyKey);
    }

    private Submission cache(String idempotencyKey, Submission submission) {
        if (idempotencyKey != null) {
            submissionsByIdempotencyKey.put(idempotencyKey, submission);
        }
        return submission;
    }

    public Optional<Transaction> findByTransactionKey(String transactionKey) {
        return Optional.ofNullable(transactions.get(transactionKey));
    }

    public Optional<Transaction> findByPaymentKey(String paymentKey) {
        String matchedTransactionKey = transactionsByPaymentKey.get(paymentKey);
        if (matchedTransactionKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(transactions.get(matchedTransactionKey));
    }

    public List<Transaction> findByOrderId(String orderId) {
        return transactions.values().stream()
            .filter(transaction -> transaction.orderId().equals(orderId))
            .sorted(Comparator.comparing(Transaction::transactionKey))
            .toList();
    }

    public synchronized Optional<Transaction> updateStatus(String transactionKey, TransactionStatus status) {
        Optional<Transaction> updated = Optional.ofNullable(transactions.computeIfPresent(transactionKey,
            (key, transaction) -> transaction.status() == TransactionStatus.PENDING
                ? transaction.withStatus(status)
                : transaction));
        if (updated.isPresent()) {
            persist();
        }
        return updated;
    }

    private void load() {
        try {
            PersistedState state = objectMapper.readValue(persistenceFile.toFile(), PersistedState.class);
            for (Transaction transaction : state.transactions()) {
                transactions.put(transaction.transactionKey(), transaction);
                transactionsByPaymentKey.put(transaction.paymentKey(), transaction.transactionKey());
            }
            for (PersistedSubmission saved : state.submissions()) {
                Transaction transaction = saved.transactionKey() == null
                    ? null : transactions.get(saved.transactionKey());
                OffsetDateTime approvedAt = saved.approvedAt() == null
                    ? null : OffsetDateTime.parse(saved.approvedAt());
                submissionsByIdempotencyKey.put(saved.idempotencyKey(), new Submission(
                    transaction, saved.alreadyProcessed(), saved.paymentKey(), approvedAt,
                    saved.httpStatus(), saved.errorCode(), saved.errorMessage()));
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not load local test PG data from " + persistenceFile, exception);
        }
    }

    private void persist() {
        if (persistenceFile == null) {
            return;
        }
        try {
            Path parent = persistenceFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            List<PersistedSubmission> savedSubmissions = submissionsByIdempotencyKey.entrySet().stream()
                .map(entry -> {
                    Submission submission = entry.getValue();
                    String transactionKey = submission.transaction() == null
                        ? null : submission.transaction().transactionKey();
                    String approvedAt = submission.approvedAt() == null
                        ? null : submission.approvedAt().toString();
                    return new PersistedSubmission(entry.getKey(), transactionKey, submission.alreadyProcessed(),
                        submission.paymentKey(), approvedAt, submission.httpStatus(), submission.errorCode(),
                        submission.errorMessage());
                })
                .toList();
            PersistedState state = new PersistedState(
                transactions.values().stream().toList(), savedSubmissions);
            Path temporaryFile = persistenceFile.resolveSibling(persistenceFile.getFileName() + ".tmp");
            objectMapper.writeValue(temporaryFile.toFile(), state);
            try {
                Files.move(temporaryFile, persistenceFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, persistenceFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save local test PG data to " + persistenceFile, exception);
        }
    }

    public record Transaction(String transactionKey, String paymentKey, String orderId, long amount,
                              TransactionStatus status, List<Cancellation> cancels) {
        public Transaction {
            cancels = cancels == null ? List.of() : List.copyOf(cancels);
        }

        public Transaction(String transactionKey, String paymentKey, String orderId, long amount,
                           TransactionStatus status) {
            this(transactionKey, paymentKey, orderId, amount, status, List.of());
        }

        public Transaction withStatus(TransactionStatus nextStatus) {
            return new Transaction(transactionKey, paymentKey, orderId, amount, nextStatus, cancels);
        }

        public Transaction withCancellation(Cancellation cancellation) {
            List<Cancellation> updatedCancels = new java.util.ArrayList<>(cancels);
            updatedCancels.add(cancellation);
            return new Transaction(transactionKey, paymentKey, orderId, amount,
                TransactionStatus.CANCELED, updatedCancels);
        }
    }

    public record Cancellation(long cancelAmount, String cancelReason, String canceledAt,
                               String transactionKey, String cancelStatus) {
    }

    public record Submission(Transaction transaction, boolean alreadyProcessed, String paymentKey,
                             OffsetDateTime approvedAt, int httpStatus, String errorCode, String errorMessage) {
    }

    private record PersistedState(List<Transaction> transactions, List<PersistedSubmission> submissions) {
    }

    private record PersistedSubmission(String idempotencyKey, String transactionKey, boolean alreadyProcessed,
                                      String paymentKey, String approvedAt, int httpStatus,
                                      String errorCode, String errorMessage) {
    }

    public enum TransactionStatus {
        PENDING,
        SUCCESS,
        FAILED,
        CANCELED
    }
}
