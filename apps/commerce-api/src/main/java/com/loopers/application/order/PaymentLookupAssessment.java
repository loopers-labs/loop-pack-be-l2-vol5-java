package com.loopers.application.order;

import com.loopers.application.payment.port.PaymentAnomalyRepository.Kind;
import com.loopers.application.payment.port.PaymentTransaction;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.domain.payment.PaymentRecord;
import java.util.List;

public sealed interface PaymentLookupAssessment {
    default String callbackComparisonKey() {
        return null;
    }

    record NotCalled() implements PaymentLookupAssessment { }

    record Uncertain(Kind kind, String summary, String callbackComparisonKey) implements PaymentLookupAssessment { }

    record Review(Kind kind, String summary, String callbackComparisonKey) implements PaymentLookupAssessment { }

    record Verified(PaymentReport report, String callbackComparisonKey) implements PaymentLookupAssessment { }

    static PaymentLookupAssessment assess(PaymentRecord payment, PaymentTransactionsLookup lookup) {
        return switch (lookup) {
            case PaymentTransactionsLookup.NotCalled notCalled -> new NotCalled();
            case PaymentTransactionsLookup.Failed failed ->
                new Uncertain(Kind.LOOKUP_FAILED, "lookupFailed=true", payment.getTransactionKey());
            case PaymentTransactionsLookup.Found found -> assessFound(payment, found);
        };
    }

    private static PaymentLookupAssessment assessFound(PaymentRecord payment, PaymentTransactionsLookup.Found found) {
        String storedKey = payment.getTransactionKey();
        List<PaymentTransaction> transactions = found.transactions();
        if (transactions.isEmpty()) {
            // 0건은 효과 없음의 증거가 아니므로 실패 보고나 재발송 허가로 해석하지 않는다.
            return new Uncertain(Kind.NO_TRANSACTION, "count=0, storedKeyPresent=" + (storedKey != null), storedKey);
        }
        if (found.coverage() == PaymentTransactionsLookup.Coverage.ALL && transactions.size() > 1) {
            return new Review(Kind.DUPLICATE_TRANSACTIONS, "count=" + transactions.size(), storedKey);
        }
        if (found.coverage() == PaymentTransactionsLookup.Coverage.SINGLE && transactions.size() > 1) {
            return new Review(Kind.COVERAGE_CONTRACT_VIOLATION, "count=" + transactions.size(), storedKey);
        }
        PaymentTransaction transaction = transactions.get(0);
        PaymentRecord.TransactionMatch match = payment.compareTransaction(
            transaction.providerOrderId(), transaction.amount(), transaction.transactionKey());
        if (!match.matches()) {
            return new Review(Kind.TRANSACTION_MISMATCH,
                "orderMatches=" + match.orderMatches() + ", amountMatches=" + match.amountMatches()
                    + ", keyMatches=" + match.keyMatches(), storedKey);
        }
        // 저장 키가 없을 때는 일치하는 단일 거래만 callback 키 비교의 근거가 된다.
        String comparisonKey = storedKey;
        if (comparisonKey == null && transactions.size() == 1) {
            comparisonKey = transaction.transactionKey();
        }
        return new Verified(PaymentReport.from(transaction), comparisonKey);
    }
}
