package com.loopers.interfaces.api.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandApiE2ETest {

    private static final String BRANDS_URL = "/api/v1/brands/";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // 브랜드 조회는 요청자를 식별하지 않으므로 X-USER-ID 헤더 없이 호출한다.
    // 응답 필드 구성(시각 필드 제외)을 확인하려고 data 를 Map 으로 받는다.
    private ResponseEntity<ApiResponse<Map<String, Object>>> getBrand(String brandId) {
        return testRestTemplate.exchange(
            BRANDS_URL + brandId, HttpMethod.GET, HttpEntity.EMPTY,
            new ParameterizedTypeReference<>() {}
        );
    }

    private void assertFail(
        ResponseEntity<ApiResponse<Map<String, Object>>> response, HttpStatus status, String errorCode
    ) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().errorCode()).isEqualTo(errorCode);
    }

    @DisplayName("GET /api/v1/brands/{brandId}")
    @Nested
    class GetDetail {
        @DisplayName("요청자를 식별하지 않아도 브랜드의 id 와 이름만 반환한다. 생성·수정 시각은 담지 않는다.")
        @Test
        void returnsIdAndNameOnly() {
            // arrange
            Brand brand = brandRepository.save(Brand.create("루퍼스"));

            // act
            ResponseEntity<ApiResponse<Map<String, Object>>> response = getBrand(String.valueOf(brand.getId()));

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data())
                .containsOnlyKeys("id", "name")
                .containsEntry("name", "루퍼스");
            assertThat(((Number) response.getBody().data().get("id")).longValue()).isEqualTo(brand.getId());
        }

        @DisplayName("없는 브랜드이면 404 NOT_FOUND 로 응답한다.")
        @Test
        void returnsNotFound_whenBrandDoesNotExist() {
            ResponseEntity<ApiResponse<Map<String, Object>>> response = getBrand("999");

            assertFail(response, HttpStatus.NOT_FOUND, "NOT_FOUND");
            assertThat(response.getBody().meta().message()).isEqualTo("존재하지 않는 브랜드입니다.");
        }
    }
}
