package com.loopers.infrastructure.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OrderRepositoryIntegrationTest {
    @Autowired private OrderRepository orders;
    @Autowired private OrderFixture fixture;
    @Autowired private BrandFixture brands;
    @Autowired private ProductFixture products;
    @Autowired private UserFixture users;
    @Autowired private DatabaseCleanUp cleanUp;

    @Test
    void 이전에_조회한_DRAFT로_이미_확정된_주문을_저장하면_상태_오류로_거절한다() {
        // arrange
        users.createUser(1);
        long brandId = brands.createBrand().getId();
        Product product = products.createProduct(brandId, "상품", 1_000, 5);
        Order confirmed = fixture.createOrder(1, product, 1);
        Order previous = fixture.order(confirmed.getId());
        confirmed.confirm();
        orders.confirmIfDraft(confirmed);
        previous.confirm();

        // act
        CoreException error =
                assertThrows(CoreException.class, () -> orders.confirmIfDraft(previous));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(fixture.order(confirmed.getId()))
                .usingRecursiveComparison()
                .isEqualTo(confirmed);
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
