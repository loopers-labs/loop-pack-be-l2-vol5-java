package com.loopers.support.payment;

import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 가짜 PG 주소를 따로 지정하지 않은 통합 테스트도 응답하는 가짜 PG를 쓰게 한다.
 * PG에 연결하지 못한 생성 요청은 "보내지 못함"으로 바로 실패하므로, 재고 예약·잠금을 보는 테스트는 PG가 요청을 받아 줘야 한다.
 * 테스트가 @DynamicPropertySource로 지정한 주소가 이 값보다 우선한다.
 */
@Configuration
public class DefaultPgTestServerConfig {

    private static final FakePaymentServer PG;

    static {
        try {
            PG = new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        System.setProperty("commerce.payment.pg.base-url", PG.baseUrl());
    }
}
