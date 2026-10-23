# 5주차 결제 경계와 상태 설계

실습의 로컬 PG 계약을 구현했다. 신규·기존 DRAFT의 확정 API는 카드 결제를 시작하며 포인트를 차감하지 않는다.
과거 포인트 결제 주문은 저장된 금액·결과·품목을 보존하고 POINT로 조회한다.
현재 결제 수단은 POINT·CARD enum이며, 결제 행이 있으면 CARD, 결제 행 없이 지급 금액이 있는 과거 주문은 POINT로 표시한다.
새 시도·환불·사용자 취소·예약 자동 만료·상시 스케줄러는 이번 범위에 포함하지 않는다.

## 식별과 영속화

| 값 | 역할 | DB 보호 |
| --- | --- | --- |
| orderId | 내부 주문 | 주문 행 잠금, 상태 조건 갱신 |
| paymentAttemptKey | 고객이 같은 결제 요청을 식별하는 UUID v7 | 유니크 제약, 주문·저장 금액 일치 확인 |
| providerOrderId | PG 생성·조회에 공통으로 보내는 주문번호 | `LP-ORD-%010d`, 유니크 제약 |
| transactionKey | PG가 만든 거래 | 유니크 제약, 기존 거래키와 일치 확인 |

주문당 결제 행은 하나이며 발송 시작 시각도 DB에 남긴다. 합성 카드 번호는 DB·로그에 저장하지 않는다.
PG는 paymentAttemptKey를 받지 않고 같은 주문번호 POST를 중복 제거하지 않는다.
같은 주문·시도는 저장 상태를 반환하며 다른 주문에서 쓴 키나 같은 주문의 새 키는 CONFLICT다.
과거 POINT 확정 주문은 기존 INVALID_REQUEST를 유지한다.

## 트랜잭션과 HTTP 순서

