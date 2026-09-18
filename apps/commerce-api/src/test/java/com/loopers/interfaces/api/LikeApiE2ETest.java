package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeApiE2ETest {
    @Autowired
    private ProductLikeRepository likes;

    @Autowired
    private BrandRepository brands;

    @Autowired
    private ProductRepository products;

    @Autowired
    private TestRestTemplate rest;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DatabaseCleanUp cleanUp;

    @Test
    @DisplayName("좋아요 등록은 요청자와 상품의 관계를 저장한다")
    void registersRelation() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(likes.exists(1, product.getId())).isTrue();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 등록한 좋아요를 다시 등록해도 관계는 하나다")
    void repeatedRegistrationKeepsOneRelation() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    @DisplayName("상품의 좋아요 수는 저장된 사용자 관계 수와 같다")
    void readsLikeCountFromRelations() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        likes.save(new ProductLike(1, product.getId()));
        likes.save(new ProductLike(2, product.getId()));

        // act
        var response = rest.getForEntity("/api/v1/products/" + product.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode count = response.getBody().requiredAt("/data/likeCount");
        assertThat(count.isIntegralNumber()).isTrue();
        assertThat(count.longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("DB는 같은 사용자와 상품의 중복 관계를 거절한다")
    void databaseRejectsDuplicateRelation() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        ProductLike duplicate = new ProductLike(1, product.getId());

        // act
        assertThrows(DataIntegrityViolationException.class, () -> likes.save(duplicate));

        // assert
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요 취소는 본인 관계를 제거한다")
    void cancelsOwnRelation() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(likes.exists(1, product.getId())).isFalse();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("본인 관계가 없는 상품의 취소도 성공한다")
    void absentRelationCancellationSucceeds() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("좋아요 취소는 다른 사용자의 관계를 유지한다")
    void cancellationPreservesOtherUsersRelation() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        likes.save(new ProductLike(1, product.getId()));
        likes.save(new ProductLike(2, product.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(likes.exists(1, product.getId())).isFalse();
        assertThat(likes.exists(2, product.getId())).isTrue();
    }

    @Test
    @DisplayName("내 좋아요 목록에는 내가 등록한 상품만 나온다")
    void readsOnlyOwnLikedProducts() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        Product otherProduct = Product.create(brand.getId(), "otherProduct", 2_000);
        otherProduct.setStock(0);
        otherProduct = products.save(otherProduct);
        likes.save(new ProductLike(1, product.getId()));
        likes.save(new ProductLike(2, otherProduct.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/users/1/likes", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId")).extracting(JsonNode::longValue).containsExactly(product.getId());
    }

    @Test
    @DisplayName("다른 사용자의 좋아요 목록은 접근 거절로 응답한다")
    void rejectsOtherUsersList() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/users/2/likes", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("삭제한 상품에는 기존 관계가 있어도 등록할 수 없다")
    void rejectsRegistrationOnDeletedProduct() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제한 상품은 내 좋아요 목록에서 제외하고 관계는 보존한다")
    void excludesDeletedProductsFromOwnList() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/users/1/likes", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).isEmpty();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제한 상품에 남은 본인 관계를 취소할 수 있다")
    void cancelsRelationOnDeletedProduct() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(likes.exists(1, product.getId())).isFalse();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("없는 상품의 좋아요 등록을 거절한다")
    void rejectsMissingProduct() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/999/likes", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("유효한 상품이라도 식별 헤더가 없으면 등록할 수 없다")
    void rejectsMissingIdentityForExistingProduct() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        HttpEntity<Void> request = new HttpEntity<>(new HttpHeaders());

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("없는 상품의 없는 관계를 취소해도 성공한다")
    void cancelsAbsentRelationForMissingProduct() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/999/likes", HttpMethod.DELETE, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("좋아요 취소 결과는 공개 상품의 좋아요 수에 반영된다")
    void cancellationUpdatesPublicCount() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);
        var detail = rest.getForEntity("/api/v1/products/" + product.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        JsonNode count = detail.getBody().requiredAt("/data/likeCount");
        assertThat(count.isIntegralNumber()).isTrue();
        assertThat(count.longValue()).isZero();
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
