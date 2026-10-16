package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.infrastructure.product.ProductRepositoryImpl;
import com.loopers.infrastructure.product.LocalPricePageCache;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {"commerce.cache.price.enabled=true", "commerce.cache.price.provider=local"})
@AutoConfigureMockMvc
class LocalPricePageCacheIntegrationTest {
    @Autowired private ProductFacade products;
    @Autowired private ProductQueryService query;
    @Autowired private ProductListQueryService lists;
    @Autowired private BrandFacade brands;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private LikeFacade likes;
    @Autowired private UserRegistrationService users;
    @Autowired private TransactionTemplate transaction;
    @Autowired private MockMvc mvc;
    @Autowired private LocalPricePageCache cache;
    @MockitoSpyBean private ProductRepositoryImpl repository;

    @AfterEach
    void clean() {
        reset(repository);
        cleanUp.truncateAllTables();
    }

    @Test
    void rollbackKeepsCacheAndDoesNotPublishTemporaryData() {
        long brand = brands.create("brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        var before = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        var cached = cache.get(brand, 0, 20);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            products.update(product, "temporary", 500);
            assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC).items().getFirst().price()).isEqualTo(500);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(cache.get(brand, 0, 20)).isEqualTo(cached);
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(before);
    }

    @Test
    void personalLikesStayLiveAndSeparateAcrossSharedLocalHits() {
        long brand = brands.create("personal local", null).id();
        long first = products.create(brand, "first", 1000, 5).id();
        long second = products.create(brand, "second", 2000, 5).id();
        long a = users.register().getId();
        long b = users.register().getId();
        likes.register(a, first);
        likes.register(b, second);
        assertThat(lists.getList(a, brand, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(true, false);
        var shared = cache.get(brand, 0, 20);
        clearInvocations(repository);
        assertThat(lists.getList(b, brand, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(false, true);
        likes.cancel(a, first);
        assertThat(lists.getList(a, brand, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(false, false);
        likes.register(a, first);
        assertThat(lists.getList(a, brand, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(true, false);
        assertThat(lists.getList(null, brand, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(false, false);
        assertThat(cache.get(brand, 0, 20)).isEqualTo(shared);
        verify(repository, times(0)).findActiveByPriceAsc(brand, 0, 20);
    }

    @Test
    void repeatedPricePageUsesCacheAndPriceCommitChangesOrder() throws Exception {
        long brand = brands.create("cache brand", null).id();
        long first = products.create(brand, "first", 1000, 5).id();
        long second = products.create(brand, "second", 2000, 5).id();
        clearInvocations(repository);
        var miss = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        var hit = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(hit).isEqualTo(miss);
        verify(repository, times(1)).findActiveByPriceAsc(anyLong(), anyInt(), anyInt());
        mvc.perform(put("/api-admin/v1/products/{id}", second).header("X-USER-ROLE", "ADMIN")
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"second updated\",\"price\":500}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/products").param("brandId", String.valueOf(brand))
            .param("page", "0").param("size", "20").param("sort", "price_asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(second))
            .andExpect(jsonPath("$.data.items[0].price").value(500))
            .andExpect(jsonPath("$.data.totalCount").doesNotExist());
        var changed = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(changed.items()).extracting(CustomerProductInfo::id).containsExactly(second, first);
        assertThat(changed.items().getFirst().price()).isEqualTo(500);
        assertThat(changed.items().getFirst().name()).isEqualTo("second updated");
    }

    @Test
    void outsidePagePriceChangeAndCreationDeletionRefreshPage() {
        long brand = brands.create("brand", null).id();
        for (int i = 1; i <= 20; i++) { products.create(brand, "p" + i, i * 1000, 1); }
        long outside = products.create(brand, "outside", 30000, 1).id();
        var old = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(old.items()).extracting(CustomerProductInfo::id).doesNotContain(outside);
        products.update(outside, "moved in", 1);
        assertThat(query.getList(brand, 0, 20, ProductSort.PRICE_ASC).items().getFirst().id()).isEqualTo(outside);
        products.delete(outside);
        var deleted = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(deleted.items()).hasSize(20);
        assertThat(deleted.items()).extracting(CustomerProductInfo::id).doesNotContain(outside);
        long added = products.create(brand, "added", 1, 1).id();
        var created = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(created.items()).hasSize(20);
        assertThat(created.items().getFirst().id()).isEqualTo(added);
    }

    @Test
    void brandDeletionKeepsOtherBrandCacheAndHidesDeletedProducts() {
        long first = brands.create("first brand", null).id();
        long second = brands.create("second brand", null).id();
        products.create(first, "first", 1000, 5);
        products.create(second, "second", 1000, 5);
        query.getList(first, 0, 20, ProductSort.PRICE_ASC);
        var other = query.getList(second, 0, 20, ProductSort.PRICE_ASC);
        clearInvocations(repository);
        brands.delete(first);
        assertThatThrownBy(() -> query.getList(first, 0, 20, ProductSort.PRICE_ASC))
            .isInstanceOf(com.loopers.support.error.CoreException.class);
        assertThat(query.getList(second, 0, 20, ProductSort.PRICE_ASC)).isEqualTo(other);
        verify(repository, times(0)).findActiveByPriceAsc(second, 0, 20);
        assertThat(query.getList(null, 0, 20, ProductSort.PRICE_ASC).items()).hasSize(1);
    }

    @Test
    void likesStockAndBrandLabelsStayLiveWithoutRefillingPricePage() {
        long brand = brands.create("brand", null).id();
        long product = products.create(brand, "first", 1000, 5).id();
        long user = users.register().getId();
        query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        clearInvocations(repository);
        likes.register(user, product);
        products.changeStock(product, 9);
        brands.update(brand, "new brand", "new description");
        var hit = query.getList(brand, 0, 20, ProductSort.PRICE_ASC);
        assertThat(hit.items().getFirst().likeCount()).isEqualTo(1);
        assertThat(hit.items().getFirst().brand().name()).isEqualTo("new brand");
        verify(repository, times(0)).findActiveByPriceAsc(brand, 0, 20);
    }

}
