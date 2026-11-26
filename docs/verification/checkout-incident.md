# 결제 사고 진단·동일 주문 복구 검증 보고서 (W10)

계약의 원본은 [결제 사고 관측·복구 설계](../system-design/features/checkout-incident.md)와 [PG fixture W10 절](../system-design/features/pg-simulator/fixture-contract.md#w10-success-저장-후-생성-응답-유실)이다. 이 보고서는 health가 UP인 동안 PG 성공 응답 유실로 UNKNOWN이 된 주문을 진단하고, 새 결제 없이 같은 주문을 복구한 실제 증거와 검증 범위를 설명한다. 통제된 연결 테스트의 실행 결과이며 실제 운영 사고를 처리한 기록은 아니다. 핵심 수치와 필요한 원문 발췌를 본문에 담아 로컬 증거 폴더를 열지 않아도 판단 근거를 읽을 수 있게 했다. 따라 하는 절차는 W10 runbook(`docs/runbook/week10.md`)에 있다.

주 증거는 **2026-11-26 최종 회귀에서 실행한 `20261126T021136Z-bd52918d`**다. 11월 25일 관측 원문 보존 보완을 포함한 코드의 실제 결과이며, 아래 표·응답·로그는 모두 이 실행을 기준으로 한다. 11월 23일 과거 원문은 별도로 보존하고 최신 값으로 덮어쓰지 않았다.

기능별 검증 범위·남은 위험과 누적/보완 검사 결과는 `docs/verification/week10-verification.md`로 분리했다.

## 1. 기준 실행

|항목|값|
|---|---|
|주 증거 run|`20261126T021136Z-bd52918d` — 단일 `./gradlew check --continue --rerun-tasks --no-build-cache`(2026-11-26 10:57:35~11:14:14 KST, 종료코드 0) 안의 `:apps:commerce-api:checkoutIncidentTest` 1건 통과|
|실행 코드 기준|run `metadata.json`: HEAD `c3fb22c` + tracked diff 74,062바이트(SHA-256 `dce9cf3d4699f32278c01b665a7391fd477fc831a951412dafdb2b8975c6411a`) + untracked `CheckoutIncidentObservationTest.java`(`f259da1adbf58520dd9187cd6c2ebbfe2e34d6a301188324c88eea2c72469e1e`). 실행 전 별도 기록 및 실행 뒤 코드 사본과 일치|
|제출 원문|[실행별 원문과 hash 목록](evidence/20261126T021136Z-bd52918d/manifest.json). 원문55파일은 `docs/verification/evidence/20261126T021136Z-bd52918d/`에 원래 구조·바이트 그대로 두고 manifest1개를 추가했다. 자식 로그·전체 검사 XML/로그는 로컬 `/tmp/w10-final-regression-20261126-5f5wcZeE/`에 별도 보존하며 필요한 로그 발췌는 본문에 담았다.|
|커밋 연결|실행 시 변경된 코드3파일이 커밋 `31db770`의 파일과 바이트 단위로 같음을 확인했다. 이후 실패 상태 보존 보완은 6절로 구분한다. metadata의 실행 당시 HEAD/diff/untracked 기록은 그대로 보존했다.|
|시각 구분|실제 시작 `2026-11-26T02:11:36.502880Z`. 최초 confirmedAt만 합성 시각 `2026-11-23T01:00:00Z`를 사용. 실행 날짜를 과거로 바꾼 것이 아님|

### 1.1 환경·입력과 진행 방법

전용 MySQL·Redis 테스트 컨테이너와 새 로컬 PG fixture를 사용했다. API는 loopback 업무 port 62101·management port 62109에서 실행했고, 실제 Kafka에 실행별 topic/group을 생성해 streamer와 batch를 별도 JVM으로 실행했다. 단계 사이 DB·PG를 초기화하지 않았다. 자동 Outbox relay는 끄고 기존 relay의 실제 전송 경로를 테스트가 주문별로 호출해 진행 시점을 통제했다. PG의 자동 callback 전송은 없으며 복구 후 반복 적용 검사에서만 명시적으로 보냈다.

|입력|실제 준비 값·목적|
|---|---|
|상품|productId 1(A): 1,000원×2 + productId 2(B): 2,000원×1, 주문당 저장금액 4,000원. 시작 재고 각각 100|
|주문 집합|BASELINE orderId 1~10, FAULT orderId 11~20. 실제 주문 API가 발급한 ID이며 두 집합은 겹치지 않음|
|사고 대상 U|FAULT의 첫 세 주문 11·12·13. confirm 전에 실제 orderId·attemptKey·providerOrderId 명단 고정|
|보존 대조군|userId 1의 포인트 10,000·충전 이력 1개, 보유 쿠폰 1개·캠페인 issuedCount 1. userId 2는 소유권 거절 검사에 사용|
|업무 시각|최초 confirmedAt만 `2026-11-23T01:00:00Z`로 고정. 집계 주는 `[2026-11-23, 2026-11-30)`. 증거·PG·로그 시각은 실제 UTC이며 고정 업무 시각과 구분|
|실제 검증 경로|주문·confirm·본인 reconcile·callback·랭킹은 실제 HTTP, 상태 확인은 실제 DB. 전달은 실제 Outbox→Kafka→streamer, 집계는 새 snapshot의 실제 batch 실행|

출처: `run/baseline/roster.json`, `run/baseline/preserved-before.json`, `run/resources/001-kafka.json`·`004-api.json`, `run/metadata.json`. 이하 `run/`은 §1의 제출 원문 디렉터리, `children/`는 로컬 보존 위치의 `after/children/`를 뜻한다. 전체 검사 결과는 같은 로컬 위치의 `after/apps/`에 있다. 입력·순서·기대값은 기준 설계 §2·§8을 따르며, 아래는 그 설계에 대한 실제 관찰이다.

## 2. 사고 실행 결과와 입증 범위

|단계|실제 값(같은 run)|입증하는 것|
|---|---|---|
|BASELINE|B10 확정 10·UNKNOWN 0·PG SUCCESS 10, 거래·CREATE 10, 재고 80/90, Outbox 10, 활동 20(주문별 A2/B1), 예약 20, gauge 0.0|정상 경로(PENDING → PG 변경 → 본인 reconcile)와 전달·활동이 기준값으로 맞는다|
|FAULT|F 확정 7·UNKNOWN 3·PG SUCCESS 10, 누적 거래·CREATE 20, 재고 60/80, Outbox 17, 활동 34(U 없음), 예약 40, gauge 3.0, 장애 plan 등록 3·U별 CREATE 1·`CLOSED_EARLY`·같은 거래 키|U3에서만 SUCCESS 저장 후 응답이 유실됐고 커머스는 UNKNOWN·예약 유지로 남았다|
|진단|U 11·12·13 모두 PG 처리 중 배제(권위 조회 SUCCESS)·응답 유실 지지·적용 TX 실패 배제, 진단 실패 0, `proceed=true`, fault 14파일 봉인|세 가설 중 응답 유실만 이 실행의 증거와 맞는다. 이 실행이 포착한 범위에 한한다|
|RECOVERY|F 확정 10·UNKNOWN 0, F의 동일 PG 거래 10개를 유지하며 전체 B+F의 거래 키 20개도 FAULT와 동일, CREATE 20 유지(추가 0), plan 등록 3 유지, 반복 reconcile·callback 2회·다른 사용자 404 뒤 변화 없음|새 결제 없이 같은 주문·같은 거래로 복구했다|
|후속|U3 relay APPLIED, 재발행 `ALREADY_PROCESSED`, 활동 40·수량 40/20, 예약 40(크기:감소 2:2/1:1), 반환·차감 0, 포인트·쿠폰 값 4시점 동일, 확정 시각 20건 `2026-11-23 01:00:00`, snapshot `weekly-20261123-r1` input 40·COMPLETED/PUBLISHED, API A 40점 1위·B 20점 2위|복구가 기존 전달·활동·랭킹 경로로 이어지고 부수 효과가 없다|
|정리|`releaseCompleted=true`·`kept=[]`, streamer·batch 자식·PG 종료|소유 자원만 정리됐다|

직접 관측: 세 단계 모두 실제 `/actuator/health` UP과 `/actuator/prometheus`의 `commerce_payment_unknown` 0.0→3.0→0.0을 수신했다. 각 요청의 본문·HTTP 상태·수신 시각을 assertion 전에 저장했다. 이 실행에서 Grafana 화면 관찰은 하지 않았다.

### 2.1 health 정상과 UNKNOWN 증가를 함께 관찰

각 단계의 업무 처리가 안정된 뒤 실제 management endpoint를 직접 GET했다. 모든 단계의 health 본문은 다음과 같았다.

```json
{"status":"UP","groups":["liveness","readiness"]}
```

|단계|health 수신 시각(UTC)|Prometheus 수신 시각(UTC)|HTTP·health|Prometheus 원문 표본|
|---|---|---|---|---|
|정상|`2026-11-26T02:12:08.913242Z`|`2026-11-26T02:12:08.952832Z`|200/200·UP|`commerce_payment_unknown{application="commerce-api"} 0.0`|
|사고|`2026-11-26T02:12:11.535481Z`|`2026-11-26T02:12:11.553764Z`|200/200·UP|`commerce_payment_unknown{application="commerce-api"} 3.0`|
|복구|`2026-11-26T02:12:16.561710Z`|`2026-11-26T02:12:16.579564Z`|200/200·UP|`commerce_payment_unknown{application="commerce-api"} 0.0`|

출처: 각 단계의 `health-response.txt`·`prometheus-response.txt`와 `health-response.json`·`prometheus-response.json`. 각 JSON에 HTTP 상태·수신 시각·본문 존재 여부가 저장됐고, 기존 결합 원문 `direct-observation.txt`도 함께 보존됐다.

이 증거는 **health UP이어도 UNKNOWN 3건이 존재한다**는 것을 보여준다. gauge는 전용 DB 전체의 UNKNOWN만 세며 PENDING을 포함하지 않는다. 따라서 gauge 0만으로 결제 전체 완료를 주장하지 않고 아래 주문·거래·후속 효과를 함께 확인했다. 직접 수집 원문이 0→3→0의 근거이며 Grafana의 전체 구간 캡처를 근거로 제시하지 않는다.

### 2.2 사고 전 명단과 같은 거래의 복구 연결

다음은 사전 명단·장애 시점 PG 거래·복구 주문의 대응표다. 세 주문 모두 저장금액 4,000원이다.

|orderId|providerOrderId|confirm 전 고정 attemptKey|사고 시 PG SUCCESS 거래 키 = 복구 후 내부 거래 키|
|---|---|---|---|
|11|`LP-ORD-0000000011`|`db9cb5ce-52ac-38b4-9c66-04734bbc83c9`|`7059b804-567f-4ba9-8889-b34de91cd25e`|
|12|`LP-ORD-0000000012`|`27a70364-0795-3a42-b2dc-e7817251a485`|`caa46b03-a14f-4c84-8208-6d6ae17657d1`|
|13|`LP-ORD-0000000013`|`cbf205d0-7a33-3b44-9e5e-a9286c7fef55`|`8cef712e-40f6-4bb5-8319-838e241e54ff`|

사고 시점에는 세 주문의 내부 `transactionKey`가 모두 null·결제 UNKNOWN·주문 PAYMENT_PENDING이었고, PG에는 위 SUCCESS 거래가 존재했다. 복구 후 같은 orderId·attemptKey가 유지되면서 내부 transactionKey가 위 거래에 연결되고 CONFIRMED/SUCCESS로 바뀌었다. 장애 집합 전체 ID도 11~20으로 유지됐다.

출처: `run/baseline/roster.json`, `run/fault/orders.json`·`pg-state.json`, `run/recovery/orders.json`·`pg-state.json`. FAULT와 RECOVERY의 PG 거래 목록 20개는 키·주문·금액·상태가 동일했다. 전체 CREATE는 20으로 유지됐고, 복구 시작 이후 journal에도 CREATE가 없었다. 새 주문으로 성공 건수를 채우거나 외부 거래를 다시 생성한 결과가 아니다.

### 2.3 복구 전에 세 가설을 구분한 근거

PG 진단 조회는 commerce reconcile 대신 PG의 기존 계정·주문 조회 HTTP 경로를 사용했다. 내부 결과를 적용하지 않는 읽기이며, 조회 전후 DB의 UNKNOWN·예약·Outbox가 같은지도 검사했다. 세 대상 모두 HTTP 200·단일 SUCCESS 거래·금액 4,000원이었고, 주문 11의 실제 응답 본문은 다음과 같다.

```json
[{"transactionKey":"7059b804-567f-4ba9-8889-b34de91cd25e","orderId":"LP-ORD-0000000011","amount":4000,"status":"SUCCESS"}]
```

출처: `run/fault/u-lookups.json`. 아래는 `run/fault/diagnostic-log.txt`에서 주문 11의 생성 호출과 반영 TX에 해당하는 행만 원문 그대로 발췌한 것이다.

```text
PAYMENT_PG_START {"event":"PAYMENT_PG_START","at":"2026-11-26T02:12:09.449440Z","callId":"44f7f0e4-a0f2-47dc-bfb8-e6ceb5d4f73a","operation":"CREATE","providerOrderId":"LP-ORD-0000000011"}
PAYMENT_PG_RESULT {"event":"PAYMENT_PG_RESULT","at":"2026-11-26T02:12:09.453621Z","callId":"44f7f0e4-a0f2-47dc-bfb8-e6ceb5d4f73a","operation":"CREATE","classification":"Unavailable","source":"CREATE","reason":"TRANSPORT_UNAVAILABLE"}
PAYMENT_APPLY_INPUT {"event":"PAYMENT_APPLY_INPUT","at":"2026-11-26T02:12:09.458404Z","observationId":"f4d37db8-aeba-474a-9e40-cb20cc499730","classification":"Unavailable","source":"CREATE"}
PAYMENT_APPLY_DECISION {"event":"PAYMENT_APPLY_DECISION","at":"2026-11-26T02:12:09.458446Z","observationId":"f4d37db8-aeba-474a-9e40-cb20cc499730","effect":"UNAVAILABLE","conflictCode":null,"paymentStatus":"UNKNOWN"}
TX_COMPLETION {"event":"TX_COMPLETION","at":"2026-11-26T02:12:09.461034Z","observationId":"f4d37db8-aeba-474a-9e40-cb20cc499730","operation":"APPLY_OBSERVATION","orderId":11,"paymentId":18,"source":"CREATE","outcome":"COMMITTED"}
```

같은 callId가 PG 시작·결과를 연결하고, 같은 observationId가 내부 입력·판정·실제 TX 종료를 연결한다. 두 UUID는 서로 다른 값이다. 원문의 PAYMENT_CONTEXT와 orderId/providerOrderId 대응, 해당 구간 CREATE·APPLY 각각 1개와 RESULT→APPLY 순서로 두 경계를 잇는다. 실제 분산 trace 전파를 구현한 것은 아니다.

PG journal에서도 주문 11의 CREATE는 sequence 35·응답 상태 200·`failure=CLOSED_EARLY`·위 거래 키로 남았다. 생성 응답 본문을 끝까지 보내지 못했지만 거래가 이미 존재한다는 경계 관측이다. 200은 fixture가 보내려 한 HTTP 상태이며 commerce가 성공 응답 전체를 받았다는 뜻은 아니다. 주입 plan 이름만으로 원인을 확정하지 않고 이 관측을 로그·권위 조회·DB와 대조했다.

|경쟁 가설|사전 예상|실제 관찰·판정|
|---|---|---|
|진단 시 PG가 아직 처리 중|같은 거래 권위 조회가 PENDING|세 거래 모두 SUCCESS이므로 해당 관측 시점의 가설 배제|
|PG 성공 생성 결과 전달 중 유실|SUCCESS 거래 + 같은 CREATE의 Unavailable + CLOSED_EARLY + UNKNOWN 유지 TX commit|세 주문 모두 일치해 지지|
|성공 관찰의 내부 적용 TX 실패|SUCCESS 입력과 실제 ROLLED_BACK이 연결|세 CREATE는 성공 입력 대신 Unavailable을 받았고 UNAVAILABLE 판정 TX가 COMMITTED. 복구 전 다른 callback/reconcile·추가 적용 없음까지 확인해 해당 호출 원인으로 배제|

출처: `run/fault/diagnosis.json`, `diagnostic-log.txt`, `pg-state.json`, `orders.json`. U11·U12·U13 모두 같은 판정이고 diagnosticFailures는 빈 목록, `proceed=true`였다. 여기서 COMMITTED는 **UNKNOWN 유지 처리를 저장했다**는 뜻이며 주문 성공 확정을 뜻하지 않는다. 로그 부재만으로 rollback이나 다른 원인을 배제하지 않았고, 필수 증거가 부족하면 복구를 허용하지 않는 규칙을 사용했다. 이 판정은 포착한 실행 범위에 한하며 실제 rollback 사고까지 이 AUTO에서 추가 주입한 결과는 아니다.

### 2.4 같은 주문 복구와 불변 조건

진단·fault 봉인을 마친 뒤 U11·U12·U13의 기존 본인 reconcile API를 호출했다. 복구 중 새 결제 생성·DB 상태 강제 수정·새 plan 등록은 없었다.

주입은 CREATE 한 번에 소비되는 plan3개를 U3에만 등록했다. 각 대상의 유일한 CREATE가 SUCCESS+CLOSED_EARLY로 끝났음을 journal과 대조하고, 복구 시작에는 진행 요청·콜백·gate가 0임을 검사했다. 복구는 조회만 수행하고 신규 plan 등록·CREATE가 없어 이 실행에서 응답 유실 주입을 다시 적용하지 않았다. 남은 plan 수를 조회하는 공개 API가 있다고 주장하지 않는다.

|대조 항목|사고 시점|복구·반복 적용 뒤|해석|
|---|---|---|---|
|같은 F10 내부 결과|CONFIRMED 7 / UNKNOWN 3|CONFIRMED 10 / UNKNOWN 0|U3이 같은 집합 안에서 복구|
|PG 거래 / 누적 CREATE|20 / 20|동일 거래 20 / 20|추가 결제 생성 0|
|가용재고 A/B|60 / 80|60 / 80|UNKNOWN 예약 유지, 성공 시 재차감 없음|
|예약 / 반환 / 추가 차감 이력|40 / 0 / 0|40 / 0 / 0|각 주문 A2·B1의 예약 효과 한 번|
|전체 Outbox / 구매 활동|17 / 34|20 / 40|복구 세 주문의 사실 3개·품목 활동 6개 추가|
|포인트·쿠폰|준비 직후 값과 동일|같은 값 유지|결제 복구가 미사용 포인트·보유 쿠폰을 변경하지 않음|

출처: 각 단계의 `summary.json`, `orders.json`, `preserved.json`, `plan-ledger.json`, `pg-state.json`. 활동·예약은 총합뿐 아니라 orderId/productId/수량별 목록 전체를 비교했다. 예약 changed_quantity는 크기 2/1이며 `before_quantity-after_quantity`도 2/1로 감소 방향을 확인했다.

포인트는 잔액 10,000·CHARGE 이력 1개(0→10,000), 쿠폰은 id 1/userId 1/campaignId 1·FIXED_AMOUNT 5,000·발급시각 `2026-11-26T02:12:03.835709`, campaign issuedCount 1을 포함한 저장 값 전체가 준비 직후·정상·사고·복구의 네 기록에서 같았다. 단순히 빈 테이블이 계속 비어 있음을 비교한 결과가 아니다.

각 U에 reconcile 1회 추가와 callback 2회씩(총 6회)을 보내 기대한 ACK를 확인한 뒤에도 위 효과는 늘지 않았다. 다른 사용자 2의 주문 조회·reconcile은 404 ORDER_NOT_FOUND였고 PG 호출·저장 효과 증가가 없음을 AUTO assertion으로 확인했다. 이 HTTP 응답 전체는 별도 원문 파일로 보존하지 않았으므로 근거를 assertion 코드와 해당 AUTO XML의 통과 범위로 한정한다.

### 2.5 후속 결과: 실제 전달·중복 소비·랭킹

정상 10주문과 정상 처리된 F7의 활동을 포함한 상태에서 복구 U3의 Outbox를 실제 relay로 발행했다. 각 eventId의 broker 위치, streamer 처리 로그, consumer group committed offset을 확인한 뒤 활동을 대조했다. broker 발행 횟수와 DB 업무 활동 수를 같은 것으로 세지 않았다.

U11의 같은 Outbox 사실을 실제 Kafka에 한 번 더 보냈을 때 consumer는 다음을 기록했다. 아래는 streamer 로그에서 시각·logger 접두어만 생략한 사건 문자열이다.

```text
ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic=checkout-incident-test-1126111136-307b8a9c-1 partition=0 offset=10 eventId=order-confirmed:11 result=ALREADY_PROCESSED
```

출처: `children/streamer-1126111136-307b8a9c.log`, `run/recovery/deliveries.json`. 중복 재발행 뒤에도 DB 활동은 40행·수량 A40/B20이었다.

배치는 같은 주의 새 snapshot을 만들었고, `children/batch-new-2026-11-23-459105542600791.log`에도 instanceId 1·executionId 1·COMPLETED·PUBLISHED가 기록됐다. DB와 공개 랭킹 API를 대조해 저장한 결과는 다음과 같다(원문 JSON 값을 재배치·들여쓰기).

```json
{
  "instanceId": 1,
  "executionId": 1,
  "status": "COMPLETED",
  "publicationOutcome": "PUBLISHED",
  "snapshotId": "weekly-20261123-r1",
  "inputCount": 40,
  "results": [
    "1:40:1",
    "2:20:2"
  ],
  "apiItems": [
    "1:40:1",
    "2:20:2"
  ]
}
```

출처: `run/recovery/ranking.json`. `results`와 `apiItems`의 항목은 productId:score:rank다. 따라서 상품 1(A)은 40점 1위, 상품 2(B)는 20점 2위다. 활동 40행은 20주문×2품목이며 구매 수량 합계는 60이다. 같은 주의 정상 집합도 포함한 점수로, 장애 집합만으로 기대값을 계산하지 않았다. 모든 주문의 최초 confirmedAt은 고정 업무 시각을 유지했고, PG 요청 시각이나 복구 실제 시각으로 덮어쓰지 않았다.

### 2.6 증거 보존·정리와 출처 확인

fault 원문 14파일과 사전 예상의 SHA-256을 `2026-11-26T02:12:11.799588Z`에 봉인했다. 문서 작성 시 사본의 `fault-seal.json` 목록을 다시 계산해 14개 모두 일치함을 확인했다. 14파일에는 health/Prometheus의 본문·상태/시각 4파일이 포함된다. hash는 바이트 변경 여부를 확인하는 수단이며 주문·상태·효과의 정확성은 위 대조와 assertion으로 별도 확인했다.

사람은 실행 전 테스트의 대상·허용 조치·중단 조건을 확인하고 실행 범위를 승인했다. 고정 예상과 자동 판정은 그 범위 안에서 증거를 비교하는 구현이며 사람 확인을 대체하는 승인 장치가 아니다. 실제 운영에서 상태를 바꾸는 조치는 런북의 대상/중단 조건을 사람이 확인한 뒤 수행한다.

이번 최종 실행에는 중간 실패가 없었다. 과거 중간 실패 기록은 이전 run들과 함께 보존했으며 성공 원문으로 바꾸지 않았다. 예를 들어 `apps/commerce-api/build/checkout-incident-runs/20261123T112400Z-0940a840/failures/001-baseline.json`은 예약 변경량 assertion 실패 기록이다. 이것을 최신 실행의 실패로 합산하지 않는다.

`run/resources/007-cleanup.json`에는 `releaseCompleted=true`, `kept=[]`, streamer/batch 자식·PG 종료가 기록됐다. 이 실행 소유 자원을 정리한 결과이며 공유 인프라 전체의 운영 상태를 보증하는 기록은 아니다.

최신 원문55파일과 과거 실행을 각각 보존했다. 제출 원문55개는 원본·별도 보존 사본과 바이트가 같고, manifest의 bytes·SHA-256으로 다시 확인할 수 있다. 보고서의 표는 같은 실행 파일의 필드 대조이며 원문 로그/응답 발췌와 재서식 JSON을 구분했다. 이 보고서와 실행별 원문55파일·manifest1개를 사고 증거로 함께 제출한다.

실패 시 상태 증거 보존은 이 실행 이후 보완했으며, 변경 내용과 관련 검사 결과는 6절에 구분한다. 기존 run의 metadata·파일 집합·봉인은 보완 후 결과로 바꾸지 않았다.

## 3. 요구별 검증 범위(설계 §10)

아래 표는 11월 26일 최종 회귀와 새 사고 실행 기준이다. 관측 원문 보존 보완의 실패 경로는 §5에서 구분한다.

|ID|검증 방식|근거|
|---|---|---|
|I1 fixture|실제 HTTP 단위|`PgFixtureControlTest` SuccessThenLostResponse 4, fixture 회귀 38건 중 포함|
|I2 UNKNOWN 지표|실제 MySQL·Actuator + 예외 경계 대역|`PaymentMetricsIntegrationTest` 6, `PaymentMetricsConfigTest` 6. NaN은 대역 검증이며 실제 DB 장애 아님|
|I3 PG·TX 진단 로그|실제 HTTP·MySQL + 제어 단위|`PgHttpGatewayDiagnosticsTest` 5, `DiagnosticLogTest` 3, `PaymentTxDiagnosticsIntegrationTest` 5, `PaymentTxDiagnosticsTest` 5. STATUS_UNKNOWN·등록 실패는 제어 단위|
|I4 정상/장애 단계|AUTO 실제 연결|위 §2|
|I5 진단 판정|AUTO 실제 증거 + 합성 단위|`fault/diagnosis.json`, `IncidentDiagnosisTest` 10. 승인된 테스트 범위/중단 조건과 고정 예상에 따라 판정하며, 사람 확인을 자동 판정이 대신했다고 주장하지 않음|
|I6 복구·소유권·반복|AUTO 실제 API·DB·PG|위 §2|
|I7 포인트·쿠폰 불변|AUTO 행 값 비교|`*/preserved.json`|
|I8 전달·소비·랭킹|AUTO 실제 Kafka·streamer·batch·API|`*/deliveries.json`, `recovery/ranking.json`, 자식 로그|
|I9 직접 관찰|**제외**(2026-11-23 사용자 결정)|OBSERVED 코드 없음|
|I10 진행 helper|AUTO 기록만 단위 검증|`IncidentCheckpointTest` 3. OBSERVED 명령·한도 검증은 기능과 함께 삭제|
|I11 증거·실패·정리|단위 + AUTO 정상 정리|`IncidentEvidenceWriterTest` 7, `CheckoutIncidentLifecycleTest` 4, `CheckoutIncidentRowsTest` 4, `CheckoutIncidentHttpLimitTest` 1. 준비 직후 실패·실제 정리 실패의 강제 재현은 없음. 실패 상태 보존 보완과 관련 검사는 6절|
|I12 누적 회귀|단일 `check` 1회|`docs/verification/week10-verification.md` 2절(11월 26일)|

## 4. 사고 검증의 미실행·한계

- 직접 관찰 Lab(제외), 실제 DB 장애·쿼리 취소·운영 장애, TX 시작 실패·프로세스 종료 상황의 진단.
- AUTO는 테스트 소유 자원의 1개 시나리오다. 운영 배포·실계정·운영 규모의 지표 수집 및 업무 부하·다중 인스턴스는 검증하지 않았다. 모든 장애 유형을 검증한 결과가 아니다.
- 진단 판정의 호출 연결은 주문 ID·유일성·로그 순서에 기대며, 같은 주문의 동시 CREATE가 있으면 미확정이 된다.

## 5. 직접 관측 실패 원문 보존 보완 (2026-11-25)

- 기준: HEAD `c3fb22c` + 기존 문서/주석 변경 + `CheckoutIncidentScenario` 보완과 신규 `CheckoutIncidentObservationTest`. 이번 보완은 테스트 코드이며 제품 결제 판정·기대값·기존 assertion은 변경하지 않았다.
- 변경: 각 health/Prometheus 응답을 받은 직후, assertion 전에 단계 디렉터리에 `<endpoint>-response.txt` 본문과 `<endpoint>-response.json`의 HTTP 상태·수신 시각·본문 존재 여부를 CREATE_NEW로 저장한다. 정상 경로의 기존 `direct-observation.txt`도 유지한다. 새 통신 실패로 받지 못한 응답을 합성하지 않는다.
- 신규 검증 9건: 정상 응답, health DOWN/HTTP 503, Prometheus HTTP 503/NaN/표본 누락/기대값 불일치, 기존 원문과 충돌하는 저장 실패, health 수신 후 Prometheus 통신 실패. 실제 시나리오의 observer에 `MockRestServiceServer` 응답 대역을 연결하고 임시 디렉터리 파일과 예외·다음 요청 미진행을 확인했다. 실제 Actuator·DB 장애 실험은 아니다.
- 실행 결과: 위 실패 경로 검사는 11월 26일 최신 전체 회귀에도 포함됐다. 전체 실행·수량·Checkstyle 결과는 `docs/verification/week10-verification.md` 2절에 정리했다.
- 보존: 검사 전 일반 test XML/HTML·Checkstyle·JaCoCo는 `/tmp/w10-observation-check-20261125-6r1mkpy4/before/`에 복사했다. 새 로그 `checks.log`, 결과/XML·Checkstyle·변경 코드 사본 `after/`, 원본/사본 SHA-256 일치 manifest는 같은 로컬 디렉터리에 있다. 기존 제출 후보 사본과 run metadata는 수정하지 않았다.
- 한계: 보완 당시에는 선택 검사만 실행했고, 이후 11월 26일 전체 회귀와 연결 검증을 마쳤다(§1~3). 새 Grafana 관찰은 하지 않았다. 디스크 완전 고장이나 프로세스 강제 종료를 재현한 검증도 아니다.

## 6. 실패 시 상태 증거 보존 보완 (2026-11-26)

실패 예외·메시지만 기록하던 경로에 정리 전 상태 수집을 추가했다. `failures/<순번>-<단계>.json`은 기존 실패 요약으로 유지하고, 같은 순번의 하위 디렉터리에 주문/결제·예약·재고·Outbox·PG 거래/수신 횟수/journal·진단 로그·명단을 독립적으로 저장한다. 각 파일은 수집 시각과 COLLECTED/value 또는 UNAVAILABLE/failureClass를 기록한다. 로그 수집기 시작 전은 UNAVAILABLE로, 시작 후 실제 이벤트가 없는 경우만 COLLECTED/빈 목록으로 구분한다. 실패 수집은 검증 assertion·복구 요청·DB 변경을 하지 않는다.

일부 수집이나 저장이 실패해도 다른 수집을 계속하고 최초 오류에 추가 오류를 suppressed로 남긴다. 기존 fault 봉인과 과거 실패 파일은 바꾸지 않는다. 실패 수집 전용 DB 연결·읽기 각 1초와 쿼리 1초를 적용하며, 정상 조회 설정은 유지한다. 순차 조회이므로 전체 수집이 하나의 원자 snapshot이거나 총 1초 안에 끝난다는 뜻은 아니다.

신규 `CheckoutIncidentFailureSnapshotTest` 9건으로 정리 전 수집, DB/PG 수집 실패의 독립성, 저장 충돌 시 최초 오류·정리 유지, 동일 예외 재전달, 봉인/과거 파일 불변, 준비 미완료·PG 미준비, DB 한도 분리를 검증했다. 시나리오의 실패 수집 메서드·writer와 기존 runThenRelease 경계에 임시 파일·대역을 연결한 검사다. DB 수집 SQL은 엔티티 컬럼과 정적으로 대조했으며 실제 MySQL에서 실행하지 않았다. 실제 DB 장애나 새 전체 사고 실행의 검증이 아니다. 구체적인 명령·검사 결과는 `docs/verification/week10-verification.md` 3절을 따른다.

DB 접속 불가·테이블 미준비·저장 공간 장애·프로세스 강제 종료 등에서는 일부 또는 전체 상태를 남기지 못할 수 있다. 수집 불가와 저장 실패를 정상 빈 결과나 성공으로 처리하지 않는다.
