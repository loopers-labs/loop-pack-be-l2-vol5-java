package com.loopers.fixture;

import com.loopers.domain.point.PointModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import org.springframework.stereotype.Component;

/**
 * 실습용 사용자 준비 코드. User 생성 API 가 없으므로 User 와 잔액 0 인 Point 를 함께 저장한다.
 */
@Component
public class UserFixture {

    private final UserJpaRepository userJpaRepository;
    private final PointJpaRepository pointJpaRepository;

    public UserFixture(UserJpaRepository userJpaRepository, PointJpaRepository pointJpaRepository) {
        this.userJpaRepository = userJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
    }

    /** User 와 잔액 0 인 Point 를 함께 저장하고 User 를 반환한다. */
    public UserModel createUserWithPoint() {
        UserModel user = userJpaRepository.save(UserModel.create());
        pointJpaRepository.save(PointModel.of(user.getId()));
        return user;
    }

    /** Point 없이 User 만 저장한다. 포인트 초기화 불변식 위반을 검증할 때 사용한다. */
    public UserModel createUserWithoutPoint() {
        return userJpaRepository.save(UserModel.create());
    }
}
