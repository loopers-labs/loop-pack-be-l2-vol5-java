# W5 HMAC 지문·버전 저장 TDD 실행 기록

[선행 계산·비교 기록](payment-request-fingerprint-tdd-log.md) · [정책 선택](../week2/commerce-policy-decisions.md#w5-hmac-요청-지문-선택) · [스키마 적용 안내](../week2/commerce-schema-operations.md) · [완료 체크리스트](../week2/commerce-completion-checklist.md)

## 결과와 이번 범위

**2026-10-23 15:24:51 KST 결과 확인: 신규 52개를 포함한 관련 22개 스위트·554개 통과. 실패·오류·skip 0, ArchUnit 1개 통과, Checkstyle main/test 위반 0.** PaymentAttempt에 지문·형식 버전·키 버전을 저장하는 하위 범위를 구현했다. 사용자 승인대로 지문이 없는 기존 PG 기록은 세 컬럼 모두 NULL로 보존한다. 사용자 DB에 연결하거나 이행 SQL을 적용하지 않았으며, 별도 Testcontainers MySQL만 사용했다.

| 구현한 것 | 아직 연결하지 않은 것 |
| --- | --- |
| 지문을 받는 생성자, 방어 복사, 변경 메서드 없는 3개 JPA 필드 | 신규 confirm의 입력 → 지문 계산 → 서비스 TX A 저장 |
| 기존 전체 NULL의 명시적 비교 불가, 기존 결제 상태·거래 복구 유지 | 고객 재요청의 같은 내용 재사용·변경 내용 충돌과 HTTP 오류 |
| 011 추가 SQL·실제 MySQL 이행·저장·DB 제약·롤백·유일 제약 경합 | 운영 키 공급·Spring bean·키 버전 라우팅·실제 키 교체 |

**기존 5인자 생성자와 `OrderPaymentStockService.prepareNew`는 아직 무지문 경로다.** 이번에는 저장 기능을 추가했으며 모든 신규 PG 요청에 지문이 자동 저장된다는 뜻이 아니다. 기존 주문·재고 트랜잭션·락·PG HTTP 조율·POINT API는 변경하지 않았다. callback·180초 제한 조회·Circuit Breaker도 이번 범위가 아니다.

## 저장 규칙과 선택 이유

| 컬럼 | 표현 | 의미 |
| --- | --- | --- |
| `request_fingerprint` | nullable `VARBINARY(32)` | HMAC-SHA256 전체 32바이트 비교 자료. 카드 번호나 복원용 암호문이 아님 |
| `fingerprint_version` | nullable 양의 `INT` | 입력을 어떤 규칙으로 직렬화했는지 식별 |
| `key_version` | nullable 양의 `INT` | 계산에 사용한 비밀키의 버전 식별. 비밀키 자체는 저장하지 않음 |

- 세 값은 **전부 NULL**이거나 **전부 유효**해야 한다. 부분 NULL·32바이트가 아닌 지문·0 이하 버전을 DB CHECK가 거절한다. MySQL CHECK의 NULL 판정으로 부분 값이 통과하지 않도록 각 `IS NOT NULL`도 명시했다.
- `BINARY(32)`의 짧은 값 패딩을 피하려고 `VARBINARY(32)`와 `OCTET_LENGTH = 32`를 함께 사용한다. 33바이트는 컬럼 길이 제약에서 거절한다.
- 형식 버전과 키 버전은 서로 다른 의미이므로 같은 숫자일 필요가 없다. 양의 미래 버전을 저장할 수 있어도 현재 계산기가 이를 지원한다고 간주하지 않는다.
- 세 JPA 컬럼은 `updatable=false`이고 변경 메서드를 제공하지 않는다. 생성·반환 배열은 복사한다. 이는 모델/JPA의 변경 방지이며 관리자 직접 SQL까지 차단하는 DB 권한 정책은 아니다.
- 전체 NULL은 `Optional.empty()`로 읽고 비교를 요구하면 `PaymentFingerprintUnavailableException`을 낸다. 동일 요청·다른 요청·결제 FAILED 또는 UNKNOWN으로 단정하지 않는다. 이후 카드 입력으로 과거 지문을 채우는 경로는 없다.
- 카드 원문·요청 본문·비밀키 컬럼을 추가하지 않았다. 지문 자체도 외부 응답용 필드가 아니다. 전체 HTTP·SQL bind·trace 비노출 검증은 실제 요청 연결 단계의 별도 범위다.

## 변경 책임과 파일

| 파일 | 책임 |
| --- | --- |
| [PaymentAttempt](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentAttempt.java) | 불변 지문·버전의 생성/조회, 과거 전체 NULL과 손상된 비교 자료 구분. 기존 상태 전이 유지 |
| [011-payment-request-fingerprint.sql](../../apps/commerce-api/src/main/resources/db/schema/011-payment-request-fingerprint.sql) | 기존 `payment_attempt`에 컬럼·CHECK만 추가. 행 삭제·갱신·backfill 없음 |
| [PaymentAttemptFingerprintTest](../../apps/commerce-api/src/test/java/com/loopers/domain/payment/PaymentAttemptFingerprintTest.java) | 값 보존·방어 복사·모든 상태·legacy/손상 값의 비교 불가 |
| [PaymentAttemptFingerprintPersistenceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentAttemptFingerprintPersistenceIntegrationTest.java) | 수동 SQL 적용 후 JPA validate·실제 저장/재조회·복구·rollback·경합 |
| [W5PaymentSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/schema/W5PaymentSchemaIntegrationTest.java) | 기존 010 DB에서 011 이행, 과거 데이터 보존·DB 제약 반례 |
| [CommerceSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/schema/CommerceSchemaIntegrationTest.java) | 전체 수동 스키마 목록에 011 추가, 기존 검증 유지 |

001~010 SQL은 바꾸지 않았다. 010의 지문 미저장 설명은 그 단계의 사실이고, 후속 011이 저장 컬럼을 추가한다. 신규 설치는 001~011 순서, 010까지 적용한 DB에는 011만 한 번 적용하는 방식이다. **이번 작업에서 실제 사용자 DB 이행은 실행하지 않았다.**

## TDD 실행 순서

| 단계 | 관찰한 결과 |
| --- | --- |
| Red 1 · 테스트 먼저 | 새 생성자/조회 메서드가 없어 compileTestJava 오류 20건. 테스트 본문은 실행되지 않음 |
| Red 2 · 행동 실패 | 저장하지 않는 최소 뼈대로 모델 12개 실행, 6개 실패·6개 통과. 지문 전달 뒤 읽기와 5가지 상태의 지문 보존에서 실패 |
| Red 3 · 이행 부재 | 모델 저장 구현 뒤 모델 12개는 통과. 기존 PG 7행을 만든 실제 MySQL 이행 테스트는 011 파일이 없어 실패. 총 13개 중 1개 실패이며 DB 연결 실패와 구분 |
| Green · 저장/스키마 | 011 추가 후 모델 12·새 영속성 10·W5 스키마 55·전체 스키마 33, **4개 스위트 110개 통과**, 실패·오류·skip 0 |
| 보강 | 읽기 검토 후 손상된 모델 값 13개 사례 추가. 지문이 있는 5가지 상태도 재조회 직후 주문·예약 상태를 명시적으로 확인하도록 보강. 기존 기대값 삭제·완화 없음 |
| 최종 관련 검사 | 신규 52개를 포함한 **22개 스위트·554개 통과**, 실패·오류·skip 0. ArchUnit 포함, Checkstyle main/test 보고서 2개 위반 0. BUILD SUCCESSFUL·1분 38초·종료 0 |

초기 Green까지 새로 추가한 사례는 39개였고, 손상 값 13개 보강 후 **이번 신규 사례는 총 52개**다. 구성은 모델 25개 + 새 영속성 10개 + 기존 W5 스키마에 추가한 17개다. 기존 전체 스키마 테스트 33개는 회귀이며 신규 개수로 세지 않는다.

## 실제로 확인한 반례

| 범위 | 준비·실행·기대값 |
| --- | --- |
| 모든 상태와 값 보존 | NOT_OBSERVED/PENDING/UNKNOWN/SUCCESS/FAILED 각각 지문·두 버전 저장 후 다른 Session에서 재조회. 원래 상태·주문·예약 및 지문 유지, 같은 카드 입력 일치·변경 입력 불일치 |
| 기존 기록 이행 | 010까지 적용한 DB에 PG 상태 5종, 거래키 있는 UNKNOWN, 거래 없는 확정 거절 FAILED를 포함한 7개 기록과 POINT·DRAFT 준비. 011 적용 뒤 기존 모든 컬럼 스냅샷 동일·PG 7개 모두 세 컬럼 NULL·POINT 금액/당시 품목 보존 |
| 기존 거래 결과 복구 | NULL 지문인 UNKNOWN을 재조회하고 권위 SUCCESS 관찰을 모델에 적용·저장. 주문 CONFIRMED·거래 키 유지, 지문 세 값은 계속 NULL. 실제 PG HTTP 호출 증거는 아님 |
| 표현 제약 | 전체 NULL·정상 32바이트와 독립 양의 버전 허용. 부분 NULL 6조합·0/31바이트·각 버전 0/음수는 이름 지정 CHECK 오류 3819, 33바이트는 길이 오류 1406. 실패 뒤 기존 행과 정상 지문 보존 |
| 손상된 모델 값 | DB 제약과 별도로 Java 로딩 값의 부분 NULL 6종·0/31/33바이트·각 버전 0/음수 13종 주입. 비교 불가 예외만 발생하고 결제 상태·발송 시각 유지 |
| 실제 SQL 후 rollback | 시도·지문 INSERT와 주문/품목 변경을 flush하고 DB에서 존재·HELD 확인 후 예외 주입. 다른 MySQL 연결 ID로 재조회하면 시도 0, DRAFT·수단 null·NOT_RESERVED. 포인트·재고·목록 revision 원래 값 유지 |
| 저장 경합 | 서로 다른 Session/DB TX 두 개가 같은 주문·키의 같은 지문 또는 다른 지문을 동시에 INSERT. 커밋 1·유일 제약 1062 거절 1·행 1, 승자의 지문만 보존 |
| 미래 버전 | 형식 2·키 99를 저장/재조회할 수 있지만 현재 계산기로 비교하면 기술적 비교 불가. 새 형식 지원으로 오해하지 않음 |
| 이행 재실행 | 011 재적용 실패 후 기존 컬럼·정상 지문·NULL 행 보존. 자동 반복 실행하는 migrator는 추가하지 않음 |

롤백 테스트는 **저장 계층 트랜잭션** 검증이다. 실제 재고 수량/revision을 변경한 서비스 TX A에 지문을 연결한 것은 아니며, 같은 내용을 성공 재사용하는 고객 응답·다른 내용의 HTTP 충돌·PG POST 1회를 위 경합 테스트가 증명하지 않는다. 그 검증은 다음 연결 단계에서 한다.

## 격리와 데이터 보존

사용자는 기존 데이터 유무를 알 수 없다고 답했고 이번에는 별도 테스트 DB 사용을 승인했다. 앞선 읽기 전용 환경 확인에서 로컬 설정의 DB에 접속할 수 없었으므로 **기존 PG 기록이 0건이라고 판단하지 않는다.** 실제 데이터가 있을 수 있다고 보고 기존 행을 갖춘 fixture로 이행을 검증했다.

- 새 영속성 테스트는 자체 MySQLContainer의 `w5_fingerprint_storage_test`·동적 포트만 사용하고 application.yml을 로딩하지 않는다. 정리 전에 DB 이름도 확인한다.
- 스키마 테스트도 각각 자체 Testcontainers URL을 사용한다. 기존 Spring 통합 회귀는 `MySqlTestContainersConfig`의 별도 컨테이너 동적 URL을 사용한다.
- JPA가 테이블을 자동 생성해서 011을 우회하지 않도록 새 저장/스키마 테스트는 실제 SQL 적용 후 `hbm2ddl.auto=validate`로 매핑을 확인한다.
- 사용자 DB 실행·초기화·DDL·데이터 변경 및 기존 볼륨 삭제는 하지 않는다. 정리는 테스트가 소유한 컨테이너/연결/테이블에만 한정한다.
- SessionFactory/Session은 닫고, 경합 Future 최대 10초·시작 barrier 5초·executor 종료 5초로 제한한다. 테스트는 합성 카드·공개 시험용 키만 사용하며 지문 바이트 비교 실패는 boolean으로 관찰한다.

## 실행 명령과 최종 관련 결과

초기 저장/스키마 Green:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*PaymentAttemptFingerprintTest' \
  --tests '*PaymentAttemptFingerprintPersistenceIntegrationTest' \
  --tests '*W5PaymentSchemaIntegrationTest' \
  --tests '*CommerceSchemaIntegrationTest' --console=plain
```

최종 관련 회귀:

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.payment.*' \
  --tests '*HmacPaymentRequestFingerprinterTest' \
  --tests '*PaymentAttempt*PersistenceIntegrationTest' \
  --tests '*W5PaymentSchemaIntegrationTest' \
  --tests '*CommerceSchemaIntegrationTest' \
  --tests 'com.loopers.domain.order.*' \
  --tests '*OrderPayment*Test' \
  --tests '*OrderV1ApiE2ETest' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

실행 종료 후 테스트 XML 22개와 Checkstyle main/test XML 2개로 위 결과를 확인했다. 기존 주문 도메인·POINT 고객 HTTP 42개·PG 재고/HTTP 연결·rollback/경합도 해당 명령의 회귀에 포함됐다. 전체 check와 장시간 캐시 측정은 재실행하지 않았다. 종료 후 Docker 목록에는 작업 전부터 중지된 모니터링 컨테이너 2개만 남아 테스트 컨테이너 정리도 확인했다. [HTML 보고서](../../apps/commerce-api/build/reports/tests/test/index.html)는 다음 실행에서 덮어써질 수 있다. 이번 범위의 회귀를 W5 전체 완료로 확대하지 않는다.

## 다음 연결 단계

1. 비밀키 공급·버전 선택을 설정과 연결한다. 재시작/여러 서버에서도 같은 버전은 같은 키여야 한다.
2. 신규 시도의 지문을 기존 준비 TX A에 함께 저장하고, 기존 시도는 저장 버전으로 비교한다. 기존 NULL은 채우지 않고 비교 불가로 남긴다.
3. 고객 confirm의 동일 요청·내용 변경·비교 불가 오류 계약을 확정한 뒤 실제 HTTP/DB/PG 경합·rollback·POST 수를 검증한다. 기존 거래 조회·복구에는 카드 재제출을 요구하지 않는다.

커밋·PR 제출은 수행하지 않았다.
