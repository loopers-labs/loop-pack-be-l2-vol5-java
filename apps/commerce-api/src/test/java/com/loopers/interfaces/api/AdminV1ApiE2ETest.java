package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.admin.brand.BrandAdminV1Dto;
import com.loopers.interfaces.api.admin.product.ProductAdminV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminV1ApiE2ETest {

    private static final ParameterizedTypeReference<ApiResponse<BrandAdminV1Dto.BrandResponse>> BRAND_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<ProductAdminV1Dto.ProductResponse>> PRODUCT_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Object>> VOID_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public AdminV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api-admin/v1/brands — 브랜드를 생성하고 저장된 값을 조회한다.")
    @Test
    void createsBrand_andReadsIt() {
        // act
        ResponseEntity<ApiResponse<BrandAdminV1Dto.BrandResponse>> created = testRestTemplate.exchange(
            "/api-admin/v1/brands", HttpMethod.POST,
            new HttpEntity<>(new BrandAdminV1Dto.CreateRequest("새 브랜드")), BRAND_TYPE
        );
        ResponseEntity<ApiResponse<BrandAdminV1Dto.BrandResponse>> read = testRestTemplate.exchange(
            "/api-admin/v1/brands/" + created.getBody().data().id(), HttpMethod.GET,
            new HttpEntity<>(null), BRAND_TYPE
        );

        // assert
        assertAll(
            () -> assertTrue(created.getStatusCode().is2xxSuccessful()),
            () -> assertThat(read.getBody().data().name()).isEqualTo("새 브랜드"),
            () -> assertThat(read.getBody().data().deleted()).isFalse()
        );
    }

    @DisplayName("삭제되지 않은 상품이 연결된 브랜드의 삭제는, 400으로 거절한다. 재고 0인 상품도 포함한다.")
    @Test
    void rejectsBrandDeletion_whenActiveProductExists() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        productJpaRepository.save(new ProductModel(brand.getId(), "재고 0 상품", 10_000L, 0));

        // act
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
            "/api-admin/v1/brands/" + brand.getId(), HttpMethod.DELETE, new HttpEntity<>(null), VOID_TYPE
        );

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(brandJpaRepository.findById(brand.getId()).get().getDeletedAt()).isNull()
        );
    }

    @DisplayName("상품이 모두 삭제된 브랜드는 삭제할 수 있고, 이후 고객 조회에서 404다.")
    @Test
    void deletesBrand_whenAllProductsAreDeleted() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = new ProductModel(brand.getId(), "삭제된 상품", 10_000L, 5);
        product.delete();
        productJpaRepository.save(product);

        // act
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
            "/api-admin/v1/brands/" + brand.getId(), HttpMethod.DELETE, new HttpEntity<>(null), VOID_TYPE
        );
        ResponseEntity<ApiResponse<Object>> customerRead = testRestTemplate.exchange(
            "/api/v1/brands/" + brand.getId(), HttpMethod.GET, new HttpEntity<>(null), VOID_TYPE
        );

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(customerRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
        );
    }

    @DisplayName("존재하지 않거나 삭제된 브랜드를 참조하는 상품 생성은, 400으로 거절한다.")
    @Test
    void rejectsProductCreation_whenBrandIsNotActive() {
        // act
        ResponseEntity<ApiResponse<ProductAdminV1Dto.ProductResponse>> response = testRestTemplate.exchange(
            "/api-admin/v1/products", HttpMethod.POST,
            new HttpEntity<>(new ProductAdminV1Dto.CreateRequest(999_999L, "상품", 10_000L, 5)), PRODUCT_TYPE
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @DisplayName("PUT /api-admin/v1/products/{id} — 이름·가격을 수정하고 브랜드는 유지한다.")
    @Test
    void updatesProduct_keepingBrand() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "이전 이름", 10_000L, 5));

        // act
        ResponseEntity<ApiResponse<ProductAdminV1Dto.ProductResponse>> response = testRestTemplate.exchange(
            "/api-admin/v1/products/" + product.getId(), HttpMethod.PUT,
            new HttpEntity<>(new ProductAdminV1Dto.UpdateRequest("새 이름", 12_000L)), PRODUCT_TYPE
        );

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().name()).isEqualTo("새 이름"),
            () -> assertThat(response.getBody().data().price()).isEqualTo(12_000L),
            () -> assertThat(response.getBody().data().brandId()).isEqualTo(brand.getId())
        );
    }

    @DisplayName("PUT /api-admin/v1/products/{id}/stock — 0 이상의 최종 수량으로 설정하고, 음수는 400으로 거절한다.")
    @Test
    void changesStock_toFinalQuantity() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 10_000L, 5));

        // act
        ResponseEntity<ApiResponse<ProductAdminV1Dto.ProductResponse>> changed = testRestTemplate.exchange(
            "/api-admin/v1/products/" + product.getId() + "/stock", HttpMethod.PUT,
            new HttpEntity<>(new ProductAdminV1Dto.StockRequest(0)), PRODUCT_TYPE
        );
        ResponseEntity<ApiResponse<ProductAdminV1Dto.ProductResponse>> rejected = testRestTemplate.exchange(
            "/api-admin/v1/products/" + product.getId() + "/stock", HttpMethod.PUT,
            new HttpEntity<>(new ProductAdminV1Dto.StockRequest(-1)), PRODUCT_TYPE
        );

        // assert
        assertAll(
            () -> assertTrue(changed.getStatusCode().is2xxSuccessful()),
            () -> assertThat(changed.getBody().data().stock()).isEqualTo(0),
            () -> assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(productJpaRepository.findById(product.getId()).get().getStock()).isEqualTo(0)
        );
    }

    @DisplayName("DELETE /api-admin/v1/products/{id} — 삭제 후 고객 조회는 404, 관리자 조회는 deleted=true다.")
    @Test
    void deletesProduct_softly() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 10_000L, 5));

        // act
        ResponseEntity<ApiResponse<Object>> deleteResponse = testRestTemplate.exchange(
            "/api-admin/v1/products/" + product.getId(), HttpMethod.DELETE, new HttpEntity<>(null), VOID_TYPE
        );
        ResponseEntity<ApiResponse<Object>> customerRead = testRestTemplate.exchange(
            "/api/v1/products/" + product.getId(), HttpMethod.GET, new HttpEntity<>(null), VOID_TYPE
        );
        ResponseEntity<ApiResponse<ProductAdminV1Dto.ProductResponse>> adminRead = testRestTemplate.exchange(
            "/api-admin/v1/products/" + product.getId(), HttpMethod.GET, new HttpEntity<>(null), PRODUCT_TYPE
        );

        // assert
        assertAll(
            () -> assertTrue(deleteResponse.getStatusCode().is2xxSuccessful()),
            () -> assertThat(customerRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(adminRead.getBody().data().deleted()).isTrue()
        );
    }
}
