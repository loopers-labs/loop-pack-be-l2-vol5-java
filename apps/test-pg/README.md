# 로컬 테스트 PG

Commerce와 HTTP로 통신하는 독립 실행용 테스트 서버다. 거래를 메모리에 저장하고, 정상 요청은 즉시 `202/PENDING`으로 접수한 뒤 기본 3초 후 처리한다. 최종 상태를 저장한 뒤 요청의 `callbackUrl`로 웹훅을 보낸다. 실패·생성 전 거절·응답 유실 같은 시나리오는 테스트 제어 API로 주문별로 지정한다.

## 실행

저장소 루트에서 실행한다.

```bash
./gradlew :apps:test-pg:bootRun
```

일반 실행은 `127.0.0.1:8082`에 바인딩한다. Commerce도 같은 호스트의 loopback 주소에서 실행해 전체 결제와 callback을 확인한다.

```bash
./gradlew :apps:commerce-api:bootRun \
  --args='--server.address=127.0.0.1 --server.port=8080 --payment.pg.base-url=http://127.0.0.1:8082 --payment.pg.callback-url=http://127.0.0.1:8080/api/v1/payments/callback'
```

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `TEST_PG_PORT` | `8082` | 로컬 포트. Commerce의 관리(actuator) 포트 `8081`과 겹치지 않게 둔다. |
| `TEST_PG_PROCESSING_DELAY` | `3s` | 자동 처리 모드에서 접수 후 처리까지의 대기 |
| `TEST_PG_PROCESSING_MODE` | `AUTO` | `AUTO`는 대기 뒤 자동 처리, `MANUAL`은 `complete` 요청 때만 처리 |
| `TEST_PG_DROP_RESPONSE_DELAY` | `3s` | `DROP_RESPONSE` 시나리오에서 생성 응답을 늦추는 시간 |
| `TEST_PG_CONTROL_ENABLED` | `false` | 테스트 제어 API 등록 여부. 로컬 Compose는 `true` |

컨테이너로 실행할 때는 JAR를 먼저 만든다.

```bash
./gradlew :apps:test-pg:bootJar
docker compose -f docker/test-pg-compose.yml up --build -d
```

이 Compose 파일은 PG API를 독립적으로 확인할 때 사용하며 PG만 실행한다. 컨테이너 내부에서는 `0.0.0.0:8080`으로 수신하되 호스트 공개 포트는 `127.0.0.1:${TEST_PG_PORT:-8082}`로 제한한다. Commerce의 외부 인터페이스 바인딩은 사용하지 않는다.

Compose 없이 이미지를 직접 실행할 수도 있다. JAR를 만든 뒤 아래 명령을 사용한다. 이미지의 기본 수신 주소는 `0.0.0.0:8080`이며, 호스트에는 loopback 포트만 공개한다.

```bash
docker build -f apps/test-pg/Dockerfile -t loopers-test-pg .
docker run --rm --name loopers-test-pg \
  -p 127.0.0.1:8082:8080 loopers-test-pg
```

컨테이너의 `127.0.0.1`은 컨테이너 자신을 가리킨다. 호스트 Commerce로의 callback까지 포함한 수동 검증은 위의 두 앱 호스트 실행을 사용한다. 자동 테스트는 Testcontainers의 호스트 접근 터널로 callback을 전달하므로 Commerce는 loopback 바인딩을 유지한다. 테스트 PG의 공개 포트도 loopback으로 제한하며, JVM마다 고유한 이미지를 만들고 종료 시 이미지와 컨테이너를 정리한다.

## HTTP 계약

모든 요청에 테스트 제공자 계정 헤더 `X-USER-ID: 135135`를 보낸다. 일반 Commerce API의 `ApiResponse`로 감싸지 않고 아래 JSON을 직접 반환한다.

| 요청 | 응답 |
| --- | --- |
| `POST /api/v1/payments` | `202`, 거래 객체. 시나리오에 따라 `422` 거절이나 지정한 응답 |
| `GET /api/v1/payments/{transactionKey}` | `200`, 거래 객체. 없는 키는 `404` |
| `GET /api/v1/payments?orderId={orderId}` | `200`, 거래 객체 배열. 거래가 없으면 `[]` |

생성 입력 예시 (Commerce와 PG 모두 호스트에서 실행):

```json
{
  "orderId": "LP-ORD-0000000042-7f3a9c1e",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000",
  "amount": 7000,
  "callbackUrl": "http://127.0.0.1:8080/api/v1/payments/callback"
}
```

