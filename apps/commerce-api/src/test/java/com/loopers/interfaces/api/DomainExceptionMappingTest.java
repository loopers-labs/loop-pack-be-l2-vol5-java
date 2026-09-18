package com.loopers.interfaces.api;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.support.error.Failure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DomainExceptionMappingTest {

    private static ResponseEntity<ApiResponse<?>> respondTo(DomainError error) {
        return new ApiControllerAdvice().handle(new DomainException(error));
    }

    @DisplayName("규칙 위반은 409 와 업무 코드로 나간다. 전송 계층이 업무 의미를 뭉개지 않는다.")
    @Test
    void ruleViolationBecomesConflictWithBusinessCode() {
        ResponseEntity<ApiResponse<?>> response = respondTo(DomainError.INSUFFICIENT_STOCK);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().errorCode()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
    }

    @DisplayName("대상 미특정은 404 와 업무 코드로 나간다. 없는 것과 삭제된 것을 구분하지 않는다.")
    @Test
    void unidentifiedBecomesNotFoundWithBusinessCode() {
        ResponseEntity<ApiResponse<?>> response = respondTo(DomainError.BRAND_NOT_FOUND);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().errorCode()).isEqualTo("BRAND_NOT_FOUND");
    }

    @DisplayName("참조 오류는 400 으로 나간다. 리소스는 있고 보낸 값이 틀린 것이다.")
    @Test
    void invalidReferenceBecomesBadRequest() {
        ResponseEntity<ApiResponse<?>> response = respondTo(DomainError.BRAND_NOT_AVAILABLE);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().errorCode()).isEqualTo("BRAND_NOT_AVAILABLE");
    }

    @DisplayName("성질마다 그것을 쓰는 실패가 하나 이상 있다 — 쓰이지 않는 성질은 매핑을 검증할 수 없다.")
    @Test
    void everyFailureKindIsUsedByAtLeastOneError() {
        for (Failure failure : Failure.values()) {
            assertThat(Arrays.stream(DomainError.values()).anyMatch(error -> error.failure() == failure))
                .describedAs("이 성질을 쓰는 DomainError 가 없다: %s", failure)
                .isTrue();
        }
    }

    @DisplayName("모든 실패는 성질과 메시지를 갖는다. 코드는 상수 이름과 같다.")
    @Test
    void everyErrorCarriesFailureAndMessage() {
        for (DomainError error : DomainError.values()) {
            assertThat(error.failure()).describedAs("%s 의 성질", error).isNotNull();
            assertThat(error.message()).describedAs("%s 의 메시지", error).isNotBlank();
            assertThat(error.code()).isEqualTo(error.name());
        }
    }

    @DisplayName("설계된 실패는 스택 트레이스를 만들지 않는다. 예상된 실패라 흔하고, code 로 추적한다.")
    @Test
    void designedFailureCarriesNoStackTrace() {
        assertThat(new DomainException(DomainError.INSUFFICIENT_STOCK).getStackTrace()).isEmpty();
    }

    @DisplayName("상황 정보를 덧붙이면 메시지만 바뀌고 코드와 성질은 그대로다.")
    @Test
    void customMessageKeepsCodeAndFailure() {
        DomainException exception =
            new DomainException(DomainError.POINT_BALANCE_EXCEEDED, "잔액 100 에 200 을 더하면 표현 범위를 넘습니다.");

        assertThat(exception.code()).isEqualTo("POINT_BALANCE_EXCEEDED");
        assertThat(exception.failure()).isEqualTo(Failure.RULE_VIOLATION);
        assertThat(exception.getMessage()).contains("100", "200");
    }
}
