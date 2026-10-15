package com.loopers.product.application;

import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import com.loopers.product.application.port.out.ProductQueryPort;
import com.loopers.product.domain.ProductSort;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * W4 · 캐시가 Redis 오류를 DB로 우회하는 것과 반대로, DB 조회의 실패는 감추지 않는다 (ADR-W4-05).
 * 실제 MySQL·Redis를 쓰되, DB 실패만 조회 포트를 감싼 spy로 만든다.
 */
@SpringBootTest
class ProductListDbFailureTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired
    private ProductListQueryService productListQueryService;

    @Autowired
    private BrandCommandUseCase brandCommandUseCase;

    @Autowired
    private ProductCommandUseCase productCommandUseCase;

    @MockitoSpyBean
    private ProductQueryPort productQueryPort;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        reset(productQueryPort);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("miss에서 DB 조회가 실패하면 빈 목록이 아니라 그 오류를 그대로 전달한다.")
    @Test
    void missPropagatesDbFailure() {
        // arrange
        Long brandId = brandCommandUseCase.create("브랜드", null).id();
        productCommandUseCase.create(brandId, "A", 1_000, 10);
        doThrow(new DataAccessResourceFailureException("테스트: DB 실패"))
            .when(productQueryPort).findSellable(any(), any(), any());

        // act & assert
        assertThatThrownBy(() -> productListQueryService.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE))
            .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @DisplayName("hit여도 비노출 확인(DB)이 실패하면 캐시 값을 대신 돌려주지 않고 오류를 전달한다.")
    @Test
    void hitPropagatesDbFailureOfSellableCheck() {
        // arrange — 캐시를 채운 뒤 DB 확인만 실패시킨다
        Long brandId = brandCommandUseCase.create("브랜드", null).id();
        productCommandUseCase.create(brandId, "A", 1_000, 10);
        productListQueryService.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE);
        doThrow(new DataAccessResourceFailureException("테스트: DB 실패"))
            .when(productQueryPort).findSellableByIds(any());

        // act & assert
        assertThatThrownBy(() -> productListQueryService.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE))
            .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
