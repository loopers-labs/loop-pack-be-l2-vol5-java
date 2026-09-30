package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandModelTest {
    @DisplayName("브랜드 모델을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("이름, 설명, 카테고리가 모두 주어지면, 정상적으로 생성된다.")
        @Test
        void createsBrandModel_whenAllFieldsAreProvided() {
            // arrange
            String name = "나이키";
            String description = "스포츠 브랜드";
            String category = "신발/의류";

            // act
            BrandModel brand = new BrandModel(name, description, category);

            // assert
            assertAll(
                () -> assertThat(brand.getId()).isNotNull(),
                () -> assertThat(brand.getName()).isEqualTo(name),
                () -> assertThat(brand.getDescription()).isEqualTo(description),
                () -> assertThat(brand.getCategory()).isEqualTo(category)
            );
        }

        @DisplayName("이름이 빈 값이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("   ", "설명", "카테고리")
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("이름이 255자이면, 정상적으로 생성된다.")
        @Test
        void createsBrandModel_whenNameIsExactly255Characters() {
            // arrange
            String name = "가".repeat(255);

            // act
            BrandModel brand = new BrandModel(name, "설명", "카테고리");

            // assert
            assertThat(brand.getName()).hasSize(255);
        }

        @DisplayName("이름이 255자를 초과하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameExceeds255Characters() {
            // arrange
            String name = "가".repeat(256);

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel(name, "설명", "카테고리")
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("설명이 빈 값이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenDescriptionIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("이름", "", "카테고리")
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("설명이 500자이면, 정상적으로 생성된다.")
        @Test
        void createsBrandModel_whenDescriptionIsExactly500Characters() {
            // arrange
            String description = "가".repeat(500);

            // act
            BrandModel brand = new BrandModel("이름", description, "카테고리");

            // assert
            assertThat(brand.getDescription()).hasSize(500);
        }

        @DisplayName("설명이 500자를 초과하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenDescriptionExceeds500Characters() {
            // arrange
            String description = "가".repeat(501);

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("이름", description, "카테고리")
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("카테고리가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenCategoryIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("이름", "설명", null)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("카테고리가 255자를 초과하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenCategoryExceeds255Characters() {
            // arrange
            String category = "가".repeat(256);

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("이름", "설명", category)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("브랜드 모델을 수정할 때, ")
    @Nested
    class Update {
        @DisplayName("이름, 설명, 카테고리가 모두 주어지면, 정상적으로 수정된다.")
        @Test
        void updatesBrandModel_whenAllFieldsAreProvided() {
            // arrange
            BrandModel brand = new BrandModel("나이키", "스포츠 브랜드", "신발/의류");

            // act
            brand.update("아디다스", "독일 스포츠 브랜드", "신발");

            // assert
            assertAll(
                () -> assertThat(brand.getName()).isEqualTo("아디다스"),
                () -> assertThat(brand.getDescription()).isEqualTo("독일 스포츠 브랜드"),
                () -> assertThat(brand.getCategory()).isEqualTo("신발")
            );
        }

        @DisplayName("이름이 빈 값이면, BAD_REQUEST 예외가 발생하고 기존 값이 유지된다.")
        @Test
        void throwsBadRequestException_whenNameIsBlank() {
            // arrange
            BrandModel brand = new BrandModel("나이키", "스포츠 브랜드", "신발/의류");

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                brand.update("   ", "독일 스포츠 브랜드", "신발")
            );

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(brand.getName()).isEqualTo("나이키")
            );
        }

        @DisplayName("설명이 500자를 초과하면, BAD_REQUEST 예외가 발생하고 기존 값이 유지된다.")
        @Test
        void throwsBadRequestException_whenDescriptionExceeds500Characters() {
            // arrange
            BrandModel brand = new BrandModel("나이키", "스포츠 브랜드", "신발/의류");
            String tooLong = "가".repeat(501);

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                brand.update("아디다스", tooLong, "신발")
            );

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(brand.getDescription()).isEqualTo("스포츠 브랜드")
            );
        }
    }
}
