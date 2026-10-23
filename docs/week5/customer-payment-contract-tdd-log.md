# W5 고객 결제 API 계약 경계 TDD 기록

[API 상세 계약](../week2/commerce-api-contract.md#고객-결제-api-상세-계약) · [선택 이유](../week2/commerce-policy-decisions.md#고객-결제-http-계약-선택--2026-10-23) · [TDD 계획](../week2/commerce-tdd-plan.md#고객-결제-http-계약의-tdd-증분) · [선행 내부 HMAC 연결](order-payment-fingerprint-connection-tdd-log.md)

## 사용자 선택과 이번 범위

2026-10-23 사용자가 세 추천안을 선택했다.

1. 요청은 paymentAttemptKey/cardType/cardNo, 응답은 기존 주문 필드 + paymentMethod/payment. 정상 상태 반환은 meta.SUCCESS, 실제 결제 결과는 payment.status.
2. 요청 불일치409 PAYMENT_REQUEST_CONFLICT, 기존 전체NULL 지문409 PAYMENT_REQUEST_UNVERIFIABLE, 서버키/지원형식 불가503 PAYMENT_VERIFICATION_UNAVAILABLE. 저장 손상은 기존500.
3. 신규 PG 주문 ID는 LP-ORD- + 최소10자리 주문 ID, 기존 시도는 저장된 PG ID 보존.

이번 증분은 **DB 없는 요청 파서·응답 DTO·오류 분류·PG ID 생성**이다. 고객 controller의 PG 전환이나 180초 최초 제한 조회를 구현한 것은 아니다. 사용자가 선택한 대기 정책을 즉시202로 대체하지 않았다. 사용자 DB·실제 키·기존 TX·락·SQL은 변경하지 않았다.

| 책임 | 구현·보호 |
| --- | --- |
| [PaymentConfirmRequest](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/order/PaymentConfirmRequest.java) | 정확한 세 문자열, UUID 정규 표현, nonblank 카드 입력, 원문 보존·redacted toString. 서비스 호출 없음 |
| [OrderPaymentResponse](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/order/OrderPaymentResponse.java) | 기존 OrderInfo 필드 유지·PG 다섯 상태·조회 경로·DRAFT/POINT 구분·불변 품목·일관된 상태/확정 사실. confirmHttpStatus는 PG용200/202 선택 함수이며 실제 응답/대기 실행 아님 |
| [ProviderOrderId](../../apps/commerce-api/src/main/java/com/loopers/application/order/ProviderOrderId.java) | 양수 내부 주문ID의 Locale.ROOT 십진수 최소10자리. 기존 시도는 소속 주문 검사 후 저장된 ID 그대로 반환. 실제 준비 서비스에는 아직 미연결 |
| [PaymentFingerprintUnavailableException](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentFingerprintUnavailableException.java)·[PaymentAttempt](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentAttempt.java) | 전체NULL·손상·서버 사용불가의 typed reason. 기존 상위 타입·생성자·메시지를 유지하며 저장 필드·상태 전이는 변경하지 않음 |
| [CommerceErrors](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/CommerceErrors.java) | 세 승인 오류와 손상500을 고정 code/message로 분류. 메시지 문자열 파싱 없음, 원문 예외·지문·키 비노출 |

## Red → Green과 결과

| 단계 | 실제 결과 |
| --- | --- |
| 실행 환경 | 첫 실행은 Gradle 캐시 lock 파일 접근 제한으로 테스트 전에 실패. 이를 Red나 테스트 결과로 세지 않았고 캐시 접근 허용 후 재실행 |
| Red 1 · 새 경계 없음 | DTO/ID 생성 API를 참조하는 테스트를 먼저 추가해 컴파일 실패. 총43개 오류에는 테스트의 package-private JacksonConfig 직접 접근 오류도 포함됨. 테스트 설정은 실제 JacksonConfig를 context로 불러오도록 수정 |
| Red 2 · 오류 의미 없음 | DTO/ID 최소 구현 후 신규126개 중 **11개 실패·115개 통과**. 요청 충돌·과거 지문 없음·키/형식 문제를 기존 일반500으로 반환하던 오류 분류가 기대와 다름 |
| Green | typed reason과 고정 오류 매핑 연결. 관련 **16개 스위트·522개 통과**, 실패·오류·skip 모두0. 신규126개 포함 |
| 검사 | ArchUnit1개 포함 통과. commerce-api Checkstyle main/test 보고서2개·위반0. BUILD SUCCESSFUL·8초·종료0. 결과 확인 2026-10-23 16:26:49 KST |

| 신규 테스트 | 개수 | 검증 |
| --- | ---: | --- |
| [PaymentConfirmRequestTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/PaymentConfirmRequestTest.java) | 53 | 필드/타입/누락/null/추가 입력/UUID 경계, 카드 정규화·한 번호 제한 없음, 디버그 문자열 비노출 |
| [OrderPaymentResponseTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderPaymentResponseTest.java) | 36 | 실제 공통 Jackson 설정, 기존 필드·null 생략, PG 상태별 JSON/HTTP 선택, POINT/DRAFT 표현, 상태·확정 사실 불일치 거절, 품목 복사 |
| [PaymentErrorContractTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/payment/PaymentErrorContractTest.java) | 25 | 동일한 예외 메시지도 사유별로 구분, 상태 보존, legacyNULL/손상/키·형식 지원/충돌, MockHttpServletResponse 직렬화·data 생략·비노출 |
| [ProviderOrderIdTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/ProviderOrderIdTest.java) | 12 | 최소10자리·Long 최대·반복 결과, 기존 다른 매핑 보존, 잘못된 ID·다른 주문의 시도 거절 |

522개에는 기존 주문/결제 도메인·HMAC 계산·버전 선택·키 설정 회귀가 포함된다. 순수 테스트와 Jackson 컨텍스트/응답 대역만 사용했고 DB나 실제 PG HTTP에는 연결하지 않았다. 기존 테스트·기대값을 삭제하거나 완화하지 않았다.

```bash
./gradlew :apps:commerce-api:test \
  --tests '*PaymentConfirmRequestTest' \
  --tests '*OrderPaymentResponseTest' \
  --tests '*PaymentErrorContractTest' \
  --tests '*ProviderOrderIdTest' \
  --tests 'com.loopers.domain.order.*' \
  --tests 'com.loopers.domain.payment.*' \
  --tests '*HmacPaymentRequestFingerprinterTest' \
  --tests '*VersionedPaymentRequestFingerprinterTest' \
  --tests '*PaymentFingerprintConfigurationTest' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

## 다음 연결 시 지킬 점

- 고객 confirm은 아직 기존 POINT 경로다. 새 DTO·오류 분류가 있다는 이유로 신규 PG API의 작동을 주장하지 않는다.
- PG 파서는 소유권/기존 POINT 분기를 고려해 연결한다. 기존 본문 없는 POINT 본인409·타인404가 PG 필수 입력 누락400으로 바뀌면 안 된다.
- 현재 내부 startOrReuse는 providerOrderId를 인자로 받는다. API 연결 시 잠금/소유권 확인 경계 안에서 기존 시도를 읽고 저장 ID를 사용해야 한다. 매 요청마다 새 형식으로 재생성하지 않는다.
- 주문/시도 조회를 일관된 snapshot으로 구성하고 고객/관리자 상세·목록을 확장한다. 목록 시도 조회 N+1을 만들지 않는다.
- 실제 controller의 확인된 결과/거절·DB 최종 상태·PG 호출수·포인트 변화0은 후속 통합 테스트가 필요하다. 이번 응답 대역 검증을 E2E나 실제 HTTP 호출 검증으로 세지 않는다.
- 최초 confirm의175/180초·조회12회·서킷브레이커, callback/reconcile 진입점과 후속 미정 계약은 남아 있다. 종결 FAILED200은 업무 결과 표현이며 PG 실패를 성공으로 처리하는 뜻이 아니다.
- 전체 check·사용자 DB 이행·실제 키 설정·커밋·PR 제출은 수행하지 않았다.