거래 객체는 `transactionKey`, `orderId`, 정수 `amount`, `status`를 반환한다. 상태는 처음 `PENDING`, 처리 후 `SUCCESS` 또는 `FAILED`다. 같은 주문으로 다시 생성해도 새 거래를 만든다. 카드 값은 테스트 값만 사용하며 거래·로그에 카드 번호를 저장하지 않는다.

입력 오류·헤더 누락은 `400`, 다른 제공자 계정은 `401`이다. 주문 ID는 영문 대소문자·숫자·`-`·`_`로 된 6자 이상 64자 이하 문자열이고, 금액은 양의 JSON 정수이며 카드 입력은 필수 문자열이다. callback URL은 호스트를 포함하는 HTTP(S) 주소여야 한다.

생성 전 확정 거절 응답:

```text
HTTP/1.1 422
{"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":"LP-ORD-0000000042-7f3a9c1e","amount":7000,"transactionCreated":false}
```

웹훅 본문:

```json
{
  "providerOrderId": "LP-ORD-0000000042-7f3a9c1e",
  "transactionKey": "TEST-TX-...",
  "status": "SUCCESS"
}
```

결과 저장 뒤 별도 실행기가 callback을 전송한다. 완료 처리 스레드는 callback 응답을 기다리지 않는다. callback 수신 서버가 없거나 전송이 실패해도 PG의 결과는 유지되며 조회할 수 있다. 실행기는 전송 스레드 2개·대기 작업 100개로 제한한다. 대기가 가득 차면 경고 로그를 남기고 해당 알림을 생략한다. 현재 웹훅은 자동 재전송하지 않는다. 연결 timeout은 1초, 응답 timeout은 2초다.

## 테스트 제어 API

`TEST_PG_CONTROL_ENABLED=true`일 때만 등록한다. 모든 제어 요청에 제공자 헤더 `X-USER-ID: 135135`가 필요하다. 시나리오는 고객 결제 입력이 아니라 이 API로만 고른다.

| 요청 | 응답 |
| --- | --- |
| `PUT /test-control/v1/orders/{orderId}/scenario` | `204`. 해당 주문의 이후 생성 요청에 적용할 시나리오를 등록 |
| `POST /test-control/v1/orders/{orderId}/complete` | `200`, `{"completed": n}`. 수동 처리 모드에서 거래를 접수 당시의 설정으로 종결하고 callback 전송을 제출. 응답은 callback 전송 완료를 뜻하지 않음 |
| `GET /test-control/v1/orders/{orderId}/stats` | `200`, `{"createRequests", "lookupRequests", "callbackAttempts", "transactions"}` |

시나리오 등록 예시:

```json
{"scenario": "FAIL"}
```

```json
{"scenario": "CREATE_RESPONSE_OVERRIDE", "responseStatus": 422, "responseBody": "", "createTransaction": false}
```

| 시나리오 | 동작 |
| --- | --- |
| `SUCCESS` (기본) | 처리 시 `SUCCESS` 저장 후 callback 한 번 |
| `FAIL` | 처리 시 `FAILED` 저장 후 callback 한 번 |
| `REJECT_BEFORE_CREATE` | 거래·예약 작업 없이 위의 `422` 거절 응답 |
| `DROP_RESPONSE` | 거래를 저장·예약한 뒤 `TEST_PG_DROP_RESPONSE_DELAY`만큼 늦게 응답 |
| `SKIP_CALLBACK` | 결과만 저장하고 callback을 보내지 않음 |
| `DUPLICATE_CALLBACK` | 결과 저장 후 같은 callback을 두 번 보냄 |
| `CREATE_RESPONSE_OVERRIDE` | `responseStatus`·`responseBody`를 그대로 응답하고 `createTransaction`에 따라 거래 생성 |

거래마다 접수 당시의 최종 결과와 callback 횟수를 보관한다. 같은 주문의 시나리오를 바꿔도 이미 접수된 거래에는 영향을 주지 않는다. 등록하지 않은 주문은 `SUCCESS`를 쓴다.

조회 요청 수는 거래를 찾은 단건 조회와 주문 단위 조회를 그 주문의 요청으로 세며 응답 지연 전에 기록한다. callback 횟수는 실제 전송을 시작할 때 기록한다.

### 조회 지연과 일시적 오류 제어

