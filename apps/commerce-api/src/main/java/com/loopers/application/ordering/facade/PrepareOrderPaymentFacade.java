package com.loopers.application.ordering.facade;

import com.loopers.application.ordering.command.PrepareOrderPaymentCommand;
import com.loopers.application.ordering.result.PrepareOrderPaymentResult;
import com.loopers.application.ordering.service.PrepareOrderPaymentService;
import com.loopers.application.ordering.usecase.PrepareOrderPaymentUseCase;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PrepareOrderPaymentFacade implements PrepareOrderPaymentUseCase {
    private final PrepareOrderPaymentService preparation;

    @Override
    public PrepareOrderPaymentResult execute(PrepareOrderPaymentCommand command) {
        Optional<PrepareOrderPaymentResult> prepared;
        try {
            prepared = preparation.tryPrepare(command);
        } catch (DataIntegrityViolationException exception) {
            // 실패한 TX 밖에서만 유일 키 경합을 확인한다. 다른 저장 오류를 성공으로 삼지 않는다.
            return preparation.findExisting(command).orElseThrow(() -> exception);
        }
        return prepared.orElseGet(() -> preparation.findExisting(command)
            .orElseThrow(() -> new DomainException(DomainErrorCode.ORDER_PAYMENT_NOT_ALLOWED)));
    }
}
