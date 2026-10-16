package com.loopers.application.like;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductQueryService;
import com.loopers.application.product.ProductSort;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LikeCounterIntegrationTest {
    @Autowired private ProductFacade products;
    @Autowired private ProductQueryService query;
    @Autowired private LikeFacade likes;
    @Autowired private UserRegistrationService users;
    @Autowired private BrandJpaRepository brands;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private MockMvc mvc;

    @AfterEach
    void clean() { cleanUp.truncateAllTables(); }

    @Test
    void createsZeroCounterWithProductAndRollsBothBackTogether() {
        long product = product();
        assertThat(count(product)).isZero();
        long brand = brands.save(new BrandModel("brand", null)).getId();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            long id = products.create(brand, "rolled back", 1000, 5).id();
            assertThat(count(id)).isZero();
            throw new IllegalStateException("after counter initialization");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_like_count", Long.class)).isEqualTo(1);
    }

    @Test
    void preservesHttpStatusesAndAllCustomerCountViews() throws Exception {
        long first = product();
        long second = product();
        long user = users.register().getId();
        mvc.perform(post("/api/v1/products/{id}/likes", second).header("X-USER-ID", user))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/products/{id}/likes", second).header("X-USER-ID", user))
            .andExpect(status().isOk());
        assertThat(count(second)).isEqualTo(1);
        mvc.perform(get("/api/v1/products/{id}", second)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.likeCount").value(1));
        mvc.perform(get("/api/v1/users/{id}/likes", user).header("X-USER-ID", user))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].likeCount").value(1));
        assertThat(query.getList(null, 0, 20, ProductSort.LIKES_DESC).items())
            .extracting(item -> item.id()).containsExactly(second, first);
        assertThat(query.getList(null, 0, 20, ProductSort.PRICE_ASC).items())
            .extracting(item -> item.likeCount()).containsExactly(1L, 0L);
        mvc.perform(delete("/api/v1/products/{id}/likes", second).header("X-USER-ID", user))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/products/{id}/likes", second).header("X-USER-ID", user))
            .andExpect(status().isNoContent());
        assertThat(count(second)).isZero();
        assertConsistent();
    }

    @Test
    void missingCounterRollsBackNewRelationshipAndDoesNotMasqueradeAsZero() {
        long product = product();
        long user = users.register().getId();
        jdbc.update("DELETE FROM product_like_count WHERE product_id=?", product);
        assertThatThrownBy(() -> likes.register(user, product)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("카운터");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_like", Long.class)).isZero();
        assertThatThrownBy(() -> query.get(product)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("카운터");
    }

    @Test
    void invalidCounterRollsBackCancellationRatherThanLosingRelationship() {
        long product = product();
        long user = users.register().getId();
        likes.register(user, product);
        jdbc.update("UPDATE product_like_count SET like_count=0 WHERE product_id=?", product);
        assertThatThrownBy(() -> likes.cancel(user, product)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("카운터");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_like", Long.class)).isEqualTo(1);
        assertThat(count(product)).isZero();
    }

    @Test
    void registrationAndCancellationParticipateInOuterRollback() {
        long product = product();
        long user = users.register().getId();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            likes.register(user, product);
            assertThat(count(product)).isEqualTo(1);
            throw new IllegalStateException("after register");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(product)).isZero();
        likes.register(user, product);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            likes.cancel(user, product);
            assertThat(count(product)).isZero();
            throw new IllegalStateException("after cancel");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(product)).isEqualTo(1);
        assertConsistent();
    }

    @Test
    void deletedProductRetainsCounterUntilOwnerCancelsHiddenRelationship() {
        long product = product();
        long user = users.register().getId();
        likes.register(user, product);
        products.delete(product);
        assertThat(likes.getUserLikes(user, user)).isEmpty();
        assertThat(count(product)).isEqualTo(1);
        likes.cancel(user, product);
        assertThat(count(product)).isZero();
        assertConsistent();
    }

    @Test
    void offlineBackfillIncludesZeroAndDeletedProductsAndIsIdempotent() throws Exception {
        long empty = product();
        long deleted = product();
        long user = users.register().getId();
        products.delete(deleted);
        // Reproduce the old schema state: relationships exist but no counters have been filled.
        jdbc.update("DELETE FROM product_like_count");
        jdbc.update("INSERT INTO product_like (user_id,product_id,created_at,updated_at) "
            + "VALUES (?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", user, deleted);
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("settings.gradle.kts"))) { root = root.getParent(); }
        String sql = Files.readString(root.resolve("scripts/sql/week4-20-like-counter-backfill.sql"))
            .replaceAll("(?m)^--.*$", "");
        for (int repeat = 0; repeat < 2; repeat++) {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                try (var statement = connection.createStatement()) {
                    for (String block : sql.split(";")) {
                        if (!block.isBlank()) { statement.execute(block); }
                    }
                } finally {
                    try (var statement = connection.createStatement()) { statement.execute("UNLOCK TABLES"); }
                }
                return null;
            });
            assertThat(count(empty)).isZero();
            assertThat(count(deleted)).isEqualTo(1);
            assertConsistent();
        }
        likes.cancel(user, deleted);
        assertThat(count(deleted)).isZero();
    }

    private long product() {
        long brand = brands.save(new BrandModel("brand", null)).getId();
        return products.create(brand, "product", 1000, 5).id();
    }

    private long count(long product) {
        return jdbc.queryForObject("SELECT like_count FROM product_like_count WHERE product_id=?", Long.class, product);
    }

    private void assertConsistent() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_like_count c LEFT JOIN "
            + "(SELECT product_id,COUNT(*) n FROM product_like GROUP BY product_id) l ON l.product_id=c.product_id "
            + "WHERE c.like_count <> COALESCE(l.n,0)", Long.class)).isZero();
    }
}
