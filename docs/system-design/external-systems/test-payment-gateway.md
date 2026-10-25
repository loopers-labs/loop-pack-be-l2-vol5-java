# 로컬 테스트 PG

`apps:test-pg`는 Commerce와 별도 컨테이너로 실행하는 작은 테스트 PG다. 현재 생성·조회·3초 뒤 자동 처리·웹훅 발송과 [시나리오 제어](#시나리오-제어)를 구현했다. 실제 카드 승인·금전 처리·정산은 하지 않는다. HTTP 요청·응답의 기준은 [PG 연동 설계](payment-gateway.md#테스트-pg-계약), 실행 명령·입력 제약·제어 API 요청 형식과 저장 구조·예약 방식·웹훅 client 같은 구현 선택은 [서버 README](../../../apps/test-pg/README.md)에 둔다. Commerce의 PG adapter·callback·reconcile·주기 조회를 연결했다.

## 실행 경계

| 실행 대상 | 책임 |
| --- | --- |
| Commerce | 주문·재고·내부 결제 시도를 저장하고, adapter로 PG를 호출해 검증한 결과를 반영한다. |
| 로컬 테스트 PG | 별도 주소에서 HTTP 요청을 받고, 외부 거래를 저장·조회하며 결과 알림을 보낸다. Commerce의 DB와 도메인 객체를 참조하지 않는다. |

코드는 `apps/test-pg`에 두고 Gradle 모듈을 `:apps:test-pg`로 구성한다. `commerce-api`와 서로 Java 모듈 의존을 맺지 않으며 HTTP로 통신한다. Commerce의 PG adapter는 기존 `apps:commerce-api`의 infrastructure에 둔다.

별도 프로세스를 쓰는 이유는 Commerce를 재시작해도 외부 거래 상태를 유지하며 복구를 검증하기 위해서다. 실제 adapter의 HTTP 형식 검증은 별도의 작은 `HttpServer` fixture로 나눴다. PG 제품이나 관리 화면은 추가하지 않는다.

전체 결제와 callback의 수동 검증은 두 앱을 호스트의 `127.0.0.1`에서 실행한다. PG의 기본 바인딩도 `127.0.0.1`이다. Docker Compose는 PG API 독립 검증용이며, 컨테이너 내부 수신만 `0.0.0.0`으로 열고 호스트 공개 포트는 loopback으로 제한한다. Commerce의 바인딩을 외부 인터페이스로 넓히지 않는다. 실행 명령과 구성별 제약은 [서버 실행 안내](../../../apps/test-pg/README.md#실행)를 따른다.

`apps:test-pg` 자체의 HTTP 계약 테스트에서는 임의 포트로 PG와 웹훅 수신 서버를 실행한다. 정상 애플리케이션의 HTTP·저장·웹훅 구현을 사용하고 예약 작업의 실행 시점을 테스트가 제어한다.

### Commerce 테스트의 PG 실행

Commerce의 adapter·결과 반영 통합 테스트는 Testcontainers로 test-pg 컨테이너를 별도 프로세스로 실행한다. 로컬 Compose와 같은 `apps/test-pg/Dockerfile` 이미지를 사용하므로 계약 구현은 test-pg 하나다. `commerce-api`는 여전히 `apps:test-pg`를 Java 모듈로 의존하지 않는다.

| 항목 | 규칙 |
| --- | --- |
| 빌드 | `:apps:commerce-api:test`는 `:apps:test-pg:bootJar`에 의존해 이미지를 만들 JAR를 준비한다. Docker 실행 환경은 기존 MySQL Testcontainers와 같은 전제다. |
| 시작·종료 | JVM마다 고유 이름의 이미지를 만들고 `deleteOnExit=true`로 종료 시 정리한다. 시작 비용을 줄이기 위해 테스트 클래스 사이에서 하나의 컨테이너를 공유한다. |
| 주소 | PG 컨테이너의 호스트 공개 포트는 `127.0.0.1`의 임의 포트로 제한한다. adapter는 그 매핑 포트를 사용한다. PG가 보내는 callback을 실제로 받는 테스트만 고정한 Commerce 서버 포트와 `host.testcontainers.internal` 호스트 접근 터널을 사용하며 Commerce는 loopback 바인딩을 유지한다. 나머지 callback 테스트는 알림을 직접 보낸다. |
| 처리 시점 | 컨테이너는 [수동 처리 모드](#시나리오-제어)로 실행한다. 3초 대기나 실행 순서의 우연에 기대지 않는다. |
| 격리 | 테스트마다 정한 무작위 값으로 `providerOrderId` 생성기를 교체한다. Commerce DB 초기화로 주문 ID가 재사용돼도 `providerOrderId`가 달라 이전 테스트의 거래·시나리오와 섞이지 않는다. PG에는 거래 초기화 API를 두지 않으며, 메모리 거래는 컨테이너 종료 때 사라진다. |
| timeout | test 프로필의 adapter 응답 timeout은 `DROP_RESPONSE`의 응답 지연보다 짧게 둔다. |

## 거래 처리

- 생성 요청의 `orderId`는 [주문번호 규칙](payment-gateway.md#테스트-pg-계약)(영문 대소문자·숫자·`-`·`_`, 6~64자)을 검사하고 위반하면 `400`이다.
- 생성·조회는 고정된 테스트 제공자 계정 범위에서 처리한다. 같은 `orderId`로 생성을 반복해도 매번 새 `transactionKey`를 만든다.
- 거래의 제공자 계정·키·주문 ID·금액·상태와 알림에 필요한 callback URL을 보관한다. 카드 번호는 저장하거나 로그에 남기지 않는다.
- 기본 시나리오 `SUCCESS`는 거래를 저장하고 응답을 바로 반환하며, 별도 처리 작업이 기본 3초 대기 뒤 `PENDING → SUCCESS`로 변경한다. 요청 처리 스레드에서 대기하지 않는다. 대기 시간은 설정으로 조절한다.
- 최종 상태를 저장한 뒤 별도 실행기에 callback 전송을 제출한다. 완료 처리 작업은 callback 응답을 기다리지 않고, callback 직후의 조회도 저장된 최종 결과를 본다. callback 전송 실패·대기 한도 초과로 알림을 보내지 못해도 결과를 되돌리거나 자동 재전송하지 않으며 조회로 확인할 수 있다.
- 거래와 예약 작업은 메모리에 둔다. PG 프로세스·컨테이너를 재시작하면 초기화되며, Commerce만 재시작하면 PG 거래는 유지된다.

## 구현·검증 순서

기본 시나리오 `SUCCESS`를 먼저 구현하고 검증한 뒤 [시나리오 제어](#시나리오-제어)의 나머지 시나리오를 추가했다. 조회 지연과 정한 횟수의 조회 HTTP 오류 주입도 구현했다. 호출 한도는 Commerce의 RateLimiter로 다룬다.

첫 단계에서는 즉시 반환되는 `202/PENDING`, 대기 중의 조회, 자동 처리 뒤의 `SUCCESS`, 저장된 성공 결과와 같은 내용의 callback을 검증한다. 자동 테스트는 처리 시점을 제어해 상태를 확인하며 실제 3초 대기나 무작위 실행 순서에 기대지 않는다.

시나리오 제어는 로컬 실행과 테스트를 위한 기능이다. 고객 결제 입력에 시나리오 선택 필드를 추가하지 않는다. 각 시나리오에서 실제 생성·조회·callback 호출 횟수와 생성된 거래 수를 확인할 수 있어야 한다.

## 시나리오 제어

거래 시나리오는 PG의 테스트 전용 제어 API로 주문 ID별로 지정한다. 거래에는 접수 당시의 최종 결과와 callback 횟수를 고정하므로 이후 설정 변경은 새로 접수하는 거래에만 적용한다. 카드 번호·금액 같은 고객 결제 입력의 값으로 시나리오를 고르지 않는다.

| 요청 | 동작 |
| --- | --- |
| `PUT /test-control/v1/orders/{orderId}/scenario` | 해당 `orderId`(Commerce의 `providerOrderId`)의 이후 생성 요청에 적용할 시나리오를 등록한다. 테스트는 생성기로 정한 값으로 confirm 전에 등록한다. |
| `POST /test-control/v1/orders/{orderId}/complete` | 수동 처리 모드에서 해당 주문의 `PENDING` 거래를 접수 당시의 설정으로 종결하고 callback 전송을 제출한다. callback 전송 완료까지 기다리지 않는다. |
| `GET /test-control/v1/orders/{orderId}/stats` | 생성·조회 요청 수, callback 시도 수, 저장된 거래 수를 반환한다. |

| 시나리오 | PG의 동작 | 검증할 내용 |
| --- | --- | --- |
| `SUCCESS` (기본) | 거래를 `PENDING`으로 만들고, 처리 시 `SUCCESS` 저장 후 callback 한 번 | callback·조회가 같은 성공 결과를 제공 |
| `FAIL` | 처리 시 `FAILED` 저장 후 callback 한 번 | callback·조회가 같은 실패 결과를 제공 |
| `REJECT_BEFORE_CREATE` | [생성 전 확정 거절](payment-gateway.md#생성-전-확정-거절) 응답. 거래·예약 작업을 만들지 않는다. | 거래 0건, 후속 처리 없음 |
| `DROP_RESPONSE` | 거래를 저장·예약한 뒤 생성 응답을 설정한 지연보다 늦게 보내 호출자의 timeout을 만든다. | 주문 ID 조회로 거래를 찾을 수 있음 |
| `SKIP_CALLBACK` | 결과는 저장하고 callback을 보내지 않는다. | 조회만으로 결과 확인 |
| `DUPLICATE_CALLBACK` | 결과 저장 후 같은 callback을 두 번 보낸다. | 중복 반영 방지 |
| `CREATE_RESPONSE_OVERRIDE` | 지정한 HTTP 상태·원문 본문으로 생성 응답을 대체한다. 거래 생성 여부도 함께 지정한다. 본문 없는 422, 다른 code, 문자열 `"false"`, 필드 누락, 잘못된 JSON, `orderId`·`amount` 불일치를 만든다. | adapter가 거절 조건을 하나라도 어긴 응답을 `UNKNOWN`으로 판정 |

- 처리 모드는 실행 설정으로 고른다. 기본은 지연 뒤 자동 처리이고, 수동 모드에서는 `complete` 요청 전까지 `PENDING`을 유지한다. 수동 모드로 callback-first·lookup-first 순서를 테스트가 정한다.
- 제어 API는 실행 설정으로 켠 경우에만 등록하며 기본은 꺼짐이다. Testcontainers와 로컬 Compose에서 켠다. 제공자 헤더 `X-USER-ID: 135135`를 같이 요구하고 Commerce 운영 설정에서는 호출하지 않는다.
- 거래 성공·실패를 확률이나 카운트로 결정하지 않는다. 조회 HTTP의 일시 실패 횟수는 거래 결과와 별도로 제어한다.

### PG 조회 지연과 일시적 오류

거래의 성공·실패 설정과 분리해 두 조회 API 전체의 HTTP 응답 지연과 일시적 오류를 제어한다. 기본은 지연과 오류 주입 없는 정상 상태이며 테스트 전용 제어 API로 설정을 바꾸거나 정상으로 복구한다. 제어 API 자체는 지연하지 않는다.

- 조회 요청 시점의 지연 설정과 거래 상태를 사용한다. 조회 지연 중에도 결제 처리와 callback 전송은 계속하며 저장된 거래·금액·결과는 바꾸지 않는다.
- 설정 변경은 새 조회부터 적용하고 이미 대기 중인 조회는 선택한 지연을 유지한다. PG 재시작 없이 정상 복구할 수 있다.
- 실제 조회 요청 수는 지연 전에 기록하므로 호출자가 timeout된 조회도 집계한다. `0ms` 복구 후 같은 거래를 정상 조회할 수 있어야 한다.
- `failureCount`와 `failureStatus`로 정한 횟수의 조회 HTTP 오류도 주입할 수 있다. 거래 생성과 성공·실패는 바꾸지 않는다. 요청 형식과 값의 범위는 [조회 지연과 일시적 오류 제어](../../../apps/test-pg/README.md#조회-지연과-일시적-오류-제어)를 따른다. 호출 한도 차단은 Commerce의 RateLimiter가 맡는다.

## 후속 작업

- 웹훅 재전송: Commerce가 10초 안에 `200`을 주지 않으면 최대 7회 재전송한다. 간격은 지수 증가 방식으로 하되 테스트에서 줄일 수 있게 설정으로 둔다. callback 응답 timeout도 현재 2초에서 10초로 맞춘다. Commerce의 [응답 규칙](payment-gateway.md#응답-규칙)은 이미 재전송을 전제로 한다.

timeout, 조회 재시도와 서킷브레이커 등 구현한 정책과 운영 미정 사항은 [Commerce의 회복 전략](../use-cases/order-payment-recovery.md#조회-회복-정책)에서 관리한다.
