package com.loopers.application.user;

import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointService {

    private final UserResolver userResolver;
    private final UserRepository userRepository;

    public PointService(UserResolver userResolver, UserRepository userRepository) {
        this.userResolver = userResolver;
        this.userRepository = userRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BalanceInfo charge(String requester, long amount) {
        long userId = userResolver.resolve(requester).userId();
        User user = userRepository.lockById(userId).orElseThrow(UserResolutionException::userNotFound);
        user.charge(amount);
        return new BalanceInfo(user.getBalance());
    }

    @Transactional(readOnly = true)
    public BalanceInfo balance(String requester) {
        long userId = userResolver.resolve(requester).userId();
        User user = userRepository.findById(userId).orElseThrow(UserResolutionException::userNotFound);
        return new BalanceInfo(user.getBalance());
    }
}
