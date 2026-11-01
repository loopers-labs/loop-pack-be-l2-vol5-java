# PG 결제 실행·검토 기록

기존 절 번호 참조: [3.6](../features/order-payment/model.md#36-pg-저장-모델과-제약) · [4.5](../features/order-payment/verification.md#45-pg-연동의-검증과-관찰) · [A.32](../decisions/payment.md#a32-생성-회로-추가와-미발송-차단의-두-경계).

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

아래 날짜·검사 결과·진척 문장은 당시 기록이다. 이번 문서 재편의 실행 결과로 해석하지 않는다.

## 과거 실행 기록

### I4 구현과 검증 근거 — 2026-10-23

I1 fixture·I2 adapter·I3 저장/TX 위에 confirm·callback·reconcile과 고객/관리자 주문 응답을 연결했다. Facade에는 상위 TX가 없으며 `PaymentObservationApplier`가 공통 TX B 호출과 거래 unique rollback 후 별도 충돌 기록 호출을 맡는다. 충돌은 기록 TX가 반환한 뒤 409로 변환하며, 기술 오류를 확인 불가 관찰로 바꾸지 않는다. 주문 생성·조회·목록은 TX 안에서 OrderInfo로 복사하고 목록의 payment는 해당 페이지 ID로 일괄 조회한다.

검증 대상은 `7a9723a` 이후 I4 변경이다. `./gradlew :apps:commerce-api:test :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --offline` 실행에서 **752건, 실패·오류·skip 0**을 확인했다(ArchUnit 3 포함). Checkstyle main 157파일·test 111파일 위반 0이며 main은 동일 소스의 직전 실제 검사 이후 UP-TO-DATE였다. 이후 캐시 테스트의 과거 주문 준비를 명시적 PG 즉시 성공과 CONFIRMED 검사로 보강하고 `--tests 'com.loopers.domain.product.ProductCacheInvalidationIntegrationTest'`와 checkstyleTest를 실행해 **9건·실패·오류·skip 0, 위반 0**을 확인했다. 두 실행 사이 Java 변경은 그 캐시 테스트 한 파일뿐이며 운영 코드는 동일하다. 전체 752건을 보강 후 다시 실행한 것으로 합산하지 않는다.

|검증 경계|실제 근거와 기대값|
|---|---|
|실제 PG → adapter → MySQL → commerce HTTP|PgPaymentApiIntegrationTest 15건. 기동 전 commerce 포트/콜백 URL 동시 주입·실제 포트 일치 확인. Point 없는 PENDING/즉시 SUCCESS, 정확한 422 무효과 실패·반환, 생성 응답 유실 후 주문별 조회 복구, 같은 시도 재요청의 POST 추가 0, 입력/소유권/없는 시도 오류 확인|
|명시적 콜백|실제 sendCallbacks의 SUCCESS 1건·FAILED 3건에서 기대 200 ACK를 받은 뒤 API/DB로 종결·금액·확보/반환 이력을 확인. 알림 상태와 권위 상태 불일치·미존재 거래 키·다른 주문 거래의 위조 알림은 별도 실제 HTTP로 확인. 콜백 data 생략·고객 헤더 불필요·중복 반환 0|
|종결 후 대사·충돌 보존|저장 종결 뒤 조회 실패는 202/UNAVAILABLE·종결 유지, 종결 후 PENDING은 409/TERMINAL_REGRESSION·요약 commit. 식별 충돌도 409 뒤 저장 키/업무 상태/확보를 유지|
|TX 경계와 실패 복구|실제 create/lookup 호출의 상위 TX 부재를 spy에서 확인. OrderTransactionIntegrationTest 3건은 TX A SQL 뒤 rollback/PG 0, PG SUCCESS 뒤 TX B SQL 실패의 500·UNKNOWN/확보 유지와 기존 거래 대사 복구, 실패 반환 SQL rollback 뒤 단 한 번 반환을 확인|
|동시성과 회귀|OrderConcurrencyIntegrationTest 8건은 동일 주문 POST/확보 1회, 잔액과 무관한 다중 PG 결제·충전 병행, 재고 부족 경쟁·관리자 수량 설정·정보 수정·삭제·반대 품목 순서 경쟁을 확인. OrderConfirmFacadeIntegrationTest의 Order/Product 잠금 6건도 유지|
|읽기·과거 POINT|고객/관리자 금액 필드 totalAmount/pointAmount/paidAmount·분리된 payment DTO·관리자 orderUrl/고객 reconcileUrl, 페이지 결제 1회 조회/개별 조회 0, PG 결제 행 누락의 500을 확인. 과거 POINT 주문의 구매자·품목·실제 금액·삭제 뒤 조회는 전용 과거 snapshot fixture와 기존 데이터 전환 검사로 유지|

신규 POINT 결제 실행을 전제로 했던 사례의 전환 근거는 위 ‘기존 테스트의 유지·교체’ 계약이다. 다음 사례 외 소유권·재고·조회·잠금 보호를 삭제하거나 완화하지 않았다.

|이전 사례|대체 사례|
|---|---|
|OrderConfirmFacadeIntegrationTest의 포인트 사용 이력·잔액 부족·Point 미생성 거절|savesReservationWithoutPointUse·allowsInsufficientPointBalance·allowsZeroPointBalance·allowsUninitializedPoint. POINT 변화 0·Point 없는 PG 성공|
|동일 주문의 확정 재요청 거절|returnsSamePgAttemptWithoutMoreEffects·confirmsOnlyOnce: 같은 PG 키는 저장 결과, 발송/확보는 1회. 과거 POINT 재결제 거절은 OrderV1ApiE2ETest.rejectsAlreadyConfirmed로 유지|
|OrderTransactionIntegrationTest의 단일 POINT TX rollback|rollsBackEverythingWhenLastOrderSaveFailsAfterFlush·respondsInternalServerErrorOnTechnicalFailure·failureReleaseSqlRollbackDoesNotUndoCommittedReservation: TX A/B SQL 뒤 실패를 분리하고 기존 외부 거래 복구까지 확인|
|OrderConcurrencyIntegrationTest의 잔액 소비 경쟁·충전/사용 직렬화|confirmsOnlyWhatBalanceAllows는 모든 PG 결제 허용·잔액 유지로 전환, keepsBothChargeAndPayment는 충전과 PG 성공을 함께 보존. 독립 포인트 기능 검사는 유지|
|OrderV1ApiE2ETest·CustomerPurchaseFlowE2ETest의 입력 없는 신규 POINT 결제|UUID/합성 카드 입력·PG payment 응답·200/202, 충전 잔액 유지로 전환. 비소유·없는 주문·재고 부족/삭제·과거 POINT 확정 오류는 유지|

I4의 기본 API 연결 완료이며 4.5 전체 검증 완료는 아니다. I5의 callback-first/조회 시작·반영 역전 장벽, 실제 풀 점유와 미발송의 업무 연결, 같은 PG·DB를 유지한 commerce 컨텍스트 재시작, 전체 V 항목의 남은 조건과 글 실험은 별도로 검증해야 한다. 일반 회귀 통과를 이 조건들의 대체 근거로 사용하지 않는다.

### I5 경쟁·장애 회복·재시작 검증 근거 — 2026-10-23

I4 커밋 `58b5faf`와 마지막 검증 소스가 같음을 확인하고, 실제 HTTP·Facade 오류 경계·ArchUnit 30건을 다시 실행해 통과한 뒤 I5를 시작했다. 구현 종료 시 I5 변경은 스테이징하지 않았다. 이후 사용자의 추가 요청으로 I5 코드·테스트와 기준 설계의 검증 기록을 스테이징했으며, 커밋은 사용자가 직접 수행한다. 제품의 timeout·풀/회로 초기값·격리 수준·한 시도/재발송 금지 계약은 유지했다.

**제품 보완:** 저장 품목 없음·수량 0·고정 품목 합계 불일치의 실제 MySQL 사례가 기존 코드에서 모두 202로 진행되는 것을 먼저 재현했다(3건 실패). OrderModel.requireStoredItemsConsistent가 저장 품목·양수 수량·합계를 검사하고, 기존 PaymentTransactionService의 신규 준비와 저장 결제 정합성 경계에서 호출하도록 최소 보완했다. 저장 값의 산술 overflow도 내부 정합성 오류로 다루며 확인/반영 전에 500으로 중단한다. 이는 3.6·4.3에 있던 검사를 구현한 것이며 새 정책이 아니다. 동일 3건과 TX/부모 잠금 회귀로 500·PG 조회 0·결제/재고 변화 0을 확인했다.

|I5 단위·경계|구현·실제 관측|
|---|---|
|I5-1 실제 복구·경쟁·오류|PaymentRecoveryFlowIntegrationTest 30건. 정상 PENDING→SUCCESS/FAILED 단일 콜백의 기대 200 ACK 뒤 새 API/DB·정확한 PG/포인트/이력 수량, 생성 응답 유실의 callback-first/lookup-first 수렴, 즉시 성공 뒤 같은/반대 종결 콜백, 같은 주문의 다른 거래·금액 복합 충돌, 위조 A/B/C의 실제 권위 GET/회로 표본, 빈/2건/3건/혼합 목록·직접 대상 위반을 확인했다. 실제 InnoDB LOCK WAIT를 관측한 callback/reconcile 실패 반환 경쟁은 반환 1회, 두 주문의 동일 거래 unique 경쟁은 패자 TX rollback 뒤 별도 TRANSACTION_ALREADY_BOUND commit·409다. PG DELAY 중 DB pool active=0·관리자 변경 선완료를 확인했고, 반환은 현재 가용 재고에 가산했다. overflow·물리/논리 삭제·품목 누락·요약 SQL 뒤 실패의 상태 보존도 확인했다|
|I5-1/2 반환 캐시|PaymentReleaseCacheIntegrationTest 2건. 실제 반환 SQL 뒤 commit 전에는 이전 cache를 유지하고 DEL 0, commit 후 DEL 1·새 가용량 조회, rollback은 DB/캐시 유지·DEL 0이다|
|I5-2 생성/조회 장애와 업무 효과|PgPaymentResilienceIntegrationTest 13건. 사전 확인 뒤 actual OPEN/HALF_OPEN 거절은 본문 미진입·POST 0·CIRCUIT_REJECTED·반환 1회. 본문 안/장식 전 대조 오류는 표본/관찰 합성/TX B 0·500·UNKNOWN/예약 유지다. 정상 응답 뒤 decoder 프로그램 오류도 저장 확인 정보를 덮지 않는다. GATE 10개와 Apache 실제 leased=10을 관측한 뒤 조회와 생성의 풀 획득 실패를 읽기 timeout보다 먼저 끝내고 점유 요청 미완료를 확인했다. 생성은 POOL_ACQUIRE_TIMEOUT·표본/거래 0·반환 1회, 조회는 UNAVAILABLE·반환 0이며 반환 SQL rollback과 이후 0건 조회의 UNKNOWN 보존도 확인했다. gate 해제 후 leased/pending=0·점유 요청 200을 확인했다. 읽기 지연/본문 중단 뒤 연결 반환·재시도 0, 실제 연결 거절의 UNKNOWN 보존, 사전 차단 직전 다른 TX가 commit한 결제 재확인도 검증했다|
|I5-2 승인된 실제 시간|PaymentCircuitTimingIntegrationTest 7건. 빠른 성공 2+실패 3의 실패율 OPEN과 GET 차단, 실제 OPEN 5초 후 2회 탐색의 CLOSED/OPEN, 실패 없는 느린 성공 3/5의 OPEN, 실제 HTTP HALF_OPEN 두 슬롯과 세 번째 미수신, 승인 10초 만료의 PG/업무 자동 효과 0을 확인했다. 진행 작업 비취소만 별도 회로 계층의 허용된 100ms maxWait 대조이며 실제 HTTP 취소 근거가 아니다. TX A DB 대기 900ms는 pgCreate 느림 표본에 들어가지 않았다|
|I5-3 재시작|PaymentRestartIntegrationTest 2건. 테스트 MySQL 별도 스키마와 같은 PG를 두 commerce context보다 오래 유지했다. 최초 create→두 번째 validate 사이 reset/TRUNCATE/재생성 없이 결제 전체 행/발송 시각/스키마 정의/확보 수량을 비교했다. 두 context는 기동 전 자기 포트/콜백 URL을 주입하고 실제 일치를 확인했다. 첫 client/회로/DB pool 종료·두 번째 새 instance, 저장 UNKNOWN 고객 조회→기존 주문별 reconcile로 동일 거래 종결·추가 POST 0을 확인했다. TX A 직후 외부 생성 전 중단 대조는 거래 0건이므로 UNKNOWN/예약 유지·202이며 재발송하지 않는다. 저장된 첫 callback URL을 수정하거나 이전 주소로 받지 않는다|
|I5-4 독립 TX/SQL·정리|PaymentOverlapEvidenceIntegrationTest 2건. 서로 다른 두 실제 CONNECTION_ID를 확보한 두 TX가 payment 또는 payment_conflict 조회 후 INSERT 직전 모두 대기하는 동안 해당 INSERT SQL 0·테이블 행 0·양쪽 미완료를 확인하고, 해제 뒤 각각 commit·2행·정확한 재고를 확인했다. I3의 첫 업무 SQL/최신 읽기/관찰 순서/스키마 전환 검증도 전체 회귀에서 실행했다. I5 HTTP 사례마다 새 fixture/회로/context를 사용하며, caller·실제 servlet 요청이 모두 종료된 뒤 정리하고 context 종료 후 PG HTTP/콜백 worker와 DB pool 종료를 직접 assert한다|

**실행 버전과 수량:** 제품 보완과 I5 기본 구현을 포함한 전체 commerce-api 회귀는 **805건·fail/error/skip 0**(ArchUnit 3 포함), Checkstyle 위반 0으로 통과했다. 이후 테스트에만 독립 INSERT SQL 근거 2건·사전 차단 경합 1건과 사례별 context/자원 종료 검사를 보강했다. 최종 I5 전체 **56건·fail/error/skip 0**, Checkstyle main 157파일·test 118파일 위반 0을 확인했다. 추가로 transport fault를 주입한 PgCreateFailureClassificationTest 5건을 별도 실행해 socket timeout·interrupt·취소를 감싼 I/O·이름만 풀 오류인 I/O·lease timeout 하위 타입이 모두 UNAVAILABLE/실패 1이며 LOCAL_NOT_DISPATCHED가 아님을 확인했다. 이 5건은 실제 네트워크 취소 실측이 아닌 타입 경계 대조다. 최종 Checkstyle test 119파일 위반 0이다. 운영 소스는 이 실행들 사이 변경 0이며, 서로 겹치는 건수를 최종 단일 전체 실행으로 합산하지 않는다. 전체 805건은 3분 57초, 최종 I5 56건은 2분 29초였다.

전체 실행은 `./gradlew :apps:commerce-api:test :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --offline`, 최종 보강 실행은 6개 I5 테스트 클래스 필터와 같은 Checkstyle task다. 전체 XML은 `/tmp/codex-i5-evidence/full-xml/`, 최종 XML은 `final-i5-xml/`, 실행별 집계·class/운영 소스·최종 소스 해시도 같은 디렉터리에 보존했다. build/test-results/test는 마지막 분류 대조 5건만 담으며 56건은 final-i5-xml/, 분류 5건은 classification-final-xml/에 보존했다. Java/테스트 외 기존 데이터 전환은 테스트 컨테이너의 별도 스키마에서만 실행했으며 실제 보존 대상 로컬 DB를 전환한 것이 아니다.

**최종 구성과 한계:** 별도 독립 probe에서 실제 Apache client의 connect/socket/pool/response 제한 300/1000/200/1000ms·총/경로별 10, redirect=false, 실행 체인 `[ContentCompressionExec, ProtocolExec, ConnectExec, MainClientExec]`를 확인했다(자동 retry/redirect 실행 없음). 실제 로드된 Resilience4j는 2.2.0이다. 연결 거절은 재현했지만 별도 통제 네트워크에서의 실제 connect timeout은 재현하지 않았으며 300ms 설정 적용과 구분한다. 단일 실행 결과를 운영 적정값·장시간 안정성·무작위 경합/부하 시험으로 확대하지 않는다. I2/I3 전체 코드 교차 검토·글 비교 실험·실제 제출은 이 실행 완료와 별개다.

### F5·Claude 검토 보완과 최종 회귀 — 2026-10-23

사용자가 실습·발제 대조에서 발견한 F5 보완과 Claude 검토 확인을 승인했다. 기존 3.6의 저장 정합성 계약대로 PaymentTransactionService.requireConsistent에서 PG의 pointAmount=0, 대기의 paidAmount=null, 성공의 paidAmount=결제 amount를 검사한다. 실패 paidAmount=null 검사는 유지하며 손상 값을 정상 값으로 덮어쓰지 않는다. 같은 시도 재요청·확인 준비·결과 반영·제약 충돌 기록이 공통 검사를 사용한다. 정책·스키마·timeout/회로 수치는 변경하지 않았다.

|보완|재현 가능한 코드·검증|
|---|---|
|F5 저장 금액 정합성|PaymentStoredConsistencyIntegrationTest 10건. UNKNOWN/PENDING/SUCCESS/FAILED의 손상 값과 null, confirm/reconcile/callback의 500·PG 추가 호출 0·주문/결제/상품/포인트/이력/확인 정보 보존을 실제 HTTP·MySQL로 확인했다. CREATE/QUERY 확인 준비 뒤 값이 손상된 경우도 실제 결과 TX에서 다시 검사해 rollback한다. 정상 API가 손상 값을 생성한다는 주장은 아니다|
|재고 부족의 직접 효과 수|OrderConfirmFacadeIntegrationTest의 여러 품목 rollback/관리자 재고 감소와 OrderV1ApiE2ETest에 create 0·거래 0을 직접 확인했다. 과거 ORDER_DEDUCTION 부재만 보던 검사를 현재 ORDER_RESERVATION/ORDER_RELEASE 부재로 바꾸고 DB snapshot 보호는 유지했다. 포인트 부족 PG 성공 검사의 메서드 이름도 기대와 맞췄다|
|성공 저장 실패 뒤 복구|OrderTransactionIntegrationTest.respondsInternalServerErrorOnTechnicalFailure가 같은 PG 거래 키·재고 3·예약 1·반환/ORDER_USE 0과 두 번째 reconcile 뒤 동일 업무 효과를 확인한다. create 1, 주문별 조회 1·그다음 키 조회 1을 분리한다|
|정확한 422 재적용|OrderTransactionIntegrationTest.applyingSameExactRejectionTwiceReleasesReservationOnlyOnce가 실제 adapter의 RejectedNoEffect를 실제 DB TX에 두 번 적용한다. 거래 0·create 1·키 없음·실패 상태·재고 5·반환 1·예약 1을 확인한다|
|관리자 값 응답|OrderAdminV1ApiTest.detailAndPageExposeStoredPgStatesWithoutQueryingPg 4건이 UNKNOWN/PENDING/SUCCESS/FAILED의 상세·목록 상태/금액/확인 정보/URL과 PG 호출·확인 정보 변화 0을 확인한다. 기존 목록 PENDING 직접 검사가 없었던 것은 아니며 이번에 상태별 상세·목록을 보강했다|
|최종 Apache 적용 근거|PgHttpConfigTest.appliesTimeoutsPoolAndNoRetryOrRedirectToActualClient가 실제 client bean의 connect/socket/pool/response 300/1000/200/1000ms, 풀 총/경로별 10, redirect=false, retry/redirect exec 부재와 실제 로드된 Resilience4j 2.2.0을 검사한다. 기존 interceptor 부재·실제 HTTP retry/redirect/timeout 검사는 유지한다. I5 당시 임시 probe는 이력이며 현재 검사의 필수 소스가 아니다. 내부 필드/메서드 리플렉션 검사는 사용 중인 Apache 버전에 종속되어 버전 변경 시 재검토한다|

최종 소스의 관련 82건과 Checkstyle main/test가 먼저 실제 실행으로 통과했다. 이후 같은 Java/리소스 280파일이 유지된 전체 commerce-api check가 **829건·failure/error/skip 0(ArchUnit 3 포함)**으로 통과했다(5분 9초). Checkstyle main 157파일·test 120파일 위반 0이며 전체 check에서는 동일 소스의 직전 검사 결과로 UP-TO-DATE였다. 82건은 전체 829건에 포함되므로 합산하지 않는다. Claude가 보고한 수정 전 813건은 이 수정 버전의 근거로 대신하지 않는다.

일반 재현은 `./gradlew :apps:commerce-api:check --rerun-tasks --offline`로 가능하다. 실제 이번 전체 실행은 `./gradlew :apps:commerce-api:check --init-script /tmp/codex-w5-review-fix/isolated.init.gradle --offline`이며 init script는 test의 binary/XML/HTML 출력 경로만 분리했다. 테스트·제품 설정은 바꾸지 않았다. 결과는 /tmp/codex-w5-review-fix의 full-xml/·full-summary.json·full-check.log·full-source-sha256.json, 관련 검사는 related-xml/·related-summary.json에 보존했다. 이 임시 출력 없이도 저장소의 위 테스트를 재실행할 수 있으며 핵심 수량·판정·한계는 이 절에 남긴다.

문서의 현행 구현 전/미실행 표기는 구현·사례별 근거와 맞췄다. 접힌 이전 POINT의 Order→Point→Product 표는 역사 기록이므로 유지하고 현행 PG가 아님을 명시했다. 2.9에는 GET 최대 1회·자동 간격 해당 없음과 단계별 대기 제한을 명시했으며 합계 1500ms를 전체 deadline으로 보장하지 않는다. 미발송 실패·반환(A.32)의 한 시도 소진 비용, 인증 없는 콜백의 확인 정보/요약 오염 가능성, UNKNOWN 예약 장기 점유는 승인된 범위의 남은 한계이며 변경하지 않았다. PR·기술 글은 별도 I6 산출물로 남아 있다. 이번 변경을 stage/commit하지 않았다.
