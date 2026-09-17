package com.loopers.application.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.Product;
import com.loopers.application.product.CustomerProductInfo;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MyLikeFacadeIntegrationTest {

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private BrandJpaRepository brandRepository;

    @Autowired
    private ProductJpaRepository productRepository;

    @Autowired
    private LikeJpaRepository likeRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void returnsOnlyActiveProductsLikedByUser() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product active = productRepository.save(Product.create(brand, "Air Max", 100_000L));
        Product deleted = productRepository.save(Product.create(brand, "Pegasus", 90_000L));
        deleted.delete();
        productRepository.save(deleted);
        likeRepository.save(Like.create(1L, active.getId()));
        likeRepository.save(Like.create(1L, deleted.getId()));

        List<CustomerProductInfo> result = likeFacade.getMyLikes(1L);

        assertThat(result).extracting(CustomerProductInfo::id).containsExactly(active.getId());
    }
}
