package com.loopers.interfaces.api;

import com.loopers.support.error.ErrorCode;
import com.loopers.support.error.ErrorType;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class ErrorStatusMapperTest {

    @DisplayName("ErrorCode 를 구현한 모든 enum 의 모든 값은 HTTP 상태 대응표에 있다.")
    @Test
    void mapsEveryErrorCode() {
        // arrange
        List<ErrorCode> errorCodes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.loopers")
            .stream()
            .filter(JavaClass::isEnum)
            .filter(javaClass -> javaClass.isAssignableTo(ErrorCode.class))
            .flatMap(javaClass -> Arrays.stream(javaClass.reflect().getEnumConstants()))
            .map(ErrorCode.class::cast)
            .toList();

        // act & assert
        assertThat(errorCodes).isNotEmpty();
        assertThat(errorCodes).allSatisfy(errorCode -> assertThat(ErrorStatusMapper.isMapped(errorCode))
            .as("%s 가 대응표에 없다", errorCode.getCode())
            .isTrue());
    }

    @DisplayName("공통 오류 코드는 정해진 HTTP 상태로 바뀐다.")
    @Test
    void keepsStatusOfCommonErrorTypes() {
        assertAll(
            () -> assertThat(ErrorStatusMapper.statusOf(ErrorType.INTERNAL_ERROR)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR),
            () -> assertThat(ErrorStatusMapper.statusOf(ErrorType.BAD_REQUEST)).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(ErrorStatusMapper.statusOf(ErrorType.UNAUTHENTICATED)).isEqualTo(HttpStatus.UNAUTHORIZED),
            () -> assertThat(ErrorStatusMapper.statusOf(ErrorType.NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(ErrorStatusMapper.statusOf(ErrorType.CONFLICT)).isEqualTo(HttpStatus.CONFLICT)
        );
    }

    @DisplayName("대응표에 없는 코드는 500 으로 바뀐다.")
    @Test
    void returnsInternalServerError_whenErrorCodeIsNotMapped() {
        // arrange
        ErrorCode unmapped = UnmappedErrorCode.UNMAPPED;

        // act
        HttpStatus status = ErrorStatusMapper.statusOf(unmapped);

        // assert
        assertThat(status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private enum UnmappedErrorCode implements ErrorCode {
        UNMAPPED;

        @Override
        public String getCode() {
            return name();
        }

        @Override
        public String getMessage() {
            return "대응표에 없는 코드";
        }
    }
}
