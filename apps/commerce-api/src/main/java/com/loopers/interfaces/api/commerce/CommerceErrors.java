package com.loopers.interfaces.api.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandQueryException;
import com.loopers.application.product.ProductQueryException;
import com.loopers.application.user.UserResolutionException;
import com.loopers.domain.brand.BrandDeletionException;
import com.loopers.domain.brand.BrandNameException;
import com.loopers.domain.brand.BrandStateException;
import com.loopers.domain.user.PointsException;
import com.loopers.domain.product.ProductException;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderQuantityException;
import com.loopers.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;

@Component
public class CommerceErrors {
    public record Failure(int status, String code, String message) {
    }

    private final ObjectMapper mapper;

    public CommerceErrors(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Failure translate(Exception exception) {
        if (exception instanceof OrderException order) {
            return switch (order.getReason()) {
                case INVALID_ITEMS -> invalid();
                case ORDER_NOT_FOUND -> new Failure(404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.");
                case AMOUNT_LIMIT_EXCEEDED -> new Failure(409, "ORDER_AMOUNT_LIMIT_EXCEEDED", "주문 금액의 허용 범위를 초과합니다.");
            };
        }
        if (exception instanceof OrderQuantityException) {
            return invalid();
        }
        if (exception instanceof ProductQueryException product) {
            return product.getReason() == ProductQueryException.Reason.BRAND_NOT_FOUND
                ? new Failure(404, "BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다.")
                : new Failure(404, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.");
        }
        if (exception instanceof ProductException product) {
            return product.getReason() == ProductException.Reason.DELETED_PRODUCT
                ? new Failure(404, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.") : invalid();
        }
        if (exception instanceof ProductStockException stock) {
            return switch (stock.getReason()) {
                case INVALID_STOCK_QUANTITY -> invalid();
                case INSUFFICIENT_STOCK -> new Failure(409, "INSUFFICIENT_STOCK", "상품 재고가 부족합니다.");
                case INVALID_DEDUCTION_QUANTITY -> new Failure(500, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다.");
            };
        }
        if (exception instanceof PointsException points) {
            return switch (points.getReason()) {
                case INVALID_AMOUNT -> invalid();
                case INSUFFICIENT_POINTS -> new Failure(409, "INSUFFICIENT_POINTS", "포인트가 부족합니다.");
                case BALANCE_LIMIT_EXCEEDED -> new Failure(409, "POINT_BALANCE_LIMIT_EXCEEDED", "포인트 잔액의 허용 범위를 초과합니다.");
            };
        }
        if (exception instanceof UserResolutionException user) {
            return switch (user.getReason()) {
                case INVALID_USER_ID -> invalid();
                case USER_NOT_FOUND -> new Failure(404, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
                case ADMIN_REQUIRED -> new Failure(403, "FORBIDDEN", "관리자 권한이 필요합니다.");
            };
        }
        if (exception instanceof BrandQueryException || exception instanceof BrandStateException) {
            return new Failure(404, "BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다.");
        }
        if (exception instanceof BrandDeletionException deletion) {
            return deletion.getReason() == BrandDeletionException.Reason.BRAND_NOT_FOUND
                ? new Failure(404, "BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다.")
                : new Failure(409, "BRAND_HAS_PRODUCTS", "삭제되지 않은 상품이 연결되어 있습니다.");
        }
        if (exception instanceof InvalidRequestException || exception instanceof BrandNameException
            || exception instanceof MethodArgumentTypeMismatchException
            || exception instanceof MissingServletRequestParameterException
            || exception instanceof HttpMessageNotReadableException) {
            return invalid();
        }
        if (exception instanceof HttpRequestMethodNotSupportedException) {
            return new Failure(405, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다.");
        }
        if (exception instanceof HttpMediaTypeNotSupportedException) {
            return new Failure(415, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type입니다.");
        }
        if (exception instanceof HttpMediaTypeNotAcceptableException) {
            return new Failure(406, "NOT_ACCEPTABLE", "지원하지 않는 응답 형식입니다.");
        }
        if (exception instanceof PessimisticLockingFailureException) {
            return new Failure(409, "CONCURRENT_MODIFICATION", "다른 요청과 충돌했습니다. 다시 요청해 주세요.");
        }
        return new Failure(500, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다.");
    }

    public void write(HttpServletResponse response, Failure failure) throws IOException {
        response.setStatus(failure.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiResponse.fail(failure.code(), failure.message()));
    }

    private Failure invalid() {
        return new Failure(400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
    }
}
