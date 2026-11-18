package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class ApiControllerAdvice {
    private static final int MYSQL_DEADLOCK = 1213;
    /** 장애 회복을 기다리는 간격의 잠정값(초). 다시 확인할 시각의 안내일 뿐 발급 약속이 아님 (8주차 D-148) */
    private static final String COUPON_UNAVAILABLE_RETRY_AFTER = "10";

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handle(CoreException e) {
        log.warn("CoreException : {}", e.getCustomMessage() != null ? e.getCustomMessage() : e.getMessage(), e);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ErrorStatusMapper.statusOf(e.getErrorCode()));
        if (e.getErrorCode() == CouponErrorCode.COUPON_ISSUE_UNAVAILABLE) {
            builder.header(HttpHeaders.RETRY_AFTER, COUPON_UNAVAILABLE_RETRY_AFTER);
        }
        return builder.body(ApiResponse.fail(e.getErrorCode().getCode(), e.getMessage(), e.getDetail()));
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        String type = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown";
        String value = e.getValue() != null ? e.getValue().toString() : "null";
        String message = String.format("요청 파라미터 '%s' (타입: %s)의 값 '%s'이(가) 잘못되었습니다.", name, type, value);
        return failureResponse(ErrorType.BAD_REQUEST, message);
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
        String errorMessage;
        Throwable rootCause = e.getRootCause();

        if (rootCause instanceof InvalidFormatException invalidFormat) {
            String fieldName = invalidFormat.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "?")
                .collect(Collectors.joining("."));

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
                fieldName, value, expectedType, valueIndicationMessage);

        } else if (rootCause instanceof MismatchedInputException mismatchedInput) {
            String fieldPath = mismatchedInput.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "?")
                .collect(Collectors.joining("."));
            errorMessage = String.format("필수 필드 '%s'이(가) 누락되었습니다.", fieldPath);

        } else if (rootCause instanceof JsonMappingException jsonMapping) {
            String fieldPath = jsonMapping.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "?")
                .collect(Collectors.joining("."));
            errorMessage = String.format("필드 '%s'에서 JSON 매핑 오류가 발생했습니다: %s",
                fieldPath, jsonMapping.getOriginalMessage());

        } else {
            errorMessage = "요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.";
        }

        return failureResponse(ErrorType.BAD_REQUEST, errorMessage);
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

    /** 대기 초과와 교착은 같은 코드로 응답하되, 교착은 잠금 순서가 깨졌다는 신호라 error 로 남김 (3주차 설계 4.5) */
    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handle(PessimisticLockingFailureException e) {
        if (sqlErrorCodeOf(e) == MYSQL_DEADLOCK) {
            log.error("Deadlock : {}", e.getMessage(), e);
        } else {
            log.warn("Lock acquisition failed : {}", e.getMessage(), e);
        }
        return failureResponse(ErrorType.LOCK_ACQUISITION_FAILED, null);
    }

    /** 버전 행을 바꾸는 경로는 모두 ~Retrier 를 거치므로 여기 오면 재시도 한도 초과임 (3주차 설계 4.4) */
    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handle(OptimisticLockingFailureException e) {
        log.warn("Concurrent update retries exhausted : {}", e.getMessage(), e);
        return failureResponse(ErrorType.CONCURRENT_UPDATE_CONFLICT, null);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handle(Throwable e) {
        log.error("Exception : {}", e.getMessage(), e);
        return failureResponse(ErrorType.INTERNAL_ERROR, null);
    }

    private static int sqlErrorCodeOf(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getErrorCode();
            }
        }
        return 0;
    }

    private String extractMissingParameter(String message) {
        Pattern pattern = Pattern.compile("'(.+?)'");
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private ResponseEntity<ApiResponse<?>> failureResponse(ErrorCode errorCode, String errorMessage) {
        return ResponseEntity.status(ErrorStatusMapper.statusOf(errorCode))
            .body(ApiResponse.fail(errorCode.getCode(), errorMessage != null ? errorMessage : errorCode.getMessage()));
    }
}
