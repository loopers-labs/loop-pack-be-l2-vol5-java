# 쿠폰 발급 검증 기록

> 기능 계약과 처리 흐름은 [쿠폰 발급 설계](coupon-issuance.md) 및 [대기열 설계](coupon-issuance-queue.md)를 기준으로 한다. 이 문서는 검증 시나리오와 실제 실행 결과, 확인하지 않은 항목을 모은다.

## 전체 회귀

`./gradlew :apps:commerce-api:check` 통과: 1,061건, 실패·오류·skip 0. 쿠폰 발급 대기열과 쿠폰 insert 전환을 포함한 최종 검증 결과다.

## 동기 발급

### 검증 시나리오: 현재 수용량과 permit 반환

| 사전 조건 | 값 |
|---|---|
| 수용량 | 3; 3+1 API 테스트는 Redis를 비운 뒤 테스트에서 허용 상태를 연다. 시작 runner는 별도의 실제 Redis 통합 테스트에서 `open-on-startup=true`로 확인한다. |
| Redis | 격리된 실제 Redis; 시작 전에 permit Set 비어 있음 |
| 캠페인 | 총량이 네 요청보다 큼 |
| 검증 대상 | Redis permit이 동시 신규 DB 발급 실행을 세 개로 제한하는지 확인; 총량 경합과 분리 |

| 순서 | 동작 | 기대 결과 |
|---|---|---|
| 1 | 서로 다른 세 사용자의 실제 발급 요청을 보내고, 실제 Redis permit 획득 직후·DB 트랜잭션 시작 전에 테스트 barrier에서 멈춘다. | Redis primary에 서로 다른 활성 token 세 개가 있고, 세 요청 모두 DB 트랜잭션 전이다. |
| 2 | 활성 token 세 개를 유지한 채 네 번째 사용자가 발급을 요청한다. | 429를 받고 DB 발급 트랜잭션에 진입하지 않으며 쿠폰도 발급되지 않는다. 진입 여부는 트랜잭션 spy로 직접 확인한다. |
| 3 | 첫 세 실행 중 하나만 barrier에서 풀어 발급·commit·token 반환을 완료한다. 나머지 두 실행은 계속 멈춰 둔다. | 발급 수는 1, Redis에는 나머지 두 token만 남는다. |
| 4 | 네 번째 사용자가 새 HTTP 요청을 보내고, 그 요청도 DB 트랜잭션 직전까지 멈춘다. | 새 token이 들어가며 Redis Set에는 기존 두 token과 새 token이 함께 있다. 먼저 받은 429가 자동으로 처리된 것이 아니다. |
| 5 | 남은 두 실행과 네 번째 재요청을 진행해 commit·token 반환을 기다린다. | 발급 쿠폰 네 장, issuedCount 4, 활성 token 0개가 확인된다. |

| 테스트 보조 조건 | 확인 |
|---|---|
| Barrier | deadline 설정; 실패 시 요청을 풀고 종료·token 정리까지 대기 |
| 네 번째 요청 | 첫 429 요청은 transaction spy로 DB 진입이 없음을 확인; 재요청은 나머지 두 실행이 유지되는 동안 새 permit을 얻음 |
| Set 구성원 | Redis Set에서 실제 UUID token을 비교; 단순 크기만 확인하지 않음 |
| token 반환 | 첫 `SREM=1`; 반복·미지 token은 `0`, 다른 활성 token 유지 여부를 실제 Redis에서 확인 |
| TTL | 활성 token이 있는 동안 Redis key의 TTL이 `-1`인지 확인 |
| 실제 의존성 | 실제 Redis Set과 token을 검사; mock·thread-local 대체 금지 |
| 시작 runner | `open-on-startup=true` 실제 앱 컨텍스트가 빈 primary Set에서 열리고, token 존재 시 runner가 닫는지 확인 |
| 닫힘 시나리오 | 클래스당 하나, 전용 Spring 컨텍스트·Redis, `@DirtiesContext(AFTER_CLASS)` |
| Redis 중단 | 전용 Testcontainers Redis와 `@DynamicPropertySource`; 수용 재개용 테스트 기능 없음 |

### 검증 시나리오: 쿠폰 결과·중복·장애

