# W5 고객 PG API·제한 조회 TDD 실행 기록

## 범위와 선택

2026-10-23 사용자 요청에 따라 고객 confirm과 고객·관리자 주문 조회를 기존 PG TX A/B에 연결했다. 앞선 [계약 경계 테스트](customer-payment-contract-tdd-log.md), [HMAC 주문 연결](order-payment-fingerprint-connection-tdd-log.md)의 다음 증분이다. 사용자 DB·실제 키·기존 데이터·DDL은 변경하지 않는다. 커밋·PR도 수행하지 않는다.

| 추가로 확인한 결정 | 반영 |
| --- | --- |
| PG 설정 OFF | 신규 DRAFT는 `503 PAYMENT_PROVIDER_UNAVAILABLE`. POINT 대체 금지. 기존 PG의 비교·저장 결과 반환과 POINT 재확정·GET은 유지 |
| 외부 식별·금액·종결 결과 충돌 | `409 PAYMENT_RESULT_CONFLICT`. 요청 내용 충돌과 구분하며 기존 결과·예약 보존 |
| 조회 회로 도구 | Resilience4j circuitbreaker/core 2.3.0. 생성 POST·내부 TX B는 회로 밖 |
| 이번 제외 범위 | callback·수동 reconcile HTTP, ACK·복구 요청 예산, 운영 180초 하드 상한 검증 |

## 변경 책임

| 책임 | 구현 |
| --- | --- |
| HTTP 진입 시각 | `PaymentRequestTimingFilter`가 MVC 본문 변환 전 단조 시각을 한 번 기록. 필터 진입 이전의 네트워크·서버 큐 시간을 포함한 하드 상한은 아님 |
| 고객 confirm | 소유권·POINT 호환 → 세 필드 파싱 → PG 활성 확인 → `startAndAwait` → 최신 저장 상태 응답. API 성공과 PG SUCCESS/FAILED 구분 |
| TX A | `prepareCustomer`가 기존 주문 잠금 안에서 서버 PG ID 결정·기존 ID 보존. 기존 HMAC·예약·발송 기록 공동 저장 경계를 재사용 |
| PG 비활성 재요청 | `reuseCustomer`는 기존 시도의 동일성 비교만 허용한다. DRAFT는 준비·예약 전에 503이며 기존 PG의 정상 결과·내용 충돌·비교 불가 오류는 PG 사용 여부와 독립적으로 유지 |
| 외부 조율 | 최초 발송 권한 실행만 POST 1회와 제한 조회. 동일 재요청은 저장 상태 즉시 반환. PG HTTP·대기 동안 업무 TX/락 미보유 |
| TX B | 기존 검증·최종 상태 보존·소비/반환 한 번과 기존 잠금 순서 유지. 저장 실패를 UNKNOWN/202로 숨기지 않음 |
| 조회 | `OrderPaymentQueryService`의 read-only DB 스냅샷. 페이지의 시도를 한 번에 조회하여 주문 수에 비례한 추가 SQL 방지. PG/HMAC/행 잠금 없음 |
| 응답·오류 | 기존 주문 필드에 결제수단·시도 키·상태 추가. 관리자 userId 유지. PG 대기/UNKNOWN 202, 종결 200, GET 200. 새 두 오류 분류 |
| 회로·자원 | 생성은 그대로 통과, 거래/주문 조회가 인스턴스·계정별 한 회로 공유. context 종료 시 하위 HTTP client 종료 |

기존 `OrderService.confirm`은 과거 POINT 회귀 fixture/내부 검증용으로 보존하지만 고객 confirm에서는 호출하지 않는다. 과거 POINT 금액·품목·재확정 409와 포인트 충전/조회는 유지한다. 신규 PG는 포인트 잔액과 무관하고 차감 0회다.

## 시간·회로

단일 요청 진입을 기준으로 전체 **목표 180초**, 외부 호출 마감 175초다. 생성 전체 65초·조회 전체 10초가 남지 않으면 새 호출을 시작하지 않는다. 설정으로 이 전체 호출 한도를 늘려 예산 검사를 우회하면 기동을 거절하며 더 짧은 timeout은 허용한다.

첫 조회는 즉시, 이후 완료 시점부터 5→10→15초의 ±20% jitter다. 실제 client 조회 시도 최대 12회이며 OPEN 차단은 횟수에 포함하지 않는다. 대기는 잔여 예산 이내로 제한하고 대기 전/후·조회 직전·최종 반환 전에 저장된 종결을 확인한다. 생성 timeout마다 180초를 다시 시작하지 않는다. 새 거래 생성 재시도·상시 scheduler는 없다.

