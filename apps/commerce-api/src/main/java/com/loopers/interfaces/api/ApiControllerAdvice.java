package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 설계 4-4 ER-01~22 매핑. 도메인 예외(CoreException)는 ErrorType 그대로,
 * 요청 형식 오류는 파라미터·필드 이름으로 요구사항이 이름을 준 코드에 매핑하고 나머지는 ER-22 BAD_REQUEST.
 */
@RestControllerAdvice
@Slf4j
public class ApiControllerAdvice {

    /**
     * 경로·쿼리 파라미터 타입 오류. 경로 ID 형식 오류는 해당 *_NOT_FOUND (DR-01),
     * page·size 는 ER-08. 그 밖은 ER-22.
     */
    private static final Map<String, ErrorType> PARAMETER_ERRORS = Map.of(
        "brandId", ErrorType.BRAND_NOT_FOUND,
        "productId", ErrorType.PRODUCT_NOT_FOUND,
        "orderId", ErrorType.ORDER_NOT_FOUND,
        "userId", ErrorType.USER_NOT_FOUND,
        "page", ErrorType.INVALID_PAGE,
        "size", ErrorType.INVALID_PAGE
    );

    /**
     * 바디 필드 타입 오류 중 요구사항이 이름을 준 것. 바디의 ID 필드(userId, brandId, productId)는 ER-22 (4-4 메모).
     */
    private static final Map<String, ErrorType> BODY_FIELD_ERRORS = Map.of(
        "amount", ErrorType.INVALID_AMOUNT,
        "quantity", ErrorType.INVALID_QUANTITY,
        "stock", ErrorType.INVALID_STOCK,
        "price", ErrorType.INVALID_PRODUCT_PRICE
    );

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handle(CoreException e) {
        log.warn("CoreException : {}", e.getCustomMessage() != null ? e.getCustomMessage() : e.getMessage(), e);
        return failureResponse(e.getErrorType(), e.getCustomMessage());
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        String type = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown";
        String value = e.getValue() != null ? e.getValue().toString() : "null";
        String message = String.format("요청 파라미터 '%s' (타입: %s)의 값 '%s'이(가) 잘못되었습니다.", name, type, value);
        return failureResponse(PARAMETER_ERRORS.getOrDefault(name, ErrorType.BAD_REQUEST), message);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(MissingServletRequestParameterException e) {
        String name = e.getParameterName();
        String type = e.getParameterType();
        String message = String.format("필수 요청 파라미터 '%s' (타입: %s)가 누락되었습니다.", name, type);
        return failureResponse(ErrorType.BAD_REQUEST, message);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(HttpMessageNotReadableException e) {
        JsonMappingException mapping = findMappingException(e);
        if (mapping == null) {
            return failureResponse(ErrorType.BAD_REQUEST, "요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.");
        }

        String fieldPath = mapping.getPath().stream()
            .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "?")
            .collect(Collectors.joining("."));
        String lastField = mapping.getPath().isEmpty() ? "" : lastFieldName(mapping);
        ErrorType errorType = BODY_FIELD_ERRORS.getOrDefault(lastField, ErrorType.BAD_REQUEST);

        String errorMessage;
        if (mapping instanceof InvalidFormatException invalidFormat) {
            String valueIndicationMessage = "";
            if (invalidFormat.getTargetType().isEnum()) {
                Class<?> enumClass = invalidFormat.getTargetType();
                String enumValues = Arrays.stream(enumClass.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
                valueIndicationMessage = "사용 가능한 값 : [" + enumValues + "]";
            }
            String expectedType = invalidFormat.getTargetType().getSimpleName();
            Object value = invalidFormat.getValue();
            errorMessage = String.format("필드 '%s'의 값 '%s'이(가) 예상 타입(%s)과 일치하지 않습니다. %s",
                fieldPath, value, expectedType, valueIndicationMessage);
        } else if (mapping instanceof MismatchedInputException) {
            errorMessage = String.format("필드 '%s'의 값이 올바르지 않습니다.", fieldPath);
        } else {
            errorMessage = String.format("필드 '%s'에서 JSON 매핑 오류가 발생했습니다: %s",
                fieldPath, mapping.getOriginalMessage());
        }
        return failureResponse(errorType, errorMessage);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(ServerWebInputException e) {
        String missingParams = extractMissingParameter(e.getReason() != null ? e.getReason() : "");
        if (!missingParams.isEmpty()) {
            String message = String.format("필수 요청 값 '%s'가 누락되었습니다.", missingParams);
            return failureResponse(ErrorType.BAD_REQUEST, message);
        } else {
            return failureResponse(ErrorType.BAD_REQUEST, null);
        }
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleNotFound(NoResourceFoundException e) {
        return failureResponse(ErrorType.NOT_FOUND, null);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handle(Throwable e) {
        log.error("Exception : {}", e.getMessage(), e);
        return failureResponse(ErrorType.INTERNAL_ERROR, null);
    }

    /** 원인 사슬에서 경로(path)를 가진 가장 바깥의 JsonMappingException. 숫자 범위 초과처럼 루트 원인이 경로를 잃는 경우를 위해. */
    private JsonMappingException findMappingException(Throwable e) {
        JsonMappingException found = null;
        for (Throwable cause = e.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof JsonMappingException mapping) {
                if (found == null || (!mapping.getPath().isEmpty() && found.getPath().isEmpty())) {
                    found = mapping;
                }
            }
        }
        return found;
    }

    private String lastFieldName(JsonMappingException mapping) {
        for (int i = mapping.getPath().size() - 1; i >= 0; i--) {
            String name = mapping.getPath().get(i).getFieldName();
            if (name != null) {
                return name;
            }
        }
        return "";
    }

    private String extractMissingParameter(String message) {
        Pattern pattern = Pattern.compile("'(.+?)'");
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private ResponseEntity<ApiResponse<?>> failureResponse(ErrorType errorType, String errorMessage) {
        return ResponseEntity.status(errorType.getStatus())
            .body(ApiResponse.fail(errorType.getCode(), errorMessage != null ? errorMessage : errorType.getMessage()));
    }
}
