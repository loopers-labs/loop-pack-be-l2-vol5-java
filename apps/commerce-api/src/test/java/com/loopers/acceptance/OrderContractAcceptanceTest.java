package com.loopers.acceptance;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 표시 신선도와 바꿀 수 없는 계약 — 조회 최적화가 주문 기록과 주문 판단을 건드리지 않는다
 * (docs/week4/design.md 3번 섹션 인수 조건 ②③). A9, A10.
 */
@DisplayName("주문 계약 보존")
class OrderContractAcceptanceTest extends AcceptanceTest {

    @DisplayName("A9 — 주문 뒤 관리자가 가격을 바꿔도, 내 주문의 단가와 결제액은 주문 당시 값 그대로다.")
    @Test
    void priceChangeAfterOrder_keepsOrderAmount() {
        Long brand = AdminSteps.createBrand("나이키");
        Long product = AdminSteps.createProduct(brand, "운동화", 10_000L, 10);
        Long user = givenUser();
        CustomerSteps.chargePoint(user, 100_000L);
        Long orderId = CustomerSteps.createOrder(user, product, 2).jsonPath().getLong("data.id");
        CustomerSteps.confirmOrder(user, orderId);

        AdminSteps.updateProduct(product, "운동화", 12_000L);

        ExtractableResponse<Response> order = CustomerSteps.getOrder(user, orderId);
        assertAll(
            () -> assertThat(CustomerSteps.getProduct(product).jsonPath().getLong("data.price")).isEqualTo(12_000L),
            () -> assertThat(order.jsonPath().getLong("data.items[0].unitPrice")).isEqualTo(10_000L),
            () -> assertThat(order.jsonPath().getLong("data.paidAmount")).isEqualTo(20_000L)
        );
    }

    @DisplayName("A10 — 삭제된 상품으로는 주문을 만들 수 없다(404). 조회 결과를 어떻게 재사용하든 주문 판단은 원본 기준이다.")
    @Test
    void deletedProduct_cannotBeOrdered() {
        Long brand = AdminSteps.createBrand("나이키");
        Long product = AdminSteps.createProduct(brand, "운동화", 10_000L, 10);
        Long user = givenUser();
        CustomerSteps.getProduct(product);

        AdminSteps.deleteProduct(product);

        assertThat(CustomerSteps.createOrder(user, product, 1).statusCode()).isEqualTo(404);
    }
}
