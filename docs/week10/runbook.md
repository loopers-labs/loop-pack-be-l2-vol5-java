# Week 10 결제 미확정 runbook

## 증상

고객 주문 조회는 `DRAFT`와 `PENDING` 또는 `UNKNOWN`을 반환하고, `/actuator/health`는 UP일 수 있다. health UP은 결제 주문이 확정됐다는 뜻이 아니다.

## 읽기 전용 진단

1. health 응답과 관찰 시각을 그대로 저장한다.
2. `/actuator/prometheus`의 `commerce_payment_unresolved` sample과 scrape 시각을 저장한다. Prometheus naming convention에 따라 exposition 이름이 변환될 수 있다.
3. 요청 고객이 소유한 주문 상세 API에서 주문 상태·payment 상태·금액·시각을 읽는다.
4. 주문 ID와 payment attempt key로 상관 로그를 찾아 adapter operation/outcome과 transaction 적용 결과를 확인한다.
5. 합성 PG fixture를 쓰는 로컬 실험에서는 같은 계정/주문/금액의 거래 상태·transaction key·create attempt 수를 조회한다.
6. 주문의 예약 상태·Outbox eventId/상태·구매 활동·주간 snapshot/publication을 각 저장소에서 읽는다.

### 경쟁 가설

| 가설 | 예상 | 확인 |
| --- | --- | --- |
| PG가 아직 처리 중 | 권위 조회가 PENDING/미확정 | 동일 payment key 조회 결과와 시각 |
| PG SUCCESS 응답 유실 | PG SUCCESS와 내부 UNKNOWN, create adapter timeout/reset | 같은 주문/금액 거래 원장, adapter 결과, 내부 상태 |
| 성공 결과 내부 적용 rollback | PG 조회 SUCCESS와 내부 적용 시도, DB 상태는 미변경 | transaction rollback 로그와 주문/예약/Outbox 재조회 |

증거가 부족하면 원인을 확정하지 않는다.

## 조치 전제

- 실험 입력을 중지하고 영향 주문 ID와 attempt key를 목록으로 고정한다.
- 주문 소유자·payment key·provider order ID·주문 금액·PG 거래 금액이 같은지 확인한다.
- 증거 원문을 별도 실행 위치에 저장하고 hash를 기록한다.
- 응답 유실 제어를 해제하고 PG create가 아닌 기존 본인 주문 reconcile API를 사용한다.
- 조회 재시도는 계약된 유한 횟수만 수행한다. SUCCESS 적용은 기존 결과 반영 transaction 경계를 통과해야 한다.

## 중단 조건

다음 중 하나라도 발생하면 성공으로 임의 변경하지 말고 상태를 보존하고 중단한다.

- 거래가 없거나 조회가 timeout/미확정/PENDING이다.
- 복수 거래가 반환되거나 payment key/provider order ID가 다르다.
- 금액이 내부 주문 합계와 다르다.
- health·metric·로그의 관찰 시각/범위가 설명되지 않는다.
- 내부 결과 적용이 rollback됐거나 재고 예약·Outbox 상태가 기대와 다르다.
- 누가 소유한 주문인지 확인되지 않는다.

DB를 직접 수정하거나 PG create를 다시 호출하지 않는다.

## 복구 확인

1. 복구 전후 장애 집합의 실제 order ID가 정확히 같다.
2. 내부 주문 10건이 `CONFIRMED / SUCCESS`, 예약은 모두 CONSUMED다.
3. fault 때 추가 create가 없고 총 create는 정상 baseline 10 + 장애 집합 10이다.
4. PG 거래는 장애 집합 10건 모두 SUCCESS이고, fault 집합 create 횟수도 10에서 증가하지 않았다.
5. 각 주문의 Outbox eventId와 구매 활동이 주문 품목/수량 기준으로 한 번씩 존재한다.
6. 새로운 주간 snapshot이 해당 `confirmedAt` 기간의 전체 활동을 포함하며 공개 랭킹 API 점수·순위가 기대값과 같다.
7. fault 증거 hash가 복구 후에도 같고 새 복구 단계 증거는 별도 파일로 저장됐다.

## 재진입

조회 불가·PENDING이면 외부 상태를 다시 읽을 수 있을 때까지 대기한다. 기존 주문 ID와 payment key를 유지해 다시 조회하되 PG 승인 create를 보내지 않는다. 내부 transaction rollback이면 동일 주문의 저장 상태와 예약·Outbox 경계를 먼저 확인한 뒤 제한적으로 재조정한다. 이전 fault evidence를 덮어쓰지 않고 매 재진입을 새 시각/실행 ID로 기록한다.

이 절차는 로컬 합성 PG/DB에서 검증한 runbook이다. 운영 배포·실계정·부하 상황을 검증한 절차로 간주하지 않는다.
