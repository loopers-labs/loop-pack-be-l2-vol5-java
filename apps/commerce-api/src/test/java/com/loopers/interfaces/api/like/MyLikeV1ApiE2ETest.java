package com.loopers.interfaces.api.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MyLikeV1ApiE2ETest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void returnsMyActiveLikedProducts() {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
        likeRepository.save(Like.create(user.getId(), product.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", user.getId().toString());

        ResponseEntity<ApiResponse<List<LikeV1Dto.MyLikeProductResponse>>> response = testRestTemplate.exchange(
            "/api/v1/users/" + user.getId() + "/likes",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).hasSize(1);
        assertThat(response.getBody().data().getFirst().productId()).isEqualTo(product.getId());
    }

    @Test
    void rejectsDifferentRequester() {
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
            "/api/v1/users/1/likes",
            HttpMethod.GET,
            new HttpEntity<>(headers("2")),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private HttpHeaders headers(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId);
        return headers;
    }
}