선택된 전체 대기 간격과 다음 HTTP 10초를 모두 확보할 수 없으면 종료한다. 마감 직전에 간격을 압축해 한 번 더 조회하지 않는다. TX A 커밋 뒤 생성 65초 예산이 부족하면 POST 없이 UNKNOWN·예약 유지로 남을 수 있다. 발송 기록 후 중단과 마찬가지로 자동 재발송은 하지 않으며 장기 미완료의 수동 확인 필요성을 감추지 않는다.

회로는 최근 20건, 최소 10건, 실패/느린 호출 비율 50%, 느림 3초 초과, OPEN 30초, HALF_OPEN 2건·최대 40초다. 유효한 PG FAILED/PENDING/404·빈 목록은 통신 실패가 아니다. UNKNOWN 통신/응답 오류는 장애 표본이며 회로 차단은 주문 FAILED나 예약 반환으로 변환하지 않는다. 기존 TX B는 회로 밖이라 DB 저장 실패가 PG 장애 표본에 섞이지 않는다.

**5초의 최종 처리 여유가 DB 완료를 보장하지 않는다.** 제어 시계 검증과 실제 로컬 HTTP 검증은 구별한다. 장시간 동시 요청의 worker 점유·프록시 제한·클라이언트 취소와 실제 180초 응답 상한은 후속 운영 검증이다. `links.reconcile`은 확정한 응답 계약의 경로지만 해당 endpoint 구현은 아직 남아 있다.

## Red → Green → Refactor

| 단계 | 관찰 |
| --- | --- |
| 조회·대기·회로 Red | 새 query/시간/회로 타입 부재로 컴파일 실패. 최초 타입 부재와 테스트 작성 중 변수명 충돌은 행동 실패와 구별 |
| 실제 HTTP 행동 Red | 신규 PG 성공/실패와 PG OFF·본문 누락 5개 실행 중 4개 실패. 기존 POINT confirm의 응답/효과가 새 계약과 다름을 확인 |
| 호출 예산 방어 Red | 최초 연결 검사 124개 중 122개 통과, 전체 timeout 65/10초를 초과한 설정의 기동 거절 2개 실패. 이후 client 생성 전 설정 검사 추가 |
| 단발 복구 fallback Red | 조율·대기 44개 중 2개 실패: OPEN을 단발 refreshOnce에서 저장 상태로 되돌리지 못함. 예외를 좁게 처리해 최신 저장 상태를 반환하도록 연결 |
| 간격 계약 Red | 관련 회귀 113개 중 3개 실패: 남은 시간에 맞춰 대기를 줄이던 구현을 합의한 간격 유지·예산 부족 시 종료로 수정. 기존 5개 HTTP suite와 단발 복구는 이 실행에서 통과 |
| 첫 전체 검사 | 122개 스위트·1,753개 중 4개 실패. 과거 POINT의 실제 SQL/rollback 테스트가 이전 무본문 confirm을 사용한 것이 원인. POINT 서비스 프록시 + 실제 GET/재확정 HTTP로 구분하고 원래 수치·전체 롤백 기대값을 보존 |
| PG OFF 재요청 Red | 추가 검토에서 기존 PG도 무조건 503으로 차단됨을 발견. 18개 중 신규 11개 실패, 과거 POINT 롤백 4개는 통과. 기존 PG 5상태·내용 충돌·무지문·키/형식 비교 불가를 adapter 없이 처리하는 경계 추가 |
| Refactor | 기존 TX A의 공통 준비 경로 재사용, 일괄 시도 조회, 최초 전용 조율과 기존 단발 조회 분리, 실제 HTTP 테스트와 과거 POINT fixture 구분 |

실행 로그는 이번 세션의 `/private/tmp/w5-pg-api.p61EHd/`에 보관한다. 영구적인 통과 증거는 아래 결과와 소스 테스트이며 임시 경로 보존을 전제로 하지 않는다.

## 테스트 구성

