package com.loopers.interfaces.api.admin.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
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
class AdminProductV1ApiE2ETest {

    private static final String ENDPOINT = "/api-admin/v1/products";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");
    private static final RequestPostProcessor CUSTOMER = user("customer").roles("USER");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private Brand saveBrand(String name) {
        return brandJpaRepository.save(new Brand(name, null));
    }

    private Product saveProduct(Brand brand, String name) {
        return productJpaRepository.save(new Product(brand, name, 1_000L));
    }

    private Product reload(Product product) {
        return productJpaRepository.findById(product.getId()).orElseThrow();
    }

    @DisplayName("일반 사용자와 식별 없는 요청은 403 으로 거절한다.")
    @Test
    void rejectsNonAdmin() throws Exception {
        Brand brand = saveBrand("브랜드");
        mvc.perform(get(ENDPOINT).with(CUSTOMER)).andExpect(status().isForbidden());
        mvc.perform(get(ENDPOINT)).andExpect(status().isForbidden());
        mvc.perform(withBody(post(ENDPOINT), Map.of("brandId", brand.getId(), "name", "상품", "price", 1_000))
                .with(CUSTOMER).with(csrf()))
            .andExpect(status().isForbidden());

        assertThat(productJpaRepository.count()).isZero();
    }

    @DisplayName("POST /api-admin/v1/products")
    @Nested
    class Create {
        @DisplayName("유효한 입력이면, 재고 0 인 상품을 브랜드 정보와 함께 돌려준다.")
        @Test
        void createsProductWithZeroStock() throws Exception {
            Brand brand = saveBrand("브랜드");

            mvc.perform(withBody(post(ENDPOINT), Map.of("brandId", brand.getId(), "name", "상품", "price", 1_000))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("상품"))
                .andExpect(jsonPath("$.data.price").value(1_000))
                .andExpect(jsonPath("$.data.stock").value(0))
                .andExpect(jsonPath("$.data.brand.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.brand.name").value("브랜드"));
        }

        @DisplayName("없는 브랜드면, 404 와 BRAND_NOT_FOUND 를 돌려주고 상품이 만들어지지 않는다.")
        @Test
        void returnsBrandNotFound_whenBrandDoesNotExist() throws Exception {
            mvc.perform(withBody(post(ENDPOINT), Map.of("brandId", 999, "name", "상품", "price", 1_000))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));

            assertThat(productJpaRepository.count()).isZero();
        }

        @DisplayName("가격이 빠지면, 0 원으로 채우지 않고 400 과 BAD_REQUEST 를 돌려준다.")
        @Test
        void returnsBadRequest_whenPriceIsMissing() throws Exception {
            Brand brand = saveBrand("브랜드");

            mvc.perform(withBody(post(ENDPOINT), Map.of("brandId", brand.getId(), "name", "상품"))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));

