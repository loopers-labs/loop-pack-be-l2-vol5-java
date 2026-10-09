# 커머스 전체 완료 체크리스트

[전체 설계](commerce-erd-draft.md) · [API 계약](commerce-api-contract.md) · [정책 선택](commerce-policy-decisions.md) · [TDD 계획](commerce-tdd-plan.md)

목표는 고객 12개·관리자 13개 API와 관련 도메인·저장·권한·동시성·운영 DDL을 끝까지 연결하고 검증하는 것이다. 이 문서는 완료 증거를 추적하며 새 업무 정책을 정하지 않는다. 계약의 상세 기대값은 연결한 원문을 따른다.

**W3 변경 상태:** 브랜드 일괄 삭제 `W3-BRAND-01..08`, 주문 재확정 거절 `W3-RECONFIRM-01..06`, 주문 저장 경계 실패·롤백 `W3-ORDER-TX-01..03`, 갱신 유실 대조군 `W3-LOST-UPDATE-01`과 재고·포인트·충전/결제 경쟁의 구현·검증을 완료했다. **2026-10-09 최종 전체 재검사는 60개 스위트·549개 통과, 실패·오류·건너뜀 0, Checkstyle 11개 보고서 위반 0·ArchUnit 통과**다. 검증한 코드 기준은 `12f9afc`이며 이번 마무리는 문서만 수정했다. 과거 548·547·546·545·541·538·514·513·509개와 W2의 506개 기록은 당시 증거로 보존한다. PR 초안은 준비했으며 기술 글은 사용자가 작성한다. 실제 커밋·푸시·PR 생성·과제 제출 완료와는 구분한다.

갱신 유실 대조군은 [계획·실행 기록](../week3/stock-lost-update-control-plan.md)에 재현 방법과 실제 결과를 남겼다. 테스트 전용 JDBC 경로만 추가했으며 실제 서비스의 잠금·업무 계약은 변경하지 않았다.

## 기준선과 기록 방법

