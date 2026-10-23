package com.loopers.support.pg;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SharedPgFixtureConfiguration {

    @Bean(destroyMethod = "")
    PgFixture sharedPgFixture() {
        return PgFixture.shared();
    }
}
