package com.loopers.interfaces.api.product;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.like.LikeApplicationService;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductLikeIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private LikeApplicationService likes;
    @Autowired private UserJpaRepository users;
    @Autowired private DatabaseCleanUp cleanup;
    private long brandId;

    @BeforeEach
    void prepare() {
        users.save(new UserJpaEntity(1));
        users.save(new UserJpaEntity(2));
        brandId = brands.create("브랜드").id().value();
    }
    @AfterEach
    void clean() { cleanup.truncateAllTables(); }

    @Test
    @DisplayName("중복 좋아요는 한 관계이며 삭제 상품은 내 목록에서 제외되지만 취소할 수 있다")
    void registersAndCancels() throws Exception {
        long id = products.create(brandId, "상품", 100, 3).id();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/products/{id}/likes", id).header("X-USER-ID", "1")).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/products/{id}", id).header("X-USER-ID", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(1))
            .andExpect(jsonPath("$.data.brandName").value("브랜드"));
        products.delete(id);
        mvc.perform(get("/api/v1/users/1/likes").header("X-USER-ID", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/api/v1/products/{id}/likes", id).header("X-USER-ID", "1")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/products/{id}/likes", id).header("X-USER-ID", "1")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/products/{id}/likes", id).header("X-USER-ID", "1")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("좋아요 순은 전체 후보에서 정렬 후 페이지를 자르고 가격 동률은 ID 내림차순이다")
    void sortsBeforePaging() throws Exception {
        long first = products.create(brandId, "첫 상품", 100, 1).id();
        long second = products.create(brandId, "둘째", 100, 1).id();
        likes.register(1, first);
        likes.register(2, first);
        mvc.perform(get("/api/v1/products").header("X-USER-ID", "1").param("sort", "likes_desc").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(first));
        mvc.perform(get("/api/v1/products").header("X-USER-ID", "1").param("sort", "price_asc").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(second));
        mvc.perform(get("/api/v1/products").header("X-USER-ID", "1").param("sort", "unknown"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/2/likes").header("X-USER-ID", "1")).andExpect(status().isConflict());
    }
}
