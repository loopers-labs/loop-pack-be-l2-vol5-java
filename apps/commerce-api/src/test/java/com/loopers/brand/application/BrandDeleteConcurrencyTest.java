package com.loopers.brand.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.concurrency.ConcurrentRunner;
import com.loopers.support.concurrency.ConcurrentRunner.Kind;
import com.loopers.support.concurrency.ConcurrentRunner.Outcome;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3 C-1·C-5 (plan.md R-10·R-11): 브랜드 행을 바꾸거나 기대는 다른 경로가 일괄 삭제와 겹쳐도, 삭제된 브랜드에 살아 있는 상품이 남지 않는다.
 * 결과가 순서에 따라 갈리므로, 각 순서의 직렬 결과 중 하나와 같은지 확인한다.
 */
@SpringBootTest
class BrandDeleteConcurrencyTest {

    @Autowired
    private BrandCommandUseCase brandCommandUseCase;

    @Autowired
    private ProductCommandUseCase productCommandUseCase;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("R-10 브랜드 수정과 일괄 삭제를 동시에 → 브랜드는 반드시 삭제된 채 남는다. 수정이 먼저면 새 이름으로 삭제, 삭제가 먼저면 수정은 404")
    @Test
    void updateAndDelete() throws Exception {
        // arrange
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(List.of(
            () -> brandCommandUseCase.update(nike.getId(), "나이키2", null),
            () -> {
                brandCommandUseCase.delete(nike.getId());
                return null;
            }
        ));

        // assert: 수정이 삭제를 되살리지 않는다
        Outcome update = outcomes.get(0);
        assertThat(outcomes.get(1).kind()).isEqualTo(Kind.SUCCESS);
        assertThat(update.kind() == Kind.SUCCESS || isNotFound(update)).isTrue();
        BrandModel reloaded = brandJpaRepository.findById(nike.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getName()).isEqualTo(update.kind() == Kind.SUCCESS ? "나이키2" : "나이키");
    }

    @DisplayName("R-11 상품 등록과 브랜드 일괄 삭제를 동시에 → 삭제된 브랜드에 살아 있는 상품이 없다. 등록이 먼저면 함께 삭제, 삭제가 먼저면 등록은 404")
    @Test
    void createProductAndDeleteBrand() throws Exception {
        // arrange
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(List.of(
            () -> productCommandUseCase.create(nike.getId(), "에어조던", 3_000, 5),
            () -> {
                brandCommandUseCase.delete(nike.getId());
                return null;
            }
        ));

        // assert
        Outcome create = outcomes.get(0);
        assertThat(outcomes.get(1).kind()).isEqualTo(Kind.SUCCESS);
        assertThat(create.kind() == Kind.SUCCESS || isNotFound(create)).isTrue();
        List<ProductModel> products = productJpaRepository.findAll();
        assertThat(products).hasSize(create.kind() == Kind.SUCCESS ? 2 : 1);
        assertThat(products).allSatisfy(product -> assertThat(product.getDeletedAt()).isNotNull());
    }

    private static boolean isNotFound(Outcome outcome) {
        return outcome.error() instanceof CoreException core && core.getErrorType() == ErrorType.NOT_FOUND;
    }
}
