package com.loopers.interfaces.api.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiE2ETest {

    private static final String PRODUCTS = "/api/v1/products";
    private static final String USER_ID_HEADER = "X-USER-ID";

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    // 좋아요 수 집계도 함께 바뀌어야 해서 관계는 LikeService 로 만든다.
    @Autowired
    private LikeService likeService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Brand saveBrand(String name) {
        return brandRepository.save(Brand.create(name));
    }

    private Product saveProduct(Brand brand, String name, long price) {
        return exposedProducts.save(Product.create(brand.getId(), name, new Money(price), new Stock(10)));
    }

    private Product saveDeletedProduct(Brand brand, String name) {
        Product product = Product.create(brand.getId(), name, new Money(10_000), new Stock(10));
        product.delete();
        return productRepository.save(product);
    }

    private void saveLike(Product product) {
        User user = userRepository.save(User.create());
        likeService.like(user.getId(), product.getId());
    }

    // 응답 필드 구성을 확인하려고 data 를 Map 으로 받는다. X-USER-ID 는 일부러 지정할 때만 보낸다.
    private ResponseEntity<ApiResponse<Map<String, Object>>> get(String url, String userIdHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (userIdHeader != null) {
            headers.set(USER_ID_HEADER, userIdHeader);
        }
        return testRestTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> get(String url) {
        return get(url, null);
    }

    private void assertFail(
        ResponseEntity<ApiResponse<Map<String, Object>>> response, HttpStatus status, String errorCode
    ) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().errorCode()).isEqualTo(errorCode);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentOf(ResponseEntity<ApiResponse<Map<String, Object>>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().data().get("content");
    }

    private List<Object> namesOf(ResponseEntity<ApiResponse<Map<String, Object>>> response) {
        return contentOf(response).stream().map(item -> item.get("name")).toList();
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    class GetDetail {
        @DisplayName("상품과 브랜드, 좋아요 수를 id·name·price·brand·likeCount 만으로 응답한다. 재고와 시각은 담지 않는다.")
        @Test
        @SuppressWarnings("unchecked")
        void returnsDetail_withOnlyCustomerFields() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Product product = saveProduct(brand, "러닝화", 89_000);
            saveLike(product);
            saveLike(product);

            // act
            ResponseEntity<ApiResponse<Map<String, Object>>> response = get(PRODUCTS + "/" + product.getId());

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> data = response.getBody().data();
            assertThat(data).containsOnlyKeys("id", "name", "price", "brand", "likeCount");
            assertThat(((Number) data.get("id")).longValue()).isEqualTo(product.getId());
            assertThat(data.get("name")).isEqualTo("러닝화");
            assertThat(((Number) data.get("price")).longValue()).isEqualTo(89_000);
            assertThat(((Number) data.get("likeCount")).longValue()).isEqualTo(2);
            Map<String, Object> brandData = (Map<String, Object>) data.get("brand");
            assertThat(brandData).containsOnlyKeys("id", "name");
            assertThat(((Number) brandData.get("id")).longValue()).isEqualTo(brand.getId());
            assertThat(brandData.get("name")).isEqualTo("루퍼스");
        }

        @DisplayName("좋아요가 없으면 likeCount 는 0 이다.")
        @Test
        void returnsZeroLikeCount_whenNoLikes() {
            Product product = saveProduct(saveBrand("루퍼스"), "러닝화", 89_000);

            ResponseEntity<ApiResponse<Map<String, Object>>> response = get(PRODUCTS + "/" + product.getId());

            assertThat(((Number) response.getBody().data().get("likeCount")).longValue()).isZero();
        }

        @DisplayName("좋아요가 등록되면 likeCount 가 오른다.")
        @Test
        void likeCountGoesUp_afterLike() {
            Product product = saveProduct(saveBrand("루퍼스"), "러닝화", 89_000);
            String url = PRODUCTS + "/" + product.getId();
            assertThat(((Number) get(url).getBody().data().get("likeCount")).longValue()).isZero();

            saveLike(product);

            assertThat(((Number) get(url).getBody().data().get("likeCount")).longValue()).isEqualTo(1);
        }

        @DisplayName("없거나 삭제된 상품이면 404 NOT_FOUND 이다.")
        @Test
        void returnsNotFound_whenProductDoesNotExistOrIsDeleted() {
            Product deleted = saveDeletedProduct(saveBrand("루퍼스"), "삭제됨");

            assertFail(get(PRODUCTS + "/999"), HttpStatus.NOT_FOUND, "NOT_FOUND");
            assertFail(get(PRODUCTS + "/" + deleted.getId()), HttpStatus.NOT_FOUND, "NOT_FOUND");
        }

        @DisplayName("productId 가 숫자가 아니면 400 INVALID_INPUT 이다.")
        @Test
        void returnsBadRequest_whenProductIdIsNotNumber() {
            assertFail(get(PRODUCTS + "/abc"), HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }

        @DisplayName("X-USER-ID 헤더 없이 조회되고, 헤더가 숫자가 아니거나 없는 사용자여도 읽지 않고 정상 조회된다.")
        @ParameterizedTest
        @ValueSource(strings = {"abc", "999"})
        void ignoresUserIdHeader(String header) {
            Product product = saveProduct(saveBrand("루퍼스"), "러닝화", 89_000);
            String url = PRODUCTS + "/" + product.getId();

            assertThat(get(url).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(url, header).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @DisplayName("GET /api/v1/products")
    @Nested
    class GetList {
        @DisplayName("파라미터가 없으면 최신순(id 내림차순), 0페이지, 20개씩이고, 삭제된 상품은 뺀다.")
        @Test
        void returnsLatestFirst_withDefaults_withoutDeleted() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            saveProduct(brand, "첫째", 10_000);
            saveProduct(brand, "둘째", 20_000);
            saveProduct(brand, "셋째", 30_000);
            saveDeletedProduct(brand, "삭제됨");

            // act
            ResponseEntity<ApiResponse<Map<String, Object>>> response = get(PRODUCTS);

            // assert
            assertThat(namesOf(response)).containsExactly("셋째", "둘째", "첫째");
            Map<String, Object> data = response.getBody().data();
            assertThat(data.get("page")).isEqualTo(0);
            assertThat(data.get("size")).isEqualTo(20);
            assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(3);
            assertThat(data.get("totalPages")).isEqualTo(1);
        }

        @DisplayName("항목은 id·name·price·brand·likeCount 만 담고, 브랜드와 좋아요 수가 항목마다 맞다.")
        @Test
        @SuppressWarnings("unchecked")
        void returnsItemFields_withBrandAndLikeCount() {
            // arrange
            Brand looper = saveBrand("루퍼스");
            Brand other = saveBrand("다른브랜드");
            Product shoes = saveProduct(looper, "러닝화", 89_000);
            Product bag = saveProduct(other, "가방", 50_000);
            saveLike(shoes);
            saveLike(shoes);
            saveLike(bag);

            // act
            List<Map<String, Object>> content = contentOf(get(PRODUCTS));

            // assert
            Map<String, Object> bagItem = content.get(0);
            Map<String, Object> shoesItem = content.get(1);
            assertThat(bagItem).containsOnlyKeys("id", "name", "price", "brand", "likeCount");
            assertThat(bagItem.get("name")).isEqualTo("가방");
            assertThat(((Number) bagItem.get("likeCount")).longValue()).isEqualTo(1);
            assertThat(((Map<String, Object>) bagItem.get("brand")).get("name")).isEqualTo("다른브랜드");
            assertThat(shoesItem.get("name")).isEqualTo("러닝화");
            assertThat(((Number) shoesItem.get("likeCount")).longValue()).isEqualTo(2);
            assertThat(((Map<String, Object>) shoesItem.get("brand")).get("name")).isEqualTo("루퍼스");
        }

        @DisplayName("sort=latest 는 id 내림차순이다.")
        @Test
        void sortsByLatest() {
            Brand brand = saveBrand("루퍼스");
            saveProduct(brand, "첫째", 30_000);
            saveProduct(brand, "둘째", 10_000);
            saveProduct(brand, "셋째", 20_000);

            assertThat(namesOf(get(PRODUCTS + "?sort=latest"))).containsExactly("셋째", "둘째", "첫째");
        }

        @DisplayName("sort=price_asc 는 가격 오름차순이고, 가격이 같으면 id 내림차순이다.")
        @Test
        void sortsByPriceAscending_thenIdDescending() {
            Brand brand = saveBrand("루퍼스");
            saveProduct(brand, "비쌈", 30_000);
            saveProduct(brand, "싼것-먼저", 10_000);
            saveProduct(brand, "싼것-나중", 10_000);
            saveProduct(brand, "중간", 20_000);

            assertThat(namesOf(get(PRODUCTS + "?sort=price_asc")))
                .containsExactly("싼것-나중", "싼것-먼저", "중간", "비쌈");
        }

        @DisplayName("sort=likes_desc 는 좋아요 수 내림차순이고, 같으면 id 내림차순이며 좋아요 0개 상품도 포함한다.")
        @Test
        void sortsByLikesDescending_thenIdDescending() {
            Brand brand = saveBrand("루퍼스");
            Product none = saveProduct(brand, "좋아요없음", 10_000);
            Product one = saveProduct(brand, "하나", 10_000);
            Product three = saveProduct(brand, "셋", 10_000);
            Product oneToo = saveProduct(brand, "하나-나중", 10_000);
            saveLike(one);
            saveLike(oneToo);
            saveLike(three);
            saveLike(three);
            saveLike(three);

            assertThat(namesOf(get(PRODUCTS + "?sort=likes_desc")))
                .containsExactly("셋", "하나-나중", "하나", "좋아요없음");
            assertThat(none.getId()).isNotNull();
        }

        @DisplayName("brandId 로 해당 브랜드 상품만 조회하고, 존재하지 않는 브랜드이면 오류가 아니라 빈 목록이다.")
        @Test
        void filtersByBrand_andReturnsEmpty_whenBrandDoesNotExist() {
            Brand looper = saveBrand("루퍼스");
            Brand other = saveBrand("다른브랜드");
            saveProduct(looper, "러닝화", 89_000);
            saveProduct(other, "가방", 50_000);

            ResponseEntity<ApiResponse<Map<String, Object>>> filtered = get(PRODUCTS + "?brandId=" + looper.getId());
            ResponseEntity<ApiResponse<Map<String, Object>>> missing = get(PRODUCTS + "?brandId=999");

            assertThat(namesOf(filtered)).containsExactly("러닝화");
            assertThat(((Number) filtered.getBody().data().get("totalElements")).longValue()).isEqualTo(1);
            assertThat(contentOf(missing)).isEmpty();
            assertThat(((Number) missing.getBody().data().get("totalElements")).longValue()).isZero();
        }

        @DisplayName("page 와 size 를 주면 해당 페이지만 응답하고, 전체 개수와 페이지 수가 맞다.")
        @Test
        void returnsRequestedPage() {
            Brand brand = saveBrand("루퍼스");
            for (int i = 1; i <= 5; i++) {
                saveProduct(brand, "상품" + i, 10_000L * i);
            }

            ResponseEntity<ApiResponse<Map<String, Object>>> response = get(PRODUCTS + "?page=1&size=2");

            assertThat(namesOf(response)).containsExactly("상품3", "상품2");
            Map<String, Object> data = response.getBody().data();
            assertThat(data.get("page")).isEqualTo(1);
            assertThat(data.get("size")).isEqualTo(2);
            assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(5);
            assertThat(data.get("totalPages")).isEqualTo(3);
        }

        @DisplayName("sort 가 허용 값이 아니거나 page·size 가 범위를 벗어나거나 brandId 가 숫자가 아니면 400 INVALID_INPUT 이다.")
        @ParameterizedTest
        @ValueSource(strings = {
            "sort=oldest", "sort=LATEST", "page=-1", "size=0", "size=101", "size=abc", "brandId=abc"
        })
        void returnsBadRequest_whenQueryIsInvalid(String query) {
            assertFail(get(PRODUCTS + "?" + query), HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }

        // Spring 은 값이 빈 문자열인 파라미터를 생략한 것으로 보고 기본값을 쓴다. 그 동작을 그대로 따르기로 했다.
        @DisplayName("sort·page·size 의 값이 비어 있으면 생략한 것과 같게 기본값(latest, 0, 20)을 적용한다.")
        @Test
        void appliesDefaults_whenParametersAreEmpty() {
            Brand brand = saveBrand("루퍼스");
            saveProduct(brand, "첫째", 10_000);
            saveProduct(brand, "둘째", 20_000);

            ResponseEntity<ApiResponse<Map<String, Object>>> response = get(PRODUCTS + "?sort=&page=&size=");

            assertThat(namesOf(response)).containsExactly("둘째", "첫째");
            assertThat(response.getBody().data().get("page")).isEqualTo(0);
            assertThat(response.getBody().data().get("size")).isEqualTo(20);
        }

        @DisplayName("X-USER-ID 헤더 없이 조회되고, 헤더가 숫자가 아니거나 없는 사용자여도 읽지 않고 정상 조회된다.")
        @ParameterizedTest
        @ValueSource(strings = {"abc", "999"})
        void ignoresUserIdHeader(String header) {
            saveProduct(saveBrand("루퍼스"), "러닝화", 89_000);

            assertThat(get(PRODUCTS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(PRODUCTS, header).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
