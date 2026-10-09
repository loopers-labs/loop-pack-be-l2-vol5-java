package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.product.Price;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BrandAdminV1ApiTest {

    private static final String ADMIN = "/api-admin/v1/brands";

    private final MockMvc mvc;
    private final ObjectMapper objectMapper;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    @Autowired
    BrandAdminV1ApiTest(
        MockMvc mvc,
        ObjectMapper objectMapper,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade
    ) {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.with(user("admin").roles("ADMIN")).with(csrf());
    }

    private String body(String json) {
        return json;
    }

    @Nested
    @DisplayName("등록")
    class Register {
        @DisplayName("등록하면 201 과 id 가 담긴 본문을 돌려준다")
        @Test
        void creates() throws Exception {
            String response = mvc.perform(asAdmin(post(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{\"name\":\"무신사\",\"description\":\"패션 플랫폼\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("무신사"))
                .andReturn().getResponse().getContentAsString();

            JsonNode data = objectMapper.readTree(response).get("data");
            assertThat(data.get("id").asLong()).isPositive();
        }

        @DisplayName("BRAND-001 · 이름이 공백뿐이면 400 이다. 도메인까지 가지 않는다")
        @Test
        void rejectsBlankName() throws Exception {
            mvc.perform(asAdmin(post(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{\"name\":\" \",\"description\":\"설명\"}")))
                .andExpect(status().isBadRequest());
        }

        @DisplayName("BRAND-002 · 설명은 없어도 등록된다")
        @Test
        void allowsMissingDescription() throws Exception {
            mvc.perform(asAdmin(post(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{\"name\":\"무신사\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.description").doesNotExist());
        }
    }

    @Nested
    @DisplayName("수정 · 삭제")
    class Modify {
        @DisplayName("수정하면 200 과 바뀐 값을 돌려준다")
        @Test
        void updates() throws Exception {
            Long id = brandFacade.register("무신사", "패션 플랫폼").getId();

            mvc.perform(asAdmin(put(ADMIN + "/" + id))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{\"name\":\"29CM\",\"description\":\"셀렉트샵\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("29CM"));
        }

        @DisplayName("BRAND-005 · 삭제된 브랜드의 수정은 404 다")
        @Test
        void rejectsUpdateOfDeleted() throws Exception {
            Long id = brandFacade.register("무신사", "패션 플랫폼").getId();
            brandFacade.delete(id);

            mvc.perform(asAdmin(put(ADMIN + "/" + id))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("{\"name\":\"29CM\",\"description\":\"셀렉트샵\"}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        }

        @DisplayName("삭제하면 204 이고 본문이 없다")
        @Test
        void deletes() throws Exception {
            Long id = brandFacade.register("무신사", "패션 플랫폼").getId();

            mvc.perform(asAdmin(delete(ADMIN + "/" + id)))
                .andExpect(status().isNoContent());
        }

        @DisplayName("BRAND-004 · 상품이 연결된 브랜드도 204 로 삭제되고, 그 상품은 고객 상세에서 404 다")
        @Test
        void deletesWithProducts() throws Exception {
            Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
            Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();

            mvc.perform(asAdmin(delete(ADMIN + "/" + brandId)))
                .andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/products/" + productId).header("X-USER-ID", 1L))
                .andExpect(status().isNotFound());
        }

        @DisplayName("BRAND-004 · 일반 사용자와 식별 없는 요청의 삭제는 403 이고, 브랜드와 상품은 그대로 남는다")
        @Test
        void rejectsNonAdminDeleteWithoutChanges() throws Exception {
            Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
            Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();

            mvc.perform(delete(ADMIN + "/" + brandId).with(user("customer").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
            mvc.perform(delete(ADMIN + "/" + brandId))
                .andExpect(status().isForbidden());

            mvc.perform(get("/api/v1/brands/" + brandId))
                .andExpect(status().isOk());
            mvc.perform(get("/api/v1/products/" + productId).header("X-USER-ID", 1L))
                .andExpect(status().isOk());
        }
    }
}
