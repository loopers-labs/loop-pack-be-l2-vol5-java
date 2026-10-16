package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 좋아요순은 DB가 정렬해 한 페이지의 ID만 고른다. 앱 정렬 때와 같은 순서·필터·오류를 확인한다. */
@SpringBootTest
class ProductLikesOrderIntegrationTest {
    @Autowired private ProductFacade products;
    @Autowired private ProductQueryService query;
    @Autowired private BrandFacade brands;
    @Autowired private LikeFacade likes;
    @Autowired private UserRegistrationService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private TransactionTemplate transaction;

    @AfterEach
    void clean() { cleanUp.truncateAllTables(); }

    @Test
    void ordersByLikeCountThenNewestThenLargestIdAndPagesInTheDatabase() {
        long brand = brands.create("brand", null).id();
        long older = product(brand);
        long newer = product(brand);
        long most = product(brand);
        long zeroFirst = product(brand);
        long zeroSecond = product(brand);
        jdbc.update("update product set created_at='2026-01-01 00:00:00' where brand_id=?", brand);
        jdbc.update("update product set created_at='2026-01-02 00:00:00' where id=?", newer);
        like(most, 2);
        like(older, 1);
        like(newer, 1);

        assertThat(query.getList(brand, 0, 20, ProductSort.LIKES_DESC).items())
            .extracting(CustomerProductInfo::id).containsExactly(most, newer, older, zeroSecond, zeroFirst);
        assertThat(query.getList(brand, 1, 2, ProductSort.LIKES_DESC).items())
            .extracting(CustomerProductInfo::id).containsExactly(older, zeroSecond);
        assertThat(query.getList(brand, 0, 20, ProductSort.LIKES_DESC).items())
            .extracting(CustomerProductInfo::likeCount).containsExactly(2L, 1L, 1L, 0L, 0L);
    }

    @Test
    void brandlessPageIsFilledAfterSkippingDeletedBrandsAndProducts() {
        long visibleBrand = brands.create("visible", null).id();
        long hiddenBrand = brands.create("hidden", null).id();
        long visible = product(visibleBrand);
        long deletedProduct = product(visibleBrand);
        long underDeletedBrand = product(hiddenBrand);
        like(deletedProduct, 3);
        like(underDeletedBrand, 2);
        products.delete(deletedProduct);
        // 브랜드만 삭제된 상태: 상품 행은 활성이어도 목록에서 빠져야 한다.
        jdbc.update("update brand set deleted_at=now(6) where id=?", hiddenBrand);

        assertThat(query.getList(null, 0, 1, ProductSort.LIKES_DESC).items())
            .extracting(CustomerProductInfo::id).containsExactly(visible);
    }

    @Test
    void deletionEarlierInTheSameWriteTransactionIsSeenByTheDatabaseOrderedPage() {
        long brand = brands.create("brand", null).id();
        long kept = product(brand);
        long deleted = product(brand);
        like(deleted, 1);

        var page = transaction.execute(status -> {
            products.delete(deleted);
            return query.getList(brand, 0, 1, ProductSort.LIKES_DESC);
        });

        assertThat(page.items()).extracting(CustomerProductInfo::id).containsExactly(kept);
    }

    @Test
    void missingCounterOnTheReturnedPageIsReportedInsteadOfShownAsZero() {
        long brand = brands.create("brand", null).id();
        long product = product(brand);
        jdbc.update("delete from product_like_count where product_id=?", product);

        assertThatThrownBy(() -> query.getList(brand, 0, 20, ProductSort.LIKES_DESC))
            .isInstanceOf(IllegalStateException.class);
    }

    private long product(long brand) { return products.create(brand, "product", 1000, 5).id(); }

    private void like(long product, int times) {
        for (int i = 0; i < times; i++) {
            likes.register(users.register().getId(), product);
        }
    }
}
