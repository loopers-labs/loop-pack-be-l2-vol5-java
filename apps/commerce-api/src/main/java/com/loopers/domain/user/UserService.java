package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserModel getUser(Long id) {
        return userRepository.find(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 사용자를 찾을 수 없습니다."));
    }

    @Transactional
    public UserModel chargePoint(Long id, long amount) {
        // 검증만 재사용한다 — charge()의 유일한 규칙(금액 양수)은 현재 잔액과 무관해 새 인스턴스로 돌려도 안전하다.
        // 매니지드 엔티티(getUser(id))에 대고 직접 호출하면, 원자적 UPDATE 실행 전에 Hibernate가 그 변경을
        // 먼저 flush해버려 충전이 두 번 반영되는 버그가 생긴다(design.md 5번 섹션 참고).
        new Point().charge(amount);

        int affected = userRepository.chargePoint(id, amount);
        if (affected == 0) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 사용자를 찾을 수 없습니다.");
        }
        return getUser(id);
    }

    @Transactional(readOnly = true)
    public long getBalance(Long id) {
        return getUser(id).getPoint().getBalance();
    }

    /**
     * 주문 확정 시 포인트를 차감한다. 비관적 락으로 조회한 매니지드 엔티티를 그대로 변경한다 — 이미
     * 영속 상태라 dirty checking이 커밋 시점에 자동 반영하므로 save()를 다시 부르지 않는다
     * (ProductService.decreaseStock과 같은 이유 — save()/merge()를 다시 호출하면 락 재획득 select가
     * 한 번 더 나가는 걸 관찰해서 뺐다). chargePoint()의 원자적 UPDATE와는 다른, 표준 패턴이다.
     * (docs/week2/design.md 5번 섹션 "차감 → 비관적 락" 참고)
     */
    @Transactional
    public UserModel payPoint(Long id, long amount) {
        UserModel user = userRepository.findForUpdate(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 사용자를 찾을 수 없습니다."));
        user.getPoint().pay(amount);
        return user;
    }
}