| 장면 | 준비·실행 | 핵심 기대 |
|---|---|---|
| 요청자 식별 누락 | `X-USER-ID` 없이 발급 또는 목록 API 호출 | 400 `BAD_REQUEST`; 신규 발급 permit·DB 효과 없음 |
| 캠페인 사전 거절 | 없음·기간 밖·종료·품절 | 404/409; permit과 발급 TX에 진입하지 않음 |
| Lua·키 오류 | 잘못된 입력 또는 permit 키 타입 오류 | 500 `INTERNAL_ERROR`; 수용 닫힘, DB 신규 효과 없음 |
| 캠페인 조건부 SQL | 첫 발급·마지막 발급·동시 수량 경쟁·rollback | 첫 발급에는 종료 시각 없음, 마지막 발급에만 같은 `issueNow` 저장; rollback 시 카운터와 종료 시각 원복 |
| 쿠폰 저장소 SQL | 쿠폰 insert·유일 제약·DB에 없는 로컬 사용자 ID·사용자별 정렬·예전 모델 DB의 스키마 준비 SQL | 같은 사용자 제약만 별도 중복 예외; `users` 행 없는 ID도 허용; 스키마 준비 SQL은 발급된 쿠폰을 보존하고 미발급 행을 지운다 |
| 로컬 seed | 생성·기본 재실행·명시적 reset·발급 수 불일치 | 기본 재실행에서 기존 결과 보존, reset에서만 기간·발급 수 초기화와 그 캠페인 쿠폰 삭제; 다른 캠페인 유지 |
| 정상 발급·목록 | 신규 발급 후 본인 목록 조회 | 쿠폰 1건, `issuedCount=1`, 같은 `couponId` 노출 |
| 동일 사용자 재요청 | 같은 캠페인으로 재호출 | 같은 `couponId`; 쿠폰 행 추가·카운터 증가 없음 |
| 동시 동일 사용자 | 같은 사용자 요청 병렬 실행 | DB 효과 1건, 응답은 같은 쿠폰; 타인 목록에 없음 |
| 마지막 한 장의 동시 중복 | 총량 1, 같은 사용자의 두 요청을 barrier 뒤 함께 진행 | 201·200과 같은 `couponId`; `issuedCount=1` |
| 마지막 수량 경쟁 | 총량 5, 서로 다른 사용자 8명 | 발급 5·품절 3·쿠폰 5·`issuedCount=5`; Redis 거절은 품절로 세지 않음 |
| 쿠폰 insert 실패 | 카운터 UPDATE 뒤 쿠폰 insert 실패 | 전체 TX rollback; 쿠폰 행 없음·카운터 원복·permit 반환 |
| commit 뒤 응답 유실 | 같은 사용자가 재요청 | 기존 쿠폰 반환; 추가 발급 0 |
| Redis 중단 | 실제 연결 endpoint 중단 | 신규 발급 503·DB 효과 없음; 기존 본인 목록 조회와 같은 사용자 재요청의 200 유지 |
| permit 반환 실패 | 첫 `SREM=0` 및 반환 명령 오류 주입 | DB 결과대로 응답; 쿠폰 보존·수용 닫힘·복구 확인 후 token 정리 |
| 잠금 대기 timeout | 수용량 1, 다른 연결이 캠페인 행을 잠금, 테스트 연결의 잠금 대기 1초 | 500; rollback 완료로 token 반환·수용 열림; 잠금 해제 뒤 같은 사용자 201 |
| commit 직전 연결 끊김 | 수용량 1, 발급 SQL 뒤 다른 연결에서 `KILL CONNECTION` | 500; Spring 완료 상태는 ROLLED_BACK, 쿠폰 미발급; token 1개 남음·수용 열림; 같은 사용자 429; token 정리 뒤 201 |
| TTL 경과 | 활성 token 대기; Redis key TTL 확인 | 자동 만료 없음(`TTL=-1`); 시간 경과만으로 permit 재사용 불가 |

