package com.loopers.application.coupon;

import com.loopers.config.CouponAdmissionProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.Acquisition;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 신규 발급 수용의 열림/닫힘과 Redis 자리 결과를 해석한다. 상태는 공유 accepting 값 하나이며 시작 시 닫혀 있다.
 * 관찰한 Redis 오류·불명 결과에서는 닫고, 다시 여는 것은 {@link #open()} 명시 호출뿐이다.
 * 전체 실행 종료·token 소유권은 이 서비스가 확인하지 않는다. cleanup·open 전 확인은 수동 절차(설계 §8.3)의 책임이다.
 */
@Slf4j
@Component
public class CouponAdmissionService {

    private final CouponAdmissionRepository repository;
    private final int capacity;
    private final AtomicBoolean accepting = new AtomicBoolean(false);

    public CouponAdmissionService(CouponAdmissionRepository repository, CouponAdmissionProperties properties) {
        this.repository = repository;
        this.capacity = properties.capacity();
    }

    /**
     * 정상 반환이면 이 token 이 자리를 점유했다. 닫혀 있으면 Redis 를 호출하지 않는다.
     * FULL 은 429 이며 수용을 닫지 않는다. 예외·비정상 응답은 token 이 늦게 추가됐을 수 있으므로 닫고 503 이다.
     * 어느 예외에서도 호출자는 신규 DB 발급을 시작하거나 이 token 을 반환하지 않는다.
     */
    public void acquire(String token) {
        if (!accepting.get()) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
        Acquisition result;
        try {
            result = repository.tryAcquire(token, capacity);
        } catch (RuntimeException e) {
            closeAfterFailure("획득", token, "획득 여부 불명", e);
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
        if (result == Acquisition.ACQUIRED) {
            return;
        }
        if (result == Acquisition.FULL) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_FULL);
        }
        closeAfterFailure("획득", token, "획득 여부 불명(결과 " + result + ")", null);
        throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
    }

    /**
     * ACQUIRED 를 확인한 실행의 첫 반환. 업무 결과가 정해진 뒤 호출되므로 예외를 던지지 않는다.
     * 1 이 아니면(0 = 있어야 할 점유가 없음, 예외 = 반환 여부 불명) 로그를 남기고 닫는다. 업무 결과는 호출자가 그대로 유지한다.
     */
    public void release(String token) {
        long removed;
        try {
            removed = repository.release(token);
        } catch (RuntimeException e) {
            closeAfterFailure("반환", token, "반환 여부 불명", e);
            return;
        }
        if (removed != 1) {
            closeAfterFailure("반환", token, "정상 첫 반환에서 점유 없음(removed " + removed + ")", null);
        }
    }

    public boolean status() {
        return accepting.get();
    }

    /** 반복 가능. 진행 중인 실행을 취소하거나 완료를 기다리지 않는다. */
    public boolean close() {
        accepting.set(false);
        return accepting.get();
    }

    /** 호출 전제: 운영자가 관련 실행·DB 종료와 자기 token 정리를 확인했다. 이 메서드는 그 확인을 검사하지 않는다. */
    public boolean open() {
        accepting.set(true);
        return accepting.get();
    }

    /**
     * 닫힌 상태에서 지정한 token 만 하나씩 제거하고 제거 수를 돌려준다. 이미 없는 token 의 0 은 정상이다.
     * 도중에 Redis 가 실패하면 앞서 제거한 것은 되돌리지 않고, 닫힘을 유지한 채 503 이다. 남은 token 은 재조회로 다시 확인한다.
     */
    public long cleanup(List<String> tokens) {
        if (accepting.get()) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_OPERATION_REJECTED);
        }
        if (tokens == null || tokens.isEmpty() || tokens.stream().anyMatch(t -> t == null || t.isBlank())) {
            throw new CoreException(ErrorType.INVALID_REQUEST, "정리할 token 은 비어 있지 않은 문자열 목록이어야 합니다.");
        }
        long removed = 0;
        for (String token : tokens) {
            try {
                removed += repository.release(token);
            } catch (RuntimeException e) {
                closeAfterFailure("정리", token, "이 token 의 제거 여부 불명, 앞선 token 제거 수 " + removed, e);
                throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            }
        }
        return removed;
    }

    /** token 이 남았거나 기대한 점유가 사라졌을 수 있는 경로. 수동 확인에서 요청 결과와 연결할 수 있도록 token·단계·확인된 결과를 남긴다. */
    private void closeAfterFailure(String stage, String token, String outcome, Exception cause) {
        accepting.set(false);
        log.error("쿠폰 신규 발급 수용을 닫습니다. [stage = {}, token = {}, outcome = {}]", stage, token, outcome, cause);
    }
}
