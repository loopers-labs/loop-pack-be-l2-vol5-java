# 6~7주차 현재 인계

## 작업 기준

- 사용자 요청: 6~7주차 통합 진행 + consumer Inbox. R01과 R02를 구현한 뒤 함께 확인한다.
- 출발점: `volume-5/main`의 사용자 로컬 커밋 `b4e499d`를 포함한다.
- 기준은 `volume-6-7/main`이다. R01 직접 발행을 `42d8ed8`에 보존하고 해당 커밋에서 로컬 R02 브랜치 `volume-6-7/r02-transactional-outbox`를 시작했다.
- 현재 checkout/실제 준비 결과는 `git status --short --branch`, `git log -3 --oneline`과
  [준비 검증](initial-setup.md)을 확인한다. 원격 게시/PR 생성은 하지 않는다.

## 읽는 순서

1. [통합 요구사항](total_requirement.md)
2. [R01 상세 설계](r01-order-events-inbox/plan.md)와 연결된 이벤트 계약·Inbox 저장·검증 계획
3. [개발 계획](development-plan.md)
4. [환경·명령과 미수정 설정](environment.md)
5. [W6 과제 원문](reference/week6-implementation-quest.md), [W7 과제 원문](reference/week7-implementation-quest.md)
6. [5주차 최종 점검](../week5/final-audit.md)과 실제 공통 PG 결과 반영 코드

## R01과 R02

사용자가 요청한 트레이드오프 논의를 진행했고 [T01~T08 답변](r01-order-events-inbox/trade-offs.md)을 설계에 반영했다.
앱별 DTO/JSON 계약 검사·createdAt 재사용·snapshot 필드 비교이며 공통 계약 모듈은 제외한다.
최초 UUID는 확정 TX의 OrderRecord에 저장하고 재발행/복구에서 재사용한다. 모르는 추가 필드는 무시한다.
개별 이벤트의 최종 실패는 별도 DB 트랜잭션에 보관한다. 기록 commit 뒤 원본 offset을 진행하며 별도 기능 범위는 실패 보관까지다.
공통 DB 장애가 재시도 뒤에도 이어지거나 실패 기록 저장에 실패하면 리스너를 중단하고 원본 offset을 유지한다.
최초 실패 뒤 최대 두 번을 1초 ±20% 지터로 재시도한다. [재시도·DB 실패 기록](r01-order-events-inbox/retry-failure-record.md)
R01의 첫 구현으로 앱별 이벤트 DTO와 같은 JSON 계약 검사, 최초 PG 확정 UUID·마이크로초 시각의 저장을 완료했다.
두 앱의 관련 검사 43건이 통과했다. commerce-api Checkstyle과 비즈니스·사용자 경험·기술 리뷰도 마쳤다.
별도 MySQL에서 `008` 적용 후 기존 POINT·PG 시각과 NULL UUID 보존, 신규 UUID 유일 제약을 확인했다.
Inbox·구매 활동의 원자적 저장을 완료했다. 격리 MySQL에서 `009`를 적용해 중복·동시 경합·내용 충돌과 두 번째 품목 실패의 전체 rollback을 검증했다.
사용자 선택에 따라 Streamer의 로컬 시작은 `ddl-auto=none`을 사용한다. [009 SQL](../../docker/payment-lab/009-purchase-activity.sql)로 스키마를 준비한다.
R01은 PG 확정 커밋 뒤 직접 Kafka로 발행한다. 관련 검사 42건에서 실제 발행, 전송 실패 뒤 확정 보존,
중복·충돌·계약 오류와 DB 실패 기록의 commit 뒤 offset 진행을 확인했다.

R02는 확정 TX에 Outbox를 함께 저장하고 직접 발행을 교체한다. 단일 relay는 3초 간격으로 최대 5건을 조회한다.
실패 지연 3·6·12·24초 각각에 ±20% 지터를 적용하며, 결과 미확인 예약을 포함해 5회 소진 시 BLOCKED 상태로 남긴다.
SQL과 재개 방법은 [결제 실험 환경](../../docker/payment-lab/README.md)의 Outbox 절을 참고한다.

R02 브랜치의 집중 검사 45건과 Checkstyle을 통과했다. 격리 Kafka/MySQL에서 2개 파티션의 1·2·3 소비자 배정,
품목 저장 rollback과 offset 유지, 커밋 직후 중단 지점의 같은 offset 재전달과 구매 활동 no-op을 확인했다.
API에서는 Outbox 직렬화 실패와 insert 직후 실패의 원자적 rollback, 재시도 시각과 BLOCKED·재개,
브로커 확인 뒤 상태 갱신 실패를 주입한 뒤 새 Relay가 보낸 같은 key·payload의 실제 두 Kafka offset을 확인했다.
`000`~`011`을 적용한 별도 MySQL에서 재개 프로필의 시작·스키마 검사·대상 한 행 갱신·종료도 확인했다.
commerce-api·commerce-streamer·modules/kafka 전체 `check`는 515건을 통과했다. 실패·오류·건너뛰기는 0건이다.

## 다음 작업

R03에서 R01 보존 커밋으로 W6 누락 A와 소비 완료 B를 준비하고, 같은 DB에서 A만 복구한다.
기존 UUID·확정 시각·품목·구매 활동을 대조하고 실제 PG/API/Streamer의 최종 연결을 검증한다.
사용자의 `docs/week4/r01-product-list-index/jmeter/product-list-ui.jmx` 수정은 보존하고 staging하지 않는다.
출발 커밋이 삭제한 과거 `docs/week5/handoff.md`도 복원하지 않는다. 이번 인계는 새로운 경로를 사용한다.
