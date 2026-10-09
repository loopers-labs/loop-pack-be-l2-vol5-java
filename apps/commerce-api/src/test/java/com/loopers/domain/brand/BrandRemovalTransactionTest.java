package com.loopers.domain.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class BrandRemovalTransactionTest {

    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;

    @Autowired BrandFacade brandFacade;

    // ProductJpaRepository의 save()를 가로채서 두 번째 호출에서 예외를 던짐
    @SpyBean ProductJpaRepository productJpaRepositorySpy;

    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드 삭제 중 두 번째 상품 저장에서 실패하면 브랜드·상품 전체가 원상태로 돌아온다.")
    @Test
    void rollsBack_whenSecondProductSaveFails() {
        // ── 1. 준비: 브랜드 1개 + 상품 2개 저장 ──
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product1 = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품1", 10_000L, 5)
        );
        ProductModel product2 = productJpaRepository.save(
            new ProductModel(brand.getId(), "재고0 상품", 20_000L, 0)
        );

        // ── 2. spy 설정: 두 번째 save() 호출에서 예외 발생 ──
        // (arrange 단계 save는 위에서 이미 끝났으므로 카운터가 여기서 0으로 시작)
        AtomicInteger saveCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (saveCount.incrementAndGet() >= 2) {
                throw new RuntimeException("두 번째 상품 저장 강제 실패");
            }
            return invocation.callRealMethod(); // 첫 번째 save는 실제 동작
        }).when(productJpaRepositorySpy).save(any());

        // ── 3. 브랜드 삭제 시도 → 두 번째 상품 save에서 예외 발생 ──
        assertThatThrownBy(() -> brandFacade.deleteBrand(brand.getId()))
            .isInstanceOf(Exception.class);

        // ── 4. 롤백 확인 — 트랜잭션 종료 후 새 조회로 확인 ──
        // 브랜드: deletedAt 이 null이어야 함 (삭제 안 됨)
        BrandModel reloadedBrand = brandJpaRepository.findById(brand.getId()).get();
        assertThat(reloadedBrand.getDeletedAt()).isNull();

        // 첫 번째 상품: deletedAt 이 null이어야 함 (첫 번째 save는 성공했지만 롤백됨)
        ProductModel reloadedProduct1 = productJpaRepository.findById(product1.getId()).get();
        assertThat(reloadedProduct1.getDeletedAt()).isNull();

        // 두 번째 상품: deletedAt 이 null이어야 함
        ProductModel reloadedProduct2 = productJpaRepository.findById(product2.getId()).get();
        assertThat(reloadedProduct2.getDeletedAt()).isNull();
    }
}
