package com.loopers.interfaces.api.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 공통 요청자 식별 처리로 검증한 고객 ID 를 Controller 파라미터로 전달한다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CustomerId {
}
