# W5 HMAC 환경변수 키 설정 TDD 실행 기록

[선행 계산](payment-request-fingerprint-tdd-log.md) · [선행 저장](payment-request-fingerprint-persistence-tdd-log.md) · [키 공급·교체 정책](../week2/commerce-policy-decisions.md#키-관리와-검증-범위) · [TDD 계획](../week2/commerce-tdd-plan.md#hmac-요청-지문의-검증-계획) · [완료 체크리스트](../week2/commerce-completion-checklist.md)

## 결과와 경계

**2026-10-23 15:41:32 KST 결과 확인: 신규 92개를 포함한 14개 스위트·403개 통과. 실패·오류·skip 0, ArchUnit 1개 통과, Checkstyle main/test 보고서 2개·위반 0.** 사용자 승인한 환경변수 방식으로 비밀키를 공급하고, 새 계산에는 현재 버전·과거 비교에는 저장 버전을 선택하는 기능을 구현했다.

사용자 DB에 접근하지 않았다. 이번에는 테스트 DB도 기동하지 않았고 SQL·PaymentAttempt·기존 주문/재고 트랜잭션·락·PG 호출 흐름을 변경하지 않았다. 실제 비밀키를 생성하거나 사용자 실행 환경에 등록하지 않았으며 테스트에 합성 키만 사용했다. 전체 check·DB/PG 결제 통합·실제 서버 재시작 검증은 이번 실행 범위가 아니다.

| 구현 | 후속 |
| --- | --- |
| 환경변수 읽기·유효성 검사·Spring `PaymentRequestFingerprinter` bean 등록 | 서비스 TX A에서 계산기를 호출하고 지문과 예약·시도를 함께 저장 |
| 현재/과거 버전 선택, 키 누락·설정 손상의 명시적 비교 불가 | 고객 confirm의 같은 내용 재사용·변경 내용 충돌·기술 오류 HTTP 매핑 |
| 컨텍스트 재생성·교체·동시 계산·비노출 테스트 | 실제 배포 키 공급·여러 서버 설정 일관성·키 수명/유출 시 운영 절차 |

기존 `prepareNew`는 여전히 지문 없는 5인자 생성자를 사용한다. 키 공급 bean을 등록했다는 이유로 신규 결제의 지문 저장이나 고객 멱등 처리가 연결된 것으로 표시하지 않는다. 기존 NULL 지문은 소급 생성하지 않는다.

## 변경 파일과 책임

| 파일 | 역할 |
| --- | --- |
| [PaymentFingerprintConfiguration](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/PaymentFingerprintConfiguration.java) | 선택한 환경변수만 읽고 키를 검증·디코딩. 잘못된 설정은 값 없는 경고와 비교 불가 bean으로 표현 |
| [VersionedPaymentRequestFingerprinter](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/VersionedPaymentRequestFingerprinter.java) | 불변 버전별 계산기 목록. 신규 계산은 현재 키, 비교는 저장된 키 버전만 선택 |
| [PaymentFingerprintConfigurationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentFingerprintConfigurationTest.java) | Spring 컨텍스트에서 외부 설정·오류·재생성·출력·소스 경계 52개 |
| [VersionedPaymentRequestFingerprinterTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/VersionedPaymentRequestFingerprinterTest.java) | 버전 선택·기존 계산 규격·불변성·오류·동시성 40개 |

기존 `HmacPaymentRequestFingerprinter`의 알고리즘·canonical v1·32바이트 비교는 수정하지 않고 버전마다 재사용한다. application/domain에서 infrastructure로 향하는 새 의존은 없다.

## 설정 의미

실제 공급 절차·주의사항의 기준은 [정책 문서](../week2/commerce-policy-decisions.md#키-관리와-검증-범위)다. 아래는 키 값을 포함하지 않은 형식 설명이다.

| 환경변수 | 형식 |
| --- | --- |
| `PAYMENT_FINGERPRINT_CURRENT_KEY_VERSION` | 새 계산에 사용할 양의 int 버전. 예: `2` |
| `PAYMENT_FINGERPRINT_KEYS` | 버전 → Base64 키 문자열 JSON 객체. 형식 예: `{"1":"<이전 키의 Base64>","2":"<현재 키의 Base64>"}`. 꺾쇠 부분은 실행 가능한 키가 아님 |

- 디코딩한 키는 32바이트 이상이고 현재 버전이 목록에 있어야 한다. Base64는 전달용 표현이지 키 암호화가 아니다. 시험용 반복 바이트는 테스트 전용이며 실제 키 생성 기준으로 쓰지 않는다.
- 버전 문자열은 공백·부호·앞자리 0 없는 양의 10진수, int 범위다. JSON 중복 버전·후행 토큰·객체 외 루트·문자열 아닌 값·잘못된 Base64·짧은 키를 거절한다.
- Spring의 `systemEnvironment` 소스를 직접 읽는다. 일반 YAML·CLI·시스템 프로퍼티로 우회하거나 값 내부 `${...}`를 치환하지 않는다. 키 객체를 `@ConfigurationProperties`에 바인딩해 원문이 오류 출력에 들어가는 경로도 만들지 않는다.
- 시작 시 한 번 읽는다. 실행 중 환경 설정을 바꿔도 해당 bean은 바뀌지 않으며 새 설정은 새 컨텍스트/프로세스에서 적용한다. 기본 키·시작마다 난수 키·모르는 버전의 현재 키 fallback은 없다.
- 전체 설정이 잘못되면 고정 WARN과 `PaymentFingerprintUnavailableException`으로 계산·비교만 막는다. 부분 키 목록을 임의 채택하지 않고 원본 파싱 예외/cause를 출력하지 않는다. 앱 컨텍스트는 시작할 수 있으며 결제 FAILED로 바꾸지 않는다.
- 설정은 유효하지만 특정 과거 버전이 없다면 그 버전 비교만 불가하며 현재 키 계산은 가능하다. 키 비교가 필요 없는 기존 거래 조회·복구에 새 키 의존을 연결하지 않았다.
- 디코딩한 임시 배열은 계산기가 복사한 뒤 정리한다. 환경변수·Jackson·Java 문자열과 암호 라이브러리 내부의 모든 복사본을 완전히 지운다고 주장하지 않는다.

## TDD 증거

| 단계 | 실제 결과 |
| --- | --- |
| Red 1 | 테스트 먼저 추가. 두 생산 클래스가 없어 compileTestJava 오류 19건. 테스트 본문 미실행 |
| Red 2 | 미구현 뼈대에서 92개 실행, **40개 실패·52개 통과**. 정상 설정 로딩·계산·회전·입력/키 검증에서 실패. 설정 테스트 52개 중 4개 실패, 버전 선택 테스트 40개 중 36개 실패 |
| Green | 동일 테스트 92개 모두 통과. 기존 기대값 삭제·완화 없음 |
| 관련 검사 | 기존 HMAC·결제/주문 모델·PG 설정·coordinator 설정·ArchUnit 포함 **403개 통과**, main/test Checkstyle 위반 0. BUILD SUCCESSFUL·8초·종료 0 |

읽기 검토로 설정 일부 허용·기본 키 우회·버전 혼동·예외 원문 누출 경계를 함께 확인했다. 새 설정은 기존 계산 알고리즘을 복제하지 않고, 환경 읽기/오류 격리와 순수 버전 선택을 나눠 책임을 유지했다. 무관한 코드 정리나 테스트 규칙 완화는 하지 않았다.

## 테스트에서 확인한 것

| 장면 | 기대·관찰 |
| --- | --- |
| 최초 공급 | 합성 환경변수에서 port bean 1개 생성. 현재 버전과 기존 단일 계산기의 digest 일치. DataSource·PaymentGateway bean 없음 |
| 재생성·키 교체 | 같은 설정으로 새 컨텍스트를 만들어도 같은 지문. current 1→2 변경 후 새 계산은 2, 이전 1의 지문은 1로 비교하고 저장 값을 바꾸지 않음 |
| 현재 설정 손상 | 누락·비문자열 값·잘못된 int/JSON/Base64·현재 키 없음·나쁜 과거 키도 전체 설정 불가. 컨텍스트는 유지, 계산/비교는 고정 기술 예외·cause 없음 |
| 잘못된 소스 | 같은 이름의 일반 Spring 프로퍼티만 있어도 대체하지 않음. 환경 소스 부재·소스 읽기 예외에서도 키/원인 문자열 비노출 |
| 과거 자료 | 모르는 키/형식·null 비교 자료는 불일치 false가 아니라 비교 불가. 여러 키를 전부 시도해 맞는 것을 찾는 fallback 없음 |
| 외부 변경 | 호출자가 키 배열/Map을 수정해도 내부 값 유지. 같은 실행 컨텍스트에서 환경 Map을 바꿔도 자동 교체 없음 |
| 동시성 | 하나의 버전 선택기를 4 workers·64개 계산/비교 작업이 공유해도 정상 결과. Future 및 종료 대기 5초·finally 종료. DB 동시성 테스트는 아님 |
| 비노출 | 정상/파싱/읽기 오류의 캡처 출력에 합성 키·전체 JSON·카드·원인 표식 없음. 진단 assertion도 원문 대신 boolean 비교 |
| 운영 한계 | **같은 버전 번호에 서로 다른 비밀키를 배포하면** 다른 지문이 나와 false가 된다. 버전 번호만으로 이 설정 실수와 요청 변경을 구분하지 못한다는 한계 자체를 검증 |

`ApplicationContextRunner`에 합성 `systemEnvironment`를 넣은 검증이며 실제 OS에 비밀키를 설정하거나 서버 프로세스를 재시작한 검증은 아니다. 키가 빠졌을 때 기존 고객 결제 API가 특정 HTTP 오류를 반환한다고 증명한 것도 아니다. 관련 PG 설정 회귀의 로컬 HTTP fixture는 별도 시험 장치이며 신규 키 테스트는 PG에 연결하지 않는다.

환경변수는 소스와 분리하는 전달 수단이지 보안 저장소가 아니다. 로그·덤프·진단/프로세스 권한을 통한 노출 위험 때문에 OWASP도 일반적인 운영 비밀 보관 수단으로 우선 권장하지 않는다. 이번 사용자 선택은 실습 범위의 공급 방식이며 Secret Manager/KMS 도입·운영 보안 인증을 구현한 것은 아니다. [OWASP Secrets Management](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html#51-standard-docker-and-kubernetes)

## 실행 명령

Red/Green:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*PaymentFingerprintConfigurationTest' \
  --tests '*VersionedPaymentRequestFingerprinterTest' --console=plain
```

관련 회귀·정적 검사:

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.payment.*' \
  --tests '*HmacPaymentRequestFingerprinterTest' \
  --tests '*VersionedPaymentRequestFingerprinterTest' \
  --tests '*PaymentFingerprintConfigurationTest' \
  --tests 'com.loopers.domain.order.*' \
  --tests '*OrderPaymentCoordinatorWiringTest' \
  --tests '*TestPgConfigurationTest' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

결과는 테스트 XML 14개와 Checkstyle XML 2개를 집계했다. [HTML 보고서](../../apps/commerce-api/build/reports/tests/test/index.html)는 후속 실행으로 덮어써질 수 있다. 선행 343·554·1,249개는 각 당시 범위의 기록이며 이번 403개와 합쳐 전체 통과 수로 만들지 않는다.

## 다음 작업

다음은 계산기 port를 **기존 TX A의 신규 시도 저장 및 기존 시도 비교**에 연결하는 것이다. 기존 NULL 지문 보존·소유권 우선·기존 락 순서를 유지한다. 고객 HTTP 오류/DTO는 아직 확정이 필요하고 실제 DB 경합·rollback·PG POST 수는 그 연결 단계에서 검증한다. 키 유실 시 과거 지문만으로 새 지문을 복원할 수 없으며, 이후 입력으로 과거 지문을 덮어쓰지 않는다.

사용자 DB·환경변수·볼륨은 변경하지 않았고 커밋·PR도 수행하지 않았다.
