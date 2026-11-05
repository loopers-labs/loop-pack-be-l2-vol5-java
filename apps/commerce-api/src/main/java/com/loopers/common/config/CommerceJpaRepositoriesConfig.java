package com.loopers.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// modules/jpa는 com.loopers.infrastructure만 스캔한다. domains 아래 out 리포지토리를 잡는다. 새 업무가 생겨도 목록을 관리할 필요가 없다.
@Configuration
@EnableJpaRepositories("com.loopers.domains")
public class CommerceJpaRepositoriesConfig {
}
