# 6~7주차 개발 계획

[통합 요구사항](total_requirement.md)의 완료 조건을 세 단계로 나눈다. 아래 순서는 구현 제안이며 아직 기능 완료가 아니다.

## 브랜치와 커밋

- 기준: `volume-6-7/main`. 출발점은 사용자 로컬 커밋을 포함한 `b4e499d`다.
- 초기 준비 문서와 공용 Kafka fixture를 기준 브랜치에서 독립 커밋으로 정리한다.
- 준비 후 R01만 생성한다. R02/R03은 앞 단계 검증·병합 뒤 최신 기준에서 생성한다.
- PR 대상은 `volume-6-7/main`이며 작업 브랜치끼리 직접 병합하지 않는다.
- 명시적으로 관련 파일만 staging한다. 사용자 JMeter 변경은 커밋에 포함하지 않는다.
- `type: 한글 요약`·관련 검사 Green 이후 작은 변경 단위를 유지한다.
- 원격 push·PR 게시·병합은 별도 요청 범위다.

## R01 · 이벤트와 Inbox 구매 활동

브랜치: `volume-6-7/r01-order-events-inbox`

1. 기존 공통 PG 결과 반영 TX와 최초 확정 시점/품목 snapshot을 확인한다.
2. eventId·confirmedAt 저장/유도, 이벤트 DTO·정규화·동등성 계약과 소유 모듈을 설계한다.
3. consumer Inbox·구매 활동 모델/제약과 원자적 저장을 구현한다.
4. 주문 commit 이후 직접 발행과 실제 record listener를 연결한다.
5. Kafka 2-partition/consumer 1→2→3 배정, key 매핑, offset 미커밋 재전달을 확인한다.
6. 실제 listener 실패·rollback·offset 미진행·복구 재시작을 확인한다.
7. 직접 발행 누락 A·정상 소비 B의 재현 구성과 실제 사건 기록을 보존한다.

완료 시 같은 eventId의 중복은 no-op, 다른 사실은 충돌, 품목 실패는 Inbox까지 rollback이어야 한다.
직접 발행 누락 구간은 R01에서 관찰할 대상이며 최종 보장으로 남겨두지 않는다.

## R02 · Outbox와 단일 relay

브랜치: `volume-6-7/r02-transactional-outbox`

1. 기존 공통 확정 TX에 immutable Outbox insert를 포함하고 직접 발행 경로를 교체한다.
2. 단일 polling relay·broker 확인 후 PUBLISHED·bounded retry/오류 노출을 구현한다.
3. insert/serialization 실패의 로컬 원자성과 같은 거래 결과 재적용을 검증한다.
4. commit 후 relay 전 중단과 broker 확인 후 상태 저장 전 중단을 검증한다.
5. 새 relay가 기존 PENDING에서 재개하고 Inbox가 중복 효과를 막는지 확인한다.

R01 재현 경로는 테스트 구성 또는 보존 revision으로 유지한다. 제품에 운영용 우회 스위치를 만들지 않는다.

## R03 · 재현 상태 복구와 최종 연결

브랜치: `volume-6-7/r03-delivery-recovery`

1. 새 격리 실행에서 W6 직접 발행 A·B 상태를 실제 주문 API/로컬 PG로 준비한다.
2. 같은 실행에서 고정 identity를 보존해 없는 Outbox만 복구한다.
3. PG 추가 호출·외부 거래·재확정·예약 소비 없이 A만 보완되고 B는 불변인지 확인한다.
4. 최종 E2E에서 실제 broker 재발행 2회 이상과 Inbox 1행/상품별 활동 불변을 확인한다.
5. backlog·오류·재개·보존 정책, 관련 회귀/전체 check와 PR/기술 글을 정리한다.

## R01 상세 설계

2026-11-04 사용자 요청으로 [R01 상세 설계 초안](r01-order-events-inbox/plan.md)을 작성했다.
추천안을 확정처럼 기록했던 점을 사용자 지적에 따라 바로잡고 [트레이드오프 논의](r01-order-events-inbox/trade-offs.md)를 시작했다.
사용자는 앱별 DTO/JSON 계약 테스트·기존 createdAt 재사용·정규화 snapshot 필드 비교를 확정했다.
추가 답변으로 최초 UUID 생성·저장, 추가 필드 무시·필수 계약 검증을 확정했다.
소비 오류는 제한 재시도 후 DLQ에 격리하고 전송 확인 후 원본 offset을 진행하며 DLQ 중복을 허용한다.
공통 Java 계약 모듈은 제외한다. 구체 재시도 수치·도구 세부는 구현 초기값 제안으로 구분한다.
Inbox와 모든 품목의 단일 TX 등 원문의 고정 조건은 유지하며 추천 세부는 답변 후 수정한다.
필드/정규화는 [계약](r01-order-events-inbox/event-contract.md),
제약/DDL/경합은 [저장 설계](r01-order-events-inbox/persistence.md),
설정/실험은 상세 설계와 [검증 계획](r01-order-events-inbox/verification.md)을 따른다.
T01~T06 사용자 선택을 반영한 설계이며 구체 기술 설정은 제안이다. 모듈/테이블/설정/업무 기능은 아직 구현하지 않았다.
Outbox relay의 구체 간격·회차 처리량·재시도 정책은 R02에서 설계한다.

기능 동작을 바꾸는 구현은 저장소의 비즈니스·사용자 경험·기술 세 관점 리뷰 규칙을 따른다.
중요 수정 확인은 기존 승인 범위와 실제 영향에 맞춰 적용한다. 문서·빌드·테스트 전용 준비는 별도 기능 리뷰하지 않는다.
핵심 규칙은 TDD, 변경마다 관련 검사, 필수 전체 검사는 PR 마무리에 실행한다.
