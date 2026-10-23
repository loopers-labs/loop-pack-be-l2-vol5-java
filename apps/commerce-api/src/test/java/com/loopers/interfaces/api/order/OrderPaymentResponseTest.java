package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.OrderPaymentMethod;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.interfaces.api.ApiResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPaymentResponseTest {
    private static final UUID KEY = UUID.fromString("12345678-abcd-4abc-8abc-123456789abc");
    private static final ZonedDateTime CREATED_AT = ZonedDateTime.parse("2026-10-23T12:00:00+09:00");
    private static final ZonedDateTime CONFIRMED_AT = CREATED_AT.plusSeconds(5);
    private static final OrderInfo.ItemInfo ITEM = new OrderInfo.ItemInfo(12, "stored product", 3500, 2, 7000);
    private static ObjectMapper mapper;

    @BeforeAll
    static void configureActualJackson() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(ClassUtils.resolveClassName("com.loopers.config.jackson.JacksonConfig", null))
            .run(context -> mapper = context.getBean(ObjectMapper.class));
    }

    @Test
    void draftDoesNotInventPaymentMethodOrUnknownAttempt() {
        OrderInfo info = order(OrderStatus.DRAFT);
        OrderPaymentResponse response = OrderPaymentResponse.from(info, null, null);
        JsonNode data = mapper.valueToTree(response);

        assertThat(data).isEqualTo(mapper.valueToTree(info));
        assertThat(fieldNames(data)).doesNotContain("paymentMethod", "payment", "links", "paidAmount", "confirmedAt");
        assertThat(data.get("createdAt").isTextual()).isTrue();
    }

    @Test
    void pointHistoryKeepsStoredFactsWithoutInventingPgAttempt() {
        OrderInfo info = order(OrderStatus.CONFIRMED);
        JsonNode data = mapper.valueToTree(OrderPaymentResponse.from(info, OrderPaymentMethod.POINT, null));

        assertThat(data.path("paymentMethod").asText()).isEqualTo("POINT");
        assertThat(data.path("paidAmount").asLong()).isEqualTo(7000);
        assertThat(data.path("confirmedAt").isTextual()).isTrue();
        assertThat(fieldNames(data)).doesNotContain("payment", "links");
        assertExistingFields(info, data);
    }

    @ParameterizedTest
    @EnumSource(PaymentAttemptStatus.class)
    void exposesPaymentStateSeparatelyFromSuccessfulApiProcessing(PaymentAttemptStatus status) {
        OrderInfo info = order(orderStatus(status));
        OrderPaymentResponse response = OrderPaymentResponse.from(info, OrderPaymentMethod.PG, payment(status));
        JsonNode root = mapper.valueToTree(ApiResponse.success(response));
        JsonNode data = root.path("data");

        assertThat(root.path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(fieldNames(root.path("meta"))).containsExactly("result");
        assertThat(data.path("paymentMethod").asText()).isEqualTo("PG");
        assertThat(data.path("payment").path("paymentAttemptKey").asText()).isEqualTo(KEY.toString());
        assertThat(data.path("payment").path("status").asText()).isEqualTo(status.name());
        assertThat(fieldNames(data.path("payment"))).containsExactlyInAnyOrder("paymentAttemptKey", "status");
        assertThat(data.path("links").path("order").asText()).isEqualTo("/api/v1/orders/42");
        assertThat(data.path("links").path("reconcile").asText()).isEqualTo("/api/v1/orders/42/payment/reconcile");
        assertThat(fieldNames(data.path("links"))).containsExactlyInAnyOrder("order", "reconcile");
        assertExistingFields(info, data);
        assertThat(root.toString()).doesNotContain("cardNo", "cardType", "fingerprint", "keyVersion", "formatVersion",
            "providerOrderId", "providerAccount", "transactionKey", "sendStartedAt", "confirmationStatus");
        if (status == PaymentAttemptStatus.SUCCESS) {
            assertThat(response.confirmHttpStatus()).isEqualTo(HttpStatus.OK);
        } else if (status == PaymentAttemptStatus.FAILED) {
            assertThat(response.confirmHttpStatus()).isEqualTo(HttpStatus.OK);
            assertThat(fieldNames(data)).doesNotContain("paidAmount", "confirmedAt");
        } else {
            assertThat(response.confirmHttpStatus()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(fieldNames(data)).doesNotContain("paidAmount", "confirmedAt");
        }
    }

    @Test
    void keepsZeroAmountSuccessDistinctFromUnpaid() {
        OrderInfo freeOrder = new OrderInfo(42L, OrderStatus.CONFIRMED, List.of(), 0, 0L, CREATED_AT, CONFIRMED_AT);
        JsonNode data = mapper.valueToTree(OrderPaymentResponse.from(freeOrder, OrderPaymentMethod.PG,
            payment(PaymentAttemptStatus.SUCCESS)));

        assertThat(data.has("paidAmount")).isTrue();
        assertThat(data.path("paidAmount").asLong()).isZero();
        assertThat(data.has("confirmedAt")).isTrue();
    }

    @ParameterizedTest
    @MethodSource("incoherentStates")
    void rejectsIncoherentOrderAndPaymentStates(OrderStatus orderStatus, OrderPaymentMethod method,
                                              PaymentAttemptStatus attemptStatus) {
        OrderPaymentResponse.PaymentDetails details = attemptStatus == null ? null : payment(attemptStatus);

        assertThatThrownBy(() -> OrderPaymentResponse.from(order(orderStatus), method, details))
            .isInstanceOf(IllegalStateException.class);
    }

    static Stream<Arguments> incoherentStates() {
        return Stream.of(
            Arguments.of(OrderStatus.DRAFT, OrderPaymentMethod.POINT, null),
            Arguments.of(OrderStatus.DRAFT, OrderPaymentMethod.PG, PaymentAttemptStatus.NOT_OBSERVED),
            Arguments.of(OrderStatus.DRAFT, null, PaymentAttemptStatus.UNKNOWN),
            Arguments.of(OrderStatus.CONFIRMED, null, null),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.POINT, PaymentAttemptStatus.SUCCESS),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.PG, null),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.PG, PaymentAttemptStatus.PENDING),
            Arguments.of(OrderStatus.CONFIRMED, OrderPaymentMethod.PG, PaymentAttemptStatus.FAILED),
            Arguments.of(OrderStatus.PAYMENT_PENDING, null, null),
            Arguments.of(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.POINT, null),
            Arguments.of(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.PG, null),
            Arguments.of(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.PG, PaymentAttemptStatus.SUCCESS),
            Arguments.of(OrderStatus.PAYMENT_PENDING, OrderPaymentMethod.PG, PaymentAttemptStatus.FAILED),
            Arguments.of(OrderStatus.PAYMENT_FAILED, null, null),
            Arguments.of(OrderStatus.PAYMENT_FAILED, OrderPaymentMethod.POINT, null),
            Arguments.of(OrderStatus.PAYMENT_FAILED, OrderPaymentMethod.PG, null),
            Arguments.of(OrderStatus.PAYMENT_FAILED, OrderPaymentMethod.PG, PaymentAttemptStatus.PENDING),
            Arguments.of(OrderStatus.PAYMENT_FAILED, OrderPaymentMethod.PG, PaymentAttemptStatus.SUCCESS)
        );
    }

    @ParameterizedTest
    @MethodSource("incoherentPaidFields")
    void rejectsInventedOrMissingPaidFacts(OrderStatus status, Long paidAmount, ZonedDateTime confirmedAt) {
        OrderInfo info = new OrderInfo(42L, status, List.of(ITEM), 7000, paidAmount, CREATED_AT, confirmedAt);
        PaymentAttemptStatus attemptStatus = status == OrderStatus.CONFIRMED ? PaymentAttemptStatus.SUCCESS
            : status == OrderStatus.PAYMENT_FAILED ? PaymentAttemptStatus.FAILED : PaymentAttemptStatus.PENDING;

        assertThatThrownBy(() -> OrderPaymentResponse.from(info, OrderPaymentMethod.PG, payment(attemptStatus)))
            .isInstanceOf(IllegalStateException.class);
    }

    static Stream<Arguments> incoherentPaidFields() {
        return Stream.of(
            Arguments.of(OrderStatus.PAYMENT_PENDING, 0L, null),
            Arguments.of(OrderStatus.PAYMENT_PENDING, null, CONFIRMED_AT),
            Arguments.of(OrderStatus.PAYMENT_FAILED, 0L, null),
            Arguments.of(OrderStatus.PAYMENT_FAILED, null, CONFIRMED_AT),
            Arguments.of(OrderStatus.CONFIRMED, null, CONFIRMED_AT),
            Arguments.of(OrderStatus.CONFIRMED, 7000L, null),
            Arguments.of(OrderStatus.CONFIRMED, 6999L, CONFIRMED_AT)
        );
    }

    @Test
    void copiesItemsAndDoesNotAddFieldsToExistingOrderInfo() {
        List<OrderInfo.ItemInfo> mutableItems = new ArrayList<>(List.of(ITEM));
        OrderPaymentResponse response = new OrderPaymentResponse(42L, OrderStatus.DRAFT, mutableItems, 7000,
            null, CREATED_AT, null, null, null, null);
        mutableItems.clear();

        assertThat(response.items()).containsExactly(ITEM);
        assertThatThrownBy(() -> response.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(fieldNames(mapper.valueToTree(order(OrderStatus.DRAFT))))
            .containsExactlyInAnyOrder("orderId", "status", "items", "totalAmount", "createdAt");
    }

    @Test
    void paymentDetailsRequiresARealAttemptKeyAndStatus() {
        assertThatThrownBy(() -> new OrderPaymentResponse.PaymentDetails(null, PaymentAttemptStatus.PENDING))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OrderPaymentResponse.PaymentDetails(KEY, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confirmStatusSelectionIsNotForDraftOrPointQueryResponses() {
        assertThatThrownBy(() -> OrderPaymentResponse.from(order(OrderStatus.DRAFT), null, null).confirmHttpStatus())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OrderPaymentResponse.from(order(OrderStatus.CONFIRMED), OrderPaymentMethod.POINT, null)
            .confirmHttpStatus()).isInstanceOf(IllegalStateException.class);
    }

    private static void assertExistingFields(OrderInfo info, JsonNode actual) {
        JsonNode previous = mapper.valueToTree(info);
        previous.fieldNames().forEachRemaining(field -> assertThat(actual.get(field)).as(field).isEqualTo(previous.get(field)));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static OrderInfo order(OrderStatus status) {
        boolean confirmed = status == OrderStatus.CONFIRMED;
        return new OrderInfo(42L, status, List.of(ITEM), 7000, confirmed ? 7000L : null, CREATED_AT,
            confirmed ? CONFIRMED_AT : null);
    }

    private static OrderPaymentResponse.PaymentDetails payment(PaymentAttemptStatus status) {
        return new OrderPaymentResponse.PaymentDetails(KEY, status);
    }

    private static OrderStatus orderStatus(PaymentAttemptStatus status) {
        return switch (status) {
            case SUCCESS -> OrderStatus.CONFIRMED;
            case FAILED -> OrderStatus.PAYMENT_FAILED;
            case NOT_OBSERVED, PENDING, UNKNOWN -> OrderStatus.PAYMENT_PENDING;
        };
    }
}
