package com.loopers.interfaces.api.mall.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.loopers.application.common.PageResult;
import com.loopers.application.mall.query.BrandView;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.mall.dto.BrandApiDto;
import com.loopers.support.test.E2ETest;
import com.loopers.utils.DatabaseCleanUp;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@E2ETest
class BrandApiE2ETest {
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드 관리")
    @Nested
    class ManageBrand {
        @DisplayName("브랜드를 등록·조회·수정·삭제한다")
        @Test
        void managesBrand() {
            ResponseEntity<ApiResponse<BrandApiDto.Response>> created = restTemplate.exchange(
                "/api-admin/v1/brands",
                HttpMethod.POST,
                new HttpEntity<>(new BrandApiDto.Request(" LOOP ", "설명")),
                new ParameterizedTypeReference<>() {}
            );
            long brandId = created.getBody().data().brandId();

            ResponseEntity<ApiResponse<BrandApiDto.Response>> updated = restTemplate.exchange(
                "/api-admin/v1/brands/" + brandId,
                HttpMethod.PUT,
                new HttpEntity<>(new BrandApiDto.Request("변경", null)),
                new ParameterizedTypeReference<>() {}
            );
            ResponseEntity<ApiResponse<BrandView>> customerDetail = restTemplate.exchange(
                "/api/v1/brands/" + brandId,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );
            ResponseEntity<ApiResponse<PageResult<BrandView>>> list = restTemplate.exchange(
                "/api-admin/v1/brands",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );
            ResponseEntity<ApiResponse<Object>> deleted = restTemplate.exchange(
                "/api-admin/v1/brands/" + brandId,
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );

            assertAll(
                () -> assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(created.getBody().data().name()).isEqualTo("LOOP"),
                () -> assertThat(updated.getBody().data().name()).isEqualTo("변경"),
                () -> assertThat(updated.getBody().data().description()).isNull(),
                () -> assertThat(customerDetail.getBody().data().name()).isEqualTo("변경"),
                () -> assertThat(list.getBody().data().totalElements()).isEqualTo(1L),
                () -> assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(restTemplate.getForEntity("/api/v1/brands/" + brandId, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND)
            );
        }

        @DisplayName("존재하지 않는 브랜드 삭제는 404다")
        @Test
        void returnsNotFound_whenBrandDoesNotExist() {
            ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                "/api-admin/v1/brands/999999",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("이미 삭제된 브랜드를 다시 삭제하면 404이고 상태를 유지한다")
        @Test
        void returnsNotFound_whenBrandAlreadyDeleted() {
            long brandId = createBrand();
            restTemplate.exchange("/api-admin/v1/brands/" + brandId, HttpMethod.DELETE,
                HttpEntity.EMPTY, new ParameterizedTypeReference<ApiResponse<Object>>() {});

            ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                "/api-admin/v1/brands/" + brandId,
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        private long createBrand() {
            ResponseEntity<ApiResponse<BrandApiDto.Response>> response = restTemplate.exchange(
                "/api-admin/v1/brands",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "브랜드")),
                new ParameterizedTypeReference<>() {}
            );
            return response.getBody().data().brandId();
        }
    }
}
