# W5 3단계 · 로컬 테스트 PG와 실제 HTTP TDD 기록

후속 선택·정정(2026-10-23): 요청 동일성 판별에 HMAC-SHA256을 기술안으로 선택했으며 코드·DDL·검증은 아직 미완료다. 과제 해석 질문은 필요 시 선택적으로 확인하며 담당자 확인·승인은 구현의 필수 전제가 아니다. 아래의 ‘제안·미채택·허용 확인 전’ 표현은 당시 판단 기록으로 보존하며 [현재 선택과 정정](../week2/commerce-policy-decisions.md#w5-hmac-요청-지문-제안)을 따른다.

2026-10-23. [과제 원문 2·6절](assignment-original.md) · [전체 설계](../week2/commerce-erd-draft.md#w5-pg-결제-설계) · [TDD 계획](../week2/commerce-tdd-plan.md#w5-pg-결제-tdd-계획)

후속: [PG HTTP·주문·재고 연결 기록](order-payment-http-connection-tdd-log.md)에서 내부 TX A → 실제 HTTP → TX B와 단발 조회를 연결했다. 아래는 독립 HTTP 증분 당시의 범위·실행 결과를 보존한 기록이다.

## 이번에 구현한 것

**과제에서 정의한 테스트 PG 계약을 실제 HTTP로 호출하는 경계**를 구현했다. HMAC은 제안으로 유지한다. 주문·예약·결제 시도의 선행 저장 기반은 [2단계 기록](order-payment-persistence-tdd-log.md)에 있고, 이번에는 그 데이터나 주문 서비스를 바꾸지 않았다.

| 질문 | 이번 구현의 의미 |
| --- | --- |
| 어떤 PG인가? | 토스·카드사 API나 업체 sandbox가 아니다. 과제의 세 HTTP endpoint를 직접 구현한 작은 `LocalTestPg`다. 실제 카드·계정·금전 이동은 없다. |
| 외부 API를 사용하는 것인가? | HTTP 경계라는 의미에서는 맞다. `HttpTestPgClient`가 실제 소켓으로 요청한다. 다만 상대 서버를 테스트 JVM 안에 `127.0.0.1:임시 포트`로 띄운다. 별도 업체·프로세스에 연결했다는 뜻은 아니다. |
| 테스트 서버는 무엇으로 만들었나? | JDK 21의 `HttpServer`. Commerce DB와 별개인 메모리 거래 저장소를 갖고 호출 수·거래 수·결과를 통제한다. 별도 PG 서비스 제품이나 관리 화면을 만들지 않는다. |
| 우리 서버는 무엇으로 호출하나? | Apache HttpClient **5.4.2**의 동기 HTTP adapter. 기존 Spring Boot 의존성 관리 버전을 사용한다. JSON은 기존 Jackson을 사용한다. |
| 이제 주문 결제가 PG로 바뀌었나? | 아니다. 기존 `OrderService.confirm()`은 아직 이전 POINT 경로다. 이번 단계는 HTTP adapter와 테스트 장치이며 TX A/B·주문 API 연결은 다음 단계다. |

테스트 실행의 순서는 **테스트 → 실제 HTTP client → 로컬 PG 서버 → 거래 저장·HTTP 응답 → client의 관찰값**이다. 메서드 mock으로 HTTP 성공을 가정하지 않는다. 클라이언트를 닫고 새로 만들어도 같은 fixture가 가진 거래는 남는다. fixture 자체 재시작 시 거래 보존이나 실제 DB rollback과 PG의 독립성까지 검증한 것은 아니다.

## 책임과 파일

| 책임 | 구현 |
| --- | --- |
| HTTP 구현을 숨기는 호출 약속 | [PaymentGateway](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentGateway.java) |
| 일시적인 합성 카드 입력 | [PaymentCreateRequest](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentCreateRequest.java). 카드 원문은 요청을 보내는 동안만 사용하고 `toString()`은 가린다. Entity/DDL·요청 지문은 추가하지 않는다. |
| 외부 관찰과 통신 실패 구분 | [PaymentGatewayResult](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentGatewayResult.java). 주문·재고를 직접 변경하지 않는다. |
| 세 HTTP 요청·엄격한 응답 판별·자원 종료 | [HttpTestPgClient](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/HttpTestPgClient.java) |
| 설정·생명주기 | [TestPgConfiguration](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/TestPgConfiguration.java)·[TestPgProperties](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/TestPgProperties.java)·[PgHttpTimeouts](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/PgHttpTimeouts.java) |
| 실제 로컬 HTTP 상대 | [LocalTestPg](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/support/LocalTestPg.java). 테스트 소스에만 존재한다. |

`infrastructure → domain` 의존으로 연결한다. application·controller에는 아직 PG 호출을 추가하지 않았고 기존 트랜잭션·잠금·포인트·Redis 정책은 변경하지 않았다.

## HTTP 계약과 관찰 결과

세 요청 모두 합성 계정 `X-USER-ID: 135135`를 사용한다. 내부 구매자 ID와 혼동하지 않는다.

| 호출 | 입력·정상 응답 | adapter가 지키는 경계 |
| --- | --- | --- |
| `POST /api/v1/payments` | 문자열 `orderId, cardType, cardNo, callbackUrl`, 정수 `amount`. HTTP 202 거래 객체 | 생성 수락 PENDING을 결제 SUCCESS로 바꾸지 않는다. 호출 한 번에 HTTP POST 한 번이며 자동 retry·redirect·인증 재발송을 사용하지 않는다. |
| `GET /api/v1/payments/{transactionKey}` | HTTP 200 거래 객체, 없는 거래 404 | URI builder로 키를 하나의 경로 값으로 인코딩한다. 조회한 키와 반환 키가 다르면 CONFLICT. 404는 NOT_FOUND이지 확정 실패가 아니다. |
| `GET /api/v1/payments?orderId=...` | HTTP 200 거래 배열 | 주문 ID를 query 값으로 인코딩한다. 0건은 NOT_FOUND, 1건은 검증된 관찰, 복수 건은 목록을 보존한 CONFLICT. 이 목록 endpoint의 404는 계약 밖 HTTP_STATUS다. |

| 실제 관찰 | 반환값 | 결제 의미 |
| --- | --- | --- |
| 정상 PENDING·SUCCESS·FAILED 객체 | OBSERVED + 거래 | 상태는 그대로 전달. 조회 결과의 계정·주문·금액·거래와 저장 시도의 최종 대조·적용은 후속 서비스/기존 모델 책임이다. |
| 생성 HTTP 422 + 정확한 `TEST_PG_REJECTED_BEFORE_CREATE` + boolean false + 요청 주문·금액 일치 | REJECTED_NO_EFFECT, 거래 목록 없음 | fixture가 거래 생성 전 종료를 보장하는 경우만 인정. 가짜 transactionKey를 만들지 않는다. |
| 형식이 유효하지만 요청 식별·생성 금액 불일치 또는 복수 거래 | CONFLICT + 관찰 거래 | 임의 첫 거래를 선택하거나 내부 결과를 덮어쓰지 않는다. 통신 장애와 구분한다. |
| 일반 4xx/5xx·예상 밖 상태 | UNKNOWN / HTTP_STATUS | 확정 결제 FAILED로 바꾸지 않는다. |
| 불완전한 422 증거·잘못된 JSON·타입·금액 범위·상태 | UNKNOWN / INVALID_RESPONSE | 문자열 `"false"`, 문자열/소수 금액, 중복 JSON 키, 두 JSON 문서 연결도 거절한다. |
| 응답 유실·연결 오류 / 실제 대기 한도 초과 | UNKNOWN / NETWORK 또는 TIMEOUT | 거래 존재·실패 여부를 추측하지 않는다. 같은 호출 안에서 POST를 다시 보내지 않는다. |

fixture는 같은 주문 ID의 두 POST를 중복 제거하지 않아 실제 거래 두 건이 생긴다. **adapter의 자동 재시도 없음**과 **application의 동시 발송 1회**는 별개다. 후자는 아직 TX A 선점·유일 제약과 함께 구현·검증해야 한다.

## 통신 도구와 설정을 선택한 이유

Apache의 pool·connect·response 제한과 요청 취소를 직접 연결하기 위해 동기 client를 사용했다. RestClient를 한 겹 더 두거나 Feign/WebClient·회복 라이브러리를 함께 도입하는 대안은 이번 작은 HTTP 경계에 추가하지 않았다. 라이브러리의 자동 재실행 차단 API는 [Apache 5.4.2 소스](https://github.com/apache/httpcomponents-client/blob/rel/v5.4.2/httpclient5/src/main/java/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.java)를 확인했다. 테스트 서버의 주소·handler·executor 연결은 [JDK HttpServer 문서](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html)를 따른다.

| 설정·보호 | 선택·검증 |
| --- | --- |
| 연결 / pool 획득 | 승인값 3초 / 1초. HTTP pool 대기는 실제 경합으로 검증했다. 네트워크 패킷을 버리는 별도 connect timeout 실험은 하지 않았으므로 3초 실측 검증이라고 주장하지 않는다. |
| 생성 / 조회 응답 대기 | 승인값 60초 / 5초를 실제 하위 client에 설정한다. 시험은 150ms로 축소하여 응답 지연을 재현했다. |
| 생성 / 조회 전체 호출 | 승인값 65초 / 10초. deadline worker가 실제 request를 취소한다. 대기 중인 Future만 포기하는 방식이 아니다. 헤더 전·본문 중 중단, 연결 반환과 후속 조회를 검증했다. |
| 연결 수 | client당 최대 20개. 무제한 연결을 피하기 위한 초기 기술 제한이며 처리량 실측 최적값은 아니다. 테스트에서는 1개로 제한해 pool 대기·반환을 확인했다. |
| 응답 크기 | 최대 64KiB. 한 주문의 작은 거래 응답에 대한 방어 한도다. 초과하면 연결을 중단하고 UNKNOWN으로 보존하며 임의 일부 거래를 채택하지 않는다. |
| URL | 설정의 `http://127.0.0.1:포트`만 허용. base의 경로/쿼리/fragment·userinfo를 막고 callback 경로는 `/api/v1/payments/callback`으로 제한한다. 고객 입력에 URL이 없다. |
| 카드·로그 | Entity/DB에 카드 번호 없음. 직렬화 byte 배열은 호출 후 비우고 결과·진단 객체에 원문/파싱 예외를 넣지 않는다. application 설정에서 Apache wire logger를 OFF로 둔다. 모든 임의 운영 로깅 변경에도 안전하다는 보증이나 실제 카드 보안 인증 주장은 아니다. |
| 정리 | 요청 타이머 취소·취소된 timer queue 제거·client 종료 시 연결/worker 정리. fixture는 보류 최대 5초, 종료 시 latch 해제·서버 중지·worker 종료를 수행한다. |

설정 위치는 [application.yml](../../apps/commerce-api/src/main/resources/application.yml)의 `commerce.test-pg`다. 기본은 **enabled=false**, base `http://127.0.0.1:9090`, callback `http://127.0.0.1:8080/api/v1/payments/callback`이다. 이는 통제된 endpoint 기본값이며 **9090에 서버가 자동으로 뜨거나 callback controller가 구현됐다는 뜻이 아니다.** 테스트는 fixture 임시 포트를 직접 주입한다. 현재 일반 앱에서 flag를 켜더라도 기존 주문 서비스가 자동으로 PG를 호출하지 않는다.

최초 confirm 전체 180초/외부 175초 예산, 최대 12회·조회 간격·jitter·Circuit Breaker는 이번 개별 HTTP 제한과 다르며 아직 미구현이다. 연결/응답 timeout은 외부 거래를 취소하는 기능이 아니다.

## TDD 실행 기록

| 순서 | 실제 결과 |
| --- | --- |
| fixture 첫 Red | 501 skeleton에서 17개 모두 실패. 앞선 테스트 메서드 오타의 컴파일 실패는 Red로 세지 않았다. |
| fixture 첫 Green | 17개 통과. 실제 생성·조회·거절·응답 유실·계정·카드 없는 캡처 동작을 구현했다. |
| HTTP 계약 Red | 호출 메서드가 미구현인 adapter에서 80개 모두 실패했다. |
| 조회 응답 보류 fixture 증분 | 9개 중 새 보류 시험 1개 실패 → 구현 후 fixture 19개 통과. 공백 JSON은 추가 회귀였고 인위적인 Red로 세지 않았다. |
| adapter/설정 첫 통합 실행 | 115개 중 15개 실패. 설정 bean 미연결 2개와 응답 정리 때 확정된 HTTP 분류가 NETWORK로 덮이는 사례 13개를 확인했다. 기대값은 유지하고 폐기한 연결의 body를 다시 drain하지 않도록 수정했다. |
| 본문 중 보류 추가 Red | 118개 중 새 fixture/본문 deadline 시험 2개만 실패 → 부분 body 전송·latch 보류를 구현했다. |
| 해당 범위 최종 Green | 아래 4개 스위트 **120개 통과, 실패·오류·skip 0**. Checkstyle main/test 위반 0, BUILD SUCCESSFUL/종료 0. 2026-10-23 11:34 KST. |

| 테스트 | 수 | 주로 확인한 것 |
| --- | ---: | --- |
| [LocalTestPgTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/support/LocalTestPgTest.java) | 20 | PG 자체 계약·거래/호출 수·카드 없는 기록·응답 보류·유한 정리 |
| [PaymentProviderContractTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentProviderContractTest.java) | 81 | 실제 method/path/계정/JSON 타입·금액, strict 422, 잘못된 응답, URI, no retry/redirect, 거래 보존·클라이언트 재생성 |
| [PgHttpTimeoutTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgHttpTimeoutTest.java) | 14 | 기본값, 실제 응답·pool·전체 제한, 본문 수신 중 취소·연결 반환, 외부 URL 거절 |
| [TestPgConfigurationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/TestPgConfigurationTest.java) | 5 | 기본 OFF, 명시 설정·시작 시 호출 0·종료, 외부 URL/무제한 timeout 거절, wire OFF 설정 |

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.infrastructure.payment.PaymentProviderContractTest' \
  --tests 'com.loopers.infrastructure.payment.PgHttpTimeoutTest' \
  --tests 'com.loopers.infrastructure.payment.support.LocalTestPgTest' \
  --tests 'com.loopers.infrastructure.payment.TestPgConfigurationTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

### 전체 회귀

`./gradlew :apps:commerce-api:check --rerun-tasks --console=plain`을 **2026-10-23 11:42 KST** 완료했다. 7분 21초, 39개 task 실행, BUILD SUCCESSFUL·종료 코드 0이다.

| 검사 | 실제 결과 |
| --- | --- |
| 전체 테스트 XML | **92개 스위트·1,112개 테스트**, 실패·오류·skip 모두 0 |
| 이번 HTTP 증분 | 위 4개 스위트·120개 포함 |
| ArchUnit | 1개 통과 |
| 전체 Java 모듈 Checkstyle | XML 보고서 11개·위반 0 |
| diff·문서 연결 | `git diff --check` 통과, 갱신한 문서 7개의 로컬 링크 대상 존재 확인 |

기존 POINT 주문·포인트 충전·재고·삭제·캐시·스키마 회귀를 포함했다. 기본 `check`에서 분리한 장시간 `ttlMeasurement`는 재실행하지 않았다. 이전 992개 통과 기록은 [이전 증분](order-payment-persistence-tdd-log.md)의 결과로 보존한다. 전체 검사 통과는 아직 연결하지 않은 PG 주문 흐름 전체의 완료를 뜻하지 않는다.

## 다음 단계와 남은 판단

1. **4단계:** 실제 재고 확보·반환과 TX A/B 서비스를 연결한다. TX A 커밋 성공 실행만 외부 POST, PG HTTP 동안 DB TX·잠금 미보유, SUCCESS의 추가 차감 0·확정 실패의 반환 1·포인트 차감 0을 검증한다.
2. **연결 전에 결정:** 요청 동일성 판별은 HMAC 제안과 별개로 아직 미정이다. 신규 Commerce DTO·업무 오류·callback ACK도 남아 있으며 이번 adapter 구현으로 임의 확정하지 않았다.
3. **5단계:** callback 권위 조회·수동 reconcile·종결 결과 보존·내부 저장 실패 복구, 제한 조회와 Circuit Breaker를 연결한다. 이번 fixture의 callbackUrl 캡처는 callback 발송·수신 구현의 증거가 아니다.
4. **6단계:** 최종 업무 연결 후 실제 SQL 롤백·동시 발송/결과 적용·과거 POINT/삭제/관리자 재고/캐시 회귀와 제출 증거를 완성한다.

이번 단계에서 사용자 DB·볼륨 삭제/초기화·수동 스키마 적용·커밋·PR 제출은 하지 않았다. HTTP 테스트에서 거래 0건/1건을 확인했더라도 아직 연결하지 않은 예약 반환/유지까지 통과했다고 표시하지 않는다.
