package com.loopers.application.ordering.service;

import com.loopers.domain.ordering.repository.OrderEventFailureRepository;
import com.loopers.domain.ordering.repository.OrderEventFailureRepository.Failure;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordOrderEventFailureService {
    private final OrderEventFailureRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Failure failure) {
        repository.insertOrVerify(failure);
    }
}
