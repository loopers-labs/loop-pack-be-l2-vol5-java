package com.loopers.infrastructure.user;

import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class UserRepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("저장한 사용자는 존재하고, 저장하지 않은 식별자는 존재하지 않는다.")
    @Test
    void existsOnlyForSavedUser() {
        // arrange
        User user = userRepository.save(new User());

        // act & assert
        assertAll(
            () -> assertThat(userRepository.exists(user.getId())).isTrue(),
            () -> assertThat(userRepository.exists(user.getId() + 1)).isFalse()
        );
    }
}
