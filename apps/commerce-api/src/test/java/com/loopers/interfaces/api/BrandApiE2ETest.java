package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.infrastructure.product.ProductJpaEntity;
import org.springframework.transaction.support.TransactionTemplate;
import com.loopers.interfaces.api.brand.BrandDto;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandApiE2ETest {
    @Autowired
    private BrandRepository brands;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp cleanUp;

    private static final String ADMIN_BRANDS = "/api-admin/v1/brands";

    @Test
    @DisplayName("브랜드 등록 결과와 저장한 이름이 일치한다")
    void createsBrand() throws Exception {
        // arrange
        BrandDto.Request input = new BrandDto.Request(" 브랜드 ");
        var request = post(ADMIN_BRANDS)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("브랜드"));
        long brandId = mapper.readTree(response.andReturn().getResponse().getContentAsByteArray()).requiredAt("/data/brandId").longValue();
        assertThat(brands.findById(brandId).orElseThrow().getName()).isEqualTo("브랜드");
    }

    @Test
    @DisplayName("고객은 식별 없이 브랜드 이름을 조회한다")
    void readsPublicBrand() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));

        // act
        var response = rest.getForEntity("/api/v1/brands/" + brand.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().requiredAt("/data/name").asText()).isEqualTo("브랜드");
        assertThat(response.getBody().requiredAt("/data").has("deleted")).isFalse();
    }

    @Test
    @DisplayName("브랜드 수정 결과를 저장한다")
    void updatesBrand() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("기존"));
        BrandDto.Request input = new BrandDto.Request("변경");
        var request = put(ADMIN_BRANDS + "/" + brand.getId())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("변경"));
        assertThat(brands.findById(brand.getId()).orElseThrow().getName()).isEqualTo("변경");
    }

    @Test
    @DisplayName("브랜드 삭제는 행을 보존하고 삭제 상태를 저장한다")
    void softDeletesBrand() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        var request = delete(ADMIN_BRANDS + "/" + brand.getId())
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(brands.findById(brand.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("삭제된 브랜드는 고객 상세 조회를 거절한다")
    void hidesDeletedBrandFromCustomer() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);

        // act
        var response = rest.getForEntity("/api/v1/brands/" + brand.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("BRAND_NOT_FOUND");
    }

    @Test
    @DisplayName("관리자는 삭제한 브랜드 상세를 조회한다")
    void adminReadsDeletedBrand() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);
        var request = get(ADMIN_BRANDS + "/" + brand.getId())
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    @DisplayName("관리자 목록에는 삭제한 브랜드가 포함된다")
    void adminListsDeletedBrand() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);
        var request = get(ADMIN_BRANDS)
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].deleted").value(true));
    }

    @Test
    @DisplayName("이미 삭제한 브랜드의 삭제 재요청은 성공한다")
    void repeatsDeletion() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);
        var request = delete(ADMIN_BRANDS + "/" + brand.getId())
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk());
        assertThat(brands.findById(brand.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("삭제한 브랜드의 수정은 이름을 바꾸지 않는다")
    void rejectsRenameAfterDeletion() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);
        BrandDto.Request input = new BrandDto.Request("변경");
        var request = put(ADMIN_BRANDS + "/" + brand.getId())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        assertThat(brands.findById(brand.getId()).orElseThrow().getName()).isEqualTo("브랜드");
    }

    @Test
    @DisplayName("재고가 0이어도 미삭제 상품이 있으면 브랜드를 삭제할 수 없다")
    void activeProductBlocksDeletion() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        ProductJpaEntity product = new ProductJpaEntity(brand.getId(), "product", 2_000, 0);
        transactions.executeWithoutResult(status -> entityManager.persist(product));
        var request = delete(ADMIN_BRANDS + "/" + brand.getId())
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("BRAND_HAS_ACTIVE_PRODUCTS"));
        assertThat(brands.findById(brand.getId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("연결 상품이 모두 삭제되면 브랜드를 삭제할 수 있다")
    void deletedProductDoesNotBlockDeletion() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        ProductJpaEntity product = new ProductJpaEntity(brand.getId(), "product", 2_000, 0);
        product.delete();
        transactions.executeWithoutResult(status -> entityManager.persist(product));
        var request = delete(ADMIN_BRANDS + "/" + brand.getId())
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk());
        assertThat(brands.findById(brand.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("브랜드가 없으면 관리자 목록은 빈 배열이다")
    void readsEmptyAdminList() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS)
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("미식별 요청은 관리자 목록을 조회할 수 없다")
    void rejectsAnonymousRead() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden()).andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("일반 사용자는 관리자 목록을 조회할 수 없다")
    void rejectsCustomerRead() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS).with(csrf())
            .with(user("customer").roles("USER"));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden()).andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("CSRF가 있어도 미식별 요청은 브랜드를 등록할 수 없다")
    void rejectsAnonymousWrite() throws Exception {
        // arrange
        var request = post(ADMIN_BRANDS).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new BrandDto.Request("거절")));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden()).andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
        assertThat(entityManager.createQuery("select count(e) from BrandJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("CSRF가 있어도 일반 사용자는 브랜드를 등록할 수 없다")
    void rejectsCustomerWrite() throws Exception {
        // arrange
        var request = post(ADMIN_BRANDS).with(csrf())
            .with(user("customer").roles("USER"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new BrandDto.Request("거절")));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden()).andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
        assertThat(entityManager.createQuery("select count(e) from BrandJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("관리자라도 CSRF가 없으면 브랜드 등록을 거절한다")
    void rejectsMissingCsrf() throws Exception {
        // arrange
        var request = post(ADMIN_BRANDS).with(user("admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new BrandDto.Request("거절")));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden());
        assertThat(entityManager.createQuery("select count(e) from BrandJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\" \"}"})
    @DisplayName("빈 브랜드 이름은 저장하지 않는다")
    void rejectsInvalidBrand(String invalidBody) throws Exception {
        // arrange
        var request = post(ADMIN_BRANDS).contentType(MediaType.APPLICATION_JSON).content(invalidBody)
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"));
        assertThat(entityManager.createQuery("select count(e) from BrandJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101"})
    @DisplayName("유효하지 않은 페이지 조건을 거절한다")
    void rejectsInvalidPage(String query) throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS + query)
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("없는 브랜드의 조회를 거절한다")
    void rejectsMissingBrandGet() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS + "/999")
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 브랜드의 삭제를 거절한다")
    void rejectsMissingBrandDelete() throws Exception {
        // arrange
        var request = delete(ADMIN_BRANDS + "/999")
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
