# ADR 0074: Redis 예약은 DB 발급 결과를 확인한 뒤 확정·보상한다

Status: Accepted
Extended by: [ADR 0075](0075-judge-in-redis-before-any-db-lookup.md) (REDIS_SYNC에서 Redis 판단을 사용자 확인·기존 쿠폰 조회보다 먼저 한다. 예약 확정·보상 정책은 유지)
Extends: [ADR 0067](0067-issue-coupon-with-conditional-update-in-db.md) (DB 동기 발급·마지막 한도의 동시 중복 결과 재조회)
Supersedes: [ADR 0068](0068-judge-coupon-issue-in-redis-before-db.md) (Redis 수량·중복 판단은 유지하고 사용자 예약 표현·결과 불명 보상 정책을 보강)
Related: [8주차 설계](../week8/design.md), [8주차 실험](../week8/experiments.md), [구현 기록](../week8/implementation-log.md)

## Context

DB 조건부 UPDATE·UNIQUE·동일 TX로 한도·중복·원자성을 지키고, Redis가 수량·중복을 먼저 판단해 불필요한 DB 발급 TX를 줄인다. 두 단계의 부하 실험에서 이 효과와 통과 요청의 DB 대기 한계를 확인했다.

Redis 예약과 DB 발급은 하나의 트랜잭션이 아니다. DB 결과를 모를 때 무조건 수량을 복구하면 원래 실행이 끝나지 않은 상태에서 같은 수량을 다시 허용할 수 있다. DB가 최종 한도를 지킨다는 사실과 원래 실행이 종료됐다는 증거를 구분해야 한다. 또한 늦게 도착한 확인·보상이 다른 실행의 예약을 변경해서는 안 된다.

## Decision

- `DB_ONLY`와 `REDIS_SYNC` 모두 같은 DB 발급 TX를 사용하며 commit 뒤 새 쿠폰 201·기존 쿠폰 200을 응답한다. 기본은 REDIS_SYNC다. 응답 data는 `{couponId, campaignId, issuedAt, benefitDescription}`다.
- 조건부 UPDATE(활성·종료 전·발급 수 < 총량)와 쿠폰 INSERT는 한 TX다. `(campaign_id, user_id)` UNIQUE는 중복의 최종 방어선이다.
- 같은 사용자의 경쟁이 마지막 한도의 UPDATE 0건이나 쿠폰 UNIQUE 위반으로 끝나면, rollback 확인 뒤 TX 밖에서 기존 쿠폰을 새로 조회한다. 있으면 같은 couponId의 200, 없으면 원래 거절이다. 다른 제약 오류는 중복 성공으로 바꾸지 않는다.
- Redis 사용자 기록은 실행별 `R:UUID`(예약)와 `A`(DB 발급 확인)다. 확인·보상은 자기 R과 값이 일치할 때만 반영한다.
- 새 쿠폰 commit은 R→A로 확정한다. 기존 쿠폰 결과면 자기 예약 차감분을 한 번 돌리고 A로 바꾼다.
- rollback 확정 또는 TX 본문 진입 전 실패만 자기 R을 삭제하고 수량을 한 번 복구한다. TX 시작 뒤 결과 불명은 503·R 유지다. 경과 시간만으로 회수하거나 다른 실행이 이어받지 않는다.
- commit 뒤 Redis 확인 실패에도 실제 쿠폰과 201·200 응답을 유지한다. rollback 뒤 보상 실패는 R·차감을 남긴다. 기존 쿠폰과 목록은 DB 사실로 조회할 수 있다.
- Redis 오류·판단 정보 없음은 신규 발급 503이며 DB 발급 TX로 우회하지 않는다. 없는 캠페인은 404다. 진행 중인 같은 사용자의 재요청은 DB 재조회로 쿠폰이 있으면 200, 없으면 409 확인 중이다.
- primary Lua와 commerce-api 명령 timeout 200ms를 사용한다. 관리자 준비는 DB 기준 남은 수·기존 발급 사용자를 복사하고, stock/users는 행사 종료 + 보존 시간(기본 1시간)에 함께 만료한다. 첫 예약으로 생성되는 사용자 Hash에도 같은 만료를 건다.
- 재준비 호출자는 신규 유입 중단·모든 발급 실행과 DB 작업 종료를 확인해야 한다. 준비 API가 이 운영 전제를 자동 강제하지는 않는다.

## Alternatives and consequences

| 대안 | 판단 |
| --- | --- |
| 결과 불명에도 즉시 수량 복구 | 실제 DB 작업의 종료를 증명할 수 없어 채택하지 않음 |
| 일정 시간 뒤 예약 자동 회수 | 시간 경과만으로 DB 종료를 알 수 없어 채택하지 않음 |
| 사용자 ID만 비교해 보상 | 같은 사용자의 다른 실행 예약을 지울 수 있어 실행별 UUID 사용 |
| Redis 장애 시 DB로 우회 | 고부하가 DB로 집중돼 다른 API에 영향을 줄 수 있어 채택하지 않음 |

R이 남으면 실제보다 가용 수량이 낮아져 수동 확인·복구가 필요하다. Redis 판단이 잘못 허용해도 최종 한도·중복은 DB가 지키지만, 두 저장소 상태가 자동으로 일치하지는 않는다. Redis는 동시 실행 수 제한·입장 대기열이 아니며 사용자 확인·기존 결과 조회까지 없애지도 않는다.

## 검증

DB 한도·동시 중복·rollback·HTTP·본인 목록, 실제 Redis의 수량·소유권·중복 보상 방지·만료, 두 저장소 불일치와 오류·복구를 테스트한다. DB 완료 불명은 단위 테스트로 확인하며 실제 commit 응답 유실 재현과 구분한다. 실행 결과는 [구현 기록](../week8/implementation-log.md), 기존 1·2단계 부하 결과는 [실험 기록](../week8/experiments.md)에 둔다.