1. **TX A 시작**: 고객 소유권을 확인하고 주문 행을 잠근다. 같은 시도가 이미 있으면 저장된 결제만 반환한다.
2. DRAFT·상품·저장 금액·재고를 검사하고 가용 재고를 차감한다. 주문별 HELD 예약과 결제 PENDING·발송 시작을 저장한다.
3. **TX A 커밋**: 새 발송 권한을 얻은 실행만 PG POST를 호출한다. 여기서 중단돼도 다음 confirm은 POST를 다시 보내지 않는다.
4. **TX 밖**: 고정 로컬 PG HTTP 호출. POST에는 Apache HttpClient의 `disableAutomaticRetries()`·리다이렉트 금지를 적용한다([공식 구현](https://github.com/apache/httpcomponents-client/blob/rel/v5.4.2/httpclient5/src/main/java/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.java)). GET은 기존 JDK HTTP 클라이언트와 Retry → Circuit → HTTP를 사용한다.
5. **TX B 시작**: 주문 → 결제 순서로 잠그고 PG 주문번호·거래키·금액을 검사한다. 주문·결제·예약·재고 효과를 함께 적용한다.
6. **TX B 커밋**: 중간 저장 실패는 전체 rollback한다. 같은 PG 거래를 다시 조회해 적용할 수 있다.

준비 TX는 READ_COMMITTED를 사용한다. 주문 잠금을 기다린 실행이 앞선 실행의 새 결제 행을 읽도록 하기 위함이다.
시도 키를 다른 주문에서 동시에 쓰는 경우도 DB 유니크 제약으로 하나만 허용한다.
재고 갱신은 상품 ID 순서의 원자적 SQL이며 PG HTTP 동안 주문·재고 잠금을 보유하지 않는다.

## 상태와 효과

| PG에서 확인한 것 | 결제 | 주문 | 예약 | 가용 재고 예시 |
| --- | --- | --- | --- | --- |
| 생성 접수·조회 PENDING | PENDING | PAYMENT_PENDING | HELD | 5 → 3 유지 |
| 응답 유실·타임아웃 | UNKNOWN | PAYMENT_PENDING | HELD | 3 유지 |
| 조회 불가·회로 OPEN | 저장 상태 유지 | 저장 상태 유지 | HELD 유지 | 3 유지 |
| 조회 0건·복수 거래 | UNKNOWN, 종결 결과는 보존 | PAYMENT_PENDING 유지 | HELD 유지 | 3 유지 |
| 검증한 SUCCESS | SUCCESS | CONFIRMED | CONSUMED | 3 유지, 추가 차감 0 |
| 검증한 FAILED | FAILED | PAYMENT_FAILED | RELEASED | 3 → 5, 한 번만 반환 |
| 거래 생성 전 효과 0건 확정 거절 | FAILED, 거래키 없음 | PAYMENT_FAILED | RELEASED | 3 → 5, 한 번만 반환 |
| 식별·금액 불일치·상충 결과 | 미확인·충돌, 기존 값 보존 | 기존 값 보존 | 기존 값 보존 | 임의 반환 없음 |

효과 0건 거절은 HTTP422, 정확한 code, boolean false, 주문번호·정수 금액 일치가 모두 확인될 때만 인정한다.
일반 4xx·5xx·빈 본문·잘못된 JSON은 이 증거가 아니다. 정상 응답도 문자열 식별자·문자열 상태·정수 금액과 배열/객체 형태를 검사한다.
이미 종결한 결과는 늦은 접수 PENDING·타임아웃으로 덮어쓰지 않으며 반대 종결 결과는 충돌로 거절한다.

예약 뒤 삭제된 상품도 기존 주문 스냅샷으로 마무리하며 실패 반환이 삭제 상태를 해제하지 않는다.
관리자 재고 설정은 예약을 제외한 현재 가용 수량의 최종 설정이다. 실패 반환은 그 현재 수량에 예약 수량만 더한다. HELD 상태 변경의 행 수를 확인하고 정수 범위를 넘는 반환은 TX 전체를 rollback한다.
목록 캐시는 가용 재고를 담지 않으므로 결제 재고 변경에 대한 별도 무효화는 필요하지 않다.

## API와 복구

| API | 동작 |
| --- | --- |
| POST `/api/v1/orders/{id}/confirm` | 본인 주문, UUID v7·합성 카드 입력. 금액은 저장된 주문에서 결정 |
| GET `/api/v1/orders/{id}` | 본인 주문·결제 수단·시도 식별자·상태·결과 조회, PG 생성 없음 |
| GET `/api-admin/v1/orders` 및 상세 | 기존 관리자 접근 구분 유지, 같은 결제 메타데이터 표시 |
| POST `/api/v1/orders/{id}/payment/reconcile` | 본인의 기존 providerOrderId로 PG GET 조회 후 공통 TX B 적용 |
| POST `/api/v1/payments/callback` | 알림의 transactionKey로 고정 PG를 GET 조회한 뒤 공통 TX B 적용 |

confirm/reconcile의 미완료 응답은 HTTP202와 orderStatus·paymentStatus·orderLookupPath를 반환한다.
같은 PG 시도의 종결 결과는 HTTP200이다. reconcile은 lookupAvailable로 이번 PG 조회 가능 여부를 표시한다.
콜백 본문의 status는 성공 증거가 아니다. callback-first·lookup-first·중복·동시 실행도 같은 거래와 최종 상태로 수렴한다.
콜백 조회 불가 시 HTTP202로 수신하며, 완료 결과가 이미 저장됐다면 HTTP200으로 응답한다.

서버 재시작 이후에도 저장된 발송 기록을 보고 POST를 재전송하지 않는다. PG 거래가 조회되지 않아도 실패·예약 반환으로 판단하지 않는다.
명시적인 reconcile로 기존 거래를 재조회한다. GET 재시도·서킷·대기 예산은 아래 조회 보호 설정을 따른다.
장기 UNKNOWN은 재고를 오래 점유할 수 있다. 이번 범위에서는 자동 실패 처리하지 않으며 추가 거래 확인·운영 판단이 남는다.

## 조회 보호 설정

PG 하나를 사용하는 학습 환경에서 장애 감지를 신중하게 하도록 최소 표본을 10회로 정했다. 운영 적정값을 실측한 결과는 아니다.
주문번호 GET과 콜백의 거래키 GET은 같은 서킷과 재시도 정책을 사용한다.

| 설정 | 값 |
| --- | --- |
| 연결 / 개별 응답 타임아웃 | 500ms / 2초 |
| 서킷 집계 / 최소 표본 | 최근 10회 / 10회 |
| 차단 실패율 | 50% 이상 |
| OPEN 대기 / HALF_OPEN 허용 | 10초 / 1회 |
| GET 최대 시도 / 재시도 간격 | 최초 1회 + 재시도 1회 / 200ms |
| GET 전체 대기 예산 | 두 시도와 사이 대기를 합해 5초 |

호출 순서는 `RetryingPaymentGateway → CircuitBreakingPaymentGateway → HttpPaymentGateway`다.
실제 GET 시도마다 서킷에 집계하며 통신 오류·타임아웃·요청 취소·HTTP 5xx를 실패로 센다. 4xx와 응답 검증 오류는 집계에서 제외한다.
재시도 대상은 통신 오류·타임아웃·요청 취소·HTTP 502/503/504다. 정상 응답의 PENDING·SUCCESS·FAILED·빈 목록은 재시도하지 않는다.
OPEN이면 HTTP를 보내지 않는다. 대기 시간이 지난 뒤 실제 호출 1회를 HALF_OPEN에서 허용하고 그 결과로 회복 또는 재차단한다.

총 예산은 요청별 단조 시계로 계산하며 각 GET에는 기본 2초와 남은 예산 중 짧은 값을 적용한다.
대기 예산이 없거나 요청이 중단됐으면 추가 GET을 보내지 않는다. 5초는 DB 처리까지 포함한 API 전체 응답 시간 보장이 아니다.
조회 실패·차단에도 저장된 상태를 다시 읽어 반환하며 결제 결과나 재고 효과를 바꾸지 않는다.
서킷은 JVM별 메모리 상태다. 실제 응답 시간·오류율·호출량·동시 대기를 측정한 뒤 값을 조정해야 한다.

설정 근거: [CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker), [Retry](https://resilience4j.readme.io/docs/retry).

## 검증 위치

- `OrderPaymentDispatchIntegrationTest`: 실제 DB 동시 발송 권한·키 충돌·중복 요청·효과 0건 거절.
- `OrderPaymentIntegrationTest`, `OrderPaymentFailureIntegrationTest`: PG 조회 결과 적용, 내부 저장 실패와 재조회, 재고 효과.
- `PaymentCallbackIntegrationTest`: 실제 controller의 권위 조회, 콜백/조회 순서·동시성, 늦은 접수 응답, 중복 반환.
- `OrderPaymentRestartIntegrationTest`: 같은 DB·PG를 유지하며 결제 서비스·TX 프록시·HTTP 클라이언트·회복 도구를 재생성한다. 전체 OS 프로세스를 죽이는 실험은 아니다.
- `OrderApiE2ETest`, `OrderPaymentApiIntegrationTest`: 신규 카드 결제 API·소유권·입력·202/200·과거 POINT 기록.
- `HttpPaymentGatewayTest`, `LocalPgServerTest`: 실제 HTTP 형식과 엄격한 응답 해석·PG 비멱등성·동시 처리.

로컬 PG는 테스트 JVM 안에서만 띄우는 fixture다. 별도 PG 제품·공개 서버·실제 카드·외부 계정은 사용하지 않는다.

## 최종 검증 결과

2026-10-23, JDK 21에서 `./gradlew :apps:commerce-api:check`를 실행했다. 테스트 469개, 실패·오류·건너뜀 0개이며 Checkstyle·ArchUnit도 통과했다. 전체 검사 시간은 1분 37초였다.
