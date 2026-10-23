# ADR 0034: 확실한 미발송만 실패로 닫고, 오래 조회되지 않는 결제는 미완료로 유지한다

Status: Accepted
Related: [ADR 0028](0028-test-pg-http-boundary-and-no-create-retry.md), [ADR 0030](0030-pg-payment-split-transactions-and-apply-once.md), [ADR 0033](0033-payment-recovery-batch-for-unfinished-attempts.md)

## Context

결과를 모르는 결제는 예약을 유지하고 PG 조회로만 확정한다(ADR 0028·0030). 그런데 PG에 요청이 닿지 않은 결제는 조회해도 계속 0건이라 끝나지 않고, 그동안 재고가 묶였다. 실험에서 두 장면을 확인했다.

- PG가 꺼진 동안의 결제 생성. 카오스 테스트에서 이 결제는 배치를 돌려도 UNKNOWN·예약 유지로 남았다.
- PG는 정상인데 조회가 몰려 연결 풀(같은 주소당 5개)을 모두 잡은 동안의 결제 생성. 생성 POST가 연결 빌리기 대기에서 끝나 PG에 닿지 않았다.

기존 adapter는 연결 단계의 실패와 응답 timeout을 같은 `TEMPORARY_FAILURE`로 묶어서, "확실히 보내지 않았다"와 "보냈는지 모른다"를 구분하지 못했다. 미발송 근거가 있는 실패를 따로 분류할 필요가 있었다.

처음에는 REQUESTED·UNKNOWN이 30분을 넘고 PG 조회가 0건이면 실패로 닫는 정책도 구현했다. 하지만 조회 0건은 그 순간 거래가 보이지 않았다는 뜻이며, 이후에도 거래가 만들어지지 않는다는 보장은 아니다. 기존 테스트는 닫은 뒤 SUCCESS가 나타나도 충돌만 기록하고 실패 주문·반환 재고를 유지함을 확인했다. 이는 복구가 아니라 불일치의 감지였고, 과제의 "조회 0건에도 미완료 유지" 계약과 충돌했다. 따라서 이 자동 만료 정책은 철회한다.

## Decision

결과를 세 가지로 나눈다.

| 상황 | 처리 |
| --- | --- |
| **보내지 못한 것이 확실함** | confirm에서 바로 FAILED로 닫고 TX C가 재고를 돌려준다 |
| **보냈는지 모름** | 다시 보내지 않고 PG 조회로만 확정한다 (기존 규칙) |
| **오래 기다렸어도 조회에 거래가 없음** | 미완료 상태와 예약을 유지하고, 복구 배치가 기존 거래를 계속 조회한다 |

**보내지 못한 것이 확실한 경우**는 요청이 우리 서버 밖으로 나가기 전, 연결 단계에서 끝난 실패만이다.

| 생성 POST의 실패 | 분류 |
| --- | --- |
| 연결을 빌리지 못함 (`ConnectionRequestTimeoutException`) | 보내지 못함 (`NOT_SENT`) |
| 새 연결이 거부됨 (`ConnectException`) | 보내지 못함 |
| 연결 수립 시간 초과 (`ConnectTimeoutException`) | 보내지 못함 |
| 응답 timeout | 모름 (`UNKNOWN`). PG가 거래를 만들고 응답만 잃었을 수 있다 |
| 재사용하던 연결로 요청을 쓰다 끊김 | 모름. 요청을 쓴 뒤라 PG에 닿지 않았다고 증명할 수 없다 |
| 5xx·형식이 다른 응답 | 모름. 접수 전 거절의 증거는 계약된 422뿐이다 |

- adapter가 `PaymentObservation.notSent()`를 돌려주고, TX B가 결제 시도를 키 없이 FAILED로 닫는다(`markNotSent`, 이력 사유 `NOT_SENT`). 상태 규칙은 접수 전 거절(`reject`)과 같다. REQUESTED만 닫히고, PG의 대답을 이미 기록한 시도와는 충돌로 남는다.
- 조회 GET의 연결 실패는 지금처럼 `TEMPORARY_FAILURE`다. 조회는 결과를 확정하지 않으므로 이 구분이 필요 없다.

**복구 배치**는 만든 지 10분이 지난 모든 미완료 시도에 `OrderFacade.recover`를 적용한다(ADR 0033). 오래됐다는 이유로 다른 실패 전이를 적용하지 않는다.

- 조회 0건은 `NO_TRANSACTION_YET`·IGNORED 이력만 남기고 REQUESTED·UNKNOWN과 예약을 유지한다. PENDING 응답이나 조회 실패도 결제 실패의 근거로 삼지 않는다.
- 이후 SUCCESS·FAILED를 확인하면 기존 TX B가 결과를 저장하고 TX C가 주문·예약·재고에 한 번만 반영한다. TX B에 이미 종결 결과가 있으면 PG 조회 없이 TX C부터 복구한다.
- `OrderFacade.expire`, `expireWithoutTransaction` 전이, 이력 사유 `EXPIRED_WITHOUT_TRANSACTION`은 제거한다. local·test는 `ddl-auto: create`라 그 값이 저장된 DB가 없다.
- 30분을 기준으로 장기 미확인 건을 검토하거나 알림을 보낼 수는 있지만, 결제 실패 시각으로 쓰지 않는다. 장기 미확인 전용 알림·상태·운영 화면은 이번에 추가하지 않는다.

