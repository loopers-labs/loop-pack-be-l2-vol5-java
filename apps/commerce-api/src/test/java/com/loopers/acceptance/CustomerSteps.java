package com.loopers.acceptance;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import java.util.List;
import java.util.Map;

/** 고객 API 호출 — 상품 조회, 좋아요, 포인트, 주문. */
final class CustomerSteps {

    private static final String USER_HEADER = "X-USER-ID";

    private CustomerSteps() {}

    // --- 상품 조회 ---

    static ExtractableResponse<Response> getProducts(Map<String, ?> params) {
        return RestAssured.given()
            .queryParams(params)
            .get("/api/v1/products")
            .then().extract();
    }

    /** 정상 응답을 기대하는 목록 조회 — 상품 id만 순서대로 꺼낸다. */
    static List<Long> getProductIds(Map<String, ?> params) {
        ExtractableResponse<Response> response = getProducts(params);
        if (response.statusCode() != 200) {
            throw new AssertionError("상품 목록 조회 실패: " + response.statusCode() + " " + response.body().asString());
        }
        return response.jsonPath().getList("data.products.id", Long.class);
    }

    static ExtractableResponse<Response> getProduct(Long productId) {
        return RestAssured.given()
            .get("/api/v1/products/{productId}", productId)
            .then().extract();
    }

    // --- 좋아요 ---

    static void like(Long userId, Long productId) {
        RestAssured.given().header(USER_HEADER, userId)
            .post("/api/v1/products/{productId}/likes", productId)
            .then().statusCode(200);
    }

    static void unlike(Long userId, Long productId) {
        RestAssured.given().header(USER_HEADER, userId)
            .delete("/api/v1/products/{productId}/likes", productId)
            .then().statusCode(200);
    }

    // --- 포인트·주문 ---

    static void chargePoint(Long userId, long amount) {
        RestAssured.given().header(USER_HEADER, userId).contentType(ContentType.JSON)
            .body(Map.of("amount", amount))
            .post("/api/v1/points/charge")
            .then().statusCode(200);
    }

    static ExtractableResponse<Response> createOrder(Long userId, Long productId, int quantity) {
        return RestAssured.given().header(USER_HEADER, userId).contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("productId", productId, "quantity", quantity))))
            .post("/api/v1/orders")
            .then().extract();
    }

    static void confirmOrder(Long userId, Long orderId) {
        RestAssured.given().header(USER_HEADER, userId)
            .post("/api/v1/orders/{orderId}/confirm", orderId)
            .then().statusCode(200);
    }

    static ExtractableResponse<Response> getOrder(Long userId, Long orderId) {
        return RestAssured.given().header(USER_HEADER, userId)
            .get("/api/v1/orders/{orderId}", orderId)
            .then().extract();
    }
}
