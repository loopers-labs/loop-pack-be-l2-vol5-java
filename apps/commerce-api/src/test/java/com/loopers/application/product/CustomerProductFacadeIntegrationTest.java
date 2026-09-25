package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CustomerProductFacadeIntegrationTest {

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void returnsCustomerProductDetailWithBrandAndLikeCount() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
        likeRepository.save(Like.create(1L, product.getId()));
        likeRepository.save(Like.create(2L, product.getId()));

        CustomerProductInfo info = productFacade.getCustomerDetail(product.getId());

        assertThat(info.id()).isEqualTo(product.getId());
        assertThat(info.brandName()).isEqualTo("Nike");
        assertThat(info.name()).isEqualTo("Air Max");
        assertThat(info.price()).isEqualTo(100_000L);
        assertThat(info.likeCount()).isEqualTo(2L);
    }

    @Test
    void rejectsDeletedProduct() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
        product.delete();
        productRepository.save(product);

        assertThatThrownBy(() -> productFacade.getCustomerDetail(product.getId()))
            .hasMessageContaining("상품을 찾을 수 없습니다.");
    }
}
