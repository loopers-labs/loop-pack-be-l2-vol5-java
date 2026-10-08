package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.DeleteBrandFacade;
import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.product.DeleteProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.interfaces.api.brand.BrandDto;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandApiE2ETest {
    @MockitoSpyBean private BrandRepository brands;
    @Autowired private BrandFixture brandFixture;
    @Autowired private ProductFixture productFixture;
    @Autowired private DeleteBrandFacade deleteBrand;
    @Autowired private DeleteProductFacade deleteProduct;

    @Autowired private TestRestTemplate rest;

    @Autowired private MockMvc mvc;

    @Autowired private ObjectMapper mapper;

    @Autowired private DatabaseCleanUp cleanUp;

    private static final String ADMIN_BRANDS = "/api-admin/v1/brands";

    @Test
    void 브랜드_등록_결과와_저장한_이름이_일치한다() throws Exception {
        // arrange
        BrandDto.Request input = new BrandDto.Request(" 브랜드 ");
        var request =
                post(ADMIN_BRANDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("브랜드"));
        long brandId =
                mapper.readTree(response.andReturn().getResponse().getContentAsByteArray())
                        .requiredAt("/data/brandId")
                        .longValue();
        assertThat(brandFixture.brand(brandId).getName()).isEqualTo("브랜드");
    }

    @Test
    void 고객은_식별_없이_브랜드_이름을_조회한다() {
        // arrange
        Brand brand = brandFixture.createBrand();

        // act
        var response = rest.getForEntity("/api/v1/brands/" + brand.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().requiredAt("/data/name").asText()).isEqualTo("브랜드");
        assertThat(response.getBody().requiredAt("/data").has("deleted")).isFalse();
    }

    @Test
    void 브랜드_수정_결과를_저장한다() throws Exception {
        // arrange
        Brand brand = brandFixture.createBrand("기존");
        BrandDto.Request input = new BrandDto.Request("변경");
        var request =
                put(ADMIN_BRANDS + "/" + brand.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("변경"));
        assertThat(brandFixture.brand(brand.getId()).getName()).isEqualTo("변경");
    }

    @Test
    void 브랜드_삭제는_행을_보존하고_삭제_상태를_저장한다() throws Exception {
        // arrange
        Brand brand = brandFixture.createBrand();
        var request =
                delete(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(brandFixture.brand(brand.getId()).isDeleted()).isTrue();
    }

    @Test
    void 삭제된_브랜드는_고객_상세_조회를_거절한다() {
        // arrange
        Brand brand = brandFixture.createBrand();
        deleteBrand.delete(brand.getId());

        // act
        var response = rest.getForEntity("/api/v1/brands/" + brand.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("BRAND_NOT_FOUND");
    }

    @Test
    void 관리자는_삭제한_브랜드_상세를_조회한다() throws Exception {
        // arrange
        Brand brand = brandFixture.createBrand();
        deleteBrand.delete(brand.getId());
        var request =
                get(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void 관리자_목록에는_삭제한_브랜드가_포함된다() throws Exception {
        // arrange
        Brand brand = brandFixture.createBrand();
        deleteBrand.delete(brand.getId());
        var request = get(ADMIN_BRANDS).with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].deleted").value(true));
    }

    @Test
    void 이미_삭제한_브랜드의_삭제_재요청은_성공한다() throws Exception {
        // arrange
        Brand brand = brandFixture.createBrand();
        deleteBrand.delete(brand.getId());
        var request =
                delete(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk());
        assertThat(brandFixture.brand(brand.getId()).isDeleted()).isTrue();
    }

    @Test
    void 삭제한_브랜드의_수정은_이름을_바꾸지_않는다() throws Exception {
        // arrange
        Brand brand = brandFixture.createBrand();
        deleteBrand.delete(brand.getId());
        BrandDto.Request input = new BrandDto.Request("변경");
        var request =
                put(ADMIN_BRANDS + "/" + brand.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        assertThat(brandFixture.brand(brand.getId()).getName()).isEqualTo("브랜드");
    }

    @Test
    void 브랜드를_삭제하면_재고가_0인_연결_상품도_함께_삭제한다() throws Exception {
        // arrange
        Brand brand = brandFixture.createBrand();
        Product product = productFixture.createProduct(brand.getId(), "상품", 2_000, 0);
        var request =
                delete(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk());
        assertThat(brandFixture.brand(brand.getId()).isDeleted()).isTrue();
        assertThat(productFixture.product(product.getId()).isDeleted()).isTrue();
    }

    @Test
    void 연결_상품이_모두_삭제되면_브랜드를_삭제할_수_있다() throws Exception {
        // arrange
        Brand brand = brandFixture.createBrand();
        Product product = productFixture.createProduct(brand.getId(), "상품", 2_000, 0);
        deleteProduct.delete(product.getId());
        var request =
                delete(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk());
        assertThat(brandFixture.brand(brand.getId()).isDeleted()).isTrue();
    }

    @Test
    void 브랜드가_없으면_관리자_목록은_빈_배열이다() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS).with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 미식별_요청은_관리자_목록을_조회할_수_없다() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void 일반_사용자는_관리자_목록을_조회할_수_없다() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS).with(csrf()).with(user("customer").roles("USER"));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void CSRF가_있어도_미식별_요청은_브랜드를_등록할_수_없다() throws Exception {
        // arrange
        var request =
                post(ADMIN_BRANDS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BrandDto.Request("거절")));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
        assertThat(brandFixture.rowCount()).isZero();
    }

    @Test
    void CSRF가_있어도_일반_사용자는_브랜드를_등록할_수_없다() throws Exception {
        // arrange
        var request =
                post(ADMIN_BRANDS)
                        .with(csrf())
                        .with(user("customer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BrandDto.Request("거절")));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
        assertThat(brandFixture.rowCount()).isZero();
    }

    @Test
    void 관리자라도_CSRF가_없으면_브랜드_등록을_거절한다() throws Exception {
        // arrange
        var request =
                post(ADMIN_BRANDS)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BrandDto.Request("거절")));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden());
        assertThat(brandFixture.rowCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\" \"}"})
    void 빈_브랜드_이름은_저장하지_않는다(String invalidBody) throws Exception {
        // arrange
        var request =
                post(ADMIN_BRANDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"));
        assertThat(brandFixture.rowCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101"})
    void 유효하지_않은_페이지_조건을_거절한다(String query) throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS + query).with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void 없는_브랜드의_조회를_거절한다() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS + "/999").with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
    }

    @Test
    void 없는_브랜드의_삭제를_거절한다() throws Exception {
        // arrange
        var request = delete(ADMIN_BRANDS + "/999").with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
    }

    @Test
    void 브랜드_삭제_중_오류가_발생하면_HTTP_500을_반환한다() throws Exception {
        // arrange
        Brand brand = brandFixture.createBrand();
        Product inStock = productFixture.createProduct(brand.getId(), "재고 있음", 3_000, 5);
        Product soldOut = productFixture.createProduct(brand.getId(), "재고 없음", 1_000, 0);
        doThrow(new IllegalStateException("브랜드 저장 실패")).when(brands).save(any(Brand.class));
        var request =
                delete(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"));
        assertThat(brandFixture.brand(brand.getId())).usingRecursiveComparison().isEqualTo(brand);
        assertThat(productFixture.product(inStock.getId()))
                .usingRecursiveComparison()
                .isEqualTo(inStock);
        assertThat(productFixture.product(soldOut.getId()))
                .usingRecursiveComparison()
                .isEqualTo(soldOut);
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
