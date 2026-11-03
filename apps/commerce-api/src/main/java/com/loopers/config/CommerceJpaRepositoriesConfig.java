package com.loopers.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// modules/jpa는 com.loopers.infrastructure만 스캔한다. 업무 먼저 구조로 옮긴 api 리포지토리를 잡기 위해서다. 범위가 겹치지 않아 중복 등록이 없다.
// 새 컨텍스트가 생기면 이 목록에 추가해야 한다.
@Configuration
@EnableJpaRepositories({"com.loopers.domains.brand.out", "com.loopers.domains.like.out", "com.loopers.domains.user.out",
    "com.loopers.domains.point.out", "com.loopers.domains.product.out", "com.loopers.domains.payment.out",
    "com.loopers.domains.order.out"})
public class CommerceJpaRepositoriesConfig {
}
