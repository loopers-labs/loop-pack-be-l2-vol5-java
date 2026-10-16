package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.like.LikeService;
import com.loopers.support.error.CoreException;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig.class)
@Transactional
class ProductBrandFilterIntegrationTest {
    @Autowired
    private ProductQueryService query;
    @Autowired
    private ProductRepository products;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LikeService likes;

    @Test
    void brandFilterPreservesTiesPagesAndPageLikeCounts() {
        BrandModel brand = brand(false);
        ProductModel expensive = product(brand, 2000, false);
        ProductModel older = product(brand, 1000, false);
        ProductModel newer = product(brand, 1000, false);
        ProductModel sameTimeHigherId = product(brand, 1000, false);
        product(brand, 500, true);
        product(brand(false), 100, false);
        likes.register(1L, sameTimeHigherId.getId());
        likes.register(2L, sameTimeHigherId.getId());
        em.flush();
        jdbc.update("update product set created_at = '2026-01-01 00:00:00' where id = ?", older.getId());
        jdbc.update("update product set created_at = '2026-01-02 00:00:00' where id in (?, ?)",
            newer.getId(), sameTimeHigherId.getId());
        em.clear();

        assertThat(products.findActiveByPriceAsc(brand.getId(), 0, 20)).extracting(ProductModel::getId)
            .containsExactly(sameTimeHigherId.getId(), newer.getId(), older.getId(), expensive.getId());

        var first = query.getList(brand.getId(), 0, 2, ProductSort.PRICE_ASC);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.items()).extracting(CustomerProductInfo::id)
            .containsExactly(sameTimeHigherId.getId(), newer.getId());
        assertThat(first.items()).extracting(CustomerProductInfo::likeCount).containsExactly(2L, 0L);
        var second = query.getList(brand.getId(), 1, 2, ProductSort.PRICE_ASC);
        assertThat(second.items()).extracting(CustomerProductInfo::id)
            .containsExactly(older.getId(), expensive.getId());
        var empty = query.getList(brand.getId(), 2, 2, ProductSort.PRICE_ASC);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.page()).isEqualTo(2);
        assertThat(empty.size()).isEqualTo(2);
    }

    @Test
    void unfilteredPageExcludesProductsOfDeletedBrands() {
        ProductModel visible = product(brand(false), 1000, false);
        product(brand(true), 100, false);
        product(brand(false), 100, true);
        em.flush();
        em.clear();

        var result = query.getList(null, 0, 20, ProductSort.PRICE_ASC);
        assertThat(result.items()).extracting(CustomerProductInfo::id).containsExactly(visible.getId());
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void databasePageExcludesDeletedBrandsBeforeLimit() {
        product(brand(true), 100, false);
        ProductModel first = product(brand(false), 200, false);
        ProductModel second = product(brand(false), 300, false);
        product(brand(false), 50, true);
        em.flush();
        em.clear();

        var page = query.getList(null, 0, 1, ProductSort.PRICE_ASC);
        assertThat(page.items()).extracting(CustomerProductInfo::id).containsExactly(first.getId());
        assertThat(page.items()).hasSize(1);
        assertThat(query.getList(null, 1, 1, ProductSort.PRICE_ASC).items())
            .extracting(CustomerProductInfo::id).containsExactly(second.getId());
        assertThat(query.getList(null, Integer.MAX_VALUE, 100, ProductSort.PRICE_ASC).items()).isEmpty();
    }

    @Test
    void preservesBrandAndPageValidation() {
        assertThatThrownBy(() -> query.getList(Long.MAX_VALUE, 0, 20, ProductSort.PRICE_ASC))
            .isInstanceOf(CoreException.class);
        assertThatThrownBy(() -> query.getList(null, -1, 20, ProductSort.PRICE_ASC))
            .isInstanceOf(CoreException.class);
        assertThatThrownBy(() -> query.getList(null, 0, 101, ProductSort.PRICE_ASC))
            .isInstanceOf(CoreException.class);
    }

    private BrandModel brand(boolean deleted) {
        var brand = new BrandModel("test brand", null);
        if (deleted) {
            brand.markDeleted();
        }
        em.persist(brand);
        return brand;
    }

    private ProductModel product(BrandModel brand, long price, boolean deleted) {
        var product = new ProductModel(brand.getId(), "test product", price, 10);
        if (deleted) {
            product.markDeleted();
        }
        em.persist(product);
        likes.initializeCount(product.getId());
        return product;
    }
}
