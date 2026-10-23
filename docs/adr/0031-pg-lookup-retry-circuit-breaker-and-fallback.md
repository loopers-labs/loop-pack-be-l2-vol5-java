# ADR 0031: PG 조회만 이유 라벨로 판정해 제한된 retry·회로·fallback으로 감싼다

Status: Accepted
Related: [ADR 0032](0032-persist-payment-result-before-order-application.md), [ADR 0028](0028-test-pg-http-boundary-and-no-create-retry.md), [ADR 0030](0030-pg-payment-split-transactions-and-apply-once.md)

## Context

PG 조회가 timeout·503으로 실패하면 고객이 reconcile을 다시 눌러야 결과를 확인할 수 있었다. 반대로 PG 장애가 계속될 때 요청마다 조회를 보내고 timeout까지 기다리면, 장애 중인 PG에 부하를 더하고 우리 쪽 대기도 늘어난다.

adapter는 HTTP 오류를 잡아 UNKNOWN 값으로 돌려준다. Resilience4j의 retry·회로는 기본으로 예외만 실패로 보므로, 그대로 감싸면 503에도 재시도하지 않는다(실제 GET 1회). UNKNOWN에는 timeout·5xx·JSON 오류가 섞여 있어 "UNKNOWN이면 재시도"도 할 수 없다.

조회 GET은 기존 거래를 읽기만 하므로 다시 보내도 외부 효과가 없다. 생성 POST는 다시 보내면 거래가 하나 더 생긴다(ADR 0028).

## Decision

**판정: UNKNOWN에 이유 라벨**

- `PaymentObservation.UnknownCause`: `TEMPORARY_FAILURE`(timeout·연결 실패·5xx), `UNEXPECTED_RESPONSE`(형식 오류·예상 밖 상태 코드·4xx·증거가 부족한 422), `CIRCUIT_OPEN`(회로가 막아 PG에 묻지 않음).
- adapter는 RestClient 예외 종류로 라벨만 고른다(`ResourceAccessException`·`HttpServerErrorException` → TEMPORARY_FAILURE). 반환 모양은 유지한다.
- retry(`retryOnResult`)와 회로(`recordResult`)는 같은 라벨을 본다. 대답을 들은 결과(SUCCESS·FAILED·PENDING·0건)와 UNEXPECTED_RESPONSE는 재시도하지 않고 회로의 실패로도 세지 않는다. 정상 FAILED는 PG가 정상 동작한다는 증거다.
- UNEXPECTED_RESPONSE는 통계에서 제외하는 것이 아니라 성공 표본으로 센다. HALF_OPEN의 탐색 응답이 JSON 오류여도 CLOSED로 회복할 수 있으며, 애플리케이션은 그 응답을 UNKNOWN으로 처리한다. 현재 회로의 회복 판정은 업무 결과 적용 가능성을 보장하지 않는다.

**감싸는 범위와 순서**

- `ResilientPaymentProvider`가 조회 두 경로(거래 키·주문 번호)만 감싼다. 생성 POST는 그대로 adapter로 넘긴다.
- 순서는 바깥부터 fallback → retry → 회로 → GET이다. retry가 GET을 할 때마다 회로 허가를 받으므로 회로는 실제 GET 하나를 한 표본으로 센다. 회로가 막은 호출(`CallNotPermittedException`)은 retry에서 제외하고 바로 CIRCUIT_OPEN으로 돌려준다.
- 두 조회 경로는 같은 PG 조회 장애를 공유한다고 보고 회로 하나를 쓴다.

**숫자 (현재 코드의 임시 학습값 — 선정 미완료)**

값은 `application.yml`의 `commerce.payment.pg` 아래(`lookup-read-timeout`, `lookup-budget`, `lookup-retry.*`, `lookup-circuit.*`)에 두고 `LookupResilienceSettings`로 묶어 넘긴다. 코드를 바꾸지 않고 조정할 수 있다.

