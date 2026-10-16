package com.loopers.infrastructure;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@DisplayName("상품 고객 조회 조건")
class ProductCustomerQueryIntegrationTest {

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("브랜드와 가격 오름차순을 적용하고, 같은 가격이면 ID 오름차순으로 조회한다.")
    void filtersActiveProductsByBrandAndSortsPriceAscendingWithIdTieBreaker() {
        Brand nike = brandRepository.save(Brand.create("Nike"));
        Brand adidas = brandRepository.save(Brand.create("Adidas"));
        Product firstAtSamePrice = productRepository.save(Product.create(nike.getId(), "First", 10_000L));
        Product secondAtSamePrice = productRepository.save(Product.create(nike.getId(), "Second", 10_000L));
        Product expensive = productRepository.save(Product.create(nike.getId(), "Expensive", 20_000L));
        productRepository.save(Product.create(adidas.getId(), "Other", 1_000L));
        Product deleted = productRepository.save(Product.create(nike.getId(), "Deleted", 500L));
        productRepository.softDeleteActiveById(deleted.getId(), ZonedDateTime.now());

        entityManager.flush();
        entityManager.clear();

        List<Product> results = productRepository.findActivePage(nike.getId(), ProductSort.PRICE_ASC, 0, 10);

        assertThat(results).extracting(Product::getId)
            .containsExactly(firstAtSamePrice.getId(), secondAtSamePrice.getId(), expensive.getId());
    }

    @Test
    @DisplayName("최근 등록순은 생성 시각 내림차순, 같으면 ID 내림차순으로 조회한다.")
    void sortsLatestByCreatedAtDescendingThenIdDescending() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product oldest = productRepository.save(Product.create(brand.getId(), "Oldest", 10_000L));
        Product olderAtLatestTime = productRepository.save(Product.create(brand.getId(), "Older tie", 10_000L));
        Product newerAtLatestTime = productRepository.save(Product.create(brand.getId(), "Newer tie", 10_000L));
        entityManager.flush();
        Timestamp olderTime = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp latestTime = Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"));
        jdbcTemplate.update("UPDATE products SET created_at = ? WHERE id = ?", olderTime, oldest.getId());
        jdbcTemplate.update(
            "UPDATE products SET created_at = ? WHERE id IN (?, ?)",
            latestTime,
            olderAtLatestTime.getId(),
            newerAtLatestTime.getId()
        );
        entityManager.clear();

        List<Product> results = productRepository.findActivePage(brand.getId(), ProductSort.LATEST, 0, 10);

        assertThat(results).extracting(Product::getId)
            .containsExactly(newerAtLatestTime.getId(), olderAtLatestTime.getId(), oldest.getId());
    }

    @Test
    @DisplayName("좋아요 수 내림차순으로 조회하고, 같으면 ID 내림차순으로 조회한다.")
    void sortsByLikeCountDescendingThenIdDescending() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product mostLiked = productRepository.save(Product.create(brand.getId(), "Most liked", 10_000L));
        Product olderTie = productRepository.save(Product.create(brand.getId(), "Older tie", 10_000L));
        Product newerTie = productRepository.save(Product.create(brand.getId(), "Newer tie", 10_000L));
        Product notLiked = productRepository.save(Product.create(brand.getId(), "Not liked", 10_000L));
        likeRepository.save(Like.create(1L, mostLiked.getId()));
        likeRepository.save(Like.create(2L, mostLiked.getId()));
        likeRepository.save(Like.create(1L, olderTie.getId()));
        likeRepository.save(Like.create(1L, newerTie.getId()));
        entityManager.flush();
        entityManager.clear();

        List<Product> results = productRepository.findActivePage(brand.getId(), ProductSort.LIKES_DESC, 0, 10);

        assertThat(results).extracting(Product::getId)
            .containsExactly(mostLiked.getId(), newerTie.getId(), olderTie.getId(), notLiked.getId());
    }
}
