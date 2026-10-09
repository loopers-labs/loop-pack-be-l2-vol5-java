package com.loopers.infrastructure.initializer.shopping;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.shopping.query.UserQueryDao;
import com.loopers.domain.pay.repository.WalletRepository;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.infrastructure.persistence.pay.entity.QWalletJpaEntity;
import com.loopers.infrastructure.persistence.shopping.entity.QUserJpaEntity;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class LocalUserFixtureInitializerIntegrationTest {
    private static final QUserJpaEntity USER = QUserJpaEntity.userJpaEntity;
    private static final QWalletJpaEntity WALLET = QWalletJpaEntity.walletJpaEntity;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserQueryDao userQueryDao;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JPAQueryFactory queryFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("실제 DB에서 fixture를 초기화할 때")
    @Nested
    class Initialize {
        @DisplayName("각 트랜잭션에서 반복 실행해도 기존 사용자와 fixture를 보존한다")
        @Test
        void initializesIdempotentlyAcrossTransactions() {
            // arrange
            userRepository.save(User.create(1L));
            userRepository.save(User.create(3L));
            LocalUserFixtureInitializer initializer =
                new LocalUserFixtureInitializer(userRepository, userQueryDao, walletRepository);
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);

            // act
            transaction.executeWithoutResult(status -> initializer.run(null));
            transaction.executeWithoutResult(status -> initializer.run(null));

            // assert
            List<Long> ids = queryFactory.select(USER.id).from(USER).orderBy(USER.id.asc()).fetch();
            assertThat(ids).containsExactly(1L, 2L, 3L);
            List<Long> walletUserIds = queryFactory.select(WALLET.userId).from(WALLET).orderBy(WALLET.userId.asc()).fetch();
            assertThat(walletUserIds).containsExactly(1L, 2L);
        }
    }
}
