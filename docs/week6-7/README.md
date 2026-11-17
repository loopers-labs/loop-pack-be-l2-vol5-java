# 6~7주차 · 주문 확정 전달과 구매 활동

R01 직접 발행·Inbox, R02 Transactional Outbox·단일 relay, R03 누락 복구와 독립 프로세스 검증을 구현했다.
현재 runtime의 발행 경로는 Outbox다. R01 직접 after-commit 발행은 `42d8ed8`의 학습 재현 경로로 구분한다.
완료 여부·실험 결과는 아래 근거를 따르며 옛 준비 문구를 현재 작업 지시로 읽지 않는다.

| 목적 | 문서 |
|---|---|
| 요구사항·최종 경계와 과제 출처 | [통합 요구사항](total_requirement.md) |
| 현재 설정·실행·검사 명령 | [환경](environment.md) |
| wire·identity·snapshot 동등성 | [이벤트 계약](r01-order-events-inbox/event-contract.md) |
| Inbox·구매 활동·DB 제약·TX | [저장 경계](r01-order-events-inbox/persistence.md) |
| 실패·중단·offset·DB 보관 | [재시도와 실패 기록](r01-order-events-inbox/retry-failure-record.md) |
| 실제 테스트와 확인 한계 | [검증 매핑](r01-order-events-inbox/verification.md) |
| 구현 선택·변경 이유 | [결정 기록](r01-order-events-inbox/trade-offs.md) |
| 단계별 검증과 최종 실험 결과 | [R03 결과](r03-delivery-recovery/result.md) |
| W6 재현·Outbox 재개·단건 복구 | [운영 절차](r03-delivery-recovery/operations.md) |
| 전달/멱등성 기술 설명 | [기술 정리](r03-delivery-recovery/technical-note.md) |

과제 원문은 [W6 학습](reference/week6-learning.md)·[W6 과제](reference/week6-implementation-quest.md)·
[W7 학습](reference/week7-learning.md)·[W7 과제](reference/week7-implementation-quest.md)에 보존한다.
당시 43/42/45/516/542건의 검사는 서로 겹치므로 현재 전체 고유 테스트 수로 합산하지 않는다.
후속 쿠폰 기능까지 포함한 현재 개발 진입점은 [루트 안내](../../README.md)다.
