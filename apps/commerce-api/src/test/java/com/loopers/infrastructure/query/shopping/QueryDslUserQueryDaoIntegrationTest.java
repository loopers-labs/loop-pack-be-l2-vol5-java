package com.loopers.infrastructure.query.shopping;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.shopping.query.UserQueryDao;
import com.loopers.application.shopping.query.UserView;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.fixtures.UserFixture;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class QueryDslUserQueryDaoIntegrationTest {
    @Autowired
    private UserQueryDao userQueryDao;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @DisplayName("사용자 ID로 조회할 때")
    @Nested
    class FindById {
        @DisplayName("JPA로 저장한 사용자를 같은 DB에서 JDBC 조회 모델로 읽는다")
        @Test
        void findsStoredUserAfterFlushAndClear() {
            // arrange
            userRepository.save(UserFixture.firstUser());
            userRepository.save(UserFixture.secondUser());
            entityManager.flush();
            entityManager.clear();

            // act
            var first = userQueryDao.findById(1L);
            var second = userQueryDao.findById(2L);

            // assert
            assertThat(first).contains(new UserView(1L));
            assertThat(second).contains(new UserView(2L));
        }

        @DisplayName("없는 사용자는 빈 결과를 반환하고 기존 사용자 저장 상태를 유지한다")
        @Test
        void returnsEmptyWithoutChangingStoredUser() {
            // arrange
            userRepository.save(UserFixture.firstUser());
            entityManager.flush();
            entityManager.clear();

            // act
            var missing = userQueryDao.findById(Long.MAX_VALUE);

            // assert
            assertThat(missing).isEmpty();
            assertThat(userQueryDao.findById(1L)).contains(new UserView(1L));
            assertThat(entityManager.createQuery("select count(u) from UserJpaEntity u", Long.class).getSingleResult())
                .isEqualTo(1L);
        }
    }
}
