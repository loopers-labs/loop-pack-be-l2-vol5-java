package com.loopers.infrastructure.shopping.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.loopers.application.shopping.user.UserQueryDao;
import com.loopers.application.shopping.user.UserQueryModel;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
import com.loopers.domain.shopping.user.User;
import com.loopers.domain.shopping.user.UserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LocalUserFixtureInitializerTest {
    @DisplayName("local fixture를 초기화할 때")
    @Nested
    class Initialize {
        @DisplayName("반복 실행해도 사용자 1과 2, 각각의 초기 잔액 0 지갑만 한 번씩 저장한다")
        @Test
        void initializesTwoUsersIdempotently() {
            // arrange
            InMemoryUserRepository repository = new InMemoryUserRepository();
            InMemoryWalletRepository walletRepository = new InMemoryWalletRepository();
            UserQueryDao dao = id -> Optional.ofNullable(repository.users.get(id))
                .map(user -> new UserQueryModel(user.getId()));
            LocalUserFixtureInitializer initializer = new LocalUserFixtureInitializer(repository, dao, walletRepository);

            // act
            initializer.run(null);
            initializer.run(null);

            // assert
            assertThat(repository.users).containsOnlyKeys(1L, 2L);
            assertThat(repository.savedIds).containsExactly(1L, 2L);
            assertThat(walletRepository.wallets).containsOnlyKeys(1L, 2L);
            assertThat(walletRepository.savedUserIds).containsExactly(1L, 2L);
        }

        @DisplayName("이미 있는 사용자·지갑은 다시 저장하지 않고 다른 사용자도 보존한다")
        @Test
        void preservesExistingUsersAndOnlySavesMissingFixture() {
            // arrange
            InMemoryUserRepository repository = new InMemoryUserRepository();
            User existing = User.create(1L);
            User other = User.create(3L);
            repository.users.put(1L, existing);
            repository.users.put(3L, other);
            InMemoryWalletRepository walletRepository = new InMemoryWalletRepository();
            Wallet existingWallet = Wallet.zero(1L);
            walletRepository.wallets.put(1L, existingWallet);
            UserQueryDao dao = mock(UserQueryDao.class);
            given(dao.findById(1L)).willReturn(Optional.of(new UserQueryModel(1L)));
            given(dao.findById(2L)).willReturn(Optional.empty());
            LocalUserFixtureInitializer initializer = new LocalUserFixtureInitializer(repository, dao, walletRepository);

            // act
            initializer.run(null);

            // assert
            assertThat(repository.users).containsOnlyKeys(1L, 2L, 3L);
            assertThat(repository.users.get(1L)).isSameAs(existing);
            assertThat(repository.users.get(3L)).isSameAs(other);
            assertThat(repository.savedIds).containsExactly(2L);
            assertThat(walletRepository.wallets).containsOnlyKeys(1L, 2L);
            assertThat(walletRepository.wallets.get(1L)).isSameAs(existingWallet);
            assertThat(walletRepository.savedUserIds).containsExactly(2L);
            verify(dao).findById(1L);
            verify(dao).findById(2L);
            verifyNoMoreInteractions(dao);
        }
    }

    private static class InMemoryUserRepository implements UserRepository {
        private final Map<Long, User> users = new HashMap<>();
        private final List<Long> savedIds = new ArrayList<>();

        @Override
        public User save(User user) {
            users.put(user.getId(), user);
            savedIds.add(user.getId());
            return user;
        }
    }

    private static class InMemoryWalletRepository implements WalletRepository {
        private final Map<Long, Wallet> wallets = new HashMap<>();
        private final List<Long> savedUserIds = new ArrayList<>();

        @Override
        public Wallet save(Wallet wallet) {
            wallets.put(wallet.getUserId(), wallet);
            savedUserIds.add(wallet.getUserId());
            return wallet;
        }

        @Override
        public Optional<Wallet> findByUserId(long userId) {
            return Optional.ofNullable(wallets.get(userId));
        }

        @Override
        public Optional<Wallet> findByUserIdForUpdate(long userId) {
            return Optional.ofNullable(wallets.get(userId));
        }
    }
}
