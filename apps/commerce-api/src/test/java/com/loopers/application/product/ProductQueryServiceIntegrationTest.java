package com.loopers.application.product;

import com.loopers.application.user.UserResolutionException;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductQueryServiceIntegrationTest {

    @Autowired private ProductQueryService queryService;
    @Autowired private AdminProductQueryService adminQueryService;
    @Autowired private AdminProductService mutations;
    @Autowired private ProductQueryRepository queryRepository;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;

    private Brand brand;
    private Product low;
    private Product high;
    private Product tie;

    @BeforeEach
    void setUp() {
        brand = brands.save(new Brand("브랜드"));
        low = products.save(new Product(brand, "낮은 가격", 100, 5));
        high = products.save(new Product(brand, "높은 가격", 200, 1));
        tie = products.save(new Product(brand, "동률 품절", 100, 0));
        LocalDateTime timestamp = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbc.update("UPDATE product SET created_at = ?, updated_at = ?", timestamp, timestamp);
        users.save(new User(1));
        users.save(new User(2));
        addLike(1, high.getId(), timestamp);
        addLike(1, low.getId(), timestamp);
        addLike(2, low.getId(), timestamp);
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @Test
    void aggregatesLikesBeforeSortingAndPagingAndIncludesZeroLikesAndSoldOutProducts() {
        var first = queryService.getList(null, 0, 1, ProductSort.LIKES_DESC);
        var second = queryService.getList(null, 1, 1, ProductSort.LIKES_DESC);
        var third = queryService.getList(null, 2, 1, ProductSort.LIKES_DESC);

        assertThat(first.items()).extracting(ProductInfo::productId).containsExactly(low.getId());
        assertThat(first.items().getFirst().likeCount()).isEqualTo(2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(second.items()).extracting(ProductInfo::productId).containsExactly(high.getId());
        assertThat(second.items().getFirst().likeCount()).isEqualTo(1);
        assertThat(third.items()).extracting(ProductInfo::productId).containsExactly(tie.getId());
        assertThat(third.items().getFirst().likeCount()).isZero();
        assertThat(third.items().getFirst().stockQuantity()).isZero();
    }

    @Test
    void appliesIdDescendingTieBreakersForEveryProductSort() {
        assertThat(queryService.getList(null, 0, 20, ProductSort.LATEST).items())
            .extracting(ProductInfo::productId).containsExactly(tie.getId(), high.getId(), low.getId());
        assertThat(queryService.getList(null, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(ProductInfo::productId).containsExactly(tie.getId(), low.getId(), high.getId());
        jdbc.update("DELETE FROM `like`");
        assertThat(queryService.getList(null, 0, 20, ProductSort.LIKES_DESC).items())
            .extracting(ProductInfo::productId).containsExactly(tie.getId(), high.getId(), low.getId());
    }

    @Test
    void filtersBothDeletedProductsAndBrandsForCustomerWhileAdminIncludesThem() {
        Brand other = brands.save(new Brand("다른 브랜드"));
        Product otherProduct = products.save(new Product(other, "다른 상품", 300, 3));
        jdbc.update("UPDATE product SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", high.getId());
        jdbc.update("UPDATE brand SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", other.getId());

        assertThat(queryService.getList(null, 0, 20, ProductSort.LATEST).items())
            .extracting(ProductInfo::productId).containsExactly(tie.getId(), low.getId());
        assertThat(queryService.getList(other.getId(), 0, 20, ProductSort.LATEST).totalElements()).isZero();
        assertThat(queryService.getList(Long.MAX_VALUE, 0, 20, ProductSort.LATEST).items()).isEmpty();
        assertThat(adminQueryService.getList(UserRole.ADMIN, null, 0, 20, ProductSort.LATEST).totalElements()).isEqualTo(4);
        assertThat(adminQueryService.getDetail(UserRole.ADMIN, high.getId()).deletedAt()).isNotNull();
        assertThat(adminQueryService.getDetail(UserRole.ADMIN, otherProduct.getId()).brand().brandId()).isEqualTo(other.getId());
        for (long id : List.of(high.getId(), otherProduct.getId(), Long.MAX_VALUE)) {
            assertThatThrownBy(() -> queryService.getDetail(id))
                .isInstanceOfSatisfying(ProductQueryException.class,
                    error -> assertThat(error.getReason()).isEqualTo(ProductQueryException.Reason.PRODUCT_NOT_FOUND));
        }
    }

    @Test
    void detailCombinesCurrentBrandAndExactCountWithoutChangingAnyRows() {
        var productBefore = jdbc.queryForList("SELECT * FROM product ORDER BY id");
        var likesBefore = jdbc.queryForList("SELECT * FROM `like` ORDER BY id");
        ProductInfo info = queryService.getDetail(low.getId());

        assertThat(info.name()).isEqualTo(low.getName());
        assertThat(info.brand().brandId()).isEqualTo(brand.getId());
        assertThat(info.brand().name()).isEqualTo(brand.getName());
        assertThat(info.likeCount()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(productBefore);
        assertThat(jdbc.queryForList("SELECT * FROM `like` ORDER BY id")).isEqualTo(likesBefore);
    }

    @Test
    void mutationResponseIncludesTheActualRelationCountFromItsTransaction() {
        AdminProductInfo changed = mutations.update(UserRole.ADMIN, low.getId(), "수정한 이름", 500);
        assertThat(changed.name()).isEqualTo("수정한 이름");
        assertThat(changed.likeCount()).isEqualTo(2);
        assertThat(mutations.changeStock(UserRole.ADMIN, low.getId(), 0).likeCount()).isEqualTo(2);
        assertThat(mutations.delete(UserRole.ADMIN, low.getId()).likeCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(3);
    }

    @Test
    void likedPageUsesOnlyOwnersVisibleRelationsAndRelationTimestampThenIdDescending() {
        var first = queryRepository.findLikedPage(1, 0, 1);
        var second = queryRepository.findLikedPage(1, 1, 1);
        assertThat(first.items().getFirst().productId()).isEqualTo(low.getId());
        assertThat(first.items().getFirst().likeCount()).isEqualTo(2);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(second.items().getFirst().productId()).isEqualTo(high.getId());
        assertThat(queryRepository.findLikedPage(2, 0, 20).items()).hasSize(1);
        assertThat(queryRepository.findLikedPage(999, 0, 20).items()).isEmpty();
        jdbc.update("UPDATE product SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", low.getId());
        assertThat(queryRepository.findLikedPage(1, 0, 20).totalElements()).isEqualTo(1);
        jdbc.update("UPDATE brand SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", brand.getId());
        assertThat(queryRepository.findLikedPage(1, 0, 20).totalElements()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(3);
    }

    @Test
    void returnsEmptyHugePageWithTheCorrectTotalAndChecksAdminBeforeLookup() {
        var page = queryService.getList(brand.getId(), Integer.MAX_VALUE, 100, ProductSort.LATEST);
        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(Integer.MAX_VALUE);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThatThrownBy(() -> adminQueryService.getDetail(UserRole.CUSTOMER, Long.MAX_VALUE))
            .isInstanceOfSatisfying(UserResolutionException.class,
                error -> assertThat(error.getReason()).isEqualTo(UserResolutionException.Reason.ADMIN_REQUIRED));
        assertThatThrownBy(() -> adminQueryService.getList(UserRole.CUSTOMER, null, 0, 20, ProductSort.LATEST))
            .isInstanceOf(UserResolutionException.class);
        assertThatThrownBy(() -> adminQueryService.getDetail(UserRole.ADMIN, Long.MAX_VALUE))
            .isInstanceOf(ProductQueryException.class);
    }

    private void addLike(long userId, long productId, LocalDateTime timestamp) {
        jdbc.update("INSERT INTO `like` (user_id, product_id, created_at) VALUES (?, ?, ?)", userId, productId, timestamp);
    }
}
