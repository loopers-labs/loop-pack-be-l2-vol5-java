# W5 HMAC 요청 지문 계산·비교 TDD 실행 기록

[정책·canonical v1](../week2/commerce-policy-decisions.md#w5-hmac-요청-지문-선택) · [전체 TDD 계획](../week2/commerce-tdd-plan.md#hmac-요청-지문의-검증-계획) · [현재 완료 범위](../week2/commerce-completion-checklist.md)

## 결과와 범위

**2026-10-23 14:51:53 KST 확인: 신규 단위 54개, 관련 회귀·ArchUnit 포함 13개 스위트 343개 통과. 실패·오류·skip 0, Checkstyle main/test 위반 0.** 이 기록은 순수 계산·비교 첫 증분이며 전체 W5 검사나 고객 결제 재요청 처리의 완료가 아니다.

앞선 사용자 선택인 UUID 시도 식별 + HMAC-SHA256 내용 비교를 구현하기 위한 첫 단계다. 과제 담당자 확인은 선택적 보완 사항이며 필수 구현 게이트로 되돌리지 않는다. 기존 [PG HTTP·주문·재고 연결 기록](order-payment-http-connection-tdd-log.md)의 결과와 미완료 설명은 당시 증거로 보존한다.

| 이번에 구현한 것 | 아직 하지 않은 것 |
| --- | --- |
| 고정 canonical v1과 서버 HMAC-SHA256 계산, 전체 32바이트 비교 | PaymentAttempt 지문 필드·JPA·DDL·기존 행 이행 |
| 일시 입력·불변 지문 값·비교 포트·기술적 비교 불가 예외 | 키 공급 설정·Spring bean 연결·이전 키 버전 라우팅 |
| 동일/변경 입력·독립 정답값·방어복사·텍스트 비노출·동시 계산 테스트 | 고객 confirm 동일 요청 처리·HTTP 오류 계약·TX A 저장·발송 연결 |
| 관련 순수 주문/결제/재고/포인트·ArchUnit·Checkstyle 검사 | 실제 DB/PG·callback/reconcile·180초 예산/회로·전체 check 재실행 |

기존 서비스·모델·설정·SQL·테스트 기대값은 변경하지 않았다. 주문·재고 트랜잭션과 락, 포인트 결제, PG HTTP 흐름도 그대로다. DB나 Docker를 실행하거나 데이터를 삭제·초기화하지 않았고 커밋/PR도 만들지 않았다.

## 책임과 파일

| 위치 | 책임 |
| --- | --- |
| [PaymentFingerprintInput](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentFingerprintInput.java) | 검증된 내부 사용자 ID·주문 ID·저장 금액과 일시적인 합성 카드 입력. 원문을 포함하지 않는 toString |
| [PaymentRequestFingerprint](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentRequestFingerprint.java) | 지문 32바이트·형식 버전·키 버전을 보유. 생성자/반환 배열을 복사하며 텍스트 출력에서 값을 숨김. JPA 엔티티가 아님 |
| [PaymentRequestFingerprinter](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentRequestFingerprinter.java) | 계산·비교의 포트. 발송 권한·DB 유일성·결제 결과 적용 책임은 없음 |
| [PaymentFingerprintUnavailableException](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentFingerprintUnavailableException.java) | 키/버전 등으로 비교 불가능한 기술 상태. 내용 불일치 false나 PG 확정 실패와 구분. HTTP 매핑 미정 |
| [HmacPaymentRequestFingerprinter](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/HmacPaymentRequestFingerprinter.java) | 버전 고정 직렬화·Java Mac·32바이트 비교. 외부에서 받은 한 키를 사용하며 호출마다 Mac을 생성. Spring/DB/PG 의존 없음 |

키는 계산기 구성 시 32바이트 이상을 요구한다. 공개 RFC 벡터의 짧은 키는 알고리즘 함수 자체의 검증에만 사용하며 계산기 구성 조건을 완화하지 않는다. 테스트 키는 공개된 합성 값으로 운영용 비밀키가 아니다. 설정 연결 때는 안정된 키 공급과 버전 수명을 별도로 설계해야 한다.

## TDD 순서와 관찰

| 단계 | 실제 결과 |
| --- | --- |
| 최초 실행 환경 | sandbox가 기존 Gradle wrapper 캐시의 잠금 파일 접근을 거절. 이는 기능 Red나 테스트 실패 증거로 세지 않고 권한 승인 경로로 다시 실행 |
| Red 1 · 테스트 먼저 | 새 클래스가 없는 상태에서 compileTestJava 실패, cannot find symbol 30건. 테스트 본문은 미실행 |
| Red 2 · 행동 실패 확인 | 입력·지문 값과 미구현 계산부 뼈대를 둔 뒤 **50개 실행, 27개 실패·23개 통과**. 미구현 계산/비교, 빈 canonical 바이트, 0으로만 된 가짜 HMAC이 실제 기대값을 만족하지 못함 |
| Green | 고정 직렬화·실제 HMAC·버전 검사·전체 비교 구현 뒤 같은 **50개 모두 통과**. 기존 기대값 삭제/완화 없음 |
| 보강·Refactor | 공개 calculate 전체 결과의 독립 OpenSSL 기대값 1개, 지문 첫/중간/끝 바이트 비교 3개 추가. calculate/matches의 공통 계산과 임시 배열 정리를 private digest 경로로 추출 |
| 최종 관련 검증 | **신규 54개 + 관련 회귀·ArchUnit 289개 = 343개**, 13개 스위트 통과. main/test Checkstyle 보고서 2개 위반 0. BUILD SUCCESSFUL, 종료 0 |

단위 증분이므로 DB를 사용하는 통합 테스트나 전체 check를 실행했다고 주장하지 않는다. 기존 테스트를 비활성화하거나 테스트 태그·검사 규칙을 바꾸지 않았다. 같은 계산기의 동시 실행 검사는 고정 worker 4개·작업 64개로 수행하고 최대 대기와 종료 대기를 각각 5초로 제한해 자원을 정리했다. 이것은 DB 경합 검증이 아니다.

## 검증한 반례

| 테스트 | 반례·기대값 |
| --- | --- |
| 같은 요청 | 같은 키/입력을 반복하거나 계산기 인스턴스를 새로 만들어도 지문·버전이 동일. 결과 길이 32바이트 |
| 달라진 요청 | 사용자·주문·저장 금액·카드 종류·번호를 하나씩 바꾸면 선택한 사례에서 불일치. 기존 지문은 바뀌지 않음 |
| 임의 정규화 금지 | 카드의 하이픈 제거·앞뒤 공백 추가·종류 대소문자 변경을 같은 입력으로 합치지 않음. 새 고객 카드 허용 정책을 정한 것은 아님 |
| JSON 표현 | ObjectMapper로 필드 순서/공백이 다른 JSON을 파싱해 같은 입력 값으로 전달하면 동일. 실제 controller 테스트나 고객 DTO 구현은 아님 |
| 경계 충돌 방지 | 사용자 1/주문 23과 사용자 12/주문 3, 문자열 경계 이동·NUL 포함 입력을 구분. 고정 바이트 수/길이 prefix로 경계를 보존 |
| UTF-8 | 다중 바이트 한글·보조 문자 인코딩의 고정 바이트 기대값 확인. 깨진 surrogate를 대체 문자로 변환하지 않고 값 없는 오류로 거절 |
| 독립 정답 | RFC 4231 사례 1·2·6의 HMAC-SHA256 정답 검증. 별도로 Ruby OpenSSL로 계산한 canonical v1의 최종 정답으로 공개 calculate 경로까지 확인 |
| 잘린 비교 금지 | 저장 지문의 0·15·31번째 바이트만 바꾸어도 불일치. 끝부분을 무시하는 비교 방지 |
| 숫자 경계 | 기존 모델과 같은 양의 long 사용자/주문 ID, 저장 금액 0~Long.MAX_VALUE 표현을 유지. 이 계산기는 주문 금액 검증을 대체하지 않음 |
| 외부 배열 변경 | 생성자 키 배열, 생성자/반환 지문 배열을 바꿔도 내부 값 보존 |
| 비교 불가 | 키 누락·짧은 키·잘못된 버전, 저장 지문 누락·지원하지 않는 형식/키 버전은 기술 예외. 내용 불일치 false로 숨기지 않음 |
| 비노출 | 입력·지문·계산기 toString을 가리고 오류 메시지에 입력을 넣지 않음. 파라미터 테스트명은 값 대신 사례 번호, 민감 배열 비교 실패도 값 대신 boolean으로 관찰 |

[PaymentRequestFingerprintTest](../../apps/commerce-api/src/test/java/com/loopers/domain/payment/PaymentRequestFingerprintTest.java) 18개, [HmacPaymentRequestFingerprinterTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/HmacPaymentRequestFingerprinterTest.java) 36개다. HMAC이 모든 가능한 입력에서 절대로 충돌하지 않는다고 증명한 것은 아니며, 표의 선택한 반례와 구현 경계를 검증했다.

## 실행 명령

Red/Green 계산 증분:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*PaymentRequestFingerprintTest' \
  --tests '*HmacPaymentRequestFingerprinterTest' --console=plain
```

Refactor 후 관련 회귀·계층·코드 스타일:

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.payment.*' \
  --tests '*HmacPaymentRequestFingerprinterTest' \
  --tests 'com.loopers.domain.order.*' \
  --tests 'com.loopers.domain.product.*' \
  --tests 'com.loopers.domain.user.*' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

테스트 결과 XML과 main/test Checkstyle XML의 실제 건수를 확인했다. 테스트 보고서는 [HTML 결과](../../apps/commerce-api/build/reports/tests/test/index.html)이며 다음 테스트 실행 시 덮어써질 수 있다.

## 다음 증분과 남은 확인

1. PaymentAttempt에 지문·두 버전의 저장 구조를 연결한다. 기존 PG 행 유무는 보존 전제로 읽기 전용 확인하고, 지문이 없는 과거 행의 처리 정책을 먼저 확인한다. 새 입력으로 과거 지문을 만들어 덮어쓰지 않는다.
2. 키 공급 설정·버전 선택을 연결한다. 같은 버전은 재시작·여러 서버에서도 같은 키여야 하며 현재 단일 키 계산기만으로 운영 키 교체를 구현했다고 보지 않는다.
3. TX A와 고객 confirm에 동일 요청 재사용·변경 입력 충돌을 연결하고 실제 MySQL 경합·rollback·PG POST 수로 검증한다. 기존 트랜잭션·락 순서·POINT 기록 보존은 유지한다.

이번 통과는 W5-HMAC-01의 계산 및 05·06의 순수 하위 범위다. DB/HTTP/SQL bind·trace 전체 비노출, 실제 재시작·키 교체, 재요청 발송/예약 효과 1회는 아직 증명하지 않았다. HMAC이 DB 유일 제약·발송 전이·TX B의 결과 보호를 대신하지 않는다.

## 근거

- [Java 21 Mac](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html): 표준 HmacSHA256 구현 사용. 암호 알고리즘을 직접 구현하지 않음.
- [RFC 4231](https://www.rfc-editor.org/rfc/rfc4231.html): HMAC-SHA256의 공개 정답 벡터.
- canonical v1은 이 프로젝트의 선택이며 위 문서가 과제 담당자의 공식 승인이나 실제 카드 보안 인증을 의미하지 않는다.
