package com.loopers.application.coupon.support;

import java.util.ArrayList;
import java.util.List;

/** 한 facade 호출과 그 안의 순차 TX들을 별도로 추적한다. */
public final class CouponExecution {
    public enum Completion { PENDING, COMMITTED, ROLLED_BACK, UNKNOWN }
    public record DbWork(String serverId, long connectionId) {}
    public record Transaction(DbWork work, Completion completion) {}

    private final List<Transaction> transactions = new ArrayList<>();
    private boolean ended;
    private boolean uncertain;
    private final String id;

    public CouponExecution(String id) { this.id = id; }
    public String id() { return id; }

    public synchronized int started() {
        transactions.add(new Transaction(null, Completion.PENDING));
        return transactions.size() - 1;
    }

    public synchronized void identify(int index, DbWork work) {
        transactions.set(index, new Transaction(work, transactions.get(index).completion()));
    }

    public synchronized void completed(int index, Completion completion) {
        transactions.set(index, new Transaction(transactions.get(index).work(), completion));
    }

    public synchronized void end(boolean uncertainFailure) {
        ended = true;
        uncertain |= uncertainFailure || transactions.isEmpty();
    }

    public synchronized boolean ended() { return ended; }

    public synchronized boolean confirmed() {
        return ended && !uncertain && transactionsConfirmed();
    }

    public synchronized boolean transactionsConfirmed() {
        return !transactions.isEmpty() && transactions.stream().allMatch(tx ->
            tx.completion() == Completion.COMMITTED || tx.completion() == Completion.ROLLED_BACK);
    }

    public synchronized List<Transaction> transactions() { return List.copyOf(transactions); }
}
