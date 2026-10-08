package com.loopers.application.like;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.like.fixture.LikeFixture;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.support.ConcurrentRequests;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

@SpringBootTest
class LikeConcurrencyTest {
    @Autowired private CreateLikeFacade createLike;
    @Autowired private UserFixture users;
    @Autowired private BrandFixture brands;
    @Autowired private ProductFixture products;
    @Autowired private LikeFixture likes;
    @Autowired private DatabaseCleanUp cleanUp;

    @Test
    void 같은_좋아요를_동시에_등록해도_모든_요청이_성공하고_관계는_하나다() throws Exception {
        // arrange
        users.createUser(1);
        long brandId = brands.createBrand().getId();
        long productId = products.createProduct(brandId, "상품", 1_000, 0).getId();
        int requestCount = 20;
        List<Callable<Void>> requests =
                IntStream.range(0, requestCount)
                        .mapToObj(ignored -> registration(productId))
                        .toList();

        // act
        var result = ConcurrentRequests.run(requests);

        // assert
        assertThat(result.failures()).isEmpty();
        assertThat(result.successes()).hasSize(requestCount);
        assertThat(likes.rowCount()).isEqualTo(1);
    }

    private Callable<Void> registration(long productId) {
        return () -> {
            createLike.create(1L, productId);
            return null;
        };
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
