# ADR 0067: 쿠폰 발급의 한도는 DB 조건부 UPDATE로, 1인 1매는 유일 제약으로 지킨다

Status: Accepted
Extended by: [ADR 0074](0074-confirm-coupon-reservations-after-db-outcome.md) (마지막 한도의 동시 중복 결과 재조회·DB 결과에 따른 Redis 예약 처리)
Extended by: [ADR 0068](0068-judge-coupon-issue-in-redis-before-db.md) (DB 앞 Redis 발급 판단, 종료 시각 조건 추가)
Related: [8주차 설계](../week8/design.md), [8주차 실험](../week8/experiments.md)

## Context

선착순 쿠폰을 가장 단순한 구조(요청 → RDB)에서 시작해 측정하고, 보인 문제만 다음 단계에서 푼다. 이 ADR은 그 첫 단계의 결정이다.

- 과제 API: `POST /api/v1/coupon-campaigns/{campaignId}/issues`(첫 발급 201, 같은 사용자·캠페인의 기존 결과 200과 같은 couponId), `GET /api/v1/coupons`(본인 보유 목록).
- 한도 확인·발급 수 증가·쿠폰 저장이 다른 TX면 중간 실패로 수량만 줄거나 쿠폰만 남는다.
- 마지막 쿠폰 경쟁을 막는 방식으로 캠페인 행 `FOR UPDATE` 잠금과 조건부 UPDATE를 같은 조건에서 측정했다.

## Decision

- 캠페인(`coupon_campaign`: 이름·혜택 설명·발급 가능 여부·총 한도·발급 수)과 보유 쿠폰(`coupon`: 사용자·캠페인·발급 시각·혜택 설명)을 둔다.
- 한 TX에서 기존 쿠폰 확인 → `UPDATE coupon_campaign SET issued_count = issued_count + 1 WHERE id = ? AND active = true AND issued_count < total_quota` → 쿠폰 저장을 한다. 쿠폰 저장이 실패하면 발급 수까지 rollback한다.
- UPDATE가 0건이면 조회해 둔 캠페인 상태로 품절과 비활성을 나눠 409로 거절한다. 없는 캠페인은 404다.
- `(campaign_id, user_id)` 유일 제약으로 1인 1매를 지킨다. 같은 사용자의 동시 요청이 유일 제약에서 만나면, rollback된 뒤 TX 밖에서 다시 읽어 먼저 commit된 쿠폰을 200으로 돌려준다.
- 보유 목록은 발급 시각 내림차순, 같은 시각이면 ID 내림차순이다.

## Alternatives and costs

| 대안 | 판단 |
| --- | --- |
| 캠페인 행 `FOR UPDATE` 후 앱에서 한도 확인 | 정합성은 같았다. 처리량이 낮았고(한계 약 270~330/s 대 약 360/s), 600/s에서는 매진 뒤 18초 동안 회복하지 못했다. 잠금을 얻은 뒤 기존 쿠폰 조회·한도 확인까지 하므로 요청마다 잠금을 쥔 채 DB를 더 오간다. 채택하지 않음 |
| 조건부 UPDATE | 기존 쿠폰·캠페인 조회는 잠금 없이 먼저 하고, 잠금은 UPDATE부터 commit(또는 rollback)까지만 쥔다. 매진 뒤 밀린 요청이 빠지면 409가 10ms대로 돌아왔다. 채택 |

두 방식 모두 거절할 요청도 캠페인 행 잠금을 거친다. 차이는 잠금을 쥐는 구간의 길이다. 요청별 잠금 보유 시간은 직접 재지 않았고, 위 설명은 측정한 처리량·회복·행 잠금 평균 대기(400/s에서 168ms 대 125ms)에 대한 해석이다.

비용: 같은 사용자의 동시 요청을 잠금으로 줄 세우지 않으므로, 유일 제약 위반을 알아보고 다시 읽는 분기가 필요하다. 품절/비활성 구분은 UPDATE 전 조회 기준이라 그 사이 비활성으로 바뀐 경우는 품절로 보일 수 있다.

이 결정은 정합성만 해결한다. 측정에서 보인 대기(한 캠페인 행 앞의 한 줄 서기), 다른 API로의 피해 확산, 매진 뒤에도 DB까지 오는 요청은 다음 단계에서 다룬다.

## 검증

- `CouponIssueIntegrationTest`(실제 MySQL): 첫 발급·재요청·품절/비활성/없는 캠페인·잘못된 사용자, 20명이 5장 경쟁 시 정확히 5장, 같은 사용자 8번 동시 요청 시 한 장, 저장 실패 시 발급 수 rollback.
- `CouponApiIntegrationTest`: 201·200·409·404·400과 본인 목록 정렬.
- 부하 실험(같은 PC, 비교용): 쿠폰 5,000장·150~1200/s에서 두 방식 모두 발급 수 = 쿠폰 행 = 서로 다른 사용자 = 5,000, 따닥 쌍은 `200+201`·`409+409`만. 수치는 [실험 기록](../week8/experiments.md) 1단계.