**닫힌 결제에 callback이 오면**: 거래 없이 닫히는 것은 접수 전 거절(422)과 확실한 미발송뿐이고, 둘 다 거래가 없다는 증거가 있다. 그래서 다른 끝난 결제와 같이 조회 없이 200으로 답한다. 만료 정책에서 늦은 거래를 잡으려고 넣었던 "끝난 결제여도 다시 조회" 분기는 만료와 함께 제거했다.

**닫힌 주문**: PAYMENT_FAILED로 끝난다. 주문당 결제 시도가 하나이므로 같은 주문으로 다시 결제하지 않고, 고객은 주문을 새로 만든다.

## Alternatives and costs

- **연결 단계의 확실한 실패까지 UNKNOWN으로 유지**: 미발송 근거가 있는데도 재고를 계속 묶는다. 이 경우에만 `NOT_SENT`로 종결한다.
- **30분 경과 + 조회 0건이면 실패 처리**: 구현했으나 철회했다. 늦은 거래를 부정할 수 없고, 닫은 뒤 SUCCESS를 충돌로 기록해도 이미 반환한 재고와 실패 주문은 복구되지 않는다.
- **결과를 모르면 상태와 상관없이 N분 뒤 닫기**: 단순하지만 PENDING이나 응답만 잃은 결제까지 닫아, 돈은 빠졌는데 주문은 실패인 결과가 생길 수 있다.
- **보내지 못한 시도를 DRAFT로 되돌려 다시 결제하게 하기**: 같은 주문으로 재시도할 수 있지만, 주문당 여러 시도·시도별 PG 주문 번호·예약 처리를 다시 설계해야 한다. 이번 범위에서 제외했다.
- **응답 timeout도 보내지 못함으로 보기**: 응답 유실 장면(구현 기록 1절)처럼 PG가 거래를 만들었을 수 있어 택하지 않았다.
- **대가와 한계**
  - PG가 끝내 결과를 주지 않거나 거래가 계속 보이지 않으면 미완료 시도와 예약이 오래 남을 수 있다. 조회 횟수 제한·장기 미확인 알림·수동 처리 기준은 남은 운영 과제다.
  - PG가 꺼진 직후 재사용 연결로 나간 요청은 "모름"이라 즉시 닫지 않는다. 복구 뒤 조회가 0건이어도 같은 규칙이다.
  - timeout이나 PG 응답 시간 분포는 대기 정책의 근거가 될 수 있지만, 이후 거래가 없다는 증명은 아니다. 안전한 자동 반환에는 PG의 최종 실패 보장이나 확인된 취소 같은 추가 계약이 필요하다. 취소 응답까지 유실될 수 있으므로 취소 결과도 확인해야 한다.

## 검증

- `PaymentAttemptTest`: `markNotSent`는 REQUESTED만 키 없이 FAILED, 다시 부르면 IGNORED. UNKNOWN·PENDING·SUCCESS·거래가 있는 FAILED와는 CONFLICT이며 기존 상태·키를 유지한다.
- `PaymentProviderContractTest`: 새 연결이 거부된 생성과 연결을 빌리지 못한 생성은 `notSent`, 응답 timeout은 `UNKNOWN`.
- `OrderPaymentIntegrationTest`: 조회 5개가 연결을 모두 잡은 동안의 confirm은 바로 FAILED·RELEASED·재고 반환, PG 거래 생성 0, 이력 사유 `NOT_SENT`.
- `PaymentRecoveryBatchTest`: 31분 된 UNKNOWN·REQUESTED의 조회 0건에도 미완료·예약·재고 유지·POST 추가 0. 반복 실행·PENDING·조회 실패도 같은 규칙이다. 이후 거래가 나타나 SUCCESS면 정상 확정하며, callback·reconcile·배치를 반복해도 추가 차감·POST가 없다. 늦은 거래는 테스트에서 PG 생성 호출로 주입한 상황이며, 앱의 재전송이 아니다.
- `PaymentChaosTest`: PG가 꺼진 직후 재사용 연결로 나간 결제는 UNKNOWN, 다음 결제는 새 연결이 거부돼 바로 FAILED. PG 복구 뒤 배치는 확인한 성공만 확정하고, 31분 된 UNKNOWN의 조회 0건에는 예약을 유지한다.
- 연결이 거부되는 테스트 환경에 기대던 기존 통합 테스트(재고 동시성·잠금 등)는 기본 가짜 PG(`DefaultPgTestServerConfig`)를 쓰도록 바꿨다. 이 테스트들이 보던 재고 예약·잠금 기대값은 그대로다.
