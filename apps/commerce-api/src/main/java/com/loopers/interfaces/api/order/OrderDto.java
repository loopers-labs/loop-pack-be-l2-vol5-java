package com.loopers.interfaces.api.order;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.order.dto.OrderItemInfo;
import com.loopers.application.order.dto.OrderSummaryInfo;
import com.loopers.application.order.dto.PaymentInfo;
import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.dto.PaymentCheckInfo;
import com.loopers.application.order.dto.OrderConfirmCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderDto {

    // 필수 누락(@NotNull)은 요청 형식 오류라 여기서 거절한다. 빈 목록·수량 범위·중복 같은 값 규칙은 도메인(OrderLines)이 판단한다.
    public record CreateRequest(@NotNull List<@NotNull @Valid ItemRequest> items) {
        public List<OrderItemCommand> toCommands() {
            return items.stream().map(ItemRequest::toCommand).toList();
        }
    }

    public record ItemRequest(@NotNull Long productId, @NotNull Integer quantity) {
        OrderItemCommand toCommand() {
            return new OrderItemCommand(productId, quantity);
        }
    }

    // 결제 시작 요청. 금액은 받지 않는다(저장된 주문 합계로 정한다). 카드 값은 합성 값이며 저장하지 않고 문자열 표현에 드러내지 않는다.
    // 값 규칙은 형식만 본다. 같은 시도의 중복·충돌은 application 이 판단한다.
    public record ConfirmRequest(
        @NotBlank @Size(max = 64) String paymentAttemptKey,
        @NotBlank String cardType,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{4}-\\d{4}-\\d{4}") String cardNo
    ) {
        public OrderConfirmCommand toCommand(Long userId, Long orderId) {
            return new OrderConfirmCommand(userId, orderId, paymentAttemptKey, cardType, cardNo);
        }

        @Override
        public String toString() {
            return "ConfirmRequest[paymentAttemptKey=" + paymentAttemptKey + ", cardType=" + cardType + ", cardNo=****]";
        }
    }

    // 결제 시작·확인의 응답. 처리 중이거나 결과 미확인이면 202, PG 결과로 종결한 시도면 200 이다. 주문 상태와 시도 상태를
    // 함께 담고, 최종 결과를 알려면 links.order 로 조회한다. lookupUnavailable 은 PG 를 조회하지 못해 저장된 상태를 그대로
    // 돌려줬을 때만 true 이며, 실패가 아니라 아직 확인하지 못했다는 뜻이다(조회했다면 응답에서 생략된다).
    public record PaymentStartResponse(
        Long orderId, String orderStatus, AttemptResponse payment, LinksResponse links, Boolean lookupUnavailable
    ) {
        public static PaymentStartResponse from(PaymentAttemptInfo info) {
            return new PaymentStartResponse(
                info.orderId(),
                info.orderStatus().name(),
                new AttemptResponse(info.attemptStatus().name(), info.amount()),
                new LinksResponse("/api/v1/orders/" + info.orderId()),
                null
            );
        }

        public static PaymentStartResponse from(PaymentCheckInfo result) {
            PaymentStartResponse base = from(result.info());
            return new PaymentStartResponse(
                base.orderId(), base.orderStatus(), base.payment(), base.links(),
                result.lookupFailed() ? Boolean.TRUE : null
            );
        }
    }

    public record AttemptResponse(String status, long amount) {}

    public record LinksResponse(String order) {}

    // payment 는 확정된 주문에만 있고, 확정 전(DRAFT)에는 null 이다. orderedAt 은 주문을 만든 시각이다.
    public record OrderResponse(
        Long id,
        String status,
        List<ItemResponse> items,
        long totalAmount,
        PaymentResponse payment,
        ZonedDateTime orderedAt
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.status().name(),
                info.items().stream().map(ItemResponse::from).toList(),
                info.totalAmount(),
                PaymentResponse.from(info.payment()),
                info.orderedAt()
            );
        }
    }

    // 목록의 항목. 품목은 담지 않고 개수만 담는다. 품목은 상세에서 본다.
    public record OrderSummaryResponse(
        Long id, String status, long totalAmount, int itemCount, PaymentResponse payment, ZonedDateTime orderedAt
    ) {
        public static OrderSummaryResponse from(OrderSummaryInfo info) {
            return new OrderSummaryResponse(
                info.id(),
                info.status().name(),
                info.totalAmount(),
                info.itemCount(),
                PaymentResponse.from(info.payment()),
                info.orderedAt()
            );
        }
    }

    // 결제한 금액. 결제 수단의 상태(포인트 잔액)는 담지 않는다(잔액은 포인트 조회 API).
    public record PaymentResponse(long paidAmount) {
        // 확정 전(DRAFT)에는 결제 결과가 없으므로 null 을 받으면 null 을 돌려준다. null 필드는 응답에서 생략된다.
        public static PaymentResponse from(PaymentInfo info) {
            if (info == null) {
                return null;
            }
            return new PaymentResponse(info.paidAmount());
        }
    }

    // 상품명과 단가는 주문 당시 값이다.
    public record ItemResponse(Long productId, String productName, int quantity, long unitPrice, long amount) {
        public static ItemResponse from(OrderItemInfo info) {
            return new ItemResponse(
                info.productId(), info.productName(), info.quantity(), info.unitPrice(), info.amount()
            );
        }
    }
}
