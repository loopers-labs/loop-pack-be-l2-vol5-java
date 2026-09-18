package com.loopers.interfaces.api.admin.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

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
class AdminBrandV1ApiE2ETest {

    private static final String ENDPOINT = "/api-admin/v1/brands";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");
    private static final RequestPostProcessor CUSTOMER = user("customer").roles("USER");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
        return brandJpaRepository.save(new Brand(name, "설명"));
    }

    private void saveProduct(Brand brand) {
        transactionTemplate.executeWithoutResult(status ->
            entityManager.persist(new Product(entityManager.find(Brand.class, brand.getId()), "상품", 1_000L))
        );
    }

    @DisplayName("관리자 접근을 확인할 때, ")
    @Nested
    class Access {
        @DisplayName("관리자는 허용하고, 일반 사용자와 식별 없는 요청은 403 으로 거절한다.")
        @Test
        void allowsAdminOnly() throws Exception {
            mvc.perform(get(ENDPOINT).with(ADMIN)).andExpect(status().isOk());
            mvc.perform(get(ENDPOINT).with(CUSTOMER)).andExpect(status().isForbidden());
            mvc.perform(get(ENDPOINT)).andExpect(status().isForbidden());
        }

        @DisplayName("유효한 CSRF 입력이 있어도 일반 사용자의 등록은 403 으로 거절하고 브랜드가 만들어지지 않는다.")
        @Test
        void rejectsCustomerCreate_evenWithCsrf() throws Exception {
            mvc.perform(withBody(post(ENDPOINT), Map.of("name", "브랜드")).with(CUSTOMER).with(csrf()))
                .andExpect(status().isForbidden());

            assertThat(brandJpaRepository.count()).isZero();
        }
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    class Create {
        @DisplayName("유효한 이름이면, 브랜드를 만들고 돌려준다.")
        @Test
        void createsBrand() throws Exception {
            mvc.perform(withBody(post(ENDPOINT), Map.of("name", "브랜드", "description", "설명")).with(ADMIN).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.name").value("브랜드"))
                .andExpect(jsonPath("$.data.description").value("설명"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());

            assertThat(brandJpaRepository.count()).isEqualTo(1);
        }

        @DisplayName("이름이 공백뿐이면, 400 과 INVALID_BRAND_NAME 을 돌려주고 브랜드가 만들어지지 않는다.")
        @Test
        void rejectsBlankName() throws Exception {
            mvc.perform(withBody(post(ENDPOINT), Map.of("name", "   ")).with(ADMIN).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_BRAND_NAME"));

            assertThat(brandJpaRepository.count()).isZero();
        }
    }

    @DisplayName("GET /api-admin/v1/brands")
    @Nested
    class GetList {
        @DisplayName("삭제되지 않은 브랜드를 페이지 정보와 함께 돌려준다.")
        @Test
        void returnsActiveBrandsWithPageInfo() throws Exception {
            // arrange
            saveBrand("브랜드 A");
            Brand deleted = saveBrand("브랜드 B");
            mvc.perform(delete(ENDPOINT + "/" + deleted.getId()).with(ADMIN).with(csrf())).andExpect(status().isOk());

            // act & assert
            mvc.perform(get(ENDPOINT).param("page", "1").param("size", "10").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("브랜드 A"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
        }

        @DisplayName("page 가 1 미만이거나 size 가 1~100 을 벗어나면, 400 으로 거절한다.")
        @ParameterizedTest
        @CsvSource({"0, 20", "1, 0", "1, 101"})
        void rejectsInvalidPaging(String page, String size) throws Exception {
            mvc.perform(get(ENDPOINT).param("page", page).param("size", size).with(ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));
        }
    }

    @DisplayName("PUT /api-admin/v1/brands/{brandId}")
    @Nested
    class Update {
        @DisplayName("유효한 값이면, 수정된 브랜드를 돌려주고 저장한다.")
        @Test
        void updatesBrand() throws Exception {
            // arrange
            Brand brand = saveBrand("브랜드");

            // act & assert
            mvc.perform(withBody(put(ENDPOINT + "/" + brand.getId()), Map.of("name", "새 브랜드", "description", "새 설명"))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새 브랜드"));

            assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getName()).isEqualTo("새 브랜드");
        }

        @DisplayName("없는 브랜드면, 404 와 BRAND_NOT_FOUND 를 돌려준다.")
        @Test
        void returnsNotFound_whenBrandDoesNotExist() throws Exception {
            mvc.perform(withBody(put(ENDPOINT + "/999"), Map.of("name", "브랜드")).with(ADMIN).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        }
    }

    @DisplayName("DELETE /api-admin/v1/brands/{brandId}")
    @Nested
    class Delete {
        @DisplayName("삭제되지 않은 상품이 남아 있으면, 409 와 BRAND_HAS_PRODUCTS 를 돌려주고 브랜드는 그대로 조회된다.")
        @Test
        void rejects_whenActiveProductRemains() throws Exception {
            // arrange
            Brand brand = saveBrand("브랜드");
            saveProduct(brand);

            // act & assert
            mvc.perform(delete(ENDPOINT + "/" + brand.getId()).with(ADMIN).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_HAS_PRODUCTS"));

            mvc.perform(get(ENDPOINT + "/" + brand.getId()).with(ADMIN)).andExpect(status().isOk());
        }

        @DisplayName("삭제한 브랜드는 상세 조회와 다시 삭제에서 404 와 BRAND_NOT_FOUND 를 돌려준다.")
        @Test
        void deletedBrandIsNotFound() throws Exception {
            // arrange
            Brand brand = saveBrand("브랜드");

            // act
            mvc.perform(delete(ENDPOINT + "/" + brand.getId()).with(ADMIN).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"));

            // assert
            mvc.perform(get(ENDPOINT + "/" + brand.getId()).with(ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
            mvc.perform(delete(ENDPOINT + "/" + brand.getId()).with(ADMIN).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        }
    }
}
