package com.loopers.testsupport;

import com.loopers.domain.order.OrderConfirmed;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 사건 등록(publishEvent) 자체를 기록하는 테스트 전용 일반 listener. commit 뒤 포트 호출과 따로 세어
 * 「등록됐지만 rollback 으로 포트가 불리지 않음」을 구분한다. 운영 코드에는 이런 일반 listener 를 두지 않는다.
 */
public class RegisteredOrderEvents {

    public record Registration(OrderConfirmed event, boolean inActualTransaction) {
    }

    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

    @EventListener
    void on(OrderConfirmed event) {
        registrations.add(new Registration(event, TransactionSynchronizationManager.isActualTransactionActive()));
    }

    public List<Registration> registrations() {
        return List.copyOf(registrations);
    }

    public void clear() {
        registrations.clear();
    }

    @TestConfiguration
    public static class Config {

        @Bean
        RegisteredOrderEvents registeredOrderEvents() {
            return new RegisteredOrderEvents();
        }
    }
}
