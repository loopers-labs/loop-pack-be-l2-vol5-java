# 6~7주차 통합 개발 · Outbox와 Consumer Inbox

PG로 확정된 주문의 구매 사실을 Kafka로 전달하고, 중단·재시도·재전달에도 상품별 구매 활동을 한 번만 저장한다.
사용자 추가 조건에 따라 소비 완료 기록을 명시적인 Inbox로 구현한다.

| 문서 | 용도 |
|---|---|
| [통합 요구사항](total_requirement.md) | 업무·이벤트·Outbox·Inbox·실험·제출 완료 조건 |
| [개발 계획](development-plan.md) | R01~R03 작업 순서와 PR 경계 |
| [실행 환경](environment.md) | 공용 Kafka fixture·연결 확인·로컬 주의사항 |
| [현재 인계](handoff.md) | R01~R03 구현과 검증 기록 |
| [준비 검증](initial-setup.md) | 실제 실행한 초기 검사와 제한 |
| [R01 상세 설계 초안](r01-order-events-inbox/plan.md) | 최초 성공 전이·공유 계약·실행 설정·책임 경계 제안 |
| [R01 트레이드오프와 질문](r01-order-events-inbox/trade-offs.md) | T01~T08 사용자 선택과 대안·비용·추천 이유 |
| [R01 재시도·DB 실패 기록](r01-order-events-inbox/retry-failure-record.md) | 실패 분류, DB 보관, offset 진행과 재개 조건 |
| [R01 이벤트 계약](r01-order-events-inbox/event-contract.md) | 고정 identity·시각·정규화·엄격한 wire 검증 |
| [R01 Inbox 저장](r01-order-events-inbox/persistence.md) | 설계 DDL·전체 TX·경합 rollback·충돌 판정 |
| [R01 검증 계획](r01-order-events-inbox/verification.md) | 실제 DB/Kafka·listener·W6 A/B 재현 검사 |
| [통합 검증 결과](r03-delivery-recovery/result.md) | 전체 검사 집계와 실제 PG·MySQL·Kafka·Streamer 실험 |
| [복구·운영 절차](r03-delivery-recovery/operations.md) | 단건 복구, 중단 재현, backlog와 lag 확인 |
| [기술 정리](r03-delivery-recovery/technical-note.md) | 같은 offset 재전달과 같은 UUID 재발행의 차이 |

원문은 [W6 학습](reference/week6-learning.md), [W6 과제](reference/week6-implementation-quest.md),
[W7 학습](reference/week7-learning.md), [W7 과제](reference/week7-implementation-quest.md)에 그대로 보존한다.
통합 요구사항에서 원문과 사용자 추가 조건을 구분한다. 문서·테스트 기반 준비는 업무 기능 구현 완료가 아니다.