W2 초기 구현 증분의 기준선은 [고객 브랜드 조회 application 기록](commerce-tdd-brand-query-log.md#검사-결과)의 **111개 테스트 통과**, Checkstyle main/test 위반 0개, ArchUnit 통과와 HTTP API **0/25개**였다. 재고 수량, 사용자 식별·fixture, 브랜드 도메인·영속성·고객 상세 application까지의 과거 증거로 보존한다. 현재 결과는 아래 최신 통합 검사에 구분한다. 테스트 수는 전체 완성률을 뜻하지 않는다.

- `pending`: 구현 중이거나, 일부 검증만 있거나, 완료 증거가 없는 항목. 기존 도메인 테스트만으로 API를 완료 처리하지 않는다.
- `done`: 합의한 기대값의 구현·검증이 끝나고 증거 칸에 테스트 파일·사례와 실행 기록 링크를 남긴 항목.
- API별 정상·대표 오류·권한·저장 상태를 해당 범위에 맞게 검증한다. 복수 API가 연결된 TDD 행은 모든 연결을 검증한 뒤 완료한다.
- 실행 기록에는 변경 범위, 실제 Red·Green·Refactor, 실행 명령·결과·시점과 남은 범위를 남긴다. 이미 통과한 사례는 인위적인 Red로 만들지 않는다. 과거 로그의 테스트 수는 보존하고 최신 전체 검사 결과를 별도로 추가한다.

## W3 브랜드·상품 일괄 삭제의 완료 증거

확정 요구·합의와 구현 선택은 [정책 문서](commerce-policy-decisions.md#w3-브랜드상품-일괄-논리-삭제), 상세 기대값은 [W3 TDD 계획](commerce-tdd-plan.md#w3-브랜드상품-일괄-논리-삭제의-tdd-계획)을 따른다. `pending`은 이전 기능이 모두 미완료라는 뜻이 아니라, **변경된 계약의 증거가 일부이거나 아직 없다는 뜻**이다. 각 증분의 실제 결과는 [W3 실행 기록](../week3/brand-removal-tdd-log.md)을 따른다.

| 항목 | 연결 | 상태 | 완료 증거·검증 범위 |
| --- | --- | --- | --- |
| 잠금·SQL 구체화 | P13 · 첫 증분 | done | RC 트랜잭션·Brand 잠금 아래 Product ID 정렬 조회 → ID별 PK 잠금 → flush → bulk UPDATE → 이미 로딩된 Product refresh. 전체 clear 없이 정상 경로 상품 UPDATE 1회와 관리 객체 보존을 검증했다. N개 잠금 SELECT가 필요하며 경합 전체 검증은 아래 별도 항목이다 |
| 정상 일괄 삭제·보존 | A05 · W3-BRAND-01 | done | [BrandRemovalTransactionTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRemovalTransactionTest.java): 실제 요청200, 재고 0 포함 상품 2개·브랜드 삭제, 행·참조·다른 대상·과거 확정 주문과 잔액 보존. [실행 기록](../week3/brand-removal-tdd-log.md) |
| 기본 경계 전체 | A05 · W3-BRAND-02 | done | 빈 브랜드 삭제, 없는 브랜드 DELETE의 `404`·오류 본문·전체 DB 불변, 미삭제·기삭제 상품 혼합 및 전부 기삭제, 기존 상품의 삭제·갱신 시각과 전체 행 보존, 재삭제 후 전체 DB 불변을 확인했다. 없는 브랜드의 실제 DELETE는 이번에 추가한 검증이다. [두 번째 증분](../week3/brand-removal-tdd-log.md#두-번째-증분--w3-brand-02-경계-검증) |
| 실제 변경 뒤 전체 롤백 | A05 · W3-BRAND-03 | done | 실제 상품 bulk UPDATE와 브랜드 `saveAndFlush()` 후 테스트 예외를 유발했다. 중간 JDBC 상태와 SQL 실행을 확인하고 HTTP `500 INTERNAL_ERROR`·실패 본문, 서비스 종료 후 6개 테이블 전체 행 원복을 검증했다. [세 번째 증분](../week3/brand-removal-tdd-log.md#세-번째-증분--w3-brand-03-롤백-검증) |
| 삭제 후 사용 제한·접근 | C01~C06·C09, A04·A05·A07·A09·A11 · W3-BRAND-04·05 | done | [고객 API](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/BrandRemovalCustomerApiTest.java)·[관리자 접근](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/BrandRemovalAccessApiTest.java): 실제 삭제 후 비노출·새 좋아요/주문 거절, 본인 취소·타인 관계 보존, 수정/재고/등록 거절, 권한·CSRF 실패와 DB 불변, 관리자 삭제 행 조회 |
| DRAFT·과거 주문 보호 | C10~C12·A12·A13 · W3-BRAND-01·03·06 | done | 고객 API 테스트에서 삭제 전 DRAFT의 최초 확정404·차감 없음·저장 상태 보존. 현재 상품 이름/가격 변경·브랜드 삭제 후에도 고객·관리자 주문 목록/상세의 기존 품목·금액·결제 결과 유지. 삭제 실패의 DB 원복은 03에서 검증 |
| 삭제와 주문 경합 | W3-BRAND-07 | done | [선후·경합 테스트](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRemovalConcurrencyTest.java): 생성·최초 확정과 삭제의 선후 결과를 순차 검증하고, 별도 worker 시작만 맞춘 실제 경합에서 업무 결과와 최종 주문·재고·잔액을 대조. 기술 오류는 정상 거절로 세지 않음 |
| 삭제와 상품 등록 경합 | A07 · W3-BRAND-08 | done | 같은 테스트에서 삭제 선행 등록 거절·등록 선행 새 상품까지 삭제를 순차 검증. 별도 실제 동시 등록·삭제의 최종 상태도 검증하며 내부 장벽·sleep을 넣지 않음. 모든 동시 실행 순서를 강제로 재현했다는 뜻은 아님 |
| 첫 증분 관련 회귀 | 브랜드·상품·좋아요·주문, Checkstyle·ArchUnit | done | 관련 24개·Checkstyle 통과 후 `:apps:commerce-api:check` 종료 코드0. 52개 스위트·509개 테스트, 실패·오류·건너뜀0. [실행 기록](../week3/brand-removal-tdd-log.md). 아직 작성하지 않은 W3 사례의 완료 증거는 아니다 |
| 두 번째 증분 관련 회귀 | W3-BRAND-02, Checkstyle·ArchUnit | done | 신규 4개를 포함한 BrandRemovalTransactionTest 6개·Checkstyle 통과 후 전체 check 종료 코드0. 52개 스위트·513개 테스트, 실패·오류·건너뜀0. [실행 기록](../week3/brand-removal-tdd-log.md#두-번째-증분--w3-brand-02-경계-검증). 생산 코드 변경 없이 최초 실행부터 통과했다 |
| 세 번째 증분 관련 회귀 | W3-BRAND-03, Checkstyle·ArchUnit | done | 신규 롤백 1개를 포함한 BrandRemovalTransactionTest 7개·Checkstyle 통과 후 전체 check 종료 코드0. 52개 스위트·514개 테스트, 실패·오류·건너뜀0. [실행 기록](../week3/brand-removal-tdd-log.md#세-번째-증분--w3-brand-03-롤백-검증). 생산 코드 변경 없이 최초 실행부터 통과했다 |
| 네 번째 증분 관련 회귀 | W3-BRAND-04~08, Checkstyle·ArchUnit | done | 신규 24개·Checkstyle 통과 후 최종 전체 check 종료 코드0. 55개 스위트·538개 테스트, 실패·오류·건너뜀0. [실행 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증). 테스트 비교 기준과 동시성 검증 방식을 보완했으며 생산 코드 변경 없음 |

## W3 주문 재확정 거절

사용자가 승인한 `409 ORDER_ALREADY_CONFIRMED / 이미 확정된 주문입니다.`를 따른다. 실패 응답은 `meta.result=FAIL`·`data` 생략이며 기존 확정·결제 결과를 취소하지 않는다. 과거 W2의 재요청 성공 반환 증거와 구분한다.

| 범위 | 상태 | 완료 증거 |
| --- | --- | --- |
| Order 상태 규칙·C10 오류 응답 | done | OrderTest·OrderV1ApiE2ETest: 도메인 재확정 거절, 정확한 HTTP 오류와 6개 테이블 전체 행 불변 |
| 소유권·삭제 후 조회 | done | OrderV1ApiE2ETest: 타인의 확정 주문404 우선, 본인 확정 후 상품·브랜드 삭제여도 재확정409·GET 기존 결과 유지 |
| 같은 주문 경합·기존 DRAFT 재시도 | done | OrderConcurrencyIntegrationTest: 시작만 맞춘 실제 서비스 경합의 성공1·정확한 상태 거절1·차감1회. HTTP 테스트에서 재고/포인트 부족의2사례 모두 보충 후 같은 DRAFT 확정 성공 |
| 전체 회귀·정적 검사 | done | 관련60개 및 전체 check 종료 코드0. XML55개 스위트·541개 통과, Checkstyle11개 보고서 위반0·ArchUnit1개 통과 |

실제 Red·Green과 최종 결과는 [주문 재확정 실행 기록](../week3/order-reconfirmation-tdd-log.md)에 남긴다. 브랜드 삭제의 완료 상태는 유지하며, 이 증분으로 다른 주문 원자성·경쟁 과제까지 완료 처리하지 않는다.

## W3 주문 저장 경계 실패와 롤백

기존 업무 계약·생산 코드·비관적 잠금을 유지하고 테스트부터 추가했다. 아래 증거는 [OrderTransactionTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderTransactionTest.java)의 실제 HTTP·MySQL 실행과 [이번 기록](../week3/order-transaction-tdd-log.md)을 따른다.

| 범위 | 상태 | 완료 증거 |
| --- | --- | --- |
| 정상 다품목 확정 · W3-ORDER-TX-01 | done | 두 상품의 재고·포인트·CONFIRMED·결제액·확정 시각을 함께 반영하고 스냅샷·무관한 데이터 보존 |
| 중간 쓰기 실패 · W3-ORDER-TX-02 | done | 실제 UPDATE 1회/3회 성공 뒤 다음 UPDATE에서 실패하는 2사례. 동일 Connection의 중간 변경 행과 트랜잭션 활성 확인, 정확한 HTTP 500 실패 본문, 요청 종료 후 6개 테이블 전체 행·컬럼 원복 |
| 같은 DRAFT 재시도 · W3-ORDER-TX-03 | done | 실패 장치 해제 후 재시도 성공·차감 1회. 이후 재확정409와 저장 상태 보존 |
| 전체 회귀·정적 검사 | done | 신규4개 및 전체 check 종료 코드0. XML56개 스위트·545개 통과, Checkstyle11개 보고서 위반0·ArchUnit1개 통과 |

환경 오류와 새 테스트의 시간대 문자열 비교 오류는 업무 Red가 아니며 실행 기록에 별도로 남겼다. 기존 구현의 기대값 충족을 확인했으므로 서비스나 트랜잭션을 변경하지 않았다. 이 롤백 증분만으로 재고·포인트 지정 경쟁 수치나 갱신 유실 대조군까지 완료 처리하지 않으며, 후속 대조군의 증거는 다음 절에서 구분한다.

## W3 갱신 유실 대조군

구현·실행을 완료했다. 자세한 기대값과 실제 결과는 [계획·실행 기록](../week3/stock-lost-update-control-plan.md)·[TDD 계획](commerce-tdd-plan.md#w3-갱신-유실-대조군의-tdd-계획)을 따른다.

| 범위 | 상태 | 완료 증거 |
| --- | --- | --- |
| 재현 테스트 · W3-LOST-UPDATE-01 | done | [StockLostUpdateControlTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/StockLostUpdateControlTest.java): 서로 다른 연결·독립 READ_COMMITTED 트랜잭션에서 조회값 5·5 확인 후 상수 4 저장. 커밋 성공 2·기술 오류 0·최종 재고 4·불변식 위반을 실제 MySQL에서 검증 |
| 대기와 정리 | done | 조회·쓰기·Future·SQL·트랜잭션의 유한한 대기, finally의 해제·취소·종료 확인, worker 종료 후 DB 정리 구현. 세 실행에서 정상 종료 확인. 각 오류를 강제 주입한 별도 테스트는 아니며 타임아웃을 재현 성공으로 세지 않음 |
| 관련 회귀와 검사 | done | 관련 15개·전체 546개 통과, 전체 check 종료 코드 0. Checkstyle 11개 보고서 위반 0·ArchUnit 1개 통과 |

잘못된 결과의 재현을 확인하는 테스트가 통과했으므로 `done`으로 기록했다. 실제 주문 서비스의 잠금·API 계약·스키마는 유지하며, 대조군 통과를 8개 주문 재고 경쟁이나 포인트 경쟁의 증거로 대체하지 않는다.

## W3 재고 8주문 경쟁

`W3-STOCK-RACE-01`은 기존 서비스의 트랜잭션·잠금을 유지한 실제 MySQL 경쟁 테스트다. [OrderStockConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderStockConcurrencyIntegrationTest.java)와 [실행 기록](../week3/stock-order-concurrency-tdd-log.md)으로 과거 2개 주문 사례·대조군과 구분한다.

| 범위 | 상태 | 완료 증거 |
| --- | --- | --- |
| 결과 집계 | done | 고유 DRAFT 8개·각 수량 1·재고 5·구매자별 충분한 잔액에서 시작만 맞춰 실행. 성공 5·정확한 INSUFFICIENT_STOCK 3·기술/예상 밖 오류 0 |
| 주문·재고·잔액 정합성 | done | 성공 주문 ID와 DB CONFIRMED ID 일치, 실패 DRAFT 전체 행 보존. 수량식 5−5=0, 사용자별 초기 잔액−성공 결제액=최종 잔액, 결제 합 5,000·잔액 합 15,000 |
| 보존·정리 | done | 품목 스냅샷·무관한 주문/상품/브랜드/사용자 보존. worker 종료 후 DB 재조회·정리, 미종료 시 정리 차단 |
| 회귀·정적 검사 | done | 신규 단독 1개·관련 5개 스위트 16개·전체 58개 스위트 547개 통과. 전체 check 종료 코드 0, Checkstyle 11개 보고서 위반 0·ArchUnit 1개 통과 |

잔액 10,000원에 4,000원 주문 3개 경쟁과 지정 충전·결제 동시 실행은 아래 별도 절에서 검증했다. 재고 경쟁의 통과는 모든 DB 대기 순서나 HTTP 동시 요청을 검증했다는 뜻이 아니다.

## W3 포인트 3주문 경쟁

`W3-POINT-RACE-01`의 기대값을 과제 원문·C10·P09/P13과 대조하고 [OrderPointConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPointConcurrencyIntegrationTest.java)를 추가해 실제 서비스·MySQL로 검증했다. 준비·실제 결과는 [실행 기록](../week3/point-order-concurrency-tdd-log.md)에서 구분한다.

| 범위 | 상태 | 완료 증거 |
| --- | --- | --- |
| 결과와 주문 상태 | done | 잔액 10,000·4,000원 DRAFT 3개에서 성공 2·정확한 INSUFFICIENT_POINTS 1·기술 오류 0, 성공 ID와 DB 확정 ID 일치·거절 DRAFT 전체 행 보존 |
| 잔액·재고·무관 데이터 | done | 성공 결제 합 8,000·잔액 2,000, 상품별 성공 수량만 차감·거절 상품 재고 5와 전체 행 유지, 품목 스냅샷·다른 사용자 데이터 보존 |
| 회귀·정적 검사 | done | 신규 1개·관련 8개 스위트 48개·전체 59개 스위트 548개 통과. 전체 check 종료 코드 0, Checkstyle 11개 보고서 위반 0·ArchUnit 1개 통과 |

## W3 충전과 주문 결제 경쟁

`W3-CHARGE-ORDER-01`은 [PointChargeOrderConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/PointChargeOrderConcurrencyIntegrationTest.java)의 실제 충전·주문 서비스와 MySQL 호출로 검증했다. 준비 조건·반환 잔액과 최종 DB 값의 차이·실행 명령은 [실행 기록](../week3/point-charge-order-concurrency-tdd-log.md)을 따른다.

| 범위 | 상태 | 완료 증거·남은 범위 |
| --- | --- | --- |
| 지정 수치 경쟁 | done | 충전 2,000·결제 7,000 두 요청 성공, 업무 거절 0·기술 오류 0. 초기 10,000에서 최종 잔액 5,000 |
| 주문·재고·보존 | done | 같은 주문 ID의 CONFIRMED·결제액 7,000·확정 시각 저장, 두 품목 재고 A=5−2=3/B=4−1=3. 스냅샷·6개 테이블의 허용 변경 외 값·bob 데이터 보존 |
| 신규·관련 검사 | done | 2026-10-08 21:27 KST 신규 1개, 21:28 KST 관련 10개 스위트·50개 통과(ArchUnit 1개 포함). 실패·오류·건너뜀 0, Checkstyle test 위반 0, 명령 종료 코드 0 |
| 전체 최종 검사 | done | 2026-10-09 14:35 KST 전체 check 재실행 종료 코드 0. 60개 스위트·549개 통과, Checkstyle 11개 보고서 위반 0·ArchUnit 통과. 아래 최신 통합 검사에 명령·범위 기록 |

첫 실행부터 Green이어서 생산 코드·잠금·정책은 바꾸지 않았다. 두 선후 순서를 모두 강제하거나 DB 잠금 대기·HTTP 경쟁까지 관찰했다는 뜻은 아니다.

## W3 제출 준비

| 항목 | 상태 | 증거·남은 작업 |
| --- | --- | --- |
| 문서 정합성 | done | 관리자 권한 흐름을 Security → HTTP 역할 변환 → application 재검사로 정정. 현재 보류 상태와 과거 실행 기록을 구분하고 로컬 링크·표·diff 확인 |
| PR 본문 준비 | done | [W3 PR 초안](../week3/pr-draft.md)에 요약·리뷰 포인트 3개·검사 결과·참고 문서를 정리. 실제 PR을 작성·게시한 것은 아님 |
| 기술 글 | pending | 사용자가 별도로 작성하며, 제출 전 링크를 PR에 추가. 외부 작성 완료 여부는 확인하지 않음 |
| 실제 제출 | pending | 이번 문서 변경의 커밋·푸시와 PR 작성/갱신·플레이그라운드 링크 등록은 수행하지 않음 |

## 최신 통합 검사

### W3 최종 재검사와 문서 정합성 — 2026-10-09

2026-10-09 14:35 KST, 코드 기준 `12f9afc`에서 아래 명령이 **BUILD SUCCESSFUL / 종료 코드 0**으로 끝났다. 이번 마무리에서는 문서만 수정했으며 코드·테스트·검사 규칙은 변경하지 않았다.

```bash
./gradlew :apps:commerce-api:check --rerun-tasks --console=plain
```

| 검사 | 실제 결과·범위 |
| --- | --- |
| 전체 API 모듈 테스트 XML | **60개 스위트·549개 통과**, 실패·오류·건너뜀 0. 이전 포인트 증분의 548개에 충전/결제 경쟁 1개가 포함된 전체 실행 |
| Checkstyle | Java main/test/testFixtures의 XML 보고서 11개, 위반 0. 기존 규칙 삭제·완화 없음 |
| ArchUnit·관리자 경계 | ArchitectureTest 1개·AdminSecurityApiTest 57개 통과. 전체 549개에 포함되며 별도 가산하지 않음 |
| 변경 범위 | 관리자 진입 권한 책임 설명과 전체 검사 상태 갱신, PR 초안 준비. 업무 정책·실행 코드 변경 없음 |
| 해석 범위 | 지정 실패·경쟁 사례와 기존 회귀의 통과이며 모든 DB 실행 순서·장애·성능의 보장은 아님. 기술 글과 실제 제출은 별도 상태 |

사용자가 전달한 선행 검토에는 Docker 미실행으로 첫 검사가 실패한 뒤 Docker 실행 후 통과했다고 기록되어 있다. 이는 제품 코드의 Red가 아닌 환경 문제다. 이번 마무리 재실행은 정상 종료했으며 선행 검토의 실패와 구분한다. 로컬 보고서 `apps/commerce-api/build/reports/tests/test/index.html`은 재실행 시 덮어써지는 생성 파일이므로 이 절의 날짜·코드 기준·집계를 함께 남긴다.

아래 548개 이하의 전체 검사와 관련 50개 검사는 각 증분 당시의 기록으로 보존한다.

### W3 포인트 3주문 경쟁 — 2026-10-08

2026-10-08 21:14 KST, `./gradlew :apps:commerce-api:check --console=plain -q`가 종료 코드 0으로 통과했다.

| 검사 | 실제 결과·범위 |
| --- | --- |
| 관련 테스트 | 21:08 KST 신규 1개·21:09 KST 관련 8개 스위트 48개 통과, 각 Checkstyle test 통과 |
| 전체 API 모듈 테스트 XML | **59개 스위트·548개 통과**, 실패·오류·건너뜀 0. 기존 547개 대비 포인트 경쟁 1개 증가 |
| Checkstyle·ArchUnit | Checkstyle XML 11개 보고서 위반 0, ArchitectureTest 1개 통과. 기존 규칙 유지 |
| 완료 범위 | W3-POINT-RACE-01: 확정 2·포인트 부족 1·기술 오류 0·잔액 2,000, 결제 합 8,000·거절 주문/상품 전체 행 보존. 실제 서비스·락·정책·스키마 변경 없음 |
| 검증 한계 | 첫 실행부터 Green. 독립 브랜드·상품의 3개 주문을 실제 서비스로 시작만 맞춘 검증이며 사용자 잠금 제거 비교·모든 대기 순서·HTTP 경쟁을 재현한 것은 아님 |

[포인트 경쟁 실행 기록](../week3/point-order-concurrency-tdd-log.md#실행-기록)에 준비 조건·기대값·실제 결과를 구분했다. 이후 충전·결제 경쟁의 관련 검사를 완료하고 전체 검사는 당시 보류했다가 2026-10-09 위 최종 재검사에서 확인했다.

### W3 재고 8주문 경쟁 — 2026-10-08

2026-10-08 20:58 KST, `./gradlew :apps:commerce-api:check --console=plain -q`가 종료 코드 0으로 통과했다.

| 검사 | 실제 결과·범위 |
| --- | --- |
| 관련 테스트 | 20:55 KST 신규 1개·20:56 KST 관련 5개 스위트 16개 통과, 각 Checkstyle test 통과 |
| 전체 API 모듈 테스트 XML | **58개 스위트·547개 통과**, 실패·오류·건너뜀 0. 기존 546개 대비 재고 경쟁 1개 증가 |
| Checkstyle·ArchUnit | Checkstyle XML 11개 보고서 위반 0, ArchitectureTest 1개 통과. 기존 규칙 유지 |
| 완료 범위 | W3-STOCK-RACE-01: 확정 5·재고 부족 3·기술 오류 0·재고 0, 주문별 상태와 구매자별 결제 정합성. 실제 서비스·락·정책·스키마 변경 없음 |
| 검증 한계 | 첫 실행부터 Green. 서비스 호출의 시작만 맞춘 고객 2명·주문 8개 검증이며 HTTP 경쟁·상품 잠금만의 효과·모든 실행 순서를 재현한 것은 아님 |

[재고 경쟁 실행 기록](../week3/stock-order-concurrency-tdd-log.md#실행-기록)에 준비값·보존식·대기/정리와 실제 명령을 남겼다. 이후 포인트 3개 주문 경쟁은 별도 증분으로 검증했다.

### W3 갱신 유실 대조군 — 2026-10-08

2026-10-08 20:40 KST, `./gradlew :apps:commerce-api:check --console=plain -q`가 종료 코드 0으로 통과했다.

| 검사 | 실제 결과·범위 |
| --- | --- |
| 관련 테스트 | 20:37 KST 대조군 1개, 20:38 KST 대조군·기존 주문 트랜잭션·동시성·서비스의 4개 스위트·15개 통과. 각각 Checkstyle test 통과 |
| 전체 API 모듈 테스트 XML | **57개 스위트·546개 통과**, 실패·오류·건너뜀 0. 이전 545개 대비 대조군 테스트 1개 증가 |
| Checkstyle·ArchUnit | Checkstyle XML 11개 보고서 위반 0, ArchitectureTest 1개 통과. 기존 규칙 유지 |
| 완료 범위 | W3-LOST-UPDATE-01의 갱신 유실 재현. 실제 서비스·락·정책·스키마 변경 없음 |
| 검증 한계 | 첫 실행부터 재현 기대값을 충족했으며 업무 Red를 만든 것은 아님. 실제 서비스의 재고 8주문·포인트 3주문·지정 금액 충전/결제 경쟁은 후속 범위 |

실행 명령·커밋 판정·최종 저장 상태와 자원 정리 방법은 [대조군 실행 기록](../week3/stock-lost-update-control-plan.md#실행-기록--2026-10-08)에 남겼다.

### W3 주문 저장 경계 실패·롤백 — 2026-10-08

2026-10-08 20:00 KST, 최종 코드에서 `./gradlew :apps:commerce-api:check --console=plain -q`가 종료 코드 0으로 통과했다.

| 검사 | 실제 결과·범위 |
| --- | --- |
| 관련 테스트 | 19:58 KST, OrderTransactionTest 4개와 Checkstyle test 통과 |
| 전체 API 모듈 테스트 XML | **56개 스위트·545개 통과**, 실패·오류·건너뜀0개. 기존541개 대비 신규4개 증가 |
| Checkstyle·ArchUnit | Checkstyle XML11개 보고서 위반0개, ArchitectureTest1개 통과. 기존 규칙 유지 |
| 완료 범위 | W3-ORDER-TX-01~03 및 영향받는 기존 회귀. 생산 코드·락·정책 변경 없음 |
| 검증 한계 | 테스트 JDBC 경계의 일반 SQLException 주입. 실제 DB 제약 위반·네트워크 단절·물리 commit 실패·모든 동시 실행 순서를 재현했다는 뜻은 아님 |

시험용 부모 트랜잭션 없이 실제 HTTP로 서비스 트랜잭션을 시작했다. 성공한 실제 UPDATE와 실패 직전 상태, HTTP 종료 뒤 별도 조회의 전체 원복을 구분하며 [실행 기록](../week3/order-transaction-tdd-log.md)에 방법과 실패 교정 내역을 남겼다.

### W3 주문 재확정 거절 — 2026-10-08

2026-10-08 16:19 KST, 최종 코드에서 `./gradlew :apps:commerce-api:check --console=plain -q`가 종료 코드 0으로 통과했다.

| 검사 | 실제 결과·범위 |
| --- | --- |
| 관련 테스트 | 16:17 KST, OrderTest 8개·OrderV1ApiE2ETest 40개·OrderConcurrencyIntegrationTest 7개·OrderScenarioApiE2ETest 5개, 합계60개 통과 |
| 전체 API 모듈 테스트 XML | **55개 테스트 스위트·541개 통과**, 실패·오류·건너뜀0개. 기존538개 대비 HTTP 실행 사례3개 증가이며 나머지는 승인된 계약에 맞게 변경·보강 |
| Checkstyle·ArchUnit | Checkstyle XML11개 보고서의 위반0개, ArchitectureTest1개 통과. 검사 규칙 삭제·완화 없음 |
| 완료 범위 | W3-RECONFIRM-01~06 및 영향받는 기존 회귀. 브랜드 삭제 완료 유지, 다른 W3 주문 원자성·갱신 유실·지정 경쟁 시나리오 완료를 뜻하지 않음 |
| 검증 방식 | 도메인 Red·Green, HTTP/경합 Red47개 중5개 실패 후 서비스 상태 검사 연결. 같은 주문의 시작만 맞춘 경합과 커밋 후 순차 재요청을 구분 |

Docker 미기동으로 발생한 첫 통합 실행 오류는 업무 Red와 구분했다. 고객 요청은 실제 HTTP, 관리자 삭제/재고 설정은 실제 계층·MySQL을 연결한 MockMvc다. 정확한 변경 책임·실행 명령·검증 한계는 [재확정 TDD 기록](../week3/order-reconfirmation-tdd-log.md)을 따른다.

### W3 네 번째 증분 — 2026-10-08

아래는 브랜드 삭제 범위를 완료한 시점의 과거 기록이다. 후속 주문 재확정 거절의 검증 결과와 구분한다.

2026-10-08 12:01 KST, 최종 코드에서 `./gradlew :apps:commerce-api:check --console=plain -q`가 종료 코드 0으로 통과했다.

| 검사 | 실제 결과·범위 |
| --- | --- |
| 관련 테스트·정적 검사 | 11:59 KST, 고객 API 6개·관리자 접근 9개·선후 및 경합 9개, 신규 24개와 Checkstyle main/test 통과 |
| 전체 API 모듈 테스트 XML | **55개 테스트 스위트·538개 통과**, 실패·오류·건너뜀 0개. BrandRemoval 4개 클래스의 31개 사례 포함 |
| Checkstyle·ArchUnit | Checkstyle XML 보고서 11개의 위반 0개, ArchitectureTest 1개 통과. 검사 규칙 삭제·완화 없음 |
| 완료 범위 | W3-BRAND-01~08과 영향받는 기존 API 회귀. 다른 W3 주문 정책·경쟁 과제 전체의 완료를 뜻하지 않음 |
| 검증 방식 | 고객·관리자 연결은 실제 API, 경합은 실제 서비스·MySQL. 선후 계약은 순차 6개, 동시 요청은 시작만 맞춘 3개로 구분. 잠금 구간의 테스트 개입 없음 |

실패했던 DRAFT 시각 문자열 비교는 삭제 전후 GET 전체 응답 비교로 바로잡았다. 초기에 작성한 잠금 직후 테스트 보류는 과제 조건과 맞지 않아 제거했다. 두 교정은 [실행 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증)에 남기며, 최종 통과는 이 수정본으로 다시 확인했다. 상품 bulk UPDATE 1회는 전체 SQL 1회·대량 데이터 성능 보장을 뜻하지 않는다.

### W3 세 번째 증분 — 2026-10-08

아래는 세 번째 증분 완료 시점의 과거 기록이다. 당시 남았던 04~08의 후속 검증은 네 번째 증분에서 구분한다.

2026-10-08 11:41 KST, `W3-BRAND-03` 롤백 테스트 추가 후 `./gradlew :apps:commerce-api:check --console=plain -q`가 종료 코드 0으로 통과했다. 기존 구현에서 최초 실행부터 통과해 생산 코드는 변경하지 않았다.

| 검사 | 실제 결과·범위 |
| --- | --- |
| 관련 테스트·정적 검사 | 11:39 KST, 신규 롤백 1개를 포함한 BrandRemovalTransactionTest 7개와 Checkstyle main/test가 최초 실행부터 통과 |
| 전체 API 모듈 테스트 XML | **52개 테스트 스위트·514개 통과**, 실패·오류·건너뜀 0개 |
| Checkstyle·ArchUnit | Checkstyle XML 보고서 11개의 위반 0개, ArchitectureTest 1개 통과. 검사 규칙 삭제·완화 없음 |
| 완료 범위 | W3-BRAND-01~03과 해당 회귀. 04~07 및 08의 선후 관계별 강제 재현은 완료로 표시하지 않음 |

실제 상품 변경 SQL과 브랜드 저장 SQL의 반영을 확인한 뒤 예외를 주입했고, HTTP `500 INTERNAL_ERROR`·`meta.result=FAIL`·`data` 생략과 서비스 종료 후 6개 테이블 전체 행 원복을 검증했다. [세 번째 증분 실행 기록](../week3/brand-removal-tdd-log.md#세-번째-증분--w3-brand-03-롤백-검증)을 따른다. 당시에는 W3-BRAND-04~08의 후속 검증이 남아 있었다.

### W3 두 번째 증분 — 2026-10-08

아래는 두 번째 증분 완료 시점의 과거 기록이다. W3-BRAND-03의 이후 검증 결과는 위 세 번째 증분을 따른다.

2026-10-08 11:33 KST, `W3-BRAND-02` 경계 테스트 보완 후 `./gradlew :apps:commerce-api:check --console=plain -q`가 종료 코드 0으로 통과했다. 첫 증분의 생산 코드는 변경하지 않았다.

| 검사 | 실제 결과·범위 |
| --- | --- |
| 관련 테스트·정적 검사 | 11:31 KST, BrandRemovalTransactionTest 6개와 Checkstyle main/test 통과. 신규 4개 실행 사례는 메서드 3개 중 혼합·전부 기삭제의 파라미터 사례 2개를 포함하며 최초 실행부터 통과 |
| 전체 API 모듈 테스트 XML | **52개 테스트 스위트·513개 통과**, 실패·오류·건너뜀 0개 |
| Checkstyle·ArchUnit | Checkstyle XML 보고서 11개의 위반 0개, ArchitectureTest 1개 통과. 검사 규칙 삭제·완화 없음 |
| 완료 범위 | W3-BRAND-01·02와 해당 회귀. 03~07 및 08의 선후 관계별 강제 재현은 완료로 표시하지 않음 |

실제 요청·저장 상태의 기대값과 기존 검증의 공백, 최초 실행부터 통과해 생산 코드를 변경하지 않은 이유는 [두 번째 증분 실행 기록](../week3/brand-removal-tdd-log.md#두-번째-증분--w3-brand-02-경계-검증)에 남긴다. 아래 첫 증분과 W2의 수치는 당시 실행 결과로 보존한다.

### W3 첫 증분 — 2026-10-08

아래는 첫 증분 완료 시점의 과거 기록이다. W3-BRAND-02의 이후 검증 결과는 위 두 번째 증분을 따른다.

2026-10-08 11:09 KST, `W3-BRAND-01` 구현과 호출자 트랜잭션의 `READ_COMMITTED` 명시까지 포함한 코드에서 `./gradlew :apps:commerce-api:check --console=plain -q`가 종료 코드 0으로 통과했다.

| 검사 | 실제 결과·범위 |
| --- | --- |
| 관련 테스트·정적 검사 | 새 일괄 삭제 2개·도메인 8개·관리자 HTTP 11개·브랜드 경합 3개, 합계 24개와 Checkstyle main/test 통과 |
| 전체 API 모듈 테스트 XML | **52개 테스트 스위트·509개 통과**, 실패·오류·건너뜀 0개 |
| Checkstyle·ArchUnit | API check에 연결된 정적 검사와 ArchitectureTest 통과. 검사 규칙 삭제·완화 없음 |
| 완료 범위 | W3-BRAND-01과 첫 증분 회귀. W3-BRAND-02 전체·03~07 및 08의 선후 관계별 강제 재현은 완료로 표시하지 않음 |

개별 실행 수를 합산하지 않고 API 모듈의 전체 테스트 XML로 집계했다. API check에 다른 모듈의 정적 검사가 연결돼 있어도 그 모듈의 별도 통합 테스트까지 실행했다는 뜻은 아니다. Red·Green·Refactor와 구현 선택은 [W3 실행 기록](../week3/brand-removal-tdd-log.md)에 남긴다.

### W2 최종 검사 — 2026-09-18

아래는 W2 계약 기준의 과거 검사 기록이며, 당시 수치와 증거를 보존한다.

이전 448개 검사는 관리자 접근 지원 설정·연결 시나리오·다른 모듈 정적 검사 요구를 빠뜨렸다. 과제 피드백의 세 항목을 보완하고 아래 결과로 완료를 다시 확인했다. 이전 완료 판단을 그대로 재사용하지 않는다.

2026-09-18 14:22 KST, 마지막 CSRF 오류 본문 검증까지 포함한 최종 코드에서 아래 명령이 종료 코드 0으로 성공했다.

```shell
./gradlew :apps:commerce-api:check --console=plain -q
```

| 검사 | 실제 결과 |
| --- | --- |
| 전체 테스트 XML 합산 | **51개 테스트 스위트·506개 통과**, 실패·오류·건너뜀 0개 |
| 관리자 접근 보완 | 실제 controller·application·JPA·DB를 사용한 MockMvc 57개. ADMIN 허용, 일반·미식별403, 유효한 CSRF로 권한 거절, CSRF 누락/불일치403, 헤더 위조 거절 |
| 충전·주문 연결 보완 | 초기 잔액0→충전 API10000→복수 품목 주문·확정7000→내 주문 목록·상세·잔액3000 조회 1개 |
| 실제 HTTP API 완료 | **25/25개: 고객 C01~C12·관리자 A01~A13** |
| 요구사항 사례 | **API-01~24 완료**, 각 행에 기능별 테스트·실행 기록 연결 |
| 운영 스키마 | 6개 수동 SQL의 실제 MySQL 적용·JPA validate·저장·제약 검증. 기존 브랜드 11개와 전체 스키마 31개, 합계 42개 통과 |
| 동시성·롤백 | 브랜드 경합 3개·주문 경합 7개, 좋아요 중복·잔액 경쟁과 실제 SQL 반영 후 롤백 검증 포함 |
| 기존 회귀 | 기존 도메인·C01·Example·고객 fixture 계약 유지. 관리자 요청자 입력과 400/404는 사용자가 요청한 과제 지원 방식·403으로 교정하고 업무·DB 기대값은 유지 |
| Checkstyle | 9개 Java 모듈의 main/test 및 jpa·redis·kafka testFixtures까지 21개 태스크 연결. 소스가 있는 11개 XML 보고서의 위반 합계0, 소스 없는 태스크는 NO-SOURCE |
| ArchUnit | ArchitectureTest 1개 통과. Layer-first 계층 의존 규칙 유지 |
| 문서·변경 검토 | AGENTS와 week2 문서의 로컬 링크·앵커·코드 블록 검사 오류 0개, git diff --check 통과 |

테스트 수는 `apps/commerce-api/build/test-results/test/TEST-*.xml`의 합산이며 개별 증분 실행 수를 더하지 않았다. Checkstyle은 각 모듈의 `build/reports/checkstyle/*.xml`을 모두 확인했다. API check는 다른 모듈의 정적 검사를 포함하며 그 모듈의 별도 통합 테스트까지 실행했다는 뜻은 아니다. 과거 로그의 111·167·448개 결과는 당시 기준선으로 보존한다.

이번 보완의 실제 Red·Green과 변경 이유는 [관리자 Security](commerce-admin-security-log.md)·[전체 모듈 Checkstyle](commerce-checkstyle-scope-log.md)·[충전부터 주문 조회](commerce-tdd-order-log.md#과제-피드백-충전부터-주문-조회까지)에 기록했다.

선행 기능의 실제 Red·Green·Refactor는 [브랜드 HTTP](commerce-tdd-admin-brand-http-log.md), [상품](commerce-tdd-product-log.md), [포인트·좋아요](commerce-tdd-point-like-log.md), [주문](commerce-tdd-order-log.md), [전체 스키마](commerce-tdd-schema-log.md) 기록에 구분한다. 기존 구현에서 바로 통과한 추가 경계 사례는 요구사항 검증으로 기록했다. 운영 SQL 적용 방법과 로컬 자동 DDL 설정의 차이는 [스키마 적용 안내](commerce-schema-operations.md)를 따른다.

## 정책 확인

P00~P16의 확정 내용을 따른다. P15는 사용자의 과제 피드백 수정 요청으로 관리자 접근 방식과 검증 범위를 바로잡은 계약이다. 2026-09-18 추천 묶음 승인과 2026-10-08 후속 P16 승인으로 아래 정책을 확인했다. 이 표의 `confirmed`는 정책·문서 반영 상태이며, API·DB·동시성 구현의 `done`과 다르다. 새로운 완료 증거가 없는 구현 항목은 pending을 유지한다.

| 항목 | 확정한 범위 | 정책 상태 | 결정·문서 반영 증거 |
| --- | --- | --- | --- |
| 금액·포인트 표현 | 원 단위 정수 Long/BIGINT, Long.MAX_VALUE 상한, 덧셈·곱셈 범위 초과 검출 | confirmed | [P08과 비용 비교](commerce-policy-decisions.md#금액-자료형의-공식-자료-확인과-비용-비교) |
| 나머지 API 계약 | 전체 응답·상태·오류·필터·정렬, 엄격 JSON, C02·C03 공개 조회와 헤더 무시, C06/관리자 필터의 외부 userId, 405·415·406 | confirmed | [P12](commerce-policy-decisions.md#구현-전에-확인할-정책-선택) · [API 계약](commerce-api-contract.md) |
| 구체 잠금 설계 | READ_COMMITTED, 주문→사용자→브랜드 ID 오름차순→상품 ID 오름차순. 상품 등록·변경/브랜드 삭제의 브랜드 잠금 공유, 좋아요의 상품 잠금. 3초·자동 재시도 없음·교착/타임아웃409 | confirmed | [P13](commerce-policy-decisions.md#구현-전에-확인할-정책-선택) |
| 상품명 길이 | 브랜드명·상품명 모두 앞뒤 공백 제거 후 Unicode 코드 포인트 1~100자 | confirmed | [P02](commerce-policy-decisions.md#구현-전에-확인할-정책-선택) |
| 사용자 초기 데이터 | alice→1/CUSTOMER, bob→2/CUSTOMER, admin→3/ADMIN, 초기 잔액 0 | confirmed | [P03](commerce-policy-decisions.md#구현-전에-확인할-정책-선택). 실제 DB 초기화는 아래 구현 항목으로 별도 검증 |
| 오류 처리 순서 | 관리자 권한 우선과 승인된 공통 처리 순서·오류 매핑. 기존 Example 계약 유지 | confirmed | [공통 계약](commerce-api-contract.md#공통-입력식별응답) · [오류 계약](commerce-api-contract.md#대표-오류와-상태-보존-제안) |
| 구현 순서 | JPA 유지, 기능 구현 후 나머지 운영 수동 DDL 작성·검증 | confirmed | [P14](commerce-policy-decisions.md#구현-전에-확인할-정책-선택). 기존 브랜드 SQL 보존 |
| 과제 관리자 접근·검증 보완 | 고객 fixture와 관리자 Security 역할 분리, 미식별403·쓰기 CSRF, 연결 시나리오와 모든 Java 모듈 lint | confirmed | [P15](commerce-policy-decisions.md#구현-전에-확인할-정책-선택) · [Security 기록](commerce-admin-security-log.md) · [Checkstyle 기록](commerce-checkstyle-scope-log.md) |
| W3 브랜드 일괄 삭제 | 브랜드·연결 미삭제 상품 soft delete, 비관적 락·단일 트랜잭션·전체 롤백, 다른 대상·기존 주문 보존 | confirmed | [W3 요구·합의](commerce-policy-decisions.md#w3-브랜드상품-일괄-논리-삭제). P13을 유지하는 ID별 잠금·bulk UPDATE 구현을 채택했으며, 첫 증분 검증과 전체 계약 완료는 구분 |
| W3 주문 재확정 거절 | 본인 CONFIRMED 재확정은409·최초 결과와 차감 보존, 타인404 우선·GET 유지·실패한 DRAFT 재시도 허용 | confirmed | [P16 요구·합의](commerce-policy-decisions.md#w3-주문-재확정-거절). W2의 재확정200 반환을 대체하며 충전·주문 생성의 반복 성공 정책은 유지 |

## API 25개 완료 증거

경로는 [고객·관리자 계약표](commerce-api-contract.md#고객-api-계약표)를 따른다. 각 행의 `done`은 HTTP부터 필요한 저장 처리까지의 검증을 뜻한다. C01의 기존 [application 검증](commerce-tdd-brand-query-log.md)은 선행 증거이며 HTTP 완료 증거는 아니다. 모든 A01~A13에는 기존 업무 검증과 함께 [AdminSecurityApiTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/commerce/AdminSecurityApiTest.java)의 실제 관리자·일반·미식별 요청 검증을 적용했다.

아래 기존 증거는 W2 당시 계약 기준이다. A05의 계약 변경은 [W3 브랜드 완료 증거](#w3-브랜드상품-일괄-삭제의-완료-증거), C10의 계약 변경은 [W3 재확정 증거](#w3-주문-재확정-거절)로 구분한다. 무관한 API의 기존 완료 증거를 취소하지 않는다.

| ID | Method / Path | 연결 TDD | 상태 | 완료 증거: 테스트·실행 기록 |
| --- | --- | --- | --- | --- |
| C01 | `GET /api/v1/brands/{brandId}` | API-01·16 | done | [BrandV1ApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/BrandV1ApiE2ETest.java) BRAND-HTTP-01~06: 15개 사례, [실제 Red·Green·Refactor](commerce-tdd-brand-http-log.md) |
| C02 | `GET /api/v1/products` | API-02~04·17 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| C03 | `GET /api/v1/products/{productId}` | API-01·17·19 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| C04 | `POST /api/v1/products/{productId}/likes` | API-05·06·21 | done | [좋아요 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/like/LikeApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C05 | `DELETE /api/v1/products/{productId}/likes` | API-05·06·21 | done | [좋아요 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/like/LikeApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C06 | `GET /api/v1/users/{userId}/likes` | API-04~06·15·21 | done | [좋아요 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/like/LikeApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C07 | `POST /api/v1/points/charge` | API-07·08·21·23 | done | [포인트 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/point/PointApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C08 | `GET /api/v1/points` | API-07·21 | done | [포인트 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/point/PointApiE2ETest.java) · [실행 기록](commerce-tdd-point-like-log.md) |
| C09 | `POST /api/v1/orders` | API-09~11·21·22 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |
| C10 | `POST /api/v1/orders/{orderId}/confirm` | API-11~15·21·23·24 · W3-RECONFIRM-01..06 | done (P16 반영) | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [W2 기록](commerce-tdd-order-log.md) · [W3 재확정409·보존 검증](../week3/order-reconfirmation-tdd-log.md) |
| C11 | `GET /api/v1/orders` | API-04·15·20·21 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |
| C12 | `GET /api/v1/orders/{orderId}` | API-14·15·20·21 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |
| A01 | `GET /api-admin/v1/brands` | API-04·16·21 | done | [관리자 브랜드 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) · [실행 기록](commerce-tdd-admin-brand-http-log.md) |
| A02 | `POST /api-admin/v1/brands` | API-16·21 | done | [관리자 브랜드 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) · [실행 기록](commerce-tdd-admin-brand-http-log.md) |
| A03 | `GET /api-admin/v1/brands/{brandId}` | API-16·21 | done | [관리자 브랜드 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) · [실행 기록](commerce-tdd-admin-brand-http-log.md) |
| A04 | `PUT /api-admin/v1/brands/{brandId}` | API-16·18·21 | done | [관리자 브랜드 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) · [실행 기록](commerce-tdd-admin-brand-http-log.md) |
| A05 | `DELETE /api-admin/v1/brands/{brandId}` | API-16·21·24 · W3-BRAND-01..08 | W2 done / W3 done | [W2 실행 기록](commerce-tdd-admin-brand-http-log.md)은 과거 거절 계약의 증거다. 새 일괄 삭제 계약의 정상·경계·롤백·연결·경합은 [W3 실행 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증)과 위 별도 표 참조 |
| A06 | `GET /api-admin/v1/products` | API-02·04·17·21 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A07 | `POST /api-admin/v1/products` | API-17·21·24 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A08 | `GET /api-admin/v1/products/{productId}` | API-17·21 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A09 | `PUT /api-admin/v1/products/{productId}` | API-17·18·21 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A10 | `DELETE /api-admin/v1/products/{productId}` | API-17·21·24 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A11 | `PUT /api-admin/v1/products/{productId}/stock` | API-18·19·21·23 | done | [상품 HTTP·경계](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductApiE2ETest.java) · [집계·페이지](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [실행 기록](commerce-tdd-product-log.md) |
| A12 | `GET /api-admin/v1/orders` | API-04·20·21 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |
| A13 | `GET /api-admin/v1/orders/{orderId}` | API-20·21 | done | [주문 HTTP](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) · [실행 기록](commerce-tdd-order-log.md) |

## TDD API-01~24 완료 증거

이 표는 [TDD 기대값](commerce-tdd-plan.md#api규칙별-테스트-기대값)의 색인이다. fixture 값·정렬 순서·응답·DB 기대값을 이 요약으로 대체하지 않는다. 후속 승인한 금액·HTTP·잠금 계약의 기대값으로 실행하며 완료 여부는 실제 증거로 갱신한다.

기존 `API-16`의 상품 존재 시 삭제 거절과 `API-24`의 브랜드 경합 결과는 W2 기록이다. W3의 변경·추가 검증에는 별도 `W3-BRAND` ID를 사용한다. `API-14·23`의 재확정 성공 반환은 P16으로 변경했으며 `W3-RECONFIRM` ID로 새 검증을 추적한다.

| 사례 | 검증 범위 | 상태 | 완료 증거: 테스트·실행 기록 |
| --- | --- | --- | --- |
| API-01 | 브랜드·상품 상세 정상/없음/삭제, 조회 상태 보존 | done | [상품 기록](commerce-tdd-product-log.md) · [C01 기록](commerce-tdd-brand-http-log.md) |
| API-02 | 상품 세 정렬·동률 순서·관리자 삭제 행 포함 | done | [상품 기록](commerce-tdd-product-log.md) |
| API-03 | 브랜드 필터·정렬 후 페이지·전체 건수·빈 결과 | done | [상품 기록](commerce-tdd-product-log.md) |
| API-04 | 모든 목록의 잘못된 필터·정렬·페이지 입력 | done | 목록별 형식 검증: [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) · [상품 기록](commerce-tdd-product-log.md) · [포인트·좋아요 기록](commerce-tdd-point-like-log.md) · [주문 기록](commerce-tdd-order-log.md) |
| API-05 | 좋아요 등록·중복·취소·재취소·재등록과 실제 관계 수 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| API-06 | 상품 삭제 후 좋아요 조회 제외·신규 거절·본인 관계 물리 삭제 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| API-07 | 충전·반복 성공의 신규 반영·잔액 조회 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| API-08 | 충전 입력 오류·합산 범위 초과·잔액 보존 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| API-09 | DRAFT·항목·금액 저장, 차감 없음, 반복 생성은 새 주문 | done | [주문 기록](commerce-tdd-order-log.md) |
| API-10 | 주문 생성 입력·대상 오류와 부분 저장 방지 | done | [주문 기록](commerce-tdd-order-log.md) · OrderScenarioApiE2ETest의 없는/삭제 상품·삭제 브랜드 3개 경우 |
| API-11 | 중복 상품 수량 합산·상한·합산 수량으로 확정 | done | [주문 기록](commerce-tdd-order-log.md) · OrderScenarioApiE2ETest의 합산 수량 5·재고 4 실패 후 5 성공 |
| API-12 | 확정 시 주문·모든 재고·잔액의 원자적 반영 | done | [주문 기록](commerce-tdd-order-log.md) · OrderScenarioApiE2ETest의 4000원/재고 3·3/잔액 6000 |
| API-13 | 재고/잔액 부족·삭제·잘못된 저장 수량과 실패 상태 보존 | done | [주문 기록](commerce-tdd-order-log.md) |
| API-14 | 생성 스냅샷·재확정409·최초 결과 보존·상품/브랜드 삭제 후 GET | done (P16 반영) | [W2 기록](commerce-tdd-order-log.md)은 당시 재요청200의 증거. [W3 재확정 기록](../week3/order-reconfirmation-tdd-log.md)의 새409 계약·6개 테이블 불변 검증으로 대체 |
| API-15 | 본인 좋아요·주문 접근, 타인/없는 대상 구분과 정보 비노출 | done | [포인트·좋아요 기록](commerce-tdd-point-like-log.md) · [주문 기록](commerce-tdd-order-log.md) |
| API-16 | W2 브랜드 CRUD·고객 반영·미삭제 상품 삭제 방지·재삭제 보존 → W3 일괄 삭제 | W2 done / W3 done | [W2 브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md)은 당시 계약. 새 삭제 계약은 W3-BRAND-01~06의 [검증 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증) 참조 |
| API-17 | 상품 CRUD·브랜드 유지·고객 반영·논리 삭제·관계 보존 | done | [상품 기록](commerce-tdd-product-log.md) · [포인트·좋아요 기록](commerce-tdd-point-like-log.md) · [주문 기록](commerce-tdd-order-log.md) |
| API-18 | 삭제 대상 변경 거절·이름/가격 입력 오류와 상태 보존 | done | [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) · [상품 기록](commerce-tdd-product-log.md) |
| API-19 | 최종 재고 설정·0·음수·Integer 경계·고객 조회 반영 | done | [상품 기록](commerce-tdd-product-log.md) |
| API-20 | 관리자 주문 상세·필터와 고객 조회의 범위 차이 | done | [주문 기록](commerce-tdd-order-log.md) |
| API-21 | 고객 fixture·관리자 Security 권한 우선·CSRF·상태 보존 | done | [Security 보완](commerce-admin-security-log.md) · [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) · [포인트·좋아요 기록](commerce-tdd-point-like-log.md) · [주문 기록](commerce-tdd-order-log.md) |
| API-22 | 단가×수량·소계 합의 금액 범위 초과와 저장 방지 | done | [주문 기록](commerce-tdd-order-log.md) |
| API-23 | 같은 주문·재고·잔액 경쟁, 충전/재고 설정과 확정 경합 | done | [W2 주문 기록](commerce-tdd-order-log.md)·[재확정](../week3/order-reconfirmation-tdd-log.md)과 별도로 [재고 8주문](../week3/stock-order-concurrency-tdd-log.md)의 5성공·3품절·재고0, [포인트 3주문](../week3/point-order-concurrency-tdd-log.md)의 2성공·1잔액부족·잔액2,000·거절상품보존, [충전/결제](../week3/point-charge-order-concurrency-tdd-log.md)의 두 요청 성공·잔액5,000 검증. 2026-10-09 전체 549개 재검사 통과. [대조군](../week3/stock-lost-update-control-plan.md)은 원인 재현의 별도 증거 |
| API-24 | W2 브랜드 삭제/상품 등록, 상품 삭제/주문 확정 경합 → W3 일괄 삭제 관련 경합 | W2 done / W3 done | [W2 브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md) · [주문 기록](commerce-tdd-order-log.md)은 기존 증거. W3-BRAND-07·08의 순차 선후 결과와 시작만 맞춘 경합은 [후속 검증 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증) 참조 |

## 통합·운영·최종 회귀 완료 증거

아래 모든 항목은 W2의 최종 전체 검사에 포함했다. W3 브랜드 삭제의 새로운 롤백·잠금·연결 검증은 위 W3 별도 표에서 완료·미완료를 관리하며, 아래 과거 증거만으로 완료 처리하지 않는다.

| 항목 | 완료 확인 범위 | 상태 | 완료 증거: 파일·실행 기록 |
| --- | --- | --- | --- |
| 운영 DDL·스키마 | 6개 테이블의 수동 DDL, FK·유일 제약·논리 삭제·인덱스·자료형·예약어 인용. 빈 MySQL에 실제 적용 | done | [전체 스키마 42개 검증](commerce-tdd-schema-log.md) · [적용 순서·재실행 안내](commerce-schema-operations.md) |
| 초기 fixture | 단일 외부 ID·역할 매핑과 DB ID 1/2/3 연결, 초기 잔액 0, 재실행 시 기존 잔액·감사 시각 보존 | done | [FixtureUserInitializerIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/user/FixtureUserInitializerIntegrationTest.java) · [포인트 기록](commerce-tdd-point-like-log.md) |
| 실제 조회 계약 | 재고 0 포함·삭제 제외·브랜드 구분, 실제 관계 집계·정렬·필터 후 페이지·전체 건수 | done | [ProductQueryApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/product/ProductQueryApiE2ETest.java) · [상품 기록](commerce-tdd-product-log.md) |
| 도메인 연결 | STOCK-06, 상품의 재고 소유, 사용자 잔액·주문 상태·스냅샷, 검증 후 변경 및 실패 상태 보존 | done | [상품](commerce-tdd-product-log.md) · [포인트](commerce-tdd-point-like-log.md) · [주문](commerce-tdd-order-log.md) |
| 잠금·동시성 | READ_COMMITTED·정해진 잠금 순서·3초 타임아웃·자동 재시도 없음·409. API-23·24와 좋아요 중복의 실제 DB 경합 | done | [브랜드 경합·실제 타임아웃·교착](commerce-tdd-admin-brand-http-log.md) · [주문 경합 7개](commerce-tdd-order-log.md) · [동시 좋아요](commerce-tdd-point-like-log.md) |
| 트랜잭션·롤백 | 주문·항목·재고·잔액의 부분 반영 없음. 실제 SQL flush 후 호출자 트랜잭션 실패와 전체 상태 비교 | done | [OrderServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderServiceIntegrationTest.java) · [좋아요 롤백](commerce-tdd-point-like-log.md) |
| 재요청 | 좋아요/취소/재삭제는 추가 변경 없음, 충전/주문 생성은 성공마다 새 반영, 확정은 한 번만 차감 | done | [브랜드](commerce-tdd-admin-brand-http-log.md) · [상품](commerce-tdd-product-log.md) · [포인트·좋아요](commerce-tdd-point-like-log.md) · [주문](commerce-tdd-order-log.md) |
| HTTP·권한 경계 | 25개 API의 입출력·오류, 관리자 Security·CSRF, 본인 접근, 숫자·엄격 JSON·프레임워크 오류. 충전부터 주문 조회 연결과 기존 Example 계약 유지 | done | [API별 증거](#api-25개-완료-증거) · [공통 HTTP·JSON 보완](commerce-tdd-admin-brand-http-log.md) |
| 전체 회귀 | 최종 코드의 :apps:commerce-api:check 성공. 실패·오류·건너뜀 0개 | done | [506개 최종 검사](#최신-통합-검사) |
| Checkstyle·ArchUnit | 전체 Java 모듈 main/test/testFixtures 위반0·계층 의존 검사 통과. 규칙 삭제/완화 없음 | done | [최종 검사](#최신-통합-검사) |
| 문서·코드 일치 | 정책→API→TDD→구현·실행 기록 대조, 로컬 링크·앵커·diff 확인. 운영 적용·초기화 방법 기록 | done | [전체 설계](commerce-erd-draft.md) · [TDD 계획](commerce-tdd-plan.md) · [운영 안내](commerce-schema-operations.md) |
| 최종 범위 대조 | W2 API 25행·TDD 24행·통합 12행의 완료 증거 확보 | done | [W2 통합 검사](#최신-통합-검사). 당시 과제 피드백의 세 보완과 최종 회귀 완료. W3 브랜드 삭제의 추가 완료 증거는 위 별도 표 참조 |

동시 실패의 상태 보존은 다른 요청의 성공 효과를 취소하는 뜻이 아니다. 단독 실패는 전후 값 동일, 경합 실패는 실패 요청의 추가 효과 없음과 전체 최종 상태를 검증한다. 예를 들어 다른 조건이 충족된 상태에서 재고 5인 상품에 두 주문이 각 4개 확정을 요청하면 성공 1건·최종 재고 1·실패 주문 DRAFT를 확인한다.

전체 설계의 버드뷰·계층 의존·도메인 관계·세 대표 흐름은 [하나의 설계 문서](commerce-erd-draft.md)에 유지한다. 기존 테스트와 역사적 로그를 보존하며, 최종 미완료 항목이 있으면 이유와 다음 행동을 증거 칸에 남긴다.
