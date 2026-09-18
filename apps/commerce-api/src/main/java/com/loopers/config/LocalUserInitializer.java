package com.loopers.config;

import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 로컬 실행에서 바로 X-USER-ID 로 요청할 수 있도록 실습용 사용자를 만든다. local 프로필에서만 동작한다. */
@Slf4j
@Profile("local")
@RequiredArgsConstructor
@Component
public class LocalUserInitializer implements ApplicationRunner {

    private static final int LOCAL_USER_COUNT = 2;

    private final UserRepository userRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        for (int i = 0; i < LOCAL_USER_COUNT; i++) {
            User user = userRepository.save(new User());
            log.info("로컬 실습용 사용자를 만들었습니다. X-USER-ID: {}", user.getId());
        }
    }
}
