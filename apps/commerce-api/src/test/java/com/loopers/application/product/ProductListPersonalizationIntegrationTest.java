package com.loopers.application.product;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig.class)
@AutoConfigureMockMvc
@Transactional
class ProductListPersonalizationIntegrationTest {
    @Autowired private ProductListQueryService lists;
    @Autowired private UserRegistrationService users;
    @Autowired private LikeService likes;
    @Autowired private EntityManager em;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void readsPersonalStateForTheRequestedPricePage() {
        var brand = new BrandModel("personal brand", null);
        em.persist(brand);
        long first = product(brand.getId(), 1000);
        long second = product(brand.getId(), 2000);
        long third = product(brand.getId(), 3000);
        long a = users.register().getId();
        long b = users.register().getId();
        likes.register(a, first);
        likes.register(a, third);
        likes.register(b, second);
        em.flush();

        var pageA = lists.getList(a, brand.getId(), 0, 2, ProductSort.PRICE_ASC);
        var pageB = lists.getList(b, brand.getId(), 0, 2, ProductSort.PRICE_ASC);
        assertThat(pageA.items()).extracting(item -> item.product().id()).containsExactly(first, second);
        assertThat(pageA.items()).extracting(ProductListItemInfo::isLiked).containsExactly(true, false);
        assertThat(pageB.items()).extracting(ProductListItemInfo::isLiked).containsExactly(false, true);
        assertThat(pageA.items()).extracting(item -> item.product().likeCount()).containsExactly(1L, 1L);
        assertThat(lists.getList(a, brand.getId(), 1, 2, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(true);
        assertThat(lists.getList(b, brand.getId(), 1, 2, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(false);
        assertThat(lists.getList(a, brand.getId(), 2, 2, ProductSort.PRICE_ASC).items()).isEmpty();
        assertThat(lists.getList(null, brand.getId(), 0, 2, ProductSort.PRICE_ASC).items())
            .extracting(ProductListItemInfo::isLiked).containsExactly(false, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrDeletedRequesterReturnsNotFound(boolean deleted) throws Exception {
        long requesterId = Long.MAX_VALUE;
        if (deleted) {
            var user = users.register();
            em.flush();
            requesterId = user.getId();
            jdbc.update("update users set deleted_at=utc_timestamp(6) where id=?", requesterId);
            em.clear();
        }
        mvc.perform(get("/api/v1/products").header("X-USER-ID", requesterId))
            .andExpect(status().isNotFound());
    }

    private long product(Long brandId, long price) {
        var product = new ProductModel(brandId, "personal product", price, 10);
        em.persist(product);
        likes.initializeCount(product.getId());
        return product.getId();
    }
}
