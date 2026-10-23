package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderService;
import com.loopers.application.order.OrderPaymentCoordinator;
import com.loopers.application.order.OrderPaymentQueryService;
import com.loopers.application.order.OrderPaymentStockService;
import com.loopers.application.order.PaymentConfirmationDeadline;
import com.loopers.application.order.PaymentConfirmationTiming;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderPaymentMethod;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderV1Controller {
    private final OrderService service;
    private final OrderPaymentQueryService queries;
    private final ObjectProvider<OrderPaymentCoordinator> payments;
    private final PaymentConfirmationTiming timing;
    private final OrderPaymentStockService stocks;

    public OrderV1Controller(OrderService service, OrderPaymentQueryService queries,
                              ObjectProvider<OrderPaymentCoordinator> payments, PaymentConfirmationTiming timing,
                              OrderPaymentStockService stocks) {
        this.service = service;
        this.queries = queries;
        this.payments = payments;
        this.timing = timing;
        this.stocks = stocks;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderInfo> create(@RequestHeader(value = "X-USER-ID", required = false) String requester,
                                         @RequestBody JsonNode body) {
        StrictInput.object(body, "items");
        JsonNode items = body.get("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new InvalidRequestException();
        }
        List<OrderQuantities.Item> inputs = new ArrayList<>();
        for (JsonNode item : items) {
            StrictInput.object(item, "productId", "quantity");
            long productId = StrictInput.number(item, "productId");
            int quantity = StrictInput.integer(item, "quantity");
            if (productId <= 0 || quantity <= 0) {
                throw new InvalidRequestException();
            }
            inputs.add(new OrderQuantities.Item(productId, quantity));
        }
        return ApiResponse.success(service.create(requester, inputs));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<ApiResponse<OrderPaymentResponse>> confirm(
        @RequestHeader(value = "X-USER-ID", required = false) String requester, @PathVariable String orderId,
        @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        Object captured = request.getAttribute(PaymentRequestTimingFilter.STARTED_NANOS);
        long started = captured instanceof Long value ? value : timing.nanoTime();
        var deadline = PaymentConfirmationDeadline.start(timing, started);
        long id = StrictInput.id(orderId);
        var existing = queries.getDetail(requester, id);
        // Preserve ownership and historical bodyless POINT re-confirmation before interpreting PG-only inputs.
        if (existing.paymentMethod() == OrderPaymentMethod.POINT) {
            throw new OrderException(OrderException.Reason.ORDER_ALREADY_CONFIRMED);
        }
        var input = PaymentConfirmRequest.from(body);
        var coordinator = payments.getIfAvailable();
        if (coordinator == null) {
            // Only compare an existing attempt: the service rejects DRAFT before any reservation or fingerprint creation.
            stocks.reuseCustomer(requester, id, input.paymentAttemptKey(), input.cardType(), input.cardNo());
        } else {
            coordinator.startAndAwait(requester, id, input.paymentAttemptKey(), input.cardType(), input.cardNo(), deadline);
        }
        var response = OrderPaymentResponse.from(queries.getDetail(requester, id));
        return ResponseEntity.status(response.confirmHttpStatus()).body(ApiResponse.success(response));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderPaymentResponse> detail(@RequestHeader(value = "X-USER-ID", required = false) String requester,
                                         @PathVariable String orderId) {
        return ApiResponse.success(OrderPaymentResponse.from(queries.getDetail(requester, StrictInput.id(orderId))));
    }

    @GetMapping
    public ApiResponse<PageResult<OrderPaymentResponse>> list(
        @RequestHeader(value = "X-USER-ID", required = false) String requester,
        @RequestParam(required = false) String page, @RequestParam(required = false) String size) {
        return ApiResponse.success(queries.getPage(requester, StrictInput.page(page), StrictInput.size(size))
            .map(OrderPaymentResponse::from));
    }
}
