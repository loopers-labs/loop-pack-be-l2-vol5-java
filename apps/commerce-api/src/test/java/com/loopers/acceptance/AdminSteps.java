package com.loopers.acceptance;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

/** 관리자 API 호출 — 시나리오가 "무엇을 하는지"만 읽히게 HTTP 세부를 감춘다. */
final class AdminSteps {

    private AdminSteps() {}

    static Long createBrand(String name) {
        return admin()
            .body(Map.of("name", name, "description", name + " 설명", "category", "의류"))
            .post("/api-admin/v1/brands")
            .then().statusCode(201)
            .extract().jsonPath().getLong("data.id");
    }

    static void renameBrand(Long brandId, String name) {
        admin()
            .body(Map.of("name", name, "description", name + " 설명", "category", "의류"))
            .put("/api-admin/v1/brands/{brandId}", brandId)
            .then().statusCode(200);
    }

    static void deleteBrand(Long brandId) {
        admin()
            .delete("/api-admin/v1/brands/{brandId}", brandId)
            .then().statusCode(200);
    }

    static Long createProduct(Long brandId, String name, long price, int stock) {
        return admin()
            .body(Map.of("name", name, "price", price, "brandId", brandId, "initialStock", stock))
            .post("/api-admin/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("data.id");
    }

    static void updateProduct(Long productId, String name, long price) {
        admin()
            .body(Map.of("name", name, "price", price))
            .put("/api-admin/v1/products/{productId}", productId)
            .then().statusCode(200);
    }

    static void deleteProduct(Long productId) {
        admin()
            .delete("/api-admin/v1/products/{productId}", productId)
            .then().statusCode(200);
    }

    static void changeStock(Long productId, int quantity) {
        admin()
            .body(Map.of("quantity", quantity))
            .put("/api-admin/v1/products/{productId}/stock", productId)
            .then().statusCode(200);
    }

    private static RequestSpecification admin() {
        return RestAssured.given()
            .auth().preemptive().basic(AcceptanceTestSecurityConfig.ADMIN_USERNAME, AcceptanceTestSecurityConfig.ADMIN_PASSWORD)
            .contentType(ContentType.JSON);
    }
}
