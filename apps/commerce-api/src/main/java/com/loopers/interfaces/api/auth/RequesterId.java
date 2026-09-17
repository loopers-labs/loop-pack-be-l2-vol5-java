package com.loopers.interfaces.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * X-USER-ID 헤더 값(사용자 PK, Long)을 컨트롤러 파라미터로 받는다 (CON-03, DR-01).
 * 헤더 누락·형식 오류는 ER-01 USER_NOT_FOUND. 존재 여부는 Facade 가 UserService 로 확인한다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequesterId {
}
