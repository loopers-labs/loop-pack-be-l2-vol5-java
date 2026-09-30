package com.loopers.interfaces.api.like;

import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.domain.like.LikeModel;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserLikeV1ApiE2ETest {

    private final TestRestTemplate testRestTemplate;
    private final LikeJpaRepository likeJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public UserLikeV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        LikeJpaRepository likeJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.likeJpaRepository = likeJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Void> requestWithUser(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(null, headers);
    }

    @DisplayName("GET /api/v1/users/{userId}/likes")
    @Nested
    class Get {
        @DisplayName("본인의 좋아요 목록을 조회하면, 200과 좋아요한 productId 목록을 반환한다.")
        @Test
        void returns200WithProductIds_whenRequesterIsOwner() {
            // arrange
            likeJpaRepository.save(new LikeModel(1L, 10L));
            likeJpaRepository.save(new LikeModel(1L, 20L));
            String requestUrl = "/api/v1/users/1/likes";

            // act
            ParameterizedTypeReference<ApiResponse<UserLikeV1Dto.LikeListResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<UserLikeV1Dto.LikeListResponse>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.GET, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().productIds()).containsExactlyInAnyOrder(10L, 20L);
        }

        @DisplayName("다른 사용자의 좋아요 목록을 조회하면, 404를 반환한다.")
        @Test
        void returns404_whenRequesterIsNotOwner() {
            // arrange
            likeJpaRepository.save(new LikeModel(2L, 10L));
            String requestUrl = "/api/v1/users/2/likes";

            // act
            ParameterizedTypeReference<ApiResponse<UserLikeV1Dto.LikeListResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<UserLikeV1Dto.LikeListResponse>> response =
                testRestTemplate.exchange(requestUrl, HttpMethod.GET, requestWithUser(1L), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
