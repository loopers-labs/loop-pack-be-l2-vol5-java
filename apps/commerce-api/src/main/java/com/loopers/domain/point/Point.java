package com.loopers.domain.point;

public class Point {

    private Long userId;

    private PointBalance balance;

    private Point(Long userId, PointBalance balance) {
        this.userId = userId;
        this.balance = balance;
    }

    public static Point create(Long userId) {
        return new Point(userId, new PointBalance(0L));
    }

    public static Point create(Long userId, PointBalance balance) {
        return new Point(userId, balance);
    }

    public Long getUserId() {
        return userId;
    }

    public PointBalance getBalance() {
        return balance;
    }

    public void charge(long amount) {
        balance = balance.add(amount);
    }

    public void pay(long amount) {
        balance = balance.subtract(amount);
    }
}
