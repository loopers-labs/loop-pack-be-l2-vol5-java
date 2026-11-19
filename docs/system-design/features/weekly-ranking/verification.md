# 원문 대응·검증 구성

이 문서는 [승인된 주간 랭킹 설계](../weekly-ranking.md)의 상세 문서다. 승인 상태와 전체 읽기 순서는 진입 문서를 따른다. 기존 절 번호는 유지하며 문서 사이에 분산된 절은 [절 번호 안내](../weekly-ranking.md#절-번호-안내)로 찾는다.

## 문서 간 읽기 경로

- [입력 고정·저장 구조·checksum](input-storage.md)
- [Job 실행·식별·재시작·복구](execution-recovery.md)
- [상품별 계산·완성본 공개](calculation-publication.md)
- [랭킹 API 계약](api-contract.md)

## 이 문서의 기준 절

[§10](#10-원문-요구와-설계검증-대응) · [§11](#11-검증-구성--작은-fixture와-주문-두-건의-실제-연결)

## 10. 원문 요구와 설계·검증 대응

아래 표는 Implementation Quest의 필수 요구를 어디에서 구현/확인할지 추적한다. “설계 포함”은 테스트 통과가 아니며 현재 구현·실행 결과는 없다. 제품 계약은 이 문서, 실행 준비/진행·최종 승인/수동 검토 인계는 공동 HANDOFF에서 관리한다.

|원문|요구|처리 계약|확인할 검증|
|---|---|---|---|
|§1|UTC 월요일~다음 월요일 미포함, 최초 confirmedAt·quantity 합·동점 ID 순서|§2·§5·[§8.5](calculation-publication.md#85-상품-합계와-절대-점수-저장-sql)·[§9.7](input-storage.md#97-입력-복사-sql과-읽기-시점--기본-격리-유지)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 원문 fixture/기간 경계, [§11.4](#114-실제-연결-한-건--결제-주문-ab) 실제 A 시작 경계·끝 sentinel|
|§1|전체 구매 상품 순위 저장, 현재 공개 조건 뒤 limit, 저장 rank 유지|[§6.6](calculation-publication.md#66-순위완성-결과manifest-저장-sql)·[§7.3~7.9](api-contract.md#73-조회-개수와-저장-범위-구분)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) API 계약, [§11.4](#114-실제-연결-한-건--결제-주문-ab) c 제외 뒤1/3위·limit2|
|§1|기간/snapshot/입력 시각·점수/순위 응답, 미공개/빈 공개본 구분|[§7.1~7.10](api-contract.md#71-미공개와-빈-공개-결과)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) API 계약·빈 주 job, [§11.4](#114-실제-연결-한-건--결제-주문-ab) 실제 API·빈 주|
|§2|실제 source 품목 복사와 manifest를 일관된 한 DB TX에 저장|§3·[§8.2](input-storage.md#82-입력-고정과-재사용의-분기)·[§9.7~9.10](input-storage.md#97-입력-복사-sql과-읽기-시점--기본-격리-유지)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 복사 후 manifest 전 실패의 전체 rollback·다품목 보존|
|§2|같은 주문 여러 상품·중복 유일성, 고정 입력 재복사/추가 금지, ID/기간/입력/revision 충돌 거절|[§3.3](input-storage.md#33-물리-fk를-두지-않기)·[§4.2](execution-recovery.md#42-새-계산의-자동-revision과-id-표현)·[§8.1~8.2](input-storage.md#81-메서드의-입력과-책임)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 충돌/손상 거절, [§11.4](#114-실제-연결-한-건--결제-주문-ab) A 다품목·A 재발행·B 도착 후 입력 불변|
|§3|freeze→계산→공개 Job, 업무 identifying 값·영속 repository, 완료 freeze 재시작 생략|§4·[§5.4~5.6](../weekly-ranking.md#절-번호-안내)·[§8.4](calculation-publication.md#84-stepreader-상태와-자원-수명)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 제품 job 재시작, [§11.4](#114-실제-연결-한-건--결제-주문-ab) 새 JVM/같은 instance·새 execution|
|§3|RunIdIncrementer/ResourcelessTransactionManager 제외, 실제 점수/checkpoint 같은 commit, 자동/명시 launch 중복 방지|[§4.7~4.9](execution-recovery.md#47-실행-옵션과-최초-준비-전제)·[§5.4](calculation-publication.md#54-db-트랜잭션-연결--기존-구성로컬-소스-대조)·[§8.4~8.6](calculation-publication.md#84-stepreader-상태와-자원-수명)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 실제 DB TX/commit 전 실패, [§11.4](#114-실제-연결-한-건--결제-주문-ab) 제품 Runner/initializer 연결|
|§3|probe-1/상품7·8 commit/9 직전 실패, reader 위치2·동일 metadata로 한 메서드 재개|[§5.1](calculation-publication.md#51-chunk-크기와-실패-경계)·[§8.4](calculation-publication.md#84-stepreader-상태와-자원-수명)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) RankingJobRestartTest의 원문 전용 probe|
|§4|안정된 상품 Reader·고정 입력 SUM·재처리 안전한 절대값 writer|§5·[§8.3~8.6](calculation-publication.md#83-상품별-계산과-실패-분기)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 같은 상품 재기록·실패 chunk rollback·영속 위치 복원|
|§5|모든 상품 준비 확인·결정 순위·완성/pointer 한 TX, 실패 중 기존 공개본 보존|[§6.2](calculation-publication.md#62-공개-전-완성-확인과-테스트의-구분)·[§6.5~6.9](calculation-publication.md#65-공개-전-완성-여부-확인-sql)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) count 불일치/공개 TX rollback, [§11.4](#114-실제-연결-한-건--결제-주문-ab) 계산 실패 중 r1 유지|
|§5|같은 공개 재실행 안전·revision 증가·직렬 실행·최신본 역전 금지|[§4.3~4.6](execution-recovery.md#43-다음-revision-조회와-기존-batch-api-사용)·[§6.7~6.9](calculation-publication.md#67-현재-pointer의-조건부-update와0행-분류)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 멱등 공개·이전 revision SKIPPED_NEWER, [§11.4](#114-실제-연결-한-건--결제-주문-ab) r1/r2/r3 공개|
|§5|공개 commit과 최종 metadata 실패 구분·정상 완성본 삭제 금지|[§5.4~5.8](../weekly-ranking.md#절-번호-안내)·[§6.9](calculation-publication.md#69-실패재실행-기준과-이번-상세화의-한계)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 최종 metadata 실패 주입·fresh DB와 Batch 상태 분리·공개 서비스 멱등 재진입|
|§6|원문4행/3상품·7=5/1위·9=5/2위·8=1/3위, 정상/재시작 checksum·기대값 assert|§2·[§3.5](input-storage.md#35-입력결과-checksum-규칙)·[§5.1](calculation-publication.md#51-chunk-크기와-실패-경계)·[§8.7](calculation-publication.md#87-이번-상세화의-검토-기준)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 정상/재시작 fixture 대조. [§11.4](#114-실제-연결-한-건--결제-주문-ab)에서도 같은 A 입력 정상/재시작 API items 비교|
|§7|실제 PG→Outbox relay→실제 Kafka→소비 활동→snapshot→job→API 한 연결|[§11.2](#112-재사용할-기존-근거와-새-경계)·[§11.4~11.6](#114-실제-연결-한-건--결제-주문-ab)|WeeklyRankingFlowTest 한 건, 실제 PG 흐름의 주문 A/B와 broker/DB/HTTP 관찰|
|§7|중복 사건 불변·늦은 구매 후 기존 입력/재시작 결과 불변·새 revision 반영·공개 전후|§3·§4·§6·[§11.4](#114-실제-연결-한-건--결제-주문-ab)|A 재발행, B 후 r2 입력/체크포인트 보존·r2 재개, r3에만 B 포함|
|§7|시작 포함/끝 제외·빈 주/미공개/삭제 제외|§7·[§9.7](input-storage.md#97-입력-복사-sql과-읽기-시점--기본-격리-유지)|[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) 단독 계약 + [§11.4](#114-실제-연결-한-건--결제-주문-ab)의 실제 연결 안에서 A/끝 sentinel·빈 주·현재 c 삭제|
|§8|writer 재기록·chunk commit 전 실패·공개 rollback·실제 Job/Step/checkpoint/fresh DB 확인|§5·§6·§8·[§11.3](#113-미리-준비할-fixture와-batch-검증의-책임)|Batch fixture에서 실패 지점별 확인. 모든 실패를 PG부터 반복하지 않음|
|§8|관련 회귀/Checkstyle·기존 API/streamer ArchUnit·재시작 중 DB 보존·소유 자원 정리·0건/skip/연결 실패 제외|[§11.6~11.7](#116-실행-자원-소유권최초-준비정리)|검사별 실제 명령/건수/결과·child 종료/cleanup 증거. batch에 기존 ArchUnit이 있다고 쓰지 않으며 과거 W7 결과와 구분|

### 10.1 설계 대조와 실행 확인의 구분

원문은 증분 방식도 허용하지만 본 설계는 기본 절대값 upsert를 선택했으므로 활동별 적용 이력이라는 추가 저장 구조를 만들지 않는다. 원문이 예시로 든 pointer 단일 join 대신 같은 기본 REPEATABLE READ의 두 조회/고정 snapshotId를 선택했으며 계약은 유지한다. 원문 §7은 실제 연결 테스트로 확인하고 고정 fixture만으로 대체하지 않는다. source sentinel은 기간 경계용 합성 행으로 구분하며 PG로 만든 실제 A/B 품목의 기여를 별도로 assert한다.

지원하지 않는 강제 metadata 수선/동시 실행·자동 재시작·purge·잠금/성능 실험은 필수 요구를 대신하는 대안이 아니다. UNKNOWN은 증거 보존/중단하고 동일 공개 처리의 멱등성 검증과 framework의 재시작 가능 여부를 분리한다. 제품 job에서 수작업 정답을 재계산하는 검사를 넣지 않는다.

### 10.2 제출과 승인 상태

이 표는 설계에서 필수 검증 책임을 배정한 것이며 제출 완료표가 아니다. 실제 제출에는 원문 §8의 입력 고정/job/API 코드·재시작/연결 테스트·PR·기술 글과 주차 운영 지시의 멘토 질문/WIL 등이 따로 필요하다. PR/글의 실행 근거는 [§11.7](#117-완료-증거실행-순서범위)에서 실제로 관찰한 값만 사용한다. Claude 검토는 제안이고 사용자 최종 설계 승인 및 작은 구현 단위 착수는 별도다.

## 11. 검증 구성 — 작은 fixture와 주문 두 건의 실제 연결

### 11.1 최소 구성의 기준과 수동 확인의 위치

사용자는 배치 입력을 미리 준비하고 실제 연결용 구매는 한두 건으로 줄이되 원문 요구는 모두 유지하는 방향을 요청했다. 이 절은 전체 승인 설계의 검증 구성이다. 실제 구현/실행 결과는 아니며 검증 실행 착수는 별도 승인 범위다.

원문 Implementation Quest §3은 한 테스트 메서드의 실패→재실행과 영속 checkpoint 관찰, §6은 정상/재시작 결과의 기대 점수·순위 assert, §8은 재시작/연결 테스트 제출을 명시한다. 따라서 모든 요구를 수동 실행/화면 캡처만으로 대체하지 않는다. 직접 SQL/API/명령을 실행하는 확인도 관찰 증거가 될 수 있지만 제출할 재현 테스트의 대체물은 아니다. §8의 예시 클래스 이름·명령을 그대로 복제하는 것이 요구는 아니다.

추천 구성은 자동 테스트 세 종류다. DB fixture 기반 Batch 테스트, 가벼운 API 계약 테스트, 기존 W7 준비를 재사용하는 실제 연결 테스트 한 건이다. 실제 결제 주문 수와 테스트/검증 항목 수를 혼동하지 않는다. 원문의4행·3상품 fixture와 실패 분기는 유지하되 모든 조건마다 실제 PG 주문을 만들지 않는다. 이미 자동으로 확인한 흐름을 별도 수동 실행 세트로 반복하는 것을 기본 완료 조건에 추가하지 않는다. 구현 중 자동화 비용이 예상과 달라지면 필수 기대값을 줄이지 않고 바꿀 구성/비용을 사용자와 다시 논의한다.

선택 이유/비용: 수동 실행은 일회 관찰에는 간단할 수 있지만 이 저장소의 PG/clock·전용 Kafka/streamer·종료/정리 준비는 이미 Java 테스트 helper에 있다. 여기에 배치 child를 연결하면 제출용 재현/실패 위치 assert/관찰값 수집을 함께 처리할 수 있다. 비용은 테스트 전용 batch bootstrap/실패 주입과 Gradle classpath 입력 연결이다. 이 비용이 없는 작업이라고 주장하거나 측정하지 않은 소요 시간을 제시하지 않는다. 공통 테스트 플랫폼·새 관리 API·W7 helper 전체 공개/이동은 하지 않는다.

### 11.2 재사용할 기존 근거와 새 경계

기존 [주문 이벤트 실행 기록](../../verification/order-events.md)의 W7 Outbox 실행 근거는2026-11-05 당시 PG 처리·Outbox 기록/relay·실제 Kafka·streamer DB 저장 및 재발행 중복 보호를 확인한 과거 결과다. 과거 W6 미구현 설명이나 옛 테스트 이름과 현재 W7 준비 코드를 구분하며 그 통과를 이번 랭킹 통과로 쓰지 않는다.

이번에 새로 연결되는 것은 소비된 실제 활동의 기간/품목 → snapshot 입력/manifest → 영속 Batch 실행/재시작 → 완성 결과/pointer → API다. 기존 PG/relay/Kafka의 모든 오류를 이 연결 테스트에서 다시 재현하지 않고 실제 정상 전달·중복 한 번·늦은 구매를 지나 새 경계의 호환성과 입력 불변을 확인한다.

기존 API 테스트의 OrderOutboxE2eLifecycleSupport·OrderActivityStreamerChild는 package-private이며 업무 전제/정리 대상도 W7에 맞춰져 있다. 신규 WeeklyRankingFlowTest는 API test의 com.loopers.infrastructure.order에 두어 같은 패키지의 기동/소유권 지원을 사용한다. 기존 E2E 본문을 상속/그대로 실행하거나 helper를 batch main으로 옮기지 않는다. 기존 release는 batch 자식/랭킹 테이블을 모르므로 그대로 호출하는 것만으로 전체 정리가 된다고 가정하지 않는다. 기존 W7 정리 순서/회귀를 보존하면서 신규 테스트의 batch 자원 소유권을 추가한다.

PG는 기존 PgFixtureServer를 통한 실제 HTTP 결제 흐름, Kafka는 실제 broker, streamer는 기존 bootJar 자식 JVM이다. PG gateway/relay/consumer를 메모리 mock으로 갈아 끼워 실제 연결이라고 쓰지 않는다. 외부 실결제망 호출이나 새로운 PG 서버 구축을 추가하는 설계는 아니다. User/Product/Brand/재고/포인트 등 주문 준비는 기존 fixture를 사용한다.

### 11.3 미리 준비할 fixture와 Batch 검증의 책임

Claude 1차 검토 뒤 사용자는 단독 테스트에 없는 테이블을 테스트 전용 CREATE TABLE SQL로 준비하는 방식을 선택했다. Batch 단독 테스트는 자신이 매핑하는 랭킹 테이블 외에 source product_purchase_activity를 SQL로 준비하고, API 단독 테스트는 자신에게 Entity가 없는 랭킹 다섯 테이블을 SQL로 준비한다. 다른 앱 기동이나 테스트용 업무 Entity 복제에 의존하지 않는다. 실제 매핑의 DATETIME(6)·DECIMAL(65,0)·문자셋/정렬·업무 유일 키와 [§9.11](input-storage.md#911-조회저장-sql에-맞춘-인덱스-구성) 인덱스를 맞추고 테스트가 소유한 테이블의 준비/정리를 명시한다. 최초 실패와 재시작 사이에는 이 SQL을 다시 실행하거나 데이터를 초기화하지 않는다.

사용자가 2차 검토 뒤 확정한 요구는 update 강제가 아니라 create 재생성으로 기존 데이터를 잃지 않는 것이다. none/update 등 보존 가능한 방법을 사용할 수 있다. 이 설계에서는 없는 매핑 테이블까지 준비하는 랭킹 Batch/API 준비 context와 연결 테스트의 batch 준비 child에 update를 선택하고, 이미 준비된 DB의 명시 실행/재시작 child에는 none/never를 유지한다. 단독 테스트의 비매핑 테이블은 기존 선택대로 명시 SQL로 준비한다. 모든 실행에 update를 강제하거나 전역 jpa.yml·일반 Demo 실행/W7 설정은 일괄 변경하지 않는다. 다만 batch Test 작업 안의 기존 Demo/기동 테스트까지 포함한 비파괴 기본값은 사용자 승인한 [§4.11](execution-recovery.md#411-job-등록-조건과-실행준비테스트-설정)의 update를 적용한다.

단독 테스트의 비매핑 테이블은 여전히 SQL로 준비하고, 실제 연결에서는 소유 앱 매핑을 사용한다. update는 이미 있는 테이블을 drop/recreate하지 않지만 필요 시 매핑에 따른 schema 변경은 수행할 수 있다. 이는 JPA가 소유한 테스트 schema 준비에 허용된 동작이고 SQL helper의 구조 불일치 자동 DROP/ALTER 금지와 구분한다. update가 기존 데이터·Batch metadata·UNKNOWN을 비워 준다고 가정하지 않는다. 연결 테스트를 위해 랭킹/Batch 테이블이 반드시 없어야 한다는 새 선점 규칙은 채택하지 않으며 남은 이력/데이터 간섭은 별도 테스트 격리 문제로 다룬다.

준비 방법보다 기존 데이터 보존이 우선이다. 기존 테이블을 없애고 다시 만드는 create/create-drop이나 무조건 DROP/TRUNCATE로 준비하지 않는다. 기존 schema와 매핑이 달라 보존을 보장할 수 없는 변경이 필요하면 자동 수선/삭제로 통과시키지 않고 사유를 보고한다. 증거를 남기기 전 다른 시나리오/실행의 데이터와 metadata를 정리하지 않는다. 독립 시나리오의 격리는 [§11.3.2](#1132-독립-시나리오의-주업무-id-격리)를 따르고 UNKNOWN 등 제품의 보호 조건을 테스트 편의로 바꾸지 않는다.

원문 §6의 source4행을 공통 fixture로 준비한다. 주문42의 상품7/8, 주문43의 상품7, 주문44의 상품9이며 기대값은7=5점/1위·9=5점/2위·8=1점/3위다. 상품7/8/9는 Reader 순서를 관찰하기 위한 고정 ID이고 실제 PG 연결의 생성 ID에 강제하지 않는다. fixture는 source 구매 활동을 채운 뒤 제품 freeze를 거쳐 계산한다. 주간 job 검증에서 완성 결과/stage를 정답으로 미리 채워 계산한 것처럼 보이지 않는다. API 단독 테스트의 완성본 fixture는 별도 책임이다.

테스트 클래스는 아래 세 개를 기본 배치 단위로 제안한다. 행 하나가 반드시 메서드 하나여야 하는 것은 아니며 같은 fixture/context로 묶거나 parameterized test를 사용할 수 있다. 필요한 실패 경계와 기대값을 없애 테스트 개수만 줄이지 않는다.

|클래스·책임|최소 시나리오와 기대 관찰|원문 근거|
|---|---|---|
|batch test의 RankingJobRestartTest — 작은 연결|§3 probe-1·batch_restart_probe를 그대로 확인. 별도 실제 DB에서7/8 저장·위치2 commit →9 직전 실패 → 같은 instance/새 execution에서9부터. 한 메서드 안에서 DB/metadata 유지|§3|
|같은 클래스 — 실제 job 재시작|원문4행을 정상 snapshot으로 계산한 결과와 별도 snapshot의9 직전 실패/재시작 비교. stage7/8·checkpoint2·입력 checksum 불변·완료 freeze 생략·재개 처리 ID9·기대 점수/순위·결과 checksum·기존 공개본 유지 assert|§2~6|
|batch test의 WeeklyRankingBatchContractTest — 쓰기 원자성|같은 상품 절대 점수 재기록은 불변. writer 일부 쓰기 후 chunk commit 전 예외이면 그 chunk 점수/checkpoint 모두 rollback, 재시작은 이전 위치. 잘못된 ID/기간/revision·고아/손상 입력은 실패하고 덮어쓰지 않음|§2·4·8|
|같은 클래스 — freeze/빈 입력·기간|복사 뒤 manifest 저장 전 오류에서 input/metadata가 함께 rollback. 주 시작 포함·끝 제외.0행 주도 READY→COMPLETED·빈 결과/pointer, NOT_PUBLISHED와 구분|§2·7|
|같은 클래스 — 공개 경계|순위/COMPLETED/pointer 쓰기 후 commit 전 오류에서 전부 rollback, 기존 공개본 유지. 같은 snapshot 공개 재진입은 결과·checksum·공개 시각 불변. 더 최신 공개 뒤 오래된 대상은 완성만 하고 SKIPPED_NEWER|§5·8|
|같은 클래스 — 공개 commit/metadata 공백|공개 업무 TX commit 뒤 최종 Step metadata 저장을 테스트 대역으로 한 번 실패. fresh DB의 정상 완성본/pointer와 Batch의 실패/불명을 따로 관찰. 동일 대상 공개 서비스 재진입이 멱등적임을 별도 TX에서 확인하고 UNKNOWN Job을 강제 restart하지 않음|§5·8|
|API test의 WeeklyRankingApiTest|고객 식별·날짜/월요일·limit 기본/최대/오류·미공개/빈 공개본·score 문자열·UTC inputFixedAt·현재 이름·삭제 Product/Brand 제외 후 limit·저장 rank 간격. 고정된 snapshotId로 두 조회가 같은 TX에 참여하는 제품 경로 사용|§1·7|

probe-1의 입력은 테스트 fixture의 원문4행을 ranking_snapshot_input에 snapshot_id=probe-1로 직접 INSERT해 준비한다. [§9.12](input-storage.md#912-native-jdbc의-날짜utc-시각-바인딩읽기)의 UTC 바인딩과 실제 입력 테이블의 열/유일 키를 지킨다. 제품 WeeklyRankingRequest/freeze는 canonical ID를 요구하므로 probe-1을 그 경로로 보내거나 표준 ID 검증을 느슨하게 하지 않는다. 작은 probe는 입력 행을 읽는 별도 테스트 Job이며 제품 manifest/전체 Job의 정답 검증은 별도 시나리오가 담당한다.

batch_restart_probe는 원문 §3의 snapshot_id/product_id 복합 PK를 가진 테스트 전용 테이블로 SQL 준비한다. probe-1 입력/확인 행은 첫 실행 전에 한 번 준비하고 두 실행 사이 보존한다. 종료/증거 확인 뒤 helper가 probe 소유 행/테이블과 실행 기록만 정리하며 다른 snapshot/실행을 지우지 않는다. 이 테이블을 제품 Entity/API로 승격하지 않는다. 정상 비교본과 재시작 대상은 서로 다른 snapshot/revision으로 격리하고 재시작 대상을 지워 정상 비교본을 만들지 않는다. 최초 실행/재시작마다 새 Reader/실행 객체를 만들어도 같은 영속 state key를 복원하는지 확인한다. 전체 테스트에 @Transactional 자동 rollback을 붙이지 않으며 별도 연결의 새 조회를 사용한다.

시간 기준/해시 기대값은 고정 UTC·합의한 직렬화로 만든다. 제품 hash 메서드를 그대로 호출한 결과끼리 비교하는 것만으로 정답을 주장하지 않고 수작업 기대 점수/순위를 함께 assert한다. 테스트용 계산 정답 대조를 운영 job에 넣지 않는다. 단일 SUM이 long 경유 없이 BigInteger로 전달되는 것과65자리 컬럼 매핑/유효 strict mode는 최초 실제 DB 연결 확인에 포함한다. 별도 성능·잠금 대기 실험은 추가하지 않는다.

#### 11.3.1 단독 테스트의 준비 파일·순서·소유권

다음은 후속 구현에서 만들 테스트 리소스의 계약이며 현재 파일을 생성한 것이 아니다.

|테스트|JPA/Batch가 최초 준비하는 것|명시적으로 실행할 테스트 전용 SQL|
|---|---|---|
|Batch fixture/probe|batch Entity의 다섯 랭킹 테이블·기본 Batch metadata를 최초 한 번 준비. 자동/명시 launch는 OFF|apps/commerce-batch/src/test/resources/fixtures/weekly-ranking-source-schema.sql로 product_purchase_activity 생성|
|API 단독 계약|기존 API Entity 테이블을 해당 테스트 준비 context에서 생성|apps/commerce-api/src/test/resources/fixtures/weekly-ranking-schema.sql로 다섯 랭킹 테이블 생성|
|§7 실제 연결|[§11.6](#116-실행-자원-소유권최초-준비정리)의 각 앱 소유 준비 context로 자기 테이블/metadata를 한 번 생성|위 단독 테스트용 schema SQL을 중복 실행하지 않음|

두 SQL은 제품 schema.sql이나 자동 시작 스크립트로 등록하지 않고 각 테스트의 최초 준비 helper가 JdbcTemplate/ResourceDatabasePopulator로 명시 실행한다. JPA update 준비 완료 → 부족한 테이블 생성 → schema 핵심 정의 확인 → fixture 입력 → 명시 job/API 검증 순서다. 테스트 DB를 새로 준비하는 경로이며 다른 개발 DB에 이 스크립트를 실행하지 않는다. 기존 비소유 테이블을 DROP으로 지우지 않고, IF NOT EXISTS로 존재를 무조건 정상 채택해 schema 불일치를 숨기지 않는다.

사용자 확인에 따라 SQL 준비 helper는 대상 테이블의 존재를 먼저 조회한다. 없을 때만 해당 CREATE TABLE을 실행하고, 이미 있으면 핵심 정의를 확인한 뒤 알려진 소유 테스트 DB의 호환 테이블을 재사용한다. 구조가 다르면 실패로 알리고 자동 DROP/ALTER로 맞추지 않는다. 존재/구조 일치만으로 그 테이블의 소유권이나 최종 삭제 권한을 얻는 것은 아니다. 해당 랭킹 테스트 준비의 JPA는 update를 사용하고, 준비된 검증 DB의 제품 Job 실행/재시작 child는 none을 사용한다. update 허용을 실제 job 실행 중 schema 변경으로 확대하지 않는다.

source SQL의 기준은 streamer의 ProductPurchaseActivityModel/BaseEntity와 승인한 confirmed_at 인덱스다. ranking SQL의 기준은 §9의 batch 매핑이다. 최초 확인은 생성된 테이블의 핵심 열 타입/정밀도·null 제약·업무 UNIQUE/인덱스 이름과 열 순서·유일성·ASCII binary collation을 기준 정의와 대조하는 것으로 한정한다. 열이 같아도 이름이 다른 키를 무조건 동일한 것으로 통과시키거나 중복 인덱스 추가/삭제로 자동 수선하지 않는다. 실제 연결에서는 소유 앱이 생성한 스키마에 같은 제품 JDBC 경로가 동작하는지도 확인한다. 새 ORM/공통 schema 비교 플랫폼이나 다른 앱 Entity의 테스트 복제는 만들지 않는다. 매핑을 변경하면 대응 테스트 SQL/확인 기준도 함께 갱신한다.

DatabaseCleanUp은 Entity metamodel에 등록된 테이블만 정리하므로 SQL로 만든 테이블의 정리를 맡길 수 없다. 해당 테스트 helper가 SQL 소유 테이블/입력 행의 정리를 명시 소유한다. fixture case 전후의 정리와 메서드 안의 실패→재시작을 분리하고 두 실행 사이에는 source/input/stage/result/Batch metadata를 삭제·재생성하지 않는다. 소유 SQL 테이블의 최종 제거는 그 테이블을 쓰는 job/API 작업 종료 확인과 증거 저장 뒤 수행하며 다른 테스트/실행의 테이블은 정리하지 않는다.

같은 context에서 이어 실행하면 Hibernate schema 준비가 다시 실행되는 것은 아니다. 새 JVM/context로 기존 검증 DB를 이어 쓸 때는 ddl-auto=none·Batch initialize-schema=never로 접속한다. update는 해당 앱의 매핑에 없는 테이블의 준비를 대신하지 않으며, 이번에는 재시작 중 schema 변경 기능을 사용하지 않는다.

#### 11.3.2 독립 시나리오의 주·업무 ID 격리

사용자는 기존 UNKNOWN/완료 이력의 간섭을 막기 위해 독립 테스트 시나리오마다 다른 periodStart를 사용하는 방식을 선택했다. 하나의 시나리오 안의 정상 비교·실패·재시작·새 revision 검증은 같은 주를 사용한다. 재시작을 다른 주/다른 instance로 만들어 통과시키지 않는다. [§4.4](execution-recovery.md#44-실행-준비-메서드와-새-계산-순서)의 같은 주 UNKNOWN/진행 중/commit 불명에 대한 new 제한도 유지한다.

작은 fixture를 재사용하되 각 시나리오에 고정된 주/주문 ID 영역을 배정한다. 임의 대량 데이터나 랜덤 시각을 만들지 않는다. 원문 §6의 주문42/43/44·상품7/8/9·2026-08-17 주·4품목·기대값은 그 전용 정상/재시작 시나리오에서 그대로 유지한다. probe-1은 그 입력의 테스트용 복사본을 읽는 별도 probe Job이며 제품 weeklyRanking의 다른 주/instance로 간주하지 않는다.

|시나리오 영역|주 배정 예시|보존/격리 기준|
|---|---|---|
|원문4행 정상/실패·재시작 비교|2026-08-17|원문 값 유지. 정상 snapshot과 실패/재시작 snapshot은 같은 주의 다른 revision, 실패/재시작 한 쌍은 같은 instance|
|writer 재기록·commit 전 실패|2026-08-24|복사한 fixture의 주문 ID/confirmedAt을 해당 시나리오 값으로 배정|
|freeze rollback|2026-08-31|다른 주의 남은 실행/입력을 재사용하지 않음|
|기간 시작/끝 경계|2026-09-07|그 주 시작 직전/시작/끝 직전/끝을 기준으로 별도 경계 행 준비|
|Batch 빈 입력|2026-09-14|해당 주에는 source를 넣지 않음|
|공개 TX rollback|2026-09-21|같은 시나리오 안에서는 이전 공개본과 실패 대상의 주를 같게 유지|
|공개 멱등/오래된 revision 역전|2026-09-28|같은 주의 revision으로만 신구 비교|
|공개 commit 뒤 metadata 실패/UNKNOWN|2026-10-05|시나리오 전용 주. 후속 독립 시나리오에서 이 주로 new를 요청하지 않음|
|실제 연결의 빈 주/미공개|2026-10-12|A/B/sentinel이나 다른 테스트의 source·공개 fixture를 넣지 않는 예약 주|
|실제 PG 연결 A/B|2026-10-19|A/B·정상/실패·재시작/새 revision은 모두 같은 주. 끝 sentinel은2026-10-26|
|API 단독 계약의 독립 case|2026-11-02부터7일 간격|데이터를 저장하는 case마다 고유 주. 미공개/빈 공개본/삭제 fixture의 공개 행을 공유하지 않음|

위 값은 고정 배정 예시이며 실제 테스트별 상수/parameterized subcase에 유일하게 배정한다. 실행 순서·LocalDate.now()에 따라 주가 달라지는 카운터/무작위 배정을 사용하지 않는다. 같은 시나리오의 new/r1/r2/r3와 restart 중간에 주를 변경하지 않는다. 독립 반복 case가 있으면 그 case에도 별도 고정 주를 배정한다.

다른 시나리오에서 fixture를 복제할 때 confirmedAt은 상대 요일/시각을 유지해 배정 주로 옮기고 주문 ID는 별도 양수 영역을 사용한다. orderId/productId 유일 키에 같은 주문 ID로 다른 사실을 다시 INSERT하지 않는다. Batch의 단순 합계만 검증하는 경우 상품7/8/9의 숫자는 재사용할 수 있지만, API의 이름/삭제/브랜드 상태를 변경하는 case는 별도 카탈로그 fixture를 생성해 변경이 다른 case에 퍼지지 않게 한다. 실제 PG 주문 ID는 실제 생성값을 사용한다.

source 행 수는 해당 시나리오의 주문 ID 집합, 입력/stage/result는 snapshotId, 공개본은 periodStart로 조회해 assert한다. 공유 DB 전체 COUNT가4라는 식의 단언을 하지 않는다. Case 준비를 위해 DatabaseCleanUp으로 랭킹 테이블 전체를 비우고 Batch metadata만 남기는 조합도 사용하지 않는다. 날짜/식별값으로 격리하고 실패→재시작 사이의 원본/입력/점수/checkpoint를 유지한다. 종료/증거 확인 뒤의 정리는 소유 범위에 한정하며 제품 metadata를 강제로 수정하지 않는다.

### 11.4 실제 연결 한 건 — 결제 주문 A/B

API test의 WeeklyRankingFlowTest 한 메서드가 PG/Kafka/streamer/API·동일 테스트 DB와 batch 자식들을 소유한다. 실제 결제 주문은 두 건이다. 생성한 상품 ID를 a<b<c로 정렬해 수량을 배정하고 eventId/orderId는 실제 생성값을 사용한다.

|입력|준비·도착 시점|기대 기여|
|---|---|---|
|주문 A|최초 confirmedAt을 주 시작 UTC로 고정. a수량2·b수량1·c수량2의 다품목 주문을 기존 PG 흐름으로 확정/relay/실제 consume|원본3품목, 첫 snapshot의 a2/c2/b1 및 동점 ID 순서|
|A의 같은 사건 재발행|재시작 대상 snapshot 고정 뒤 동일 저장 payload/key를 실제 Kafka로 다시 발행. 새로운 PG 주문을 만들지 않음|새 record 위치에서도 중복 처리, 원본/점수 증가 없음|
|주문 B|재시작 대상 snapshot 고정 후 같은 주의 다음 날 confirmedAt으로 a수량3 주문을 실제 확정/relay/consume|기존 입력에 추가되지 않음. 새 revision에서 a5/c2/b1|
|기간 끝 제외 sentinel|고유 테스트 orderId와 a수량99·confirmedAt=다음 월요일을 source fixture에 미리1행 준비. 실제 PG 데이터와 구분해 기록하고 생성 A/B와 ID 충돌 없음 확인|첫 주 snapshot/점수에 포함되지 않음. 추가 PG 주문 없이 SQL의 미포함 끝 확인|

주문 확정 전용 orderConfirmationClock만 테스트 Clock으로 바꾸며 source confirmedAt을 사후 SQL로 수정하지 않는다. Outbox 시각·Kafka·시간 제한·inputFixedAt은 다른 책임이다. [§11.3.2](#1132-독립-시나리오의-주업무-id-격리)의 실제 연결 전용 주2026-10-19를 사용한다. A는2026-10-19T00:00:00Z, B는2026-10-20의 지정 UTC 시각, sentinel은2026-10-26T00:00:00Z다. 원문4행 fixture의2026-08-17 주는 변경하지 않는다. 생성 ID가 달라질 때도 a<b<c와 실제 주문/사건값으로 기대값을 계산한다.

```text
소유 DB/준비 context/topic/group/PG/streamer/API 준비; 아직 주 공개 없음 확인
sentinel 준비; 주문 A를 실제 확정/relay/consume
  Outbox payload/기록, Kafka 위치, A의 소비 완료1건·활동3품목 확인
batch new r1 정상 실행: input3행·a2/c2/b1 공개, checksum/API items를 정상 비교본으로 보관
batch new r2 실행: 같은 A 입력 freeze 뒤 a/b chunk commit, c 직전에 테스트 예외 한 번
  FAILED, input3행/checksum, stage a2/b1, 위치2, API는 정상 r1 유지 확인
A 재발행을 실제 consume한 증거 확인; source A의 활동3품목 불변
주문 B를 실제 확정/relay/consume; source의 실제 구매는 총4품목(+sentinel 별도)
  r2의 input3행/checksum·stage/checkpoint가 변하지 않음 확인
batch restart(실제 최신 실패 executionId), 새 JVM/Reader, 같은 DB/metadata 유지
  같은 instance/새 execution·처리 ID c부터·r2 a2/c2/b1·PUBLISHED 확인
  B를 포함해 기대값을 바꾸지 않고 A만으로 assert
  r1/r2의 input/result checksum·API items가 같음을 확인; snapshotId/시각은 내용 비교에서 분리
새 revision을 요청하기 직전 API가 r2임을 확인
batch new r3: source A+B를 새 입력으로 계산/공개
  r1/r2 입력/결과 보존, r3 input4행·a5/c2/b1, pointer/API r3 확인
현재 c를 삭제/비노출하는 기존 공개 조건으로 변경
  limit2는 a(1위)/b(3위), 재정렬 없음; 원본/과거 ranking 보존
공개 없는 별도 주는 NOT_PUBLISHED 확인
활동 없는 별도 주 new를 한 번 실행해 PUBLISHED·snapshotId·빈 items 확인
모든 관찰 증거를 남긴 뒤 writer 종료 → 실행 자원 정리. 공유 랭킹/Batch 테이블·행은 보존
```

실제 연결의 미공개/빈 주는 [§11.3.2](#1132-독립-시나리오의-주업무-id-격리)에 예약한2026-10-12를 사용하며 다른 case와 source/공개 fixture를 공유하지 않는다. 삭제/비노출 fixture 변경은 현재 공개 조건만 바꾸고 과거 source/snapshot/result를 수정하지 않는다. Product/Brand의 세부 오류/조회 한계는 가벼운 API 테스트에서 나눠 확인한다. r2 계산 실패 중 r1 보존과 r2→r3의 실제 공개 전후 값을 확인한다. 공개 TX rollback은 [§11.3](#113-미리-준비할-fixture와-batch-검증의-책임) fixture에서 검증한다. 모든 batch 오류를 PG부터 반복하지 않는다.

### 11.5 앱 간 실행 연결과 테스트 전용 실패 주입

사용자는 원하는 결과를 얻는 가장 단순한 구성을 선택하도록 요청했다. Batch fixture/probe의 실패/재시작은 [§11.3](#113-미리-준비할-fixture와-batch-검증의-책임)대로 한 테스트 JVM·동일 DB/context에서 수행한다. 별도 프로세스가 원문 필수 조건인 것은 아니다. §7 실제 연결 한 건에서만 이미 별도 앱인 API/streamer/batch를 연결하기 위해 기존 W7과 같은 자식 실행 방식을 사용하며, 여기서도 데이터베이스는 하나를 공유한다. 새로운 테스트 서버/IPC 프로토콜/공용 프로세스 프레임워크는 만들지 않는다.

API 앱의 main/test classpath에 commerce-batch 앱을 implementation/testImplementation으로 의존시키지 않는다. 자식에는 batch의 sourceSets.main.runtimeClasspath와 테스트 진입점/실패 주입의 전용 컴파일 출력만 전달한다. 과거 초안의 test.runtimeClasspath 전체 전달은 철회한다. main의 JPA/Redis 모듈·드라이버는 유지하고 그 모듈들의 testFixtures 출력/JAR, JUnit 등 테스트 전용 runtime 의존성과 무관한 테스트 클래스는 자식에서 제외한다. 제품의 component scan이나 기존 MySqlTestContainersConfig/RedisTestContainersConfig를 수정해 이를 우회하지 않는다. 부모/일반 JUnit의 testFixtures 사용은 그대로다.

정확한 자식 실행 경로는 batch의 sourceSets.main.runtimeClasspath + 전용 child-classes 디렉터리다. 여기서 SourceSet의 runtimeClasspath는 제품 classes·처리한 resources(application.yml/jpa.yml 등)·runtime 의존성을 포함한다. 의존성 configuration 이름인 runtimeClasspath만 전달해 자기 앱 출력/설정 파일을 빠뜨리지 않는다. 기존 testClasses 컴파일 결과 중 com/loopers/job/ranking/process 아래 진입점/실패 지원과 중첩 클래스만 child-classes로 복사한다. test resources나 다른 테스트 클래스/fixture JAR를 통째로 포함하지 않는다. 별도 source set/앱/프로젝트는 추가하지 않는다.

복사 준비 작업 prepareWeeklyRankingBatchChildClasses는 commerce-api의 build.gradle.kts에서 Sync 작업으로 등록한다. batch 스크립트에서 새로 등록한 작업을 api 구성 시점의 batch.tasks.named로 찾는 방식을 사용하지 않는다. 필요한 Java plugin/SourceSetContainer는 기존 루트의 subprojects 설정이 제공하고, api는 batch의 main/test SourceSet provider를 참조한다. 런타임 파일 해석은 task 입력/JVM 인자 provider에서 필요한 시점에 수행하며 구성 단계에서 files/asPath로 강제 해석하지 않는다. Gradle의 하위 프로젝트 평가 순서를 특정 순서로 가정하지 않는다.

```text
api의 prepareWeeklyRankingBatchChildClasses:
  dependsOn(":apps:commerce-batch:testClasses") // 경로로 의존, 지금 tasks.named 조회 안 함
  from(batch sourceSets.test의 output.classesDirs provider)
  include("com/loopers/job/ranking/process/**") // 중첩 클래스 포함
  into(api layout.buildDirectory의 weekly-ranking-batch-child/classes)

api test:
  dependsOn(자기 프로젝트의 prepareWeeklyRankingBatchChildClasses provider)
  childClasspath = files(batch sourceSets.main.runtimeClasspath provider, 위 출력 디렉터리)
  childClasspath를 별도 테스트 입력으로 추적
  CommandLineArgumentProvider에서 실제 childClasspath.asPath를 부모 JVM 설정으로 전달
  부모 테스트는 그 값으로 java -cp ... WeeklyRankingTestProcessMain 실행
```

작업은 컴파일 산출물을 준비할 뿐 batch test를 재귀 실행하지 않는다. 기존 W7 bootJar의 dependsOn/파일 입력·필터·전용 O11 작업·유효한 회귀를 바꾸지 않는다. 해당 보조 코드의 타입은 모두 전용 출력 패키지 안에 두고 제품 Spring runtime/BeanPostProcessor만 사용한다. spring-boot-test의 @TestConfiguration이나 JUnit/Mockito/TestContainers helper에 의존하지 않는다. 실제 Gradle 평가/기동 검증은 후속 승인된 구현에서 수행하며 이번에는 실행하지 않았다.

DB·Redis는 부모가 준비한 실제 접속값을 기존 OrderActivityStreamerChild와 같은 방식으로 명시 전달한다. MySQL JDBC URL/사용자와 Redis database/master/replica host·port는 명령줄 설정, MySQL 비밀번호는 기존 환경 변수 방식으로 전달하고 출력에 노출하지 않는다. 필요한 접속값이 없으면 개발 기본값으로 실행하지 않고 기동을 거절한다. 자식은 test 프로필과 ddl-auto=none·Batch initialize-schema=never·자동 launch OFF·명시 launch ON을 사용한다. 자식에 DB/Redis 컨테이너 기동 코드를 넘기지 않으므로 새 컨테이너를 준비하는 책임이 없다.

부모 DB가 가진 실제 A 품목을 자식의 freeze가 읽고, 부모의 새 JDBC 조회로 자식이 만든 snapshot/JobExecution과 점수/체크포인트를 확인하는 [§11.4](#114-실제-연결-한-건--결제-주문-ab) 관찰을 같은 DB 사용의 증거로 삼는다. 접속 URL 문자열이나 “기동 완료” 로그만으로 동일 DB라고 판정하지 않는다. 별도 확인용 테이블/추가 컨테이너를 만들지 않는다. 구현 중 자식이 testFixtures의 컨테이너 설정을 참조하거나 classpath에 포함한 것이 드러나면 통과시키지 않고 조립을 바로잡는다.

batch test의 WeeklyRankingTestProcessMain은 [§4.12](execution-recovery.md#412-제품테스트-main의-공통-시작-경로)의 CommerceBatchApplication.createApplication(WeeklyRankingFaultSupport.class)을 호출해 제품 initializer/Runner/Job/종료 코드 경로를 그대로 사용한다. 준비 child와 일반 비교/재시작/new r3/빈 주는 실패를 끄고 r2의 new에만 c 직전 실패를 켠다. 실패 옵션은 test.weekly-ranking.fail-before-product-id이며 식별 JobParameters에 넣지 않는다. 이 진입점/실패 지원은 src/test에만 두어 제품 bootJar/운영 옵션으로 노출하지 않는다.

WeeklyRankingFaultSupport는 클래스 수준 @Configuration/@Component/@TestConfiguration 등 스캔 스테레오타입이 없는 일반 클래스다. 안의 @Bean 메서드는 static으로 두고 서로 직접 호출하지 않으며 필요한 값을 메서드 인자로 주입한다. child는 위 addPrimarySources 경로로만, 같은 JVM의 실패/재시작 fixture는 해당 테스트의 @Import(WeeklyRankingFaultSupport.class)로만 명시 등록한다. Demo/NONE/일반 fixture의 component scan만으로 실패 지원이 등록되게 하지 않는다. Boot3.4.4 BeanDefinitionLoader의 명시 class 등록과 Framework6.2.5 ConfigurationClassUtils의 @Bean 메서드 후보 판정 소스를 대조했으며 실행 결과는 아니다.

실패 지원의 테스트 전용 계획 객체는 기본 비활성이고 armBeforeProduct(id)/disarm으로 제어한다. 같은 JVM fixture는 첫 launch 전9를 지정해 한 번 실패시키고, 두 번째 launch 전 비활성 상태를 확인한다. 데이터/metadata/Reader key는 바꾸지 않는다. child는 fail-before-product-id가 있을 때만 계획을 활성화하고 대상 item에서 한 번 예외를 낸 뒤 소모한다. 처리 ID의 관찰 기록은 실패 계획과 구분하며, 관찰 기록 초기화가 영속 checkpoint 초기화가 되지 않게 한다. 같은 지원을 쓰는 fixture는 공유 계획 객체를 동시에 조작하지 않고 종료 때 비활성화한다.

연결 테스트의 batch schema 준비도 같은 WeeklyRankingTestProcessMain을 child로 실행한다. job.name=NONE·Boot job.enabled=false·launch.enabled=false·JPA update·Batch initialize-schema=always와 부모 DB/Redis 설정을 전달한다. 업무 mode/period/executionId는 주지 않고 실패 계획도 비활성이다. 종료 코드0뿐 아니라 부모의 새 JDBC 조회로 다섯 랭킹 테이블/기본 metadata의 핵심 정의가 준비됐고 업무 execution을 만들지 않았는지 확인한 뒤 실제 new/restart로 넘어간다. 준비 child 종료 뒤 실제 실행 child는 none/never로 기존 데이터를 유지한다. 기존 테이블 부재를 강제하거나 create로 재생성하는 절차는 도입하지 않는다.

실패 설정은 테스트 BeanPostProcessor에서 실제 step-scoped Processor target을 감싸 process(c) 호출 직전에 한 번 예외를 던지는 방식이다. 기존 실제 processor의 다른 상품 계산과 writer/JobRepository는 그대로 사용한다. 테스트 추적은 실제 위임한 productId와 실패 지점을 child stdout에 남기고 부모가 캡처한다. 실패 child의 a/b 처리와 재시작 child의 c부터 처리를 검사하며 단순 stage 최종3행만으로 Reader 복원을 주장하지 않는다. JobParameters/Reader 이름을 바꿔 실패를 끄지 않는다.

공개 commit 뒤 최종 metadata 실패는 실제 연결 child에 추가하지 않고 batch fixture 테스트의 JobRepository 대역에서 통제한다. publish 업무 TX commit 뒤의 최종 Step metadata 갱신만 한 번 실패시키며 업무 TX 안의 checkpoint 갱신 실패와 구분한다. 실제 commit 확인 없이 afterStep 예외/로그만으로 metadata 공백을 재현했다고 쓰지 않는다. 테스트 대역 해제 후 공개 서비스의 멱등성을 확인하되 제품에서 금지한 UNKNOWN 강제 상태 변경은 하지 않는다.

### 11.6 실행 자원 소유권·최초 준비·정리

|자원|소유/준비|재시작 사이 보존·최종 정리|
|---|---|---|
|MySQL|기존 testcontainers fixture의 테스트 DB. 개발 DB에 연결하지 않음. 실행 전 대상 schema/기존 테이블을 확인하고 준비 실패를 빈 DB로 오인하지 않음|실패/new/restart/늦은 구매 revision 사이 데이터/metadata 보존. Flow는 다섯 ranking 테이블과 BATCH_* 테이블/행을 DROP/TRUNCATE/일괄 DELETE하지 않고 컨테이너 수명 종료까지 보존. 연결/실행 자원 종료는 별도 책임|
|최초 테이블|API가 자기 Entity, streamer가 source 두 테이블, batch 준비 context가 다섯 Entity/Batch metadata를 한 번 준비. 준비 context는 양쪽 자동 launch OFF, 명시 launch OFF|batch 준비 context를 닫은 뒤 실제 child는 ddl-auto=none/initialize-schema=never, initializer 적용. 준비를 재시작마다 반복하지 않음|
|PG/API/Redis|부모 테스트의 기존 fixture 서버·실제 API context와 소유 테스트 데이터. 자동 relay OFF, 기존 실제 relay를 명시 호출|새 relay 진입을 막고 in-flight 종료 확인. 공유 Redis 전체 FLUSH/다른 테스트 데이터 삭제를 하지 않음. 테스트 DB/Redis 소유 범위의 정리 규칙 사용|
|Kafka/streamer|기존 로컬 Kafka의 실행 전용 topic/group, 실제 streamer 자식 하나. 공유 broker를 테스트가 소유했다고 가정하지 않음|producer/relay·consumer 종료를 확인한 후 전용 topic/group만 정리. 공유 broker/다른 group 삭제·topic 설정 실험 추가 없음|
|batch child|부모가 각 프로세스 handle·출력·종료 코드·executionId를 추적. 부모의 제한 시간 안에서 실행|실패한 child의 종료를 확인한 뒤 다음 child 실행. 정리 때 살아 있는 child가 있으면 먼저 종료/종료 확인. 확인 실패 시 DB/Kafka를 지우지 않고 남은 자원과 원인을 보고|

사용자는 R3 보완으로 Flow가 공유 ranking_snapshot/ranking_snapshot_input/ranking_score_stage/ranking_result/ranking_publication과 BATCH_*를 최종 정리에서 삭제하지 않는 방향에 동의했다. Flow가 처음 생성한 것처럼 보여도 다른 테스트가 재사용할 수 있으므로 이 테이블의 삭제 권한을 주장하지 않는다. 이번 주/snapshot/실행 ID와 결과 증거를 남긴 뒤 relay·batch/streamer child·API/PG context·연결/관리 client 등 소유 실행 자원을 종료하고, 공유 랭킹/Batch 데이터는 기존 테스트 컨테이너의 수명 종료에 맡긴다. 이를 위해 개발 DB나 공유 broker를 종료하지 않는다.

의도적으로 보존한 공유 테이블은 cleanup 잔존 실패로 세지 않는다. 살아 있는 child/미종료 writer·반환하지 못한 연결 등은 기존대로 실패/잔존으로 보고한다. 기존 W7의 소유 source 테이블·전용 topic/group에 대한 종료 확인/정리 규칙은 유지하며 R3를 근거로 다른 테이블 삭제 범위를 늘리지 않는다. API 단독 SQL helper의 정리는 본인이 생성/소유한 범위에만 한정하고 Flow의 이 보존 규칙을 일반 DROP 허가로 해석하지 않는다.

모든 대기는 기존 W7 방식의 전체 deadline과 단계별 준비/관찰 timeout 안에서 수행하며 sleep 후 성공 추정 대신 실제 DB/record/child 준비 조건을 확인한다. timeout/연결 실패/cleanup 잔존은 통과가 아니다. 자식 강제 종료/실패 정리는 테스트 소유 프로세스에 한정하고 공유 인프라 기동/종료 권한을 확대하지 않는다. Batch test의 단일 context 재시작과 실제 연결의 새 JVM 재시작은 각각 영속 checkpoint/프로세스 간 보존이라는 책임을 갖는다.

### 11.7 완료 증거·실행 순서·범위

후속 구현/실행은 원문 작은 DB probe → Batch fixture → API 계약 → 실제 연결1건 → 관련 회귀/Checkstyle/ArchUnit 순서로 진행한다. 새 검증의 실제 클래스/필터에 맞춰 명령을 확정한다. 이 문서 작성으로 테스트 실행이나 인프라 기동이 승인된 것은 아니다.

현재 ArchitectureTest는 commerce-api와 commerce-streamer에 있고 commerce-batch에는 없다. 따라서 기존 API/streamer의 관련 ArchUnit 결과와 batch의 JUnit/Checkstyle 결과를 구분해 기록한다. batch check를 실행했다고 batch 계층이 ArchUnit으로 검증됐다고 주장하지 않으며 이번 보완으로 신규 batch ArchUnit을 추가하지 않는다. 인덱스로 source Entity를 변경하는 구현에는 streamer의 관련 회귀/검사도 포함한다.

구현 첫 단계에서는 원문 §3의 dependencyInsight로 현재 commerce-batch runtimeClasspath의 spring-batch-core 실제 해석 버전을 확인하고 JDBC driver 버전도 확인한다. 캐시에 여러 버전이 있다는 사실이나 BOM 선언만으로 적용 버전을 확정하지 않는다. 근거 소스 버전과 다르면 API/동작 차이를 검토하고 필요한 설계 수정은 사용자 확인 후 반영한다. 이 확인을 위해 임의 의존성 업그레이드나 기대값 변경을 하지 않는다.

대표 결과에는 실행 당시 코드/변경 상태·명령·시각·실제 테스트 수/failure/error/skip, instanceId/실패·재시작 executionId, 실제 reader key/위치·처리 ID, input/result checksum·기대 점수/순위, 정상/재시작/늦은 구매 반영 revision과 API 응답·늦은 구매 식별값, A 재발행의 Kafka 위치/소비 관찰, child 종료/cleanup 잔존을 남긴다. PG 요청/응답 증거는 기존 fixture의 안전한 식별값/집계 수준으로 보존하고 비밀값/payload 전체를 새 로그에 추가하지 않는다.

정상 비교본과 재시작 결과의 원문4행/기대 순위 대조는 fixture가 맡고, 실제 연결도 A만 반영한 정상/재시작 checksum·API items를 비교한 뒤 B의 새 revision 기여를 확인한다. 제출 PR/실행 기록에는 이 책임 분리와 한계를 명시한다. 과거 W7 결과, UP-TO-DATE/FROM-CACHE, 테스트0건/skip, 실제 Kafka/PG 연결 실패를 이번 통과로 기록하지 않는다. 수치 성능 주장·잠금 대기 실험·W7 모든 장애의 종단 재현·별도 수동 검증 세트는 추가하지 않는다.

현재 상태는 위 시나리오/책임/자원/증거의 설계 작성이다. 실제 데이터 투입·테스트 코드·Gradle 변경·실행물 생성·실험·앱/인프라 기동·Git stage/ignore 변경은 수행하지 않았다.
