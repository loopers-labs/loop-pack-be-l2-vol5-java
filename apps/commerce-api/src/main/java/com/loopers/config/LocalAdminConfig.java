package com.loopers.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** local 프로필 전용 관리자 계정. 로컬 부하 실험에서 관리자 API 를 Basic 인증으로 부르는 데만 쓴다. */
@Profile(LocalAdminConfig.PROFILE)
@Configuration
public class LocalAdminConfig {

    static final String PROFILE = "local";

    @Bean
    UserDetailsService localAdmin(
        @Value("${local-admin.username}") String username,
        @Value("${local-admin.password}") String password
    ) {
        return new InMemoryUserDetailsManager(
            User.withUsername(username).password("{noop}" + password).roles("ADMIN").build());
    }
}
