package com.loopers.interfaces.api.admin.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
class AdminProductApiTest {

    private static final String PRODUCTS = "/api-admin/v1/products";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private Brand saveBrand(String name) {
        return brandRepository.save(Brand.create(name));
    }

    private Brand saveDeletedBrand(String name) {
        Brand brand = Brand.create(name);
        brand.delete();
        return brandRepository.save(brand);
    }

    private Product saveProduct(Long brandId, String name) {
        return productRepository.save(Product.create(brandId, name, new Money(89_000), new Stock(10)));
    }

    private Product saveDeletedProduct(Long brandId, String name) {
        Product product = Product.create(brandId, name, new Money(89_000), new Stock(10));
        product.delete();
        return productRepository.save(product);
    }

    private Product stored(Long productId) {
        return productRepository.findActive(productId).orElseThrow();
    }

    private long storedCount() {
        return productRepository.findAllActive(PageRequest.of(0, 10)).getTotalElements();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(admin()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private String createBody(Long brandId, String name, long price, int stock) {
        return "{\"brandId\":" + brandId + ",\"name\":\"" + name + "\",\"price\":" + price + ",\"stock\":" + stock + "}";
    }

    @DisplayName("POST /api-admin/v1/products")
    @Nested
    class Create {
        @DisplayName("관리자가 등록하면 공백을 제거한 이름으로 저장하고, 재고와 시각을 포함해 응답한다.")
        @Test
        void savesProduct_andReturnsFields() throws Exception {
            Brand brand = saveBrand("루퍼스");

            send(post(PRODUCTS), createBody(brand.getId(), "  러닝화  ", 89_000, 10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.brandId").value(brand.getId()))
                .andExpect(jsonPath("$.data.name").value("러닝화"))
                .andExpect(jsonPath("$.data.price").value(89_000))
                .andExpect(jsonPath("$.data.stock").value(10))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());

            assertThat(storedCount()).isEqualTo(1);
        }

        @DisplayName("필수값(brandId, name, price, stock)이 없으면 400 INVALID_INPUT 이고, 저장하지 않는다.")
        @Test
        void rejectsWithInvalidInput_whenRequiredValueIsMissing() throws Exception {
            Brand brand = saveBrand("루퍼스");
            long id = brand.getId();
            String[] bodies = {
                "{\"name\":\"러닝화\",\"price\":89000,\"stock\":10}",
                "{\"brandId\":" + id + ",\"price\":89000,\"stock\":10}",
                "{\"brandId\":" + id + ",\"name\":\"러닝화\",\"stock\":10}",
                "{\"brandId\":" + id + ",\"name\":\"러닝화\",\"price\":89000}",
                "{}"
            };

            for (String body : bodies) {
                send(post(PRODUCTS), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
            }
            assertThat(storedCount()).isZero();
        }

        @DisplayName("이름이 없으면 브랜드가 존재하지 않아도 404 가 아니라 400 INVALID_INPUT 이다. 필수 누락은 조회 전에 거절한다.")
        @Test
        void rejectsWithInvalidInput_beforeLookup_whenNameIsMissing() throws Exception {
            send(post(PRODUCTS), "{\"brandId\":999,\"price\":89000,\"stock\":10}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
        }

        @DisplayName("숫자가 아니거나 표현 범위를 넘거나 소수인 값이면 400 INVALID_INPUT 이다. 소수점을 버려 저장하지 않는다.")
        @ParameterizedTest
        @ValueSource(strings = {
            "{\"brandId\":1,\"name\":\"러닝화\",\"price\":\"abc\",\"stock\":10}",
            "{\"brandId\":1,\"name\":\"러닝화\",\"price\":89000,\"stock\":3000000000}",
            "{\"brandId\":1,\"name\":\"러닝화\",\"price\":1.5,\"stock\":10}",
            "{\"brandId\":1,\"name\":\"러닝화\",\"price\":89000.9,\"stock\":10}",
            "{\"brandId\":1,\"name\":\"러닝화\",\"price\":89000,\"stock\":2.5}"
        })
        void rejectsWithInvalidInput_whenValueTypeIsInvalid(String body) throws Exception {
            saveBrand("루퍼스");

            send(post(PRODUCTS), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));

            assertThat(storedCount()).isZero();
        }

        @DisplayName("없거나 삭제된 브랜드이면 404 NOT_FOUND 이고, 저장하지 않는다.")
        @Test
        void returnsNotFound_whenBrandDoesNotExistOrIsDeleted() throws Exception {
            Brand deleted = saveDeletedBrand("삭제됨");

            send(post(PRODUCTS), createBody(999L, "러닝화", 89_000, 10))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.meta.message").value("존재하지 않는 브랜드입니다."));
            send(post(PRODUCTS), createBody(deleted.getId(), "러닝화", 89_000, 10))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("NOT_FOUND"));

            assertThat(storedCount()).isZero();
        }
    }

    @DisplayName("GET /api-admin/v1/products/{productId}")
    @Nested
    class GetDetail {
        @DisplayName("관리자가 조회하면 브랜드·이름·가격·재고와 시각을 응답한다.")
        @Test
        void returnsProduct() throws Exception {
            Product product = saveProduct(3L, "러닝화");

            mvc.perform(get(PRODUCTS + "/" + product.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(product.getId()))
                .andExpect(jsonPath("$.data.brandId").value(3))
                .andExpect(jsonPath("$.data.name").value("러닝화"))
                .andExpect(jsonPath("$.data.price").value(89_000))
                .andExpect(jsonPath("$.data.stock").value(10))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());
        }

        @DisplayName("없거나 삭제된 상품이면 404 NOT_FOUND 이다.")
        @Test
        void returnsNotFound_whenProductDoesNotExistOrIsDeleted() throws Exception {
            Product deleted = saveDeletedProduct(1L, "삭제됨");

            mvc.perform(get(PRODUCTS + "/999").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.meta.message").value("존재하지 않는 상품입니다."));
            mvc.perform(get(PRODUCTS + "/" + deleted.getId()).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("NOT_FOUND"));
        }
    }

    @DisplayName("GET /api-admin/v1/products")
    @Nested
    class GetList {
        @DisplayName("조건 없이 조회하면 첫 페이지를 20개 단위로, 삭제된 상품을 빼고 최신순으로 응답한다.")
        @Test
        void returnsDefaultPage_withoutDeleted_newestFirst() throws Exception {
            Product first = saveProduct(1L, "첫째");
            saveDeletedProduct(1L, "삭제됨");
            Product third = saveProduct(2L, "셋째");

            mvc.perform(get(PRODUCTS).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(third.getId()))
                .andExpect(jsonPath("$.data.content[1].id").value(first.getId()))
                .andExpect(jsonPath("$.data.content[0].stock").value(10));
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}")
    @Nested
    class Update {
        @DisplayName("관리자가 수정하면 이름과 가격이 바뀌고, 브랜드와 재고는 그대로 저장된다.")
        @Test
        void changesNameAndPrice_andPersists() throws Exception {
            Product product = saveProduct(3L, "러닝화");

            send(put(PRODUCTS + "/" + product.getId()), "{\"name\":\"  새 러닝화 \",\"price\":99000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새 러닝화"))
                .andExpect(jsonPath("$.data.price").value(99_000));

            Product found = stored(product.getId());
            assertThat(found.getName()).isEqualTo("새 러닝화");
            assertThat(found.getPrice()).isEqualTo(new Money(99_000));
            assertThat(found.getBrandId()).isEqualTo(3L);
            assertThat(found.getStock()).isEqualTo(new Stock(10));
        }

        @DisplayName("이름이 없으면 상품이 존재하지 않아도 404 가 아니라 400 INVALID_INPUT 이다. 필수 누락은 조회 전에 거절한다.")
        @Test
        void rejectsWithInvalidInput_beforeLookup_whenNameIsMissing() throws Exception {
            send(put(PRODUCTS + "/999"), "{\"price\":99000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}/stock")
    @Nested
    class ChangeStock {
        @DisplayName("관리자가 최종 수량을 지정하면 재고가 바뀌고, 변경된 상품을 응답한다. 0도 허용한다.")
        @ParameterizedTest
        @ValueSource(ints = {0, 25})
        void changesStock_andPersists(int stock) throws Exception {
            Product product = saveProduct(1L, "러닝화");

            send(put(PRODUCTS + "/" + product.getId() + "/stock"), "{\"stock\":" + stock + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(product.getId()))
                .andExpect(jsonPath("$.data.stock").value(stock));

            assertThat(stored(product.getId()).getStock()).isEqualTo(new Stock(stock));
        }
    }

    @DisplayName("DELETE /api-admin/v1/products/{productId}")
    @Nested
    class Delete {
        @DisplayName("관리자가 삭제하면 200 성공으로 응답하고, 이후 조회되지 않는다.")
        @Test
        void deletesProduct() throws Exception {
            Product product = saveProduct(1L, "러닝화");

            mvc.perform(delete(PRODUCTS + "/" + product.getId()).with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"));

            mvc.perform(get(PRODUCTS + "/" + product.getId()).with(admin()))
                .andExpect(status().isNotFound());
            assertThat(storedCount()).isZero();
        }
    }

    @DisplayName("관리자 경계")
    @Nested
    class Boundary {
        @DisplayName("관리자 역할이 없거나 식별 없는 요청은 403 FORBIDDEN 이고, DB 를 바꾸지 않는다.")
        @Test
        void rejectsWithForbidden_andKeepsDatabase() throws Exception {
            Brand brand = saveBrand("루퍼스");
            Product product = saveProduct(brand.getId(), "러닝화");
            String body = createBody(brand.getId(), "새 상품", 1_000, 1);

            mvc.perform(post(PRODUCTS).with(user("customer").roles("USER")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"));
            mvc.perform(put(PRODUCTS + "/" + product.getId() + "/stock").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"stock\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"));
            mvc.perform(delete(PRODUCTS + "/" + product.getId()).with(csrf()))
                .andExpect(status().isForbidden());

            assertThat(storedCount()).isEqualTo(1);
            assertThat(stored(product.getId()).getStock()).isEqualTo(new Stock(10));
        }
    }
}
