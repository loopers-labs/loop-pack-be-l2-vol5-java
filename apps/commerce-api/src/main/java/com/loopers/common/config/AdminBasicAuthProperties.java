package com.loopers.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 관리자 HTTP Basic 계정. 값은 환경 변수로만 받고 저장소에 두지 않는다.
@ConfigurationProperties("commerce.admin.basic-auth")
public record AdminBasicAuthProperties(boolean enabled, String username, String password) {
    boolean hasAccount() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        return "AdminBasicAuthProperties[enabled=" + enabled + ", username=" + username + ", password=***]";
    }
}
