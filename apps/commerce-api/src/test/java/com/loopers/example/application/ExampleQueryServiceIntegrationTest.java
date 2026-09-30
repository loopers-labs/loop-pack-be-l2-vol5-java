package com.loopers.example.application;

import com.loopers.example.adapter.out.persistence.ExampleJpaRepository;
import com.loopers.example.application.port.in.ExampleInfo;
import com.loopers.example.domain.ExampleModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ExampleQueryServiceIntegrationTest {
    @Autowired
    private ExampleQueryService exampleQueryService;

    @Autowired
    private ExampleJpaRepository exampleJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("예시를 조회할 때,")
    @Nested
    class Get {
        @DisplayName("존재하는 예시 ID를 주면, 해당 예시 정보를 반환한다.")
        @Test
        void returnsExampleInfo_whenValidIdIsProvided() {
            // arrange
            ExampleModel exampleModel = exampleJpaRepository.save(
                new ExampleModel("예시 제목", "예시 설명")
            );

            // act
            ExampleInfo result = exampleQueryService.getExample(exampleModel.getId());

            // assert
            assertAll(
                () -> assertThat(result).isNotNull(),
                () -> assertThat(result.id()).isEqualTo(exampleModel.getId()),
                () -> assertThat(result.name()).isEqualTo(exampleModel.getName()),
                () -> assertThat(result.description()).isEqualTo(exampleModel.getDescription())
            );
        }

        @DisplayName("존재하지 않는 예시 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenInvalidIdIsProvided() {
            // arrange
            Long invalidId = 999L; // Assuming this ID does not exist

            // act
            CoreException exception = assertThrows(CoreException.class, () -> {
                exampleQueryService.getExample(invalidId);
            });

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