| 검증 분리·정리 | 조건 |
|---|---|
| 발급 총량 경합 | Redis 용량을 충분히 두거나 DB handler를 실제 병렬 호출해 DB 불변식만 검증 |
| 장애 대상 | 실제 application endpoint; 테스트가 만든 자원만 정리 |
| 테스트 판정 | 0건·skip·연결 실패는 통과가 아님; 기존 MySQL·Redis fixture 우선 사용 |
| 실행 시점 | 발급 facade 21건·발급 실행기 14건·DB 트랜잭션 2건(완료 상태 기록 포함)·설정 3건·수용 시작 runner 5건(단위 4·실제 Redis 1)·HTTP E2E 8건·Redis 장애/스크립트/반환 실패 E2E 4건·발급 트랜잭션 실패 E2E 2건·Redis 오류 분류 4건을 검증했다. 실제 Redis와 barrier로 수용량 3, 한 permit 반환 중 재수용, 마지막 한 장의 동일 사용자 경합, 시작 시 Set 확인, Redis 중단 후 기존 결과 조회, 활성 permit의 무TTL을 확인했다. 실제 MySQL로 잠금 대기 timeout 뒤 permit 반환과 commit 직전 연결 끊김 뒤 permit 유지를 확인했다. |

## 발급 대기열

### 검증 항목

