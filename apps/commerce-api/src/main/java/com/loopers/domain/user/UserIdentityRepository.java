package com.loopers.domain.user;

import java.util.Optional;

public interface UserIdentityRepository {

    /**
     * 외부 식별값의 대소문자·공백을 변경하지 않고 연결된 ID와 권한을 조회한다.
     * DB 사용자 행의 존재 확인은 별도 책임이다.
     */
    Optional<UserIdentity> findByExternalId(String externalId);

    Optional<String> findExternalId(long userId);
}