            assertThat(productJpaRepository.count()).isZero();
        }

        @DisplayName("가격이 소수면, 소수점을 버려 받지 않고 400 과 BAD_REQUEST 를 돌려준다.")
        @Test
        void returnsBadRequest_whenPriceIsFractional() throws Exception {
            Brand brand = saveBrand("브랜드");

            mvc.perform(withBody(post(ENDPOINT), Map.of("brandId", brand.getId(), "name", "상품", "price", 1_000.5))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));

            assertThat(productJpaRepository.count()).isZero();
        }

        @DisplayName("가격이 범위를 벗어나면, 400 과 INVALID_PRICE 를 돌려준다.")
        @Test
        void returnsInvalidPrice_whenPriceIsOutOfRange() throws Exception {
            Brand brand = saveBrand("브랜드");

            mvc.perform(withBody(post(ENDPOINT), Map.of("brandId", brand.getId(), "name", "상품", "price", -1))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_PRICE"));
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}")
    @Nested
    class Update {
        @DisplayName("본문에 다른 brandId 가 있어도 무시하고, 이름과 가격만 바꾼다. (PRD-03)")
        @Test
        void ignoresBrandId() throws Exception {
            Brand brand = saveBrand("브랜드");
            Brand other = saveBrand("다른 브랜드");
            Product product = saveProduct(brand, "상품");

            mvc.perform(withBody(put(ENDPOINT + "/" + product.getId()),
                        Map.of("brandId", other.getId(), "name", "새 상품", "price", 2_000))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새 상품"))
                .andExpect(jsonPath("$.data.price").value(2_000))
                .andExpect(jsonPath("$.data.brand.id").value(brand.getId()));
        }

        @DisplayName("삭제된 상품이면, 404 와 PRODUCT_NOT_FOUND 를 돌려주고 기존 값이 유지된다.")
        @Test
        void returnsNotFound_whenProductIsDeleted() throws Exception {
            Product product = saveProduct(saveBrand("브랜드"), "상품");
            mvc.perform(delete(ENDPOINT + "/" + product.getId()).with(ADMIN).with(csrf())).andExpect(status().isOk());

            mvc.perform(withBody(put(ENDPOINT + "/" + product.getId()), Map.of("name", "새 상품", "price", 2_000))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));

            assertThat(reload(product).getName()).isEqualTo("상품");
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}/stock")
    @Nested
    class ChangeStock {
        @DisplayName("0 이상이면, 최종 수량으로 설정하고 저장한다.")
        @Test
        void setsFinalStock() throws Exception {
            Product product = saveProduct(saveBrand("브랜드"), "상품");

            mvc.perform(withBody(put(ENDPOINT + "/" + product.getId() + "/stock"), Map.of("stock", 30))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(30));

            assertThat(reload(product).getStock()).isEqualTo(30);
        }

        @DisplayName("음수면, 400 과 INVALID_STOCK 을 돌려주고 기존 재고가 유지된다.")
        @Test
        void rejectsNegativeStock() throws Exception {
            Product product = saveProduct(saveBrand("브랜드"), "상품");
            mvc.perform(withBody(put(ENDPOINT + "/" + product.getId() + "/stock"), Map.of("stock", 5))
                .with(ADMIN).with(csrf())).andExpect(status().isOk());

            mvc.perform(withBody(put(ENDPOINT + "/" + product.getId() + "/stock"), Map.of("stock", -1))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_STOCK"));

            assertThat(reload(product).getStock()).isEqualTo(5);
        }

        @DisplayName("소수면, 소수점을 버려 받지 않고 400 과 BAD_REQUEST 를 돌려주고 기존 재고가 유지된다.")
        @Test
        void rejectsFractionalStock() throws Exception {
            Product product = saveProduct(saveBrand("브랜드"), "상품");

            mvc.perform(withBody(put(ENDPOINT + "/" + product.getId() + "/stock"), Map.of("stock", 1.5))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));

            assertThat(reload(product).getStock()).isZero();
        }

        @DisplayName("삭제된 상품이면, 404 와 PRODUCT_NOT_FOUND 를 돌려준다. (PRD-04)")
        @Test
        void returnsNotFound_whenProductIsDeleted() throws Exception {
            Product product = saveProduct(saveBrand("브랜드"), "상품");
            mvc.perform(delete(ENDPOINT + "/" + product.getId()).with(ADMIN).with(csrf())).andExpect(status().isOk());

            mvc.perform(withBody(put(ENDPOINT + "/" + product.getId() + "/stock"), Map.of("stock", 10))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        }
    }

    @DisplayName("GET /api-admin/v1/products")
    @Nested
    class GetList {
        @DisplayName("brandId 로 거르면, 그 브랜드의 삭제되지 않은 상품만 돌려준다.")
        @Test
        void filtersByBrand() throws Exception {
            Brand brand = saveBrand("브랜드");
            Brand other = saveBrand("다른 브랜드");
            saveProduct(brand, "상품");
            saveProduct(other, "다른 상품");

            mvc.perform(get(ENDPOINT).param("brandId", String.valueOf(brand.getId())).with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("상품"))
                .andExpect(jsonPath("$.data.totalCount").value(1));
        }

        @DisplayName("없는 브랜드로 거르면, 빈 목록이 아니라 404 와 BRAND_NOT_FOUND 를 돌려준다. (D-24)")
        @Test
        void returnsBrandNotFound_whenFilterBrandDoesNotExist() throws Exception {
            mvc.perform(get(ENDPOINT).param("brandId", "999").with(ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        }
    }

    @DisplayName("상품을 삭제하면, 상세 조회는 404 이고 이미 삭제된 상품의 삭제도 404 다.")
    @Test
    void deletedProductIsNotFound() throws Exception {
        Product product = saveProduct(saveBrand("브랜드"), "상품");

        mvc.perform(delete(ENDPOINT + "/" + product.getId()).with(ADMIN).with(csrf())).andExpect(status().isOk());

        assertAll(
            () -> mvc.perform(get(ENDPOINT + "/" + product.getId()).with(ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND")),
            () -> mvc.perform(delete(ENDPOINT + "/" + product.getId()).with(ADMIN).with(csrf()))
                .andExpect(status().isNotFound()),
            () -> assertThat(reload(product).isDeleted()).isTrue()
        );
    }
}
