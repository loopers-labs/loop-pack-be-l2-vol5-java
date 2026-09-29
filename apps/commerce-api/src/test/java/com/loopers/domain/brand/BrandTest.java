package com.loopers.domain.brand;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandTest {

    private static String repeat(int length) {
        return "가".repeat(length);
    }

    @Nested
    @DisplayName("BRAND-001 · 브랜드는 이름을 가진다. 1~50자, 공백만은 안 된다")
    class Name {
        @DisplayName("이름과 설명으로 등록한다")
        @Test
        void registersWithNameAndDescription() {
            Brand brand = Brand.register("무신사", "패션 플랫폼");

            assertThat(brand.getName()).isEqualTo("무신사");
            assertThat(brand.getDescription()).isEqualTo("패션 플랫폼");
        }

        @DisplayName("이름이 null 이면 등록할 수 없다")
        @Test
        void rejectsNullName() {
            assertThatThrownBy(() -> Brand.register(null, "설명"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("이름이 비어 있거나 공백뿐이면 등록할 수 없다")
        @Test
        void rejectsBlankName() {
            assertThatThrownBy(() -> Brand.register("", "설명"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Brand.register("   ", "설명"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("이름이 50자를 넘으면 등록할 수 없다")
        @Test
        void rejectsTooLongName() {
            assertThatThrownBy(() -> Brand.register(repeat(51), "설명"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("이름 50자는 등록된다")
        @Test
        void acceptsNameAtUpperBound() {
            assertThat(Brand.register(repeat(50), "설명").getName()).hasSize(50);
        }
    }

    @Nested
    @DisplayName("BRAND-002 · 설명은 선택이며 0~200자다")
    class Description {
        @DisplayName("설명이 없어도 등록된다")
        @Test
        void allowsAbsentDescription() {
            assertThatCode(() -> Brand.register("무신사", null)).doesNotThrowAnyException();
        }

        @DisplayName("설명 200자는 등록된다")
        @Test
        void acceptsDescriptionAtUpperBound() {
            assertThatCode(() -> Brand.register("무신사", repeat(200))).doesNotThrowAnyException();
        }

        @DisplayName("설명이 200자를 넘으면 등록할 수 없다")
        @Test
        void rejectsTooLongDescription() {
            assertThatThrownBy(() -> Brand.register("무신사", repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("BRAND-003 · 삭제는 논리 삭제다. 지워진 브랜드는 고객 조회에서 빠진다")
    class Delete {
        @DisplayName("등록 직후에는 삭제되지 않은 상태다")
        @Test
        void isNotDeletedWhenRegistered() {
            assertThat(Brand.register("무신사", "설명").isDeleted()).isFalse();
        }

        @DisplayName("삭제하면 삭제된 상태가 된다")
        @Test
        void marksAsDeleted() {
            Brand brand = Brand.register("무신사", "설명");

            brand.delete();

            assertThat(brand.isDeleted()).isTrue();
        }

        @DisplayName("이미 삭제된 브랜드를 다시 삭제해도 결과가 같다")
        @Test
        void deleteIsIdempotent() {
            Brand brand = Brand.register("무신사", "설명");
            brand.delete();

            assertThatCode(brand::delete).doesNotThrowAnyException();

            assertThat(brand.isDeleted()).isTrue();
        }
    }

    @Nested
    @DisplayName("BRAND-005 · 삭제된 브랜드는 수정의 대상이 아니다")
    class Update {
        @DisplayName("이름과 설명을 바꾼다")
        @Test
        void updatesNameAndDescription() {
            Brand brand = Brand.register("무신사", "설명");

            brand.update("29CM", "새 설명");

            assertThat(brand.getName()).isEqualTo("29CM");
            assertThat(brand.getDescription()).isEqualTo("새 설명");
        }

        @DisplayName("수정에도 등록과 같은 이름·설명 규칙이 걸린다")
        @Test
        void appliesSameRulesAsRegister() {
            Brand brand = Brand.register("무신사", "설명");

            assertThatThrownBy(() -> brand.update("  ", "설명"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> brand.update(repeat(51), "설명"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> brand.update("무신사", repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("거절된 수정은 기존 값을 그대로 둔다")
        @Test
        void keepsExistingValuesWhenRejected() {
            Brand brand = Brand.register("무신사", "설명");

            assertThatThrownBy(() -> brand.update(repeat(51), "새 설명"))
                .isInstanceOf(IllegalArgumentException.class);

            assertThat(brand.getName()).isEqualTo("무신사");
            assertThat(brand.getDescription()).isEqualTo("설명");
        }

        @DisplayName("삭제된 브랜드는 수정할 수 없다")
        @Test
        void rejectsUpdateOnDeletedBrand() {
            Brand brand = Brand.register("무신사", "설명");
            brand.delete();

            assertThatThrownBy(() -> brand.update("29CM", "새 설명"))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.BRAND_ALREADY_DELETED);

            assertThat(brand.getName()).isEqualTo("무신사");
        }
    }
}