| 항목 | 값 |
| --- | --- |
| 조회 응답 timeout | 500ms (생성은 1s 유지). 연결·풀 대기 300ms는 공유 |
| retry | 최초 포함 최대 3회, 쉬는 시간 100ms → 200ms |
| 예산 | 2s. 남은 예산이 “가장 긴 쉬는 시간 200ms + 조회 한 번의 추정 시간(풀 대기 300 + 연결 300 + 응답 500 = 1.1s)”보다 적으면 다음 재시도를 시작하지 않는다. 진행 중인 호출의 총 시간 제한은 아니다 |
| 회로 | 최근 4개, 최소 4개, 실패율 50% 이상이면 OPEN, OPEN 10초, HALF_OPEN 시험 2회, 자동 전환 꺼짐 |

위 값은 동작 학습을 위해 적용한 임시값이다. 고정한 ‘503 두 번 뒤 정상 응답’ 테스트와 시간 측정이 통과한 사실은 최대 3회의 적절성을 증명하지 않는다. 현재 구조·대상·fallback 결정과 숫자의 선정 상태를 구분한다. 이번 과제에서는 가정과 비교 기준을 밝히고 같은 조건에서 후보 설정을 비교하며, 실제 운영의 최적값을 찾았다고 해석하지 않는다. 현재 기본 동작은 이미 테스트에 있다. 이후 감싸는 순서, retry 최대 횟수, 창 크기, 실패율 기준, 창 방식을 하나씩 바꿔 장애 모양별로 얻고 잃는 것을 비교했지만 값은 바꾸지 않았다. 비교는 요청을 하나씩 보내고 바로 오는 503으로 만든 장면이라, 운영값을 고르는 근거는 아니다([실험 기록](../week5/resilience-experiment-plan.md#wrap-order-experiments)).

**저장 결과의 복구와 fallback**

- 결과가 아직 미확정인 시도만 PG 조회 정책을 거친다. 결제 SUCCESS·FAILED가 저장됐지만 주문이 미반영이면 PG 조회·회로 없이 TX C로 복구한다(ADR 0032). C의 DB 오류는 이 GET retry의 대상이 아니다. B의 결제 종결만으로 주문 반영 완료나 callback 생략을 판단하지 않는다.

- retry 소진·예산 부족·회로 OPEN이면 TX B를 열지 않고 저장 상태를 돌려준다. 결제 시도·주문·예약·재고는 그대로다. 원래 PENDING이던 시도를 UNKNOWN으로 바꾸지 않는다.
- callback 입구에서는 같은 상황에 TX B 없이 PG에 503을 돌려준다. 200을 주면 PG가 다시 보낼 기회가 사라지기 때문이다. 다시 보낼지는 PG의 규칙이다.
- reconcile 응답의 `checked`가 false다. PENDING(“물어봤더니 처리 중”, checked true)과 fallback(“물어보지 못했다”, checked false)을 구분한다. confirm의 생성 응답이 UNKNOWN일 때도 false다.
- 같은 키 confirm이 PG를 부르지 않고 미완료 저장 상태만 반환할 때도 `checked: false`다. 저장된 SUCCESS·FAILED로 TX C를 복구했거나 주문 반영까지 완료된 결과는 추가 조회 없이 true로 반환한다. DB에 checked를 저장하지 않고 이번 응답을 구성할 때 판단한다.

## Alternatives and costs

- **에러가 보이는 adapter 안쪽에서 retry·회로를 돌리고 마지막에 값으로 바꾸기**: 에러 종류별로 세밀하게 다룰 수 있지만 adapter를 둘로 나눠야 한다. 이유를 남기는 일은 어느 쪽이든 필요해서 라벨 방식을 택했다.
- **회로를 retry 바깥에 두기**: OPEN일 때 retry까지 가지 않지만, retry 묶음의 최종 결과만 세므로 계속 503이면 OPEN까지 GET이 두 배로 나가고, retry가 매번 살리면 불안정을 영영 못 본다. 대신 회로 차단을 retry에서 제외하는 설정으로 헛기다림을 막았다.
- **어노테이션(`@Retry`·`@CircuitBreaker`)**: 코드는 짧지만 순서가 aspect 설정에 숨고 자기 호출 함정이 있다. 순서를 코드로 드러내려고 직접 감쌌다.
- **결제 생성 POST 회로와 TX A 전 확인**: 생성이 안 될 게 뻔하면 재고를 잡기 전에 거절할 수 있다. 확인할 회로는 조회가 아니라 생성 회로여야 하므로 이번에는 만들지 않았다. 조회 회로가 OPEN이어도 confirm의 POST는 나간다.
- **한계**: 숫자는 측정값이 아니다. 회로는 메모리에 있어 재시작하면 CLOSED로 새로 시작하고 서버마다 따로다. 회로는 동시 호출 수를 제한하지 않는다(bulkhead 미적용). 조회가 연결 풀(같은 주소당 5개)보다 많이 몰리면, 연결을 빌리지 못한 실패도 `TEMPORARY_FAILURE`로 세져 PG가 정상인데 회로가 열리는 것을 실험으로 확인했다([week5 실험 E](../week5/resilience-experiment-plan.md#learning-e)). 실패 분류와 bulkhead는 바꾸지 않았다. 1.1s는 재시도 판단용 추정치로, 현재의 지연 테스트에서는 GET 2회 뒤 멈춘다. 최초 GET의 시작을 예산으로 막거나 진행 중인 호출에 남은 시간을 적용하지 않으며, 실제 GET 전체의 1.1s 상한이나 전체 2s 종료를 보장하지 않는다. 예산은 PG 조회 구간을 대상으로 하며 reconcile 전체(DB 포함) 시간과 다르다.

## 검증

[week5 구현 기록](../week5/implementation-log.md)에 둔다. `PaymentLookupRetryTest`(응답 순서별 GET 수, 예산, 생성 POST 제외), `PaymentLookupCircuitBreakerTest`(GET마다 집계, OPEN에서 GET 0·즉시 반환, 정상 FAILED·형식 오류의 성공 표본 집계, 시계로 OPEN → HALF_OPEN → CLOSED/OPEN), `PaymentProviderContractTest`(라벨, 조회·생성 timeout 분리), `OrderPaymentIntegrationTest`(한 번의 reconcile로 회복, 끝내 실패·회로 OPEN에서 저장 상태·checked false), `PaymentChaosTest`(PG를 내리거나 느리게 했을 때 OPEN 뒤 reconcile 200ms 미만·장애 중 상품 조회 API 200·1초 미만, PG 응답 직후 DB가 멈춰도 회로 실패 0·CLOSED, PG 복구 후 배치의 시험 조회로 CLOSED).

형식 오류는 실패 표본에서 제외하지만 성공 표본에는 포함한다. 후속 테스트가 CLOSED의 성공 표본 수와 HALF_OPEN에서 JSON 오류 두 번 뒤 CLOSED 전환을 확인한다. 이때 두 조회 결과는 모두 UNKNOWN/UNEXPECTED_RESPONSE이며 회로의 회복과 결제 결과 확인은 구분된다. 이 테스트는 현재 판정을 검증한 것이며 그 판정이나 숫자의 적절성을 새로 선정한 것은 아니다.

`PaymentLookupPoolSeparationExperimentTest`는 총 연결 한도를 5개로 맞춰 공유 풀, 공유 풀 앞의 조회 실행 3개 제한, 조회 풀 3개·생성 풀 2개 분리를 비교했다. PG 응답 400ms·연결 대기 300ms, 조회 20건·생성 1건에서 실행 제한과 풀 분리 모두 생성 POST가 접수됐지만, 풀 분리는 조회 대기 실패로 조회 회로가 OPEN이 됐다. 넘친 조회를 회로 밖에서 즉시 거절하는 것과 연결 풀에서 기다리게 하는 것의 차이다. 실제 HTTP 경계에서 세 번씩 확인한 후보 비교이며, 앱의 공유 풀 결정과 retry·회로 설정은 유지한다. 생성 요청도 몰리는 경우와 연결 배분의 적절성은 검증하지 않았다.
