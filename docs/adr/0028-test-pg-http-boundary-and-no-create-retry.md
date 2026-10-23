# ADR 0028: 테스트 PG 호출은 adapter로 격리하고 생성을 자동 재시도하지 않는다

Status: Accepted
Date: 2026-10-18

## Context

5주차 로컬 PG는 호출자의 멱등 키를 받거나 같은 주문의 새 POST를 중복 제거하지 않는다. 거래 생성 뒤 응답만 유실될 수 있으므로 HTTP 오류를 결제 실패로 확정하거나 create를 자동 재시도하면 중복 거래를 만들 수 있다. 기존 레이어드 구조 안에서 이 외부 계약을 격리한다.

## Decision

- `domain/payment`의 `PaymentProvider`가 생성·거래 조회·주문 조회 경계를 정의하고 `infrastructure/payment`의 `TestPgPaymentAdapter`가 구현한다. domain에는 HTTP·Jackson 의존성을 두지 않는다.
- `RestClient`와 명시적인 `HttpComponentsClientHttpRequestFactory`를 사용한다. Boot 의존성 관리의 Apache HttpClient를 추가하고 자동 재시도·redirect를 끈다. 기본 연결/풀 대기는 300ms, 응답 대기는 1s이며 설정으로 지정한다.
- 정상 생성 수락 PENDING과 결과 UNKNOWN을 구분한다. 응답 유실·timeout은 외부 거래 취소나 효과 0건의 증거로 취급하지 않는다. transactionKey를 모르면 같은 providerOrderId로 조회한다.
- 합성 카드 입력만 사용하고 영속화하지 않는다. URL은 서버 설정에서 가져오고 카드 번호는 객체 문자열에도 남기지 않는다.
- 실제 HTTP 검증에는 JDK `HttpServer` fixture를 쓴다. 거래 상태·호출 수·응답 유실 시점을 직접 통제하고 테스트마다 서버·상태를 새로 만든다.

## Alternatives and costs

다른 HTTP client나 WireMock도 가능하다. 이번에는 자동 재시도 설정을 명시하고 실제 POST 횟수로 검증하기 위해 HttpClient를 선택했다. fixture는 추가 서버 라이브러리 없이 상태·응답 순서를 통제하는 대신 HTTP 처리와 종료를 직접 관리한다. 두 선택의 성능 우위를 주장하지 않는다.

재발송하지 않는 정책은 중복 결제를 줄이지만 모든 시도의 자동 완료를 보장하지 않는다. 발송 기록 뒤 실제 전송 전 중단되는 문제와 조회 0건의 판단은 이후 DB 연결·재시작 단계에서도 남는다. 현재 adapter만으로 주문당 한 시도나 결과 한 번 적용이 보장되지는 않는다.

TX A 커밋 뒤 POST 전 종료와 POST 뒤 결과 저장 전 종료는 모두 REQUESTED로 남을 수 있다. transactionKey가 없으면 저장한 providerOrderId로 기존 거래를 조회하며, 조회 0건이어도 자동 재발송·예약 반환하지 않는다. 수동 reconcile이 기본 복구 경로이고, 10분이 지난 미완료 시도는 배치가 같은 방식으로 조회한다([ADR 0033](0033-payment-recovery-batch-for-unfinished-attempts.md)). 이번 호출의 확실한 미발송이 기존 시도 전체의 효과 0건을 뜻하지는 않는다. 이후 [ADR 0034](0034-close-only-proven-unsent-payments-and-keep-unresolved-pending.md)에서, 생성이 연결 단계에서 끝나 보내지 못한 것이 확실하면 바로 실패로 닫고, 30분이 지나도 PG에 거래가 없는 시도는 배치가 닫기로 했다. 재발송하지 않는다는 이 결정은 그대로다.

망취소는 미확인 거래를 취소 요청하는 대안이지만 현재 로컬 PG에는 취소 계약이 없다. 취소 응답도 유실될 수 있으므로 그 자체가 확실한 실패·원자적 rollback을 보장하지 않는다. 이번 범위에는 추가하지 않는다.

## 적용 범위와 검증

이번 결정은 [week5 설계](../week5/design.md)의 첫 HTTP 연동 단계다. 확정 거절 분류·예약·결과 적용·조회 회복 도구는 후속 범위다. 실제 계약 테스트·Checkstyle·ArchUnit 결과와 재현 명령은 [구현 기록](../week5/implementation-log.md)에 남긴다.

검증 과정에서 생성 응답 불일치의 근거가 UNKNOWN·빈 목록으로 소실되는 점을 확인했다. 통신 계약 확인과 저장된 주문·시도의 업무 대조를 나누고 관찰 근거를 보존하는 보완 방향은 [week5 설계](../week5/design.md)에 둔다. 이후 adapter는 생성 202의 PENDING 거래를 주문 번호·금액 대조 없이 OBSERVED로 넘기고, 대조와 근거 기록은 결과 적용(TX B)이 맡도록 바꿨다([ADR 0030](0030-pg-payment-split-transactions-and-apply-once.md)). PENDING 이외 상태와 형식 오류는 그대로 UNKNOWN이다. 이 ADR의 자동 재시도 금지 원칙은 유지한다. 조회에는 이후 제한된 retry·회로를 적용했다. adapter는 UNKNOWN의 이유(일시적 장애·예상 밖 응답)를 고르고, 조회 응답 timeout을 생성(1s)보다 짧은 500ms로 나눴다. 생성 POST는 여전히 감싸지 않는다([ADR 0031](0031-pg-lookup-retry-circuit-breaker-and-fallback.md)). 기본 timeout도 운영 튜닝값으로 검증한 수치가 아니다.

설정 의미 참고: [Spring 6.2.5 request factory](https://docs.spring.io/spring-framework/docs/6.2.5/javadoc-api/org/springframework/http/client/HttpComponentsClientHttpRequestFactory.html), [Apache HttpClient builder](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.html). 실제 호출 수는 프로젝트가 사용하는 5.4.2로 검증한다.
