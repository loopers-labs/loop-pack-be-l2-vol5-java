# ADR 0100. 현재 UNKNOWN인 결제 시도 수를 Prometheus gauge로 제공한다

Status: Accepted

Extended by: [ADR 0101](0101-sample-oldest-unfinished-order-elapsed-time.md) (주문 기준 미완료 경과 시간 gauge 추가)

## 결정과 근거

주문별 PG 로그를 읽으면 한 건이 어디서 멈췄는지 알 수 있지만, 복구를 마친 주문의 timeout 로그도 계속 남는다. 지금 결과를 모르는 결제가 몇 건 남았는지 알려면 DB의 현재 상태를 세어야 한다. 단건 실험에서도 같은 결제 시도가 복구되자 DB 집계가 1에서 0으로 내려갔다. `PaymentIncidentSingleOrderExperimentTest.reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout`

commerce-api에서 `commerce.payment.attempts.unknown` gauge를 등록한다. 주문 번호로 범위를 제한하지 않고 연결된 DB 전체의 `PaymentAttempt.status = UNKNOWN` 행을 센다. 정상 접수 결제도 거치는 `PENDING`은 제외한다. 주문마다 달라지는 식별자는 지표 label에 넣지 않는다. 같은 DB를 보는 여러 인스턴스의 값은 각각 DB 전체를 나타낸다.

로컬 Prometheus가 5초마다 수집하므로 앱의 기본 집계 간격도 5초로 정했다. 각 인스턴스가 이전 집계를 끝낸 후 기다리는 시간이며 `commerce.payment.unknown-count.refresh-delay`로 조정한다. endpoint는 최근 측정값을 읽는다. 첫 조회 전이나 조회 실패 뒤에는 `NaN`을 제공해 집계 불가와 실제 0건을 구분한다. 조회 실패는 WARN 한 줄(`UNKNOWN 결제 시도 집계 실패: error=<예외 이름>`)로 남기고 다시 던지지 않는다. 다시 던지면 DB가 안 되는 동안 스케줄러가 집계 주기마다 스택트레이스를 ERROR로 남긴다. 다음 성공한 집계부터 숫자가 다시 보인다. [수집 설정](../../docker/grafana/prometheus.yml)

## 대안과 영향

스크레이프마다 DB를 조회하면 진단자가 endpoint를 반복해서 열 때마다 같은 테이블을 다시 센다. 결제 흐름에서 메모리 숫자를 직접 증감하면 프로세스 재시작과 다른 인스턴스에서 일어난 복구까지 맞춰야 한다. 주기 집계는 결제 트랜잭션 밖에서 현재 상태를 다시 읽으므로 두 부담을 피한다. 대신 짧게 머물렀다 사라지는 상태는 관찰 사이에 지나갈 수 있다. endpoint 반영에는 스케줄 대기와 DB 조회 시간이, Prometheus에 저장되기까지는 다음 수집 대기가 더해진다. [단건 endpoint 실험](../week10/implementation-log.md#c-prometheus에서-같은-결제-시도-관찰)
