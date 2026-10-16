package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductListRevisionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProductListRevisionRepositoryImpl implements ProductListRevisionRepository {

    private final EntityManager entityManager;

    public ProductListRevisionRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public long current() {
        // 현재 트랜잭션의 스냅샷을 읽되 native 증가와 어긋날 수 있는 1차 캐시 엔티티는 사용하지 않는다.
        return entityManager.createQuery(
            "select r.revision from ProductListRevision r where r.id = 1", Long.class)
            .getResultList().stream().findFirst().orElse(0L);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void advance() {
        // 업무 행 잠금·UPDATE를 먼저 완료하고, 마지막에 공통 버전 행을 갱신한다.
        entityManager.flush();
        entityManager.createNativeQuery("""
            INSERT INTO product_list_revision (id, revision) VALUES (1, 1)
            ON DUPLICATE KEY UPDATE revision = revision + 1
            """).executeUpdate();
    }
}
