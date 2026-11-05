package com.loopers.outbox.application;

import com.loopers.outbox.application.port.in.OrderOutboxResumeUseCase;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;

@RequiredArgsConstructor
@Service
public class OrderOutboxResumeService implements OrderOutboxResumeUseCase {

    private final OrderOutboxPort orderOutboxPort;
    private final Clock clock;

    @Override
    public ResumeResult resume(String eventId) {
        return null;
    }
}
