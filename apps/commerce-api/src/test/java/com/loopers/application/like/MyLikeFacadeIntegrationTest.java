package com.loopers.application.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.application.product.CustomerProductInfo;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
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
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void returnsOnlyActiveProductsLikedByUser() {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product active = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
        Product deleted = productRepository.save(Product.create(brand.getId(), "Pegasus", 90_000L));
        deleted.delete();
        productRepository.save(deleted);
        likeRepository.save(Like.create(user.getId(), active.getId()));
        likeRepository.save(Like.create(user.getId(), deleted.getId()));

        List<CustomerProductInfo> result = likeFacade.getMyLikes(user.getId());

        assertThat(result).extracting(CustomerProductInfo::id).containsExactly(active.getId());
    }
}
