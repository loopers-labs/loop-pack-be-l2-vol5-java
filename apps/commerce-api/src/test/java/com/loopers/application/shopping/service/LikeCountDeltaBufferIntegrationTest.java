package com.loopers.application.shopping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.shopping.command.LikeCommand;
import com.loopers.application.shopping.usecase.CancelLikeUseCase;
import com.loopers.application.shopping.usecase.RegisterLikeUseCase;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// AFTER_COMMIT 리스너 연결을 검증하므로 테스트 전체를 트랜잭션으로 감싸지 않는다
@IntegrationTest
class LikeCountDeltaBufferIntegrationTest {
    @Autowired
    private RegisterLikeUseCase registerLikeUseCase;
    @Autowired
    private CancelLikeUseCase cancelLikeUseCase;
    @Autowired
    private LikeCountDeltaBuffer buffer;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        buffer.drain();
    }

    @AfterEach
    void tearDown() {
        buffer.drain();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("등록은 커밋 후 +1, 같은 등록 반복은 추가 증감 없음, 취소는 -1로 누적된다")
    @Test
    void accumulatesAfterCommit() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        long productId = productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, 5)).getId();

        registerLikeUseCase.execute(new LikeCommand.Register(1L, productId));
        assertThat(buffer.drain()).containsExactlyEntriesOf(Map.of(productId, 1L));

        registerLikeUseCase.execute(new LikeCommand.Register(1L, productId));
        assertThat(buffer.drain()).isEmpty();

        cancelLikeUseCase.execute(new LikeCommand.Cancel(1L, productId));
        assertThat(buffer.drain()).containsExactlyEntriesOf(Map.of(productId, -1L));
    }
}
