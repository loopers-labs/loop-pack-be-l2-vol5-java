# 6~7주차 현재 인계

## 작업 기준

- 사용자 요청: 6~7주차 통합 진행 + consumer Inbox. 초기 준비 후 R01 이벤트 계약·Inbox 상세 설계까지 요청했다.
- 출발점: `volume-5/main`의 사용자 로컬 커밋 `b4e499d`를 포함한다.
- 기준: `volume-6-7/main`. 현재 작업 브랜치는 `volume-6-7/r01-order-events-inbox`다.
- 현재 checkout/실제 준비 결과는 `git status --short --branch`, `git log -3 --oneline`과
  [준비 검증](initial-setup.md)을 확인한다. 원격 게시/PR 생성은 하지 않는다.

## 읽는 순서

1. [통합 요구사항](total_requirement.md)
2. [R01 상세 설계](r01-order-events-inbox/plan.md)와 연결된 이벤트 계약·Inbox 저장·검증 계획
3. [개발 계획](development-plan.md)
4. [환경·명령과 미수정 설정](environment.md)
5. [W6 과제 원문](reference/week6-implementation-quest.md), [W7 과제 원문](reference/week7-implementation-quest.md)
6. [5주차 최종 점검](../week5/final-audit.md)과 실제 공통 PG 결과 반영 코드

## 다음 작업

사용자가 요청한 트레이드오프 논의를 진행했고 [T01~T08 답변](r01-order-events-inbox/trade-offs.md)을 설계에 반영했다.
앱별 DTO/JSON 계약 검사·createdAt 재사용·snapshot 필드 비교이며 공통 계약 모듈은 제외한다.
최초 UUID는 확정 TX의 OrderRecord에 저장하고 재발행/복구에서 재사용한다. 모르는 추가 필드는 무시한다.
개별 이벤트의 최종 실패는 별도 DB 트랜잭션에 보관한다. 기록 commit 뒤 원본 offset을 진행하며 별도 기능 범위는 실패 보관까지다.
공통 DB 장애가 재시도 뒤에도 이어지거나 실패 기록 저장에 실패하면 리스너를 중단하고 원본 offset을 유지한다.
재시도 수치와 저장 필드는 구현 초기값 제안이다. [재시도·DB 실패 기록](r01-order-events-inbox/retry-failure-record.md)
R01의 첫 구현으로 앱별 이벤트 DTO와 같은 JSON 계약 검사, 최초 PG 확정 UUID·마이크로초 시각의 저장을 완료했다.
두 앱의 관련 검사 43건이 통과했다. commerce-api Checkstyle과 비즈니스·사용자 경험·기술 리뷰도 마쳤다.
별도 MySQL에서 `008` 적용 후 기존 POINT·PG 시각과 NULL UUID 보존, 신규 UUID 유일 제약을 확인했다.
다음 구현은 Inbox·구매 활동의 원자적 저장과 Kafka 직접 발행·소비 연결이다. 중요한 추가 선택은 구현 전에 설명한다.

최종 구조는 Outbox지만 W6 직접 발행 A(누락)·B(소비 완료)의 실제 준비 경로를 보존해야 한다.
제품 우회 스위치나 Outbox 삭제로 이 조건을 만들지 않는다.

남은 구현 범위는 Inbox·Outbox·relay·실패 기록·업무 listener와 W6/W7 실험이다.
사용자의 `docs/week4/r01-product-list-index/jmeter/product-list-ui.jmx` 수정은 보존하고 staging하지 않는다.
출발 커밋이 삭제한 과거 `docs/week5/handoff.md`도 복원하지 않는다. 이번 인계는 새로운 경로를 사용한다.
