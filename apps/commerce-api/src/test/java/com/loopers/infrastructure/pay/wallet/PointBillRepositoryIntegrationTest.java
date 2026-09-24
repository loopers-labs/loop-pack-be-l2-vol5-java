package com.loopers.infrastructure.pay.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.pay.wallet.PointBill;
import com.loopers.domain.pay.wallet.PointBillRepository;
import com.loopers.domain.pay.wallet.PointBillType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class PointBillRepositoryIntegrationTest {
    @Autowired
    private PointBillRepository pointBillRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("충전 기록을 저장하고 영속성 컨텍스트를 비운 뒤 저장 값을 재조회한다")
    @Test
    @Transactional
    void savesChargeBill() {
        PointBill saved = pointBillRepository.save(PointBill.charge(1L, 1_000L));
        entityManager.flush();
        entityManager.clear();

        PointBillJpaEntity restored = entityManager.find(PointBillJpaEntity.class, saved.getId());

        assertThat(restored.getUserId()).isEqualTo(1L);
        assertThat(restored.getType()).isEqualTo(PointBillType.CHARGE);
        assertThat(restored.getAmount()).isEqualTo(1_000L);
        assertThat(restored.getCreatedAt()).isNotNull();
    }

    @DisplayName("사용 기록을 저장하고 영속성 컨텍스트를 비운 뒤 주문 ID를 재조회한다")
    @Test
    @Transactional
    void savesUseBill_withOrderId() {
        PointBill saved = pointBillRepository.save(PointBill.use(1L, 10L, 1_000L));
        entityManager.flush();
        entityManager.clear();

        PointBillJpaEntity restored = entityManager.find(PointBillJpaEntity.class, saved.getId());

        assertThat(restored.getType()).isEqualTo(PointBillType.USE);
        assertThat(restored.getOrderId()).isEqualTo(10L);
    }
}