| 테스트 | 기대값 |
|---|---|
| 도메인 상태와 사유 규칙 | 미완료(`WAITING`, `PROCESSING`, `UNKNOWN`)와 완료(`SUCCEEDED`, `FAILED`) 구분, reasonCode별 상태와 재접수 처리, 거절 사유 변환, 상태별 값 조합 검증, 소유자 확인 |
| Lua 상태 전이 | 실제 Redis에서 접수, 가져오기, 완료, 결과 불명 기록, 시스템 실패 재접수, 수동 복구가 허용된 상태에서만 바뀌고, 기대 상태나 실행 token이 다르면 변경하지 않음 |
| 확정 실패와 결과 불명 구분 | 품절은 `FAILED`, rollback 완료를 확인한 오류도 `FAILED`이되 reasonCode 구분, DB 종료나 결과 불명은 `UNKNOWN` |
| 사유 코드와 메시지 | 6개 reasonCode로 사유 구분, 한국어 메시지 문구 비교 없이 분류, 기존 동기 오류 응답 유지 |
| 캠페인 거절 사유 | 품절과 조기 종료는 품절 사유, 기간 전후는 기간 밖 사유를 담은 예외. 품절과 기간 밖이 함께 성립하면 품절. 동기 API는 같은 예외에 409와 기존 메시지 유지 |
| commit 뒤 예외 | commit 완료와 실제 쿠폰을 확인하면 `SUCCEEDED`와 같은 couponId 기록, 예외만으로 `FAILED` 처리하지 않음 |
| 실제 Redis 동시 중복 접수 | 같은 사용자와 캠페인은 작업 ID 하나, 대기 항목 하나 |
| 실제 Redis 접수 한도 | 마지막 한 자리를 동시에 요청해도 설정 한도를 넘지 않음 |
| 로컬 한도 20건 | 처리 중 3건과 대기 17건으로 미완료 20건 유지, 추가 신규 작업은 한도 초과로 거절, 한 작업 완료 뒤 새 접수 가능 |
| 한도 계산과 반환 | 미완료 합계에 `UNKNOWN` 포함, `SUCCEEDED`와 `FAILED` 기록 후 빈자리 신규 접수 가능 |
| 한도 도달 중복 접수와 재대기 | 기존 작업 ID 반환, List 중복 없음, 처리 중에서 재대기로 이동해도 미완료 작업 수 불변 |
| 실제 Redis claim | worker 둘이 같은 작업을 동시에 가져가지 않음, 처리 중 목록으로 원자 이동 |
| 가져오기 실행 뒤 응답 유실 | 실제 Redis에서 List 이동과 token 기록 후 응답 오류 주입. DB 진입 없음, 얻은 permit 반환, `PROCESSING`과 같은 token, 미완료 개수 유지, 신규 수용 닫힘과 추가 가져오기 중단, 자동 재대기 없음 |
| 가져오기 미실행 상태에서 응답 불명 | 작업은 `WAITING`과 대기 List에 그대로 유지. 처리 중 전환이나 DB 진입을 추측으로 수행하지 않음, 얻은 permit 반환, 신규 수용 닫힘 |
| permit 획득 응답 0 | 작업 가져오기, DB 진입, SREM 없음. 작업은 `WAITING`과 대기 List 위치 유지 |
| 자리 획득 뒤 대기열 비어 있음 | 작업 없음 응답에 자기 permit만 반환, 다른 token 유지 |
| 자리 부족과 작업 순서 | 동기 요청이 자리를 모두 쓰는 동안 대기 List의 순서와 작업 상태가 바뀌지 않음 |
| permit 획득 응답 유실 | token 추가 뒤 응답 오류를 주입. 작업 가져오기와 DB 진입 없음, 작업은 `WAITING` 유지, 신규 수용 닫힘, Set에 남은 token은 정리 전까지 유지 |
| permit 요청 전 수용 닫힘 | permit 연산, 작업 가져오기, DB 진입 없음. 작업은 `WAITING` 유지, 추가 가져오기 중지 |
| 특정 작업 이동 | 처리 중 List가 [A, B, C]일 때 B만 재대기하면 A와 C는 처리 중 List에 남고 B는 대기 List 끝으로 이동. B만 완료하면 B만 제거 |
| 실행 소유권 | 재대기 후 새 실행이 가져간 작업에 이전 실행이 완료를 기록해도 현재 상태와 List 항목을 바꾸지 못함 |
| 완료 반복 | 같은 실행과 같은 결과의 완료 기록 반복은 기존 결과 유지, 다른 token과 결과로 덮어쓰기 없음 |
| HTTP 접수와 본인 조회 | 202와 작업 ID, 본인만 상태 조회, 다른 사용자에게 결과 노출 없음 |
| 기존 쿠폰과 작업 이력 없음 | 기존 couponId와 HTTP 200, requestId 없음. 작업 정보와 중복 정보를 만들지 않음, List와 permit 진입 없음, 발급 수 불변 |
| 한도 도달, 신규 수용 닫힘, Redis 장애에서 기존 쿠폰 | 기존 couponId와 HTTP 200, 신규 발급 실행 없음 |
| 사전 거절과 동시 접수 경합 | 두 요청이 최초 작업과 쿠폰 부재를 확인한 뒤 한 요청을 완료. 뒤 요청을 캠페인 검사에서 거절하기 전 재조회해 같은 작업 ID와 결과 반환 |
| 접수 한도와 동기 발급 경합 | 쿠폰 부재 확인 뒤 동기 발급이 완료되고 대기열이 찬 경우, 최종 429 전 재조회해 기존 couponId를 200으로 반환, 신규 발급 없음 |
| 작업 결과와 조회 결과 | `FAILED`, `UNKNOWN` 본인 작업도 조회 성공 시 HTTP 200과 `meta.result=SUCCESS`, 작업 결과는 `data.status`와 reasonCode로 표현 |
| 접수 응답 timeout | 작업 생성 후 응답만 실패한 경우 재접수에서 같은 ID 복구, 추가 List 항목과 중복 발급 없음 |
| 완료 이력과 중복 정보 보존 | `SUCCEEDED`와 `FAILED` 뒤에도 같은 작업 ID와 결과 조회. `SUCCEEDED`와 업무 거절 `FAILED`의 재접수는 새 List 항목 없음, 자동 만료 없음 |
| 시스템 실패 재접수 | `DB_ERROR`, `INTERNAL_ERROR` 작업 재접수 시 같은 작업 ID로 202와 `WAITING`, 대기 항목 하나, 미완료 개수 증가. 작업 정보에서 이전 reasonCode와 사유가 비워짐. 업무 거절 `FAILED`와 `SUCCEEDED`는 200과 저장된 결과 유지 |
| 시스템 실패 재접수의 거절과 경합 | 캠페인 발급 불가, 한도 도달, 신규 수용 닫힘이면 작업은 기존 `FAILED` 유지. 그사이 쿠폰이 발급됐으면 같은 작업 ID로 `SUCCEEDED`. 처음 확인 뒤 쿠폰이 발급되고 품절, 한도 도달, 수용 닫힘이 겹쳐도 거절하지 않고 `SUCCEEDED`. 성공으로 바꾸는 Lua도 상태나 reasonCode가 바뀌었으면 변경하지 않음. 쿠폰 없이 거절할 때도 다른 재접수가 먼저 다시 대기시켰으면 현재 상태로 응답. 동시 재접수에도 대기 항목 하나. worker와 복구 절차는 `FAILED`를 자동으로 다시 대기시키지 않음 |
| 실제 DB 발급 | 처리 후 쿠폰 ID가 보유 목록과 일치, 총량과 중복 규칙 유지 |
| 동기 요청과 worker 경합 | 같은 permit 키를 공유하고 전체 발급 실행 상한 유지 |
| worker 중단과 결과 미기록 | 완료 기록이 실행되지 않은 경우 처리 중 작업 ID와 DB 결과 보존, 자동 재실행 없음, 수동 복구에서 실제 쿠폰 확인 |
| 완료 기록 실행 뒤 응답 유실 | 실제 Redis에 `SUCCEEDED` 또는 `FAILED`와 List 제거를 반영한 뒤 응답 오류 주입. 신규 수용 닫힘, 같은 결과와 실행 token 유지, List 복원이나 재발급 없음, 미완료 개수도 실제 완료 상태와 일치 |
| worker 실행 제한 | 전용 실행 수를 넘겨 Java 내부에 작업을 쌓지 않음, 자동 실행이 꺼진 테스트는 가져오기 시점을 직접 제어 |
| worker 시작과 중지 | 준비와 수용 확인 전에는 가져오기 없음, 수용 닫힘과 종료 시 추가 가져오기 중지, 기존 처리 중 작업 자동 재실행 없음 |
| permit 획득 뒤 가져오기 전 중단 | permit을 얻은 직후 실행을 멈추면 작업은 `WAITING` 유지, Set에 작업과 연결되지 않은 token이 남고 재시작 때 수용이 열리지 않음 |
| 수동 복구 Lua | 종료 불명 작업은 유지, 이미 발급됐으면 같은 couponId로 완료, 미발급 작업의 재대기에도 작업 ID와 중복 접수 정보 유지 |
| 복구 Lua의 적용 조건 | 기대 상태나 token이 빠졌거나 현재 값과 다르면 변경 없이 사유 반환. 다른 실행으로 바뀐 작업에는 적용하지 않음 |
| 복구 Lua 반복 | 이미 완료한 작업은 같은 결과 유지, List 중복과 추가 발급 없음, 다른 실행 token은 제거하지 않음 |
| 복구 상태 제한 | `FAILED` 작업의 캠페인이 현재 발급 가능해도 재대기하지 않음. `WAITING`에 재적용해도 List와 permit 변경 없음 |
| 완료 작업의 잔여 permit | DB 종료와 기대 token 확인 후 자기 잔여 token만 제거. 성공 또는 실패 결과와 List 유지, 반복 제거 0에도 다른 token 보존 |
| seed reset과 남은 실행 | 해당 캠페인의 작업이 대기·처리 중 List에 있거나 permit token이 남아 있으면 DB와 Redis를 바꾸지 않고 거절 |
| seed reset의 대기열 정리 | 남은 실행이 없으면 해당 캠페인의 작업 정보와 중복 접수 정보만 지운 뒤 DB 초기화. 다른 캠페인의 작업, 이력과 permit 유지. 초기화 뒤 같은 사용자의 접수는 새 작업 ID |
| seed reset 중 실패 | Redis 확인 불가나 정리 응답 유실이면 DB 변경 없이 실패. Redis 정리 뒤 DB 초기화가 실패하면 신규 수용과 worker를 열지 않고, 다시 접수하면 기존 쿠폰을 바로 반환. 예전 성공 이력과 초기화된 DB가 공존하지 않음 |
| 기존 회귀 | 동기 쿠폰 API, 관련 ArchUnit, Checkstyle과 `:apps:commerce-api:check` 유지 |