| 테스트 | 확인한 기대 |
| --- | --- |
| `PgOrderConfirmApiE2ETest` | 실제 고객 HTTP·로컬 PG HTTP·격리 MySQL. 성공/실패/strict 422, 응답 유실→같은 거래 조회, 12회 미완료, 조기 종료, 요청/결과 충돌, HMAC 비교 불가, 중복 동시 confirm·포인트 0·주문 조회 |
| `PgOrderConfirmDisabledApiE2ETest` | 설정 OFF 신규503·DB 변화 0, 본문 없는 POINT409/타인404, PG 본문 누락400. 기존 PG 5상태 재요청200/202, 변경 입력409·무지문409·키/형식503을 보내기 기능과 독립적으로 유지 |
| `OrderPaymentQueryIntegrationTest` | PG 5상태·POINT/DRAFT, 본인/관리자·삭제 후 snapshot, 손상 거절, 1/8개 주문 SQL 수 동일, 동시 TX B에도 일관된 read-only snapshot |
| `PaymentRequestTimingFilterTest` | MVC 진입 전 단일 시작 시각·중복 실행 시 재설정 금지·GET 제외 |
| `OrderPaymentConfirmationCoordinatorTest` | 요청 진입/TX A 소요 시간 합산, POST/GET 직전 예산 재확인, 중복 5상태 무발송/무대기, 결과 충돌과 TX B 오류 전파, 회로 차단 단발 fallback. mock 단위 검증을 실제 DB 효과 증거와 구분 |
| `PaymentConfirmationWaiterTest` | 제어 시계의 기존 경과 시간·65/10초 경계·175초 마감, 12회/간격/jitter, OPEN 무한 반복 금지, 종결 재확인·중단·TX 미보유 |
| `PgLookupCircuitTest`·`TestPgConfigurationTest` | 실제 Resilience4j 임계·창 이동·OPEN/HALF_OPEN/회복·느림, 조회 공유·POST 제외, 실제 HTTP 차단·기동 설정 한도·자원 종료 |
| 기존 5개 HTTP suite | 과거 POINT를 명시적 fixture로 생성해 기존 금액·품목·잔액·재확정 검증 보존. 새 PG 경로의 재고·삭제·입력 오류는 HTTP 검증 유지 |
| `OrderTransactionTest` | 과거 POINT의 실제 서비스 TX에 SQL 실패 주입. 기존 write 수·동일 연결·전체 rollback·별도 DB/GET 조회·POINT 잔액/재고를 보존하고 재확정409은 실제 HTTP로 검사 |

기존 suite의 포인트 부족 사례는 신규 PG의 거절 조건으로 옮기지 않고 과거 POINT 내부 회귀로 명시한다. 해당 기대를 지우지 않으며 PG 잔액 부족/0원 잔액에서도 결제 가능한 새 HTTP 사례로 분리한다.

## 최종 검사

관련 회귀는 **2026-10-23 17:05 KST, 60개 스위트·1,095개 통과, 실패·오류·skip 0**이다. `*Order*`, `*Payment*`, `*Pg*`, `*ArchitectureTest`와 commerce-api Checkstyle main/test를 실행했고 BUILD SUCCESSFUL·2분 15초·종료 0이다. PG OFF 재요청과 과거 POINT SQL 롤백 보완을 포함한다.

최종 전체 재검사는 **2026-10-23 17:14:48 KST 결과 확인, 122개 스위트·1,764개 통과, 실패·오류·skip 모두 0**이다. 결과 XML을 직접 집계했으며 앞선 증분의 통과 수를 합산한 값이 아니다.

```shell
./gradlew :apps:commerce-api:check --rerun-tasks --console=plain
```

- `BUILD SUCCESSFUL`, 8분 49초, 종료 코드 0, 39개 작업 실행.
- ArchUnit 1개 통과. 모든 Java 모듈 Checkstyle 보고서 11개·위반 0.
- `git diff --check` 통과. [테스트 보고서](../../apps/commerce-api/build/reports/tests/test/index.html)는 로컬 빌드 산출물이며 재실행 시 갱신된다.
- Testcontainers MySQL·Redis, 로컬 테스트 PG와 합성 카드·키를 사용했다. 사용자 DB·실제 키·기존 데이터·DDL은 변경하지 않았다.
- API 계약·정책·전체 설계·스키마 안내·TDD 계획·완료 체크리스트의 현재 상태를 맞추고, 과거 실행 결과는 당시 증거로 보존했다. callback/reconcile HTTP까지 끝났다는 판정은 아니다.

기존 설정상 별도 작업으로 분리된 W4 장시간 `ttlMeasurement` 실험은 이번 회귀에 포함하지 않는다. 실제 로컬 HTTP timeout 테스트와 제어 시계의 180초 예산 테스트는 실행하지만, 운영 환경에서 180초 동안 기다리는 부하 실험으로 해석하지 않는다.
