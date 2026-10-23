package com.loopers.interfaces.api.ordering.dto;

import com.loopers.application.ordering.command.OrderCommand;
import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.pay.gateway.PaymentProviderGateway.CardInput;
import com.loopers.domain.pay.model.PaymentScenario;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.util.List;
import java.util.Set;

// 주문 API 요청 DTO 묶음
public final class OrderApiDto {
    private OrderApiDto() {}

    public record ConfirmRequest(String paymentAttemptKey, String cardType, String cardNo, JsonNode testOptions) {
        private static final Set<String> FIELDS = Set.of("paymentAttemptKey", "cardType", "cardNo", "testOptions");
        private static final ObjectMapper OPTIONS = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

        // 결제 입력에만 엄격한 형태 검사를 적용하고 카드 원문이 파싱 오류에 포함되지 않게 한다.
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ConfirmRequest parse(JsonNode input) {
            if (input == null || !input.isObject()) {
                throw invalid();
            }
            input.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) { throw invalid(); } });
            return new ConfirmRequest(text(input, "paymentAttemptKey"), text(input, "cardType"),
                text(input, "cardNo"), input.get("testOptions"));
        }

        public ConfirmOrderCommand toCommand(long userId, long orderId) {
            try {
                TestOptions options = testOptions == null || testOptions.isNull()
                    ? null : OPTIONS.treeToValue(testOptions, TestOptions.class);
                PaymentScenario scenario = options == null ? PaymentScenario.resolve(null, null)
                    : PaymentScenario.resolve(options.preset(), options.overrides());
                return new ConfirmOrderCommand(userId, orderId, paymentAttemptKey, new CardInput(cardType, cardNo), scenario);
            } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
                throw invalid();
            }
        }

        private static String text(JsonNode input, String name) {
            JsonNode value = input.get(name);
            if (value == null || !value.isTextual()) {
                throw invalid();
            }
            return value.textValue();
        }

        private static DomainException invalid() { return new DomainException(DomainErrorCode.INVALID_PAYMENT_REQUEST); }

        @Override
        public String toString() { return "ConfirmRequest[redacted]"; }
    }

    private record TestOptions(PaymentScenario.Preset preset, PaymentScenario.Overrides overrides) {}

    public record ItemRequest(Long productId, Integer quantity) {
        // 요청을 커맨드 품목으로 변환
        OrderCommand.Item toCommandItem() {
            if (productId == null || quantity == null || productId <= 0 || quantity <= 0) {
                throw new DomainException(DomainErrorCode.EMPTY_ORDER_ITEMS);
            }
            return new OrderCommand.Item(productId, quantity);
        }
    }

    public record CreateRequest(List<ItemRequest> items) {
        // 요청을 주문 생성 커맨드로 변환
        public OrderCommand.Create toCommand(long userId) {
            if (items == null || items.isEmpty()) {
                throw new DomainException(DomainErrorCode.EMPTY_ORDER_ITEMS);
            }
            List<OrderCommand.Item> commandItems = items.stream().map(ItemRequest::toCommandItem).toList();
            return new OrderCommand.Create(userId, commandItems);
        }
    }
}
