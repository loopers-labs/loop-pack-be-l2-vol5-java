package com.loopers.brand.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.brand.domain.Brand;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import static com.loopers.support.http.ApiHttp.admin;
import static com.loopers.support.http.ApiHttp.body;
import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.data;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.findById;
import static com.loopers.support.http.ApiHttp.ids;
import static com.loopers.support.http.ApiHttp.isNullOrAbsent;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class BrandHttpTest {

    private static final String CUSTOMER_BRAND = "/api/v1/brands/{brandId}";
    private static final String ADMIN_BRANDS = "/api-admin/v1/brands";
    private static final String ADMIN_BRAND = "/api-admin/v1/brands/{brandId}";
    private static final long MISSING_ID = 999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @DisplayName("[R-CATALOG-01] 고객은 존재하며 삭제되지 않은 브랜드의 상세를 조회할 수 있다.")
    @Nested
    class CustomerBrandDetail {

        @DisplayName("[동등 클래스 분할] 삭제되지 않은 브랜드를 조회하면 200이고 브랜드의 id와 name을 준다.")
        @Test
        void returnsActiveBrand() throws Exception {
            // arrange
            User customer = fixture.user();
            Brand brand = fixture.brand("Nike");

            // act & assert
            mockMvc.perform(get(CUSTOMER_BRAND, brand.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.name").value("Nike"));
        }
    }

    @DisplayName("[R-CATALOG-07] 존재하지 않거나 삭제된 브랜드·상품의 고객 상세 조회는 없는 대상 오류로 처리한다.")
    @Nested
    class CustomerBrandNotFound {

        @DisplayName("[동등 클래스 분할] 존재하지 않는 브랜드를 조회하면 404 BRAND_NOT_FOUND이다.")
        @Test
        void rejectsMissingBrand() throws Exception {
            // arrange
            User customer = fixture.user();

            // act & assert
            mockMvc.perform(get(CUSTOMER_BRAND, MISSING_ID).with(customer(customer)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND"));
        }

        @DisplayName("[동등 클래스 분할] 삭제된 브랜드를 조회하면 404 BRAND_NOT_FOUND이다.")
        @Test
        void rejectsDeletedBrand() throws Exception {
            // arrange
            User customer = fixture.user();
            Brand deleted = fixture.deletedBrand("Nike");

            // act & assert
            mockMvc.perform(get(CUSTOMER_BRAND, deleted.getId()).with(customer(customer)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND"));
        }
    }

    @DisplayName("[R-ADMIN-12] 삭제된 브랜드와 상품은 고객 조회와 새 주문에서 제외한다.")
    @Nested
    class ExcludeDeletedBrandFromCustomer {

        @DisplayName("[상태 전이] 관리자가 브랜드를 삭제하면 고객 브랜드 상세가 200에서 404 BRAND_NOT_FOUND로 바뀐다.")
        @Test
        void hidesBrandAfterAdminDeletion() throws Exception {
            // arrange
            User customer = fixture.user();
            Brand brand = fixture.brand("Nike");
            mockMvc.perform(get(CUSTOMER_BRAND, brand.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK));

            // act
            mockMvc.perform(delete(ADMIN_BRAND, brand.getId()).with(admin()).with(csrf()))
                .andExpect(success(HttpStatus.OK));

            // assert
            mockMvc.perform(get(CUSTOMER_BRAND, brand.getId()).with(customer(customer)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND"));
        }
    }

    @DisplayName("[R-ADMIN-01] 관리자는 브랜드를 생성·목록 조회·상세 조회·수정·삭제할 수 있다.")
    @Nested
    class AdminBrandCrud {

        @DisplayName("[동등 클래스 분할] 이름으로 브랜드를 생성하면 201이고, 관리자 브랜드를 주며 상세 조회로 같은 브랜드를 확인한다.")
        @Test
        void createsBrand() throws Exception {
            // act
            MvcResult created = mockMvc.perform(post(ADMIN_BRANDS)
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Nike\"}"))
                .andExpect(success(HttpStatus.CREATED))
                .andReturn();
            JsonNode brand = data(created);

            // assert
            assertAll(
                () -> assertThat(brand.path("id").asLong()).isPositive(),
                () -> assertThat(brand.path("name").asText()).isEqualTo("Nike"),
                () -> assertThat(brand.path("deleted").asBoolean(true)).isFalse()
            );
            mockMvc.perform(get(ADMIN_BRAND, brand.path("id").asLong()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.name").value("Nike"))
                .andExpect(jsonPath("$.data.deleted").value(false));
        }

        @DisplayName("[오류 추측] 이름이 없거나 깨진 JSON으로 생성하면 400 INVALID_REQUEST이고 브랜드가 생기지 않는다.")
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"{}", "{\"name\":"})
        void rejectsMalformedCreation(String body) throws Exception {
            // act
            mockMvc.perform(post(ADMIN_BRANDS)
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));

            // assert
            mockMvc.perform(get(ADMIN_BRANDS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @DisplayName("[동등 클래스 분할] 브랜드 목록을 조회하면 200이고 등록한 브랜드를 모두 담는다.")
        @Test
        void listsBrands() throws Exception {
            // arrange
            Brand nike = fixture.brand("Nike");
            Brand puma = fixture.brand("Puma");

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_BRANDS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(result).path("content")))
                    .containsExactlyInAnyOrder(nike.getId(), puma.getId()),
                () -> assertThat(data(result).path("totalElements").asLong()).isEqualTo(2L)
            );
        }

        @DisplayName("[동등 클래스 분할] 존재한 적이 없는 브랜드를 상세 조회하면 404 BRAND_NOT_FOUND이다.")
        @Test
        void rejectsMissingBrandDetail() throws Exception {
            mockMvc.perform(get(ADMIN_BRAND, MISSING_ID).with(admin()))
                .andExpect(failure(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND"));
        }

        @DisplayName("[상태 전이] 브랜드 이름을 수정하면 200이고 응답과 이후 상세 조회에 새 이름이 보인다.")
        @Test
        void updatesBrandName() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");

            // act
            mockMvc.perform(put(ADMIN_BRAND, brand.getId())
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Jordan\"}"))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.name").value("Jordan"));

            // assert
            mockMvc.perform(get(ADMIN_BRAND, brand.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.name").value("Jordan"));
        }

        @DisplayName("[오류 추측] 이름이 없거나 깨진 JSON으로 수정하면 400 INVALID_REQUEST이고 브랜드 이름은 그대로다.")
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"{}", "{\"name\":"})
        void rejectsMalformedUpdate(String body) throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");

            // act
            mockMvc.perform(put(ADMIN_BRAND, brand.getId())
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));

            // assert
            mockMvc.perform(get(ADMIN_BRAND, brand.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.name").value("Nike"));
        }

        @DisplayName("[동등 클래스 분할] 존재하지 않는 브랜드를 수정하면 404 BRAND_NOT_FOUND이다.")
        @Test
        void rejectsMissingBrandUpdate() throws Exception {
            mockMvc.perform(put(ADMIN_BRAND, MISSING_ID)
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Jordan\"}"))
                .andExpect(failure(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND"));
        }

        @DisplayName("[상태 전이] 연결된 상품이 없는 브랜드를 삭제하면 200이고 data가 없으며, 이후 관리자 상세에서 삭제 상태다.")
        @Test
        void deletesBrand() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");

            // act
            MvcResult result = mockMvc.perform(delete(ADMIN_BRAND, brand.getId()).with(admin()).with(csrf()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertThat(isNullOrAbsent(body(result), "data")).isTrue();
            mockMvc.perform(get(ADMIN_BRAND, brand.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.deleted").value(true));
        }

        @DisplayName("[동등 클래스 분할] 존재하지 않는 브랜드를 삭제하면 404 BRAND_NOT_FOUND이다.")
        @Test
        void rejectsMissingBrandDeletion() throws Exception {
            mockMvc.perform(delete(ADMIN_BRAND, MISSING_ID).with(admin()).with(csrf()))
                .andExpect(failure(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND"));
        }
    }

    @DisplayName("[P-ADMIN-07] 관리자 브랜드 목록과 상세에는 삭제된 브랜드도 삭제 여부와 함께 보여 준다.")
    @Nested
    class ShowDeletedBrandsToAdmin {

        @DisplayName("[동등 클래스 분할] 관리자 목록에는 삭제된 브랜드와 삭제되지 않은 브랜드가 함께 담기고 deleted가 각각 true, false다.")
        @Test
        void listsDeletedBrandWithFlag() throws Exception {
            // arrange
            Brand active = fixture.brand("Nike");
            Brand deleted = fixture.deletedBrand("Puma");

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_BRANDS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode content = data(result).path("content");
            assertAll(
                () -> assertThat(ids(content)).containsExactlyInAnyOrder(active.getId(), deleted.getId()),
                () -> assertThat(findById(content, active.getId()).path("deleted").asBoolean(true)).isFalse(),
                () -> assertThat(findById(content, deleted.getId()).path("deleted").asBoolean(false)).isTrue()
            );
        }

        @DisplayName("[동등 클래스 분할] 삭제된 브랜드를 관리자 상세 조회하면 404가 아니라 200이고 deleted가 true다.")
        @Test
        void showsDeletedBrandDetail() throws Exception {
            // arrange
            Brand deleted = fixture.deletedBrand("Puma");

            // act & assert
            mockMvc.perform(get(ADMIN_BRAND, deleted.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.id").value(deleted.getId()))
                .andExpect(jsonPath("$.data.name").value("Puma"))
                .andExpect(jsonPath("$.data.deleted").value(true));
        }
    }

    @DisplayName("[P-ADMIN-08] 관리자 브랜드 목록은 등록 최신순으로 보여 주고 페이지를 제공한다.")
    @Nested
    class LatestPagedBrands {

        @DisplayName("[동등 클래스 분할] 세 브랜드를 차례로 등록하면 목록은 나중에 등록한 브랜드부터 담는다.")
        @Test
        void listsLatestFirst() throws Exception {
            // arrange
            Brand first = fixture.brand("First");
            Brand second = fixture.brand("Second");
            Brand third = fixture.brand("Third");

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_BRANDS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertThat(ids(data(result).path("content")))
                .containsExactly(third.getId(), second.getId(), first.getId());
        }

        @DisplayName("[경계값 분석] 세 브랜드를 크기 2로 나누면 0페이지에 최신 두 개, 1페이지에 가장 오래된 하나가 있다.")
        @Test
        void splitsIntoPages() throws Exception {
            // arrange
            Brand first = fixture.brand("First");
            Brand second = fixture.brand("Second");
            Brand third = fixture.brand("Third");

            // act
            MvcResult page0 = mockMvc.perform(get(ADMIN_BRANDS).param("page", "0").param("size", "2").with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult page1 = mockMvc.perform(get(ADMIN_BRANDS).param("page", "1").param("size", "2").with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(page0).path("content"))).containsExactly(third.getId(), second.getId()),
                () -> assertThat(data(page0).path("page").asInt(-1)).isZero(),
                () -> assertThat(data(page0).path("size").asInt()).isEqualTo(2),
                () -> assertThat(data(page0).path("totalElements").asLong()).isEqualTo(3L),
                () -> assertThat(ids(data(page1).path("content"))).containsExactly(first.getId()),
                () -> assertThat(data(page1).path("page").asInt()).isEqualTo(1),
                () -> assertThat(data(page1).path("totalElements").asLong()).isEqualTo(3L)
            );
        }

        @DisplayName("[동등 클래스 분할] page와 size 없이 조회하면 page 0, size 20으로 응답한다.")
        @Test
        void usesDefaultPage() throws Exception {
            // arrange
            fixture.brand("Nike");

            // act & assert
            mockMvc.perform(get(ADMIN_BRANDS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
        }

        @DisplayName("[경계값 분석] 마지막 페이지를 넘는 page를 요청하면 200이고 빈 목록이다.")
        @Test
        void returnsEmptyBeyondLastPage() throws Exception {
            // arrange
            fixture.brand("First");
            fixture.brand("Second");

            // act & assert
            mockMvc.perform(get(ADMIN_BRANDS).param("page", "1").param("size", "2").with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @DisplayName("[경계값 분석] size 1과 100은 200이고 요청한 size로 응답한다.")
        @ParameterizedTest(name = "size={0}")
        @ValueSource(ints = {1, 100})
        void acceptsSizeWithinRange(int size) throws Exception {
            // arrange
            fixture.brand("Nike");

            // act & assert
            mockMvc.perform(get(ADMIN_BRANDS).param("size", String.valueOf(size)).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.size").value(size));
        }

        @DisplayName("[경계값 분석] size 0과 101, page -1은 400 INVALID_REQUEST이다.")
        @ParameterizedTest(name = "page={0}, size={1}")
        @CsvSource({"0, 0", "0, 101", "-1, 20"})
        void rejectsPageOutOfRange(String page, String size) throws Exception {
            // arrange
            fixture.brand("Nike");

            // act & assert
            mockMvc.perform(get(ADMIN_BRANDS).param("page", page).param("size", size).with(admin()))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }
    }
}
