package com.loopers.config.jpa;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EntityScan({"com.loopers"})
// Spring Data 리포지토리는 기능마다 {기능}.adapter.out.persistence 에 둔다 (헥사고날의 출력 어댑터).
@EnableJpaRepositories({"com.loopers.*.adapter.out.persistence"})
public class JpaConfig {
}
