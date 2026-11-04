package com.loopers.infrastructure.persistence.mall.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
class StockAdjustmentRepositoryTest {
    @Autowired
    StockAdjustmentRepository repository;
    @Autowired
    EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("고정 품목 수량과 복구 결과를 복원하고 오래된 기록의 반대 결과 저장을 막는다")
    void preservesItemsAndGuardsStaleState() {
        StockAdjustment stale = repository.insert(StockAdjustment.create(900000004,
                List.of(new StockAdjustment.Item(1, 2), new StockAdjustment.Item(2, 3))));
        entityManager.clear();
        StockAdjustment loaded = repository.findByOrderId(stale.getOrderId()).orElseThrow();
        assertThat(loaded.getItems()).isEqualTo(stale.getItems());
        assertThat(loaded.restore()).isTrue();
        repository.save(loaded);
        entityManager.clear();
        assertThat(repository.findByOrderId(stale.getOrderId()).orElseThrow().getState()).isEqualTo(StockAdjustment.State.RESTORED);
        assertThat(stale.consume()).isTrue();
        assertThatThrownBy(() -> repository.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    }
}