주문별 결제 결과와 별도로 PG의 두 조회 API 전체에 적용한다. 기본값은 `0ms`이며 생성·결과 처리·callback 전송·제어 API에는 지연을 적용하지 않는다.

| 요청 | 응답 |
| --- | --- |
| `GET /test-control/v1/lookup` | `200`, `{"delayMillis": 0, "failuresRemaining": 0, "failureStatus": 503}`. 현재 조회 제어 상태 |
| `PUT /test-control/v1/lookup` | `204`. 본문 `{"delayMillis": 4000}`으로 지연 설정, `{"delayMillis": 0}`으로 정상 복구 |

`delayMillis`는 `0~60000`의 JSON 정수다. 누락·타입·범위 오류는 `400`, 다른 제공자 계정은 `401`이며 기존 설정을 유지한다.

각 조회는 요청 시점의 설정과 거래 상태를 읽은 뒤 HTTP 응답을 지연한다. 기존 거래·금액·결과를 바꾸지 않으며, `0ms`로 복구해도 이미 대기 중인 요청은 선택한 지연을 유지한다. PG 재시작 없이 설정을 바꿀 수 있고 PG 재시작 시에는 정상 상태로 초기화된다.

timeout을 재현하려면 조회 지연을 호출자의 응답 timeout보다 충분히 길게 설정한다. 예를 들어 응답 timeout이 3초인 호출자에는 `4000ms`를 설정하고, 실험 후 `0ms`로 복구한다. 조회 429와 주문별 부분 장애는 이번에 추가하지 않았다.

실패 횟수도 같은 제어 API에서 설정한다.

```json
{"delayMillis": 0, "failureCount": 2, "failureStatus": 503}
```

두 조회 API가 다음 응답 계획을 공유한다. 위 설정이면 먼저 들어온 조회 2개는 503, 그 뒤 조회는 정상 응답한다. `failureCount`는 0~10000의 JSON 정수, `failureStatus`는 502, 503, 504 중 하나다. 횟수는 동시에 안전하게 소비하고 거래 결과는 변경하지 않는다. 상태 조회의 `failuresRemaining`으로 남은 횟수를 확인한다.

기존처럼 `delayMillis`만 보내면 실패 횟수는 0, 오류 상태는 503으로 초기화한다. 잘못된 입력은 400이며 이전 설정을 유지한다. 이 설정은 PG 인스턴스 전체에 적용하므로 통제 실험을 동시에 실행하지 않는다.

## 구현 선택

| 항목 | 선택 |
| --- | --- |
| HTTP 서버 | 기존 프로젝트 버전의 Spring Boot 앱. 기본 로컬 포트는 `8082`, 컨테이너 내부 포트는 `8080` |
| 거래 저장 | `ConcurrentHashMap`에 불변 거래와 접수 당시의 최종 결과·callback 횟수를 저장하고, 같은 거래의 종결 전이는 한 실행만 반영 |
| 자동 처리 | 스레드 2개의 `ThreadPoolTaskScheduler`로 기본 3초 후 처리 작업 예약. HTTP 요청 스레드는 대기하지 않음 |
| 시나리오·호출 수 | 주문 ID별로 메모리에 보관. 확률·카운트 기반 결과 결정은 쓰지 않음 |
| 웹훅 | 결과 저장 후 별도 `ThreadPoolTaskExecutor`에서 `RestClient`로 전송. 스레드 2개·대기 작업 100개, 연결 timeout 1초·응답 timeout 2초 |
| 조회 지연 | 전역 설정을 메모리에 보관하고 조회별로 읽어 HTTP 응답만 지연 |
| 자동 테스트 | 실제 HTTP와 수신 서버 사용. 자동 처리 테스트는 예약 작업만 수동 실행하고, 제어 API 테스트는 수동 처리 모드로 처리 시점을 정함 |

## 검증과 범위

```bash
./gradlew :apps:test-pg:check
```

HTTP 계약 테스트는 별도 HTTP 수신 서버로 실제 웹훅을 검증하고, 처리 시점을 제어해 3초를 실제로 기다리지 않는다.

PG 프로세스·컨테이너를 재시작하면 거래·시나리오·호출 수와 대기 중 작업이 초기화된다. Commerce만 재시작하면 PG 거래는 유지된다. Commerce의 결제 요청·callback 수신·reconcile·주기 조회를 이 PG에 연결했다.

설계 책임과 후속 검증은 [로컬 테스트 PG 설계](../../docs/system-design/external-systems/test-payment-gateway.md)를 따른다.
