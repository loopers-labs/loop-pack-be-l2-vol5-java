package com.loopers.support.fixture;

import jakarta.persistence.EntityManager;

public final class TestEntities {
    private TestEntities() {}

    public static int stockQuantity(EntityManager entityManager, Long productId) {
        return entityManager.createQuery("select s.quantity from Stock s where s.productId = :id", Integer.class)
            .setParameter("id", productId)
            .getSingleResult();
    }

    public static long pointBalance(EntityManager entityManager, Long userId) {
        return entityManager.createQuery("select p.balance from Point p where p.userId = :id", Long.class)
            .setParameter("id", userId)
            .getSingleResult();
    }
}