이 항목은 계약의 핵심 검증 경계를 보여 준다. 구체적인 실행 결과와 확인하지 않은 범위는 아래에 정리한다.

### 구현과 검증 결과

단계마다 실패하는 테스트를 먼저 확인한 뒤 구현했다. 실행기 분리와 실제 환경 검증(worker E2E)은 기존 동작을 유지하거나 확인하는 단계라 실패 테스트를 먼저 두지 않았다.

| 구현 단계 | 테스트 | 건수 |
|---|---|---|
| 캠페인 거절 사유와 작업 상태 도메인 | `CouponCampaignTest`, `CouponIssueRequestTest` | 22, 41 |
| permit 이후 발급 실행 분리 | `CouponIssueExecutorTest`, 기존 `CouponIssueFacadeTest` 회귀 | 14, 21 |
| Redis 저장소와 Lua | 접수·조회 `RedisCouponIssueQueueIntegrationTest`, 가져오기 `...ClaimIntegrationTest`, 결과 기록 `...RecordIntegrationTest`, 재접수 `...ResubmitIntegrationTest`, 예외 변환 `...ExceptionTranslatorTest` | 11, 6, 11, 9, 4 |
| 접수와 조회 API | `CouponIssueQueueFacadeTest`, `CouponIssueRequestV1ApiE2ETest` | 30, 10 |
| worker | 처리 단계 `CouponIssueQueueWorkerTest`, 실행 구조 `CouponIssueQueueWorkerRunnerTest`(컨텍스트 종료 순서 포함), 설정 `CouponIssueQueuePropertiesTest`, 실제 MySQL·Redis `CouponIssueQueueWorkerE2ETest` | 22, 9, 3, 8 |
| seed reset과 수동 복구 | `RedisCouponIssueQueueCleanupIntegrationTest`, `CouponSeedServiceIntegrationTest`, 복구 Lua `RedisCouponIssueQueueRecoverScriptIntegrationTest` | 4, 8, 10 |

