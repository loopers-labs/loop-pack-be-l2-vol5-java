package com.loopers.brand.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
public class BrandCommandServiceRollbackIntegrationTest {

    @Autowired
    private BrandCommandUseCase brandCommandUseCase;

    @MockitoSpyBean
    private ProductPort productPort;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드와 첫 상품의 삭제 UPDATE가 나간 뒤 두 번째 상품 저장에서 실패하면, 예외가 전달되고 새로 읽은 브랜드·상품은 모두 삭제 전 상태다")
    @Test
    void rollsBackBrandAndProducts_whenSecondProductSaveFails() {
        // arrange
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        ProductModel first = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 3_000, 5));
        ProductModel second = productJpaRepository.save(new ProductModel(nike.getId(), "품절 상품", 1_000, 0));

        List<Boolean> deletedInsideTransaction = new ArrayList<>();
        doAnswer(invocation -> {
            ProductModel product = invocation.getArgument(0);
            if (product.getId().equals(second.getId())) {
                // 같은 트랜잭션의 커넥션으로 읽으면, 앞 단계의 UPDATE가 이미 DB에 나가 있다 (아직 commit 전)
                deletedInsideTransaction.add(isDeletedInDb("brands", nike.getId()));
                deletedInsideTransaction.add(isDeletedInDb("products", first.getId()));
                throw new IllegalStateException("두 번째 상품 저장 실패 (테스트 주입)");
            }
            return invocation.callRealMethod();
        }).when(productPort).save(any(ProductModel.class));


        // act
        assertThatThrownBy(() -> brandCommandUseCase.delete(nike.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("테스트 주입");

        // assert 1 : 실패 직전에는 브랜드·첫 상품의 UPDATE가 DB에 반영돼 있었다
        assertThat(deletedInsideTransaction).containsExactly(true, true);


        // assert 2 : 트랜잭션이 끝난 뒤 새로 읽으면 전부 삭제 전 상태다
        assertThat(isDeletedInDb("brands", nike.getId())).isFalse();
        assertThat(isDeletedInDb("products", first.getId())).isFalse();
        assertThat(isDeletedInDb("products", second.getId())).isFalse();

    }

    private boolean isDeletedInDb(String table, Long id) {
        Boolean deleted = jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM " + table + " WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(deleted);
    }

}

