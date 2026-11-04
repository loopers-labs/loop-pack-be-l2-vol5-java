# 6~7주차 통합 개발 · Outbox와 Consumer Inbox

PG로 확정된 주문의 구매 사실을 Kafka로 전달하고, 중단·재시도·재전달에도 상품별 구매 활동을 한 번만 저장한다.
사용자 추가 조건에 따라 소비 완료 기록을 명시적인 Inbox로 구현한다.

| 문서 | 용도 |
|---|---|
| [통합 요구사항](total_requirement.md) | 업무·이벤트·Outbox·Inbox·실험·제출 완료 조건 |
| [개발 계획](development-plan.md) | R01~R03 작업 순서와 PR 경계 |
| [실행 환경](environment.md) | 공용 Kafka fixture·연결 확인·로컬 주의사항 |
| [현재 인계](handoff.md) | 준비 결과와 다음 작업 |
| [준비 검증](initial-setup.md) | 실제 실행한 초기 검사와 제한 |

원문은 [W6 학습](reference/week6-learning.md), [W6 과제](reference/week6-implementation-quest.md),
[W7 학습](reference/week7-learning.md), [W7 과제](reference/week7-implementation-quest.md)에 그대로 보존한다.
통합 요구사항에서 원문과 사용자 추가 조건을 구분한다. 문서·테스트 기반 준비는 업무 기능 구현 완료가 아니다.