구현을 마친 뒤 최종 검토에서 결함 세 가지를 찾았고, 각각 실패하는 테스트를 먼저 확인한 뒤 고쳤다. worker 종료 대기가 Redis 연결이 멈춘 뒤에 실행되던 문제, 중복 충돌 뒤 쿠폰 재조회가 실패하면 작업이 처리 중으로 남고 수용이 열려 있던 문제, 수동 복구 Lua가 앱이 읽을 수 없는 사유와 couponId를 받던 문제다. 동기 발급의 거절 직전 쿠폰 재확인과 복구 Lua의 couponId 범위 검사도 같은 방식으로 추가했다.

실제 MySQL·Redis에서는 접수부터 worker 처리와 조회까지, 동기 요청과의 permit 공유, 처리 시점 품절, 잠금 대기 timeout 뒤 시스템 실패와 재접수, permit·작업 가져오기·완료 기록 응답 유실, commit 직전 연결 끊김에 따른 결과 불명을 확인했다.

자동 테스트로 확인하지 않은 항목은 다음과 같다.

| 항목 | 현재 확인 범위 |
|---|---|
| seed reset 중 Redis 확인 불가, Redis 정리 뒤 DB 초기화 실패 | 코드에서 예외로 처리하지만 장애를 주입한 테스트는 없다. 정리가 막히는 경우만 실제 Redis로 확인했다 |
| 실제 명령 timeout으로 접수 결과를 모르는 경우 | facade 단위 테스트에서 예외를 주입해 503을 확인했다. 실제 timeout을 재현하지는 않았다 |
| 작업 완료로 빈 한도에 새 접수 | 완료 기록이 처리 중 List에서 빠지는 것과 한도 계산을 각각 확인했다. HTTP로 이어서 확인하지는 않았다 |
| worker의 실제 앱 기동 순서와 자동 실행 | runner 단위 테스트로 실행 규칙을 확인했다. 앱을 띄워 seed와 수용 시작 뒤 자동 실행되는지는 확인하지 않았다 |
| 동기 요청과 worker의 동시 경합 | 같은 permit Set을 공유하는 것은 확인했다. 두 경로를 동시에 실행하는 경합 테스트는 없다 |
| 여러 앱 인스턴스 | 범위 밖이다 |

#### 남은 개선

| 항목 | 현재 동작 | 남은 개선 |
|---|---|---|
| MySQL만 새로 만든 경우 | seed 생성 경로는 Redis에 남은 같은 캠페인 ID의 작업 이력을 확인하지 않는다. 옛 성공 이력이 남으면 초기화된 DB와 다르게 응답할 수 있어, 지금은 운영 절차로 Redis 키를 함께 지운다. | seed 생성 때도 해당 캠페인의 대기열 정리를 호출하고, 막히면 기동을 실패시킨다. |
| Lua의 JSON 다시 쓰기 위치 | 가져오기와 수동 복구 Lua는 첫 쓰기 뒤에 작업 JSON을 다시 만든다. 지금은 앱만 문자열 값으로 JSON을 쓰므로 실패하기 어렵다. | JSON 생성을 첫 쓰기 앞으로 옮긴다. |
| 수용 확인과 permit 획득 사이 | 동기 발급과 같다. [쿠폰 발급 설계](coupon-issuance.md)의 "범위 밖"에 적었다. | 같은 문서를 따른다. |
