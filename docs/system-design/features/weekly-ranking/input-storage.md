# 입력 고정·저장 구조·checksum

이 문서는 [승인된 주간 랭킹 설계](../weekly-ranking.md)의 상세 문서다. 승인 상태와 전체 읽기 순서는 진입 문서를 따른다. 기존 절 번호는 유지하며 문서 사이에 분산된 절은 [절 번호 안내](../weekly-ranking.md#절-번호-안내)로 찾는다.

## 문서 간 읽기 경로

- [Job 실행·식별·재시작·복구](execution-recovery.md)
- [상품별 계산·완성본 공개](calculation-publication.md)
- [랭킹 API 계약](api-contract.md)
- [원문 대응·검증 구성](verification.md)

## 이 문서의 기준 절

[§3](#3-입력-고정) · [§8.1](#81-메서드의-입력과-책임) · [§8.2](#82-입력-고정과-재사용의-분기) · [§9](#9-db입력-고정-상세설계)

## 3. 입력 고정

### 3.1 목적과 처리 경계

구매 활동 원본은 늦은 전달로 늘어날 수 있다. 계산 전에 이번에 사용할 구매 내역을 별도로 복사하고, 이후 계산과 재시작은 그 복사본을 사용한다. 이를 입력 snapshot이라고 부른다. snapshot은 모든 사건의 도착 완료를 증명하지 않으며, 입력 고정 때 관찰한 구매 사실의 묶음이다.

사용자는 구매 내역 복사와 입력 완성 기록을 한 로컬 DB 트랜잭션에서 처리하기로 합의했다. 중간 실패 시 부분 입력으로 계산하지 않는다. 완료한 입력은 재시작 때 다시 복사하거나 늦은 구매를 추가하지 않는다.

입력은 사용자가 선택한 단일 `INSERT INTO … SELECT …`로 복사한다. DB 안에서 복사하므로 애플리케이션 왕복을 줄일 수 있지만 속도·잠금·일관된 읽기 성질을 이번에 측정하거나 확인한 것은 아니다. 사용자는 기본 REPEATABLE READ를 유지하고 격리 수준을 변경하지 말라고 지시했다. 단일 TX라는 사실만으로 여러 SQL이 같은 읽기 시점을 사용한다고 단정하지 않는다. 정확한 SQL·읽기 방식과 근거/한계는 [§9.7](#97-입력-복사-sql과-읽기-시점--기본-격리-유지)을 따른다.

### 3.2 두 테이블로 나누기

테이블 이름·타입·PK/매핑의 상세 초안은 [§9.1~9.10](#91-다섯-테이블과-공통-열)을 따른다. snapshot 상태는 [§3.4](#34-snapshot-상태)의 READY·COMPLETED를 사용한다. 아래 논리 데이터와 유일성으로 검토한다.

|논리 테이블|담을 내용|단위·유일성|
|---|---|---|
|`ranking_snapshot`|snapshotId, 기간, revision, 입력 고정 시점, 입력 건수, checksum 등 manifest|snapshot당 1행. snapshotId 유일, 기간·revision 조합 유일|
|`ranking_snapshot_input`|snapshotId, orderId, productId, quantity, confirmedAt|복사한 구매 품목당 1행. snapshotId·orderId·productId 조합 유일|

원문 fixture에서는 정보 1행에 구매 품목 4행이 연결된다. 입력에는 상품별 합계로 줄인 3행이 아니라 원본 구매 품목 4행을 보존한다. 합계는 후속 계산 단계에서 구한다.

합의한 처리 순서는 다음과 같다. 구체적인 TX·SQL·오류 분기 의사코드는 [§8.2](#82-입력-고정과-재사용의-분기)·[§9.7~9.8](#97-입력-복사-sql과-읽기-시점--기본-격리-유지)을 따른다.

```text
한 DB 트랜잭션:
  스냅샷 정보 생성
  같은 주의 구매 내역을 일관된 읽기로 복사
  복사한 입력에 맞는 manifest·입력 완성 기록
commit 성공 뒤 계산에 사용
중간 실패: 이번 입력과 정보 저장을 함께 rollback
```

### 3.3 물리 FK를 두지 않기

사용자는 정보/입력 두 테이블 사이에 물리 FK를 두지 않기로 선택했고, 후속 대화에서 stage·완성 결과·공개 pointer가 snapshot 정보를 참조하는 나머지 세 관계도 FK 없이 통일하기로 했다. 입력·stage·완성 결과는 scalar snapshotId, 공개 pointer는 publishedSnapshotId로 연결한다. 원본 주문·상품도 기존 scalar ID로 참조하며 물리 FK를 추가하지 않는다([§9.10](#910-entity-매핑쓰기-소유권과-최초-스키마-준비)).

- 문제·선택: 같은 batch가 관리하는 정보와 입력을 연결하되 DB의 물리 FK는 사용하지 않는다. 생성·검증·보존을 batch의 처리 계약으로 관리한다.
- 대안: 입력의 snapshotId에 FK를 두면 부모 없는 입력 삽입과 부모만 삭제하는 변경을 DB가 막는다. 그 대신 참조 순서·삭제 처리에서 제약을 따라야 한다. 같은 소유자 안의 관계여서 FK도 타당한 대안이었다.
- 선택 이유와 비용: 사용자가 물리 FK 없는 연결을 선택했다. 기존 소비 모델도 scalar ID를 사용하지만 저장소 전체의 FK 금지 규칙은 아니다. 성능 개선·잠금 감소를 측정한 선택으로 설명하지 않는다.
- 보호 범위: 같은 TX의 생성과 유일 제약은 각각 부분 저장·중복을 막지만 FK처럼 부모 존재를 자동 검사하지 않는다. 잘못된 직접 SQL·다른 쓰기 경로·부모만 삭제하면 고아 입력이 생길 수 있다. 정합성 검사와 자동 수선 금지는 [§8.2](#82-입력-고정과-재사용의-분기), 삭제 기능 미제공과 보존 경계는 [§9.1](#91-다섯-테이블과-공통-열)·[§9.10](#910-entity-매핑쓰기-소유권과-최초-스키마-준비)을 따른다.
- 재검토 조건: 별도 writer·삭제/정리 기능·직접 SQL 변경 요구가 생기거나 고아 입력이 관찰되면 FK 미사용의 비용과 보호 수단을 재검토한다.

확장 관계의 보호 범위도 같다. stage/완성 결과 쓰기는 해당 snapshot의 존재·처리 상태를 전제로 하고, 공개는 현재/대상 snapshot의 기간·revision·완성 상태를 확인한다. 단일 TX와 업무 유일 키로 부분 저장·중복을 막지만 FK의 자동 부모 존재/삭제 방어를 대체한다고 주장하지 않는다. 직접 SQL로 잘못된 ID를 저장하거나 snapshot만 삭제하는 손상은 여전히 가능하다. 추가 자동 수리·연쇄 삭제·정기 검사 기능을 이 선택만으로 도입하지 않는다.

### 3.4 Snapshot 상태

사용자는 snapshot 상태를 READY·COMPLETED 두 개로 두기로 선택했다.

|상태|의미|
|---|---|
|`READY`|구매 내역과 manifest의 입력 고정 완료. 고정 입력을 변경하지 않고 계산·재시작 가능|
|`COMPLETED`|점수 검증·순위 생성·완성 결과 저장과 완료 기록까지 commit됨. 현재 공개 여부를 뜻하지 않음|

입력 정보·입력·manifest를 한 TX에 저장하므로 정상적인 새 snapshot은 commit 후 READY로 보인다. 입력 생성 중 확정 rollback이면 이번 snapshot은 남지 않는다. 빈 입력도 manifest를 완성하면 READY다. 계산 중과 계산 실패에서도 READY를 유지하며 실행 진행·실패·checkpoint는 Batch metadata로 관리한다. 완성 결과와 COMPLETED 변경은 한 TX에서 commit한다. 최신본이 있어 공개를 생략해도 완성 저장에 성공한 snapshot은 COMPLETED다.

PREPARING·RUNNING·FAILED·PUBLISHED를 snapshot의 별도 상태로 중복 저장하지 않는다. 공개 여부는 기간별 pointer로 판단한다. 비용은 실행 상태와 현재 공개 여부를 확인할 때 각각 Batch metadata·pointer를 함께 읽어야 한다는 점이다. COMPLETED만 보고 Batch 최종 metadata까지 성공했다고 추론하지 않는다.

중간 상태를 추가하는 대안은 입력을 여러 TX로 나눠 생성하거나 별도 worker가 미완성 입력을 이어 만드는 경우에 필요할 수 있다. 현재 한 TX 입력 고정 구조에는 도입하지 않는다. commit 결과 불명은 메모리의 마지막 상태만으로 확정하지 않고 저장 상태를 확인해야 하며 수동 확인/재시작/중단 절차는 [§5.6~5.8](execution-recovery.md#56-재시작-전-확인-순서와-지원-범위)을 따른다. SQL 타입·상태 변경·손상 검사는 §6·§8·§9를 따르고 수동 복구의 지원 범위는 [§5.6~5.8](execution-recovery.md#56-재시작-전-확인-순서와-지원-범위)을 따른다.

### 3.5 입력·결과 checksum 규칙

사용자는 다음 정렬·직렬화와 SHA-256을 사용하기로 합의했다. 원문이 요구한 입력/결과 내용 비교의 규칙이며 업무 점수를 다시 계산하는 검증을 추가하는 것이 아니다.

|대상|정렬 순서|각 행의 필드 순서|
|---|---|---|
|입력|orderId 오름차순·productId 오름차순|orderId, productId, quantity, confirmedAt|
|결과|rank 오름차순|productId, score, rank|

- UTF-8로 인코딩한다. 필드 사이는 TAB, 각 행 끝에는 LF를 넣고 마지막 행에도 LF를 넣는다. 빈 입력/결과는 행이 없으므로0바이트를 대상으로 한다.
- 확정 시각은 UTC·소수초6자리로 쓰며 formatter는 [§8.6](calculation-publication.md#86-클래스-배치포트와-호출-순서)을 따른다.
- 정수는 소수점·쉼표 없는 십진 문자열을 쓴다. 특히 BigInteger 점수를 long·double로 바꾸지 않는다.
- 실행 ID·실행 시각·snapshotId·현재 상품명은 내용 checksum에서 제외한다. 다른 snapshot의 같은 내용도 비교할 수 있으며 소속 정보는 manifest/실행 기록으로 별도 확인한다.
- 결과는 저장된 전체 ranking의 productId·score·rank다. API의 현재 노출 필터·limit을 적용한 목록이나 HTTP JSON 전체를 hash하지 않는다.

SHA-256 digest는 입력 크기와 관계없이32바이트이며 16진 문자열로 나타내면64글자다. DB는 소문자16진 CHAR(64)로 저장하며 계산 위치는 입력 [§8.2](#82-입력-고정과-재사용의-분기)·결과 [§6.5~6.9](calculation-publication.md#65-공개-전-완성-여부-확인-sql)를 따른다. 입력 manifest의 checksum과 완성 결과의 checksum을 만드는 것, 테스트 코드에서 두 실행의 checksum과 수작업 기대 점수/순위를 대조하는 것은 구분한다. 같은 잘못된 결과도 checksum은 같을 수 있으므로 checksum만으로 계산 정답을 증명하지 않는다. 공개 단계의 완성 확인 범위는 [§6.2](calculation-publication.md#62-공개-전-완성-확인과-테스트의-구분)를 유지한다.

사용자는 전체 내역이 길면 checksum 생성 비용도 커지는지 질문했다. 비용은 실제로 발생한다. 내용 전체를 읽어 직렬화·hash하므로 처리할 바이트 수에 따라 CPU·읽기 비용이 늘고, 순서를 만들기 위한 DB 정렬 비용도 있을 수 있다. INSERT SELECT 복사만으로 애플리케이션 checksum이 자동 생성되는 것은 아니다. 별도 조회가 필요하면 추가 DB 읽기·전송과 입력 고정/공개 TX 시간에 반영해야 한다. 아직 시간·처리량을 측정하지 않았다.

구현 검토 방향은 전체 문자열을 먼저 합쳐 메모리에 보관하지 않고 정렬된 행을 순차 인코딩해 digest에 누적하는 것이다. hash 누적용으로 전체 데이터 크기의 버퍼를 만들 필요는 없지만 Reader/정렬/드라이버의 실제 메모리 사용까지 일정하다고 단정하지 않는다. 복사한 입력과 완성 결과의 별도 정렬 읽기에서 계산하며 같은 TX 안에서 manifest/완료 기록과 저장한다. chunk별 digest를 단순 결합해 전체 내용 hash를 대신하거나 상태를 메모리에만 두고 재시작해도 같다고 가정하지 않는다.

## 입력 고정 메서드 계약

기존 §8의 입력 고정 계약이다. 상품 계산·공개 처리의 나머지 계약은 [§8 처리 계약](calculation-publication.md#8-입력-고정계산의-처리-계약)을 따른다.

### 8.1 메서드의 입력과 책임

|처리|입력·출력|책임·경계|
|---|---|---|
|`WeeklyRankingParameters.parse`|[§4.3](execution-recovery.md#43-다음-revision-조회와-기존-batch-api-사용)의 JobParameters → WeeklyRankingRequest|batch 어댑터가 타입/identifying 검사 후 domain의 WeeklyRankingRequest.of(String, Long, String)로 기간/ID 검증|
|`freezeOrReuseInput`|WeeklyRankingRequest → RankingInputManifest|새 입력이면 품목 복사·manifest를 한 TX에 저장. 기존 입력은 [§8.2](#82-입력-고정과-재사용의-분기)대로 검사/재사용|
|`weeklyRankingProductReader`|snapshotId → 오름차순의 서로 다른 Long productId|JdbcCursorItemReader, step scope, 영속 상태 이름/key는 [§8.4](calculation-publication.md#84-stepreader-상태와-자원-수명)|
|`calculateProductScore`|snapshotId·productId → RankingProductScore|같은 고정 입력의 SUM을 BigInteger로 반환. 실제 SQL과 변환은 [§8.5](calculation-publication.md#85-상품-합계와-절대-점수-저장-sql)|
|`writeScores`|snapshotId·chunk의 RankingProductScore → 저장|절대 점수 upsert. chunk의 결과/checkpoint와 같은 실제 TX에 참여|

WeeklyRankingRequest는 LocalDate periodStart·periodEnd, long revision, String snapshotId의 불변 값이다. 날짜의 엄격한 형식/월요일 검증은 [§7.7](api-contract.md#77-요청자-식별과-입력-값-객체)과 같되 API 클래스에 의존하지 않고 batch 내부에서 수행한다. revision은 양수 Long이며 snapshotId는 `weekly-` + 시작일의 `uuuuMMdd` + `-r` + 선행0 없는 revision 십진 표현과 정확히 같아야 한다. periodEnd는 시작일+7일이다. 별도 DB 날짜 범위 guard는 추가하지 않는 [§7.7](api-contract.md#77-요청자-식별과-입력-값-객체)의 사용자 결정을 따른다. validator와 Step은 같은 변환 메서드를 사용하고 식별 parameter를 문자열로 재해석해 다른 값으로 보정하지 않는다.

RankingInputManifest는 요청 식별값, READY/COMPLETED, LocalDateTime inputFixedAt(UTC), long inputCount/productCount, String inputChecksum 및 nullable 결과 checksum/완료 시각을 담는다. RankingProductScore는 Long productId·BigInteger score의 불변 값이다. JDBC 객체·managed Entity를 이 값에 담지 않는다. 요청 불일치는 INVALID_REQUEST 또는 SNAPSHOT_CONTRACT_MISMATCH, 저장 불변식 위반은 INCONSISTENT_INPUT으로 구분한 batch 내부 RankingInputException(RuntimeException)으로 전파한다. SQL·드라이버 예외는 원인을 보존해 그대로 실패시킨다. HTTP ErrorType을 batch로 가져오지 않는다.

현재 이름·현재 삭제 여부는 API의 표시·제외 조건이다. 계산 중 삭제된 상품도 frozen 입력에 있으면 계산 대상에서 빼지 않는다. 그렇지 않으면 같은 snapshot의 점수·순위가 현재 상품 변경에 따라 달라진다.

### 8.2 입력 고정과 재사용의 분기

```text
freezeOrReuseInput(request): // public MANDATORY; freeze tasklet의 기존 DB TX
  요청 snapshotId와 기간/revision으로 기존 metadata 조회
  if 해당 ID와 해당 기간/revision이 서로 다른 행을 가리킴:
    SNAPSHOT_CONTRACT_MISMATCH; 덮어쓰기/자동 삭제 금지
  if 기존 snapshot 존재:
    ID/기간/revision 일치, 삭제 없음, READY/COMPLETED 확인
    inputFixedAt/inputChecksum 존재, 건수 >= 0, productCount <= inputCount 확인
    COMPLETED라면 완료 시각/resultChecksum 필수
    저장된 복사 입력에서 count·distinct product count·정렬 checksum 확인
    manifest와 불일치: INCONSISTENT_INPUT; 재복사/자동 수선 금지
    return 기존 manifest // 현재 구매 원본은 조회하지 않음
  else:
    이 ID의 input/stage/result 또는 이 ID를 가리키는 pointer 존재 여부 확인
    하나라도 남아 있음: INCONSISTENT_INPUT // 고아 저장을 새 입력으로 채택하지 않음
    §9.7의 단일 INSERT SELECT 실행
    같은 연결에서 SELECT UTC_TIMESTAMP(6) → inputFixedAt
    복사 입력에서 inputCount·productCount 조회
    정렬한 입력을 행 콜백으로 읽어 checksum·읽은 행 수 계산
    읽은 행 수 != inputCount: INCONSISTENT_INPUT
    유효 manifest의 READY metadata를 INSERT; 저장 행 수는1
    return 새 manifest
  tasklet 종료 전 Step execution context 갱신에 참여
  commit 성공 뒤 다음 계산 Step; 확정 오류는 전체 rollback
  commit 결과 불명은 수동 복구 절차로 보내며 성공/rollback 추정 금지
```

빈 주는 inputCount/productCount=0과0바이트 SHA-256을 가진 READY manifest를 저장한다. 복사 SQL의0행은 정상이며 빈 입력이라는 이유로 실패하거나 미공개로 끝내지 않는다. 반면 기존 manifest가 말하는 입력 건수와 실제 복사본이 다르면 손상이다.

기존 입력의 checksum 대조는 입력 고정 Step이 실제 다시 호출된 경로에서 수행한다. 정상 재시작에서는 완료한 freeze Step을 건너뛰므로 매 chunk·매 API 요청마다 이를 반복하지 않는다. 현재 원본과 과거 입력을 대조하는 검사나 계산 점수 정답 검사를 추가한 것이 아니다. 이 재진입에는 복사본을 한 번 더 읽는 비용이 있지만 같은 ID의 저장 입력이 달라졌는데 정상 재사용하는 것을 막는다. 완료한 freeze Step을 건너뛴 뒤 수동 SQL로 입력을 고친 손상까지 매번 검출하는 별도 감시 기능은 제공하지 않는다.

## 9. DB·입력 고정 상세설계

사용자와 상세화하고 전체 설계 승인에 포함한 DB·입력 고정 계약이다. 기합의 타입·구조·신규 랭킹 테이블 간 물리 FK 미사용과 사용자 지시인 기본 REPEATABLE READ 유지를 따른다. 내부 열/인덱스/저장 표현을 구체적으로 제안하고 inputFixedAt 기록 위치는 사용자 확인을 반영했고 스키마 매핑은 [§9.10](#910-entity-매핑쓰기-소유권과-최초-스키마-준비)을 따른다. 이 절은 승인된 설계 기준이며 실제 DDL/동작 검증은 아직 전이다.

### 9.1 다섯 테이블과 공통 열

기존 모델과 공통 BaseEntity를 맞추기 위해 신규 batch 관리 Entity에 숫자 surrogate PK와 별도 업무 유일 키를 두는 안을 제안한다. 각 테이블에 `id BIGINT AUTO_INCREMENT PRIMARY KEY`, `created_at DATETIME(6) NOT NULL`, `updated_at DATETIME(6) NOT NULL`, `deleted_at DATETIME(6) NULL`을 둔다. snapshotId는 사용자가 합의한 사람이 읽는 업무 ID이며 숫자 PK를 API에 노출하지 않는다. 복합 자연 PK만 두면 열/보조 키를 줄일 수 있지만 기존 BaseEntity와 다른 매핑을 설계해야 하므로 이 초안에서는 숫자 PK를 유지한다. 이는 논리 유일성을 숫자 PK만으로 대체하는 안이 아니다.

아래 다섯 테이블은 commerce-batch가 쓰기를 소유한다. API는 자기 조회 포트의 JDBC 구현으로 읽고 batch 앱 클래스·Entity를 의존성으로 가져오지 않는다. streamer는 기존 구매 활동 쓰기를 유지한다. modules/jpa에는 신규 업무 Entity를 넣지 않는다. 별도 앱의 JPA create가 다른 앱 소유 테이블을 관리하게 하지 않는다. Entity/매핑 원본과 최초 준비·보존 경계는 [§9.10](#910-entity-매핑쓰기-소유권과-최초-스키마-준비)을 따른다. 실행 플래그는 [§4.7](execution-recovery.md#47-실행-옵션과-최초-준비-전제), 최초 context 준비/보존은 [§11.5~11.6](verification.md#115-앱-간-실행-연결과-테스트-전용-실패-주입)을 따른다.

공통 표현 초안:

- `snapshot_id`: VARCHAR(64), ASCII binary collation, NOT NULL. canonical 생성 문자열을 사용하며 임의 공백·선행0·대소문자 차이를 같은 ID로 허용하는 별도 정규화는 하지 않는다. canonical 생성 규칙은 [§8.1](#81-메서드의-입력과-책임)을 따른다. 추가 DB 날짜 범위 검사는 두지 않는다([§7.7](api-contract.md#77-요청자-식별과-입력-값-객체)).
- 업무 날짜: DATE. 업무 순간: DATETIME(6)에 UTC 저장. confirmedAt은 원본 마이크로초를 그대로 복사한다.
- checksum: CHAR(64), ASCII binary collation의 소문자16진 문자열 제안. SHA-256 bytes를64글자로 표현하고 API에 노출하지 않는다. [§3.5](#35-입력결과-checksum-규칙)의 hash 입력 규칙은 바꾸지 않는다.
- 사용자 후속 합의에 따라 입력·stage·완성 결과·공개 pointer와 snapshot 정보 사이에 물리 FK를 두지 않는다. 그 밖의 관계는 이 선택으로 임의 변경하지 않는다. 원본 주문/상품은 기존 scalar ID 참조·과거 사실 보존 원칙에 맞춰 제약 구성을 대조한다.
- 자동 삭제·보존기간·purge·고객 삭제 API를 이 설계에 추가하지 않는다. BaseEntity 열이 있다는 이유로 snapshot/입력/결과의 soft delete를 업무 기능으로 제공하지 않는다. 삭제/정리가 필요하면 FK 없는 연결과 공개본 보존을 포함해 별도 논의한다.

### 9.2 Snapshot 정보 — ranking_snapshot

공통 열에 더해 다음 열을 제안한다.

|열|SQL 타입·null|의미|
|---|---|---|
|snapshot_id|VARCHAR(64), NOT NULL|기합의 업무 식별자|
|period_start / period_end|DATE, NOT NULL|UTC 주간 시작/미포함 끝|
|revision|BIGINT, NOT NULL|새 계산 준비 시 자동 결정해 실행 parameter로 전달하는 같은 주 계산본 번호. 재시작은 기존 값 유지. 양수|
|status|VARCHAR(16), NOT NULL|READY 또는 COMPLETED|
|input_fixed_at|DATETIME(6), NOT NULL|복사 SQL 성공 직후 같은 TX에서 얻은 DB UTC 시각. [§9.7](#97-입력-복사-sql과-읽기-시점--기본-격리-유지)|
|input_count|BIGINT, NOT NULL|복사한 주문·상품 구매 품목 수|
|product_count|BIGINT, NOT NULL|복사 입력의 서로 다른 상품 수. 공개 전 예상 상품 수|
|input_checksum|CHAR(64), NOT NULL|[§3.5](#35-입력결과-checksum-규칙) 입력 checksum|
|result_checksum|CHAR(64), nullable|완성 전체 ranking checksum. READY에서는 없음, COMPLETED에서 필요|
|completed_at|DATETIME(6), nullable|완성 결과와 같은 TX에서 기록. 현재 공개 시각과 구분|

유일 키 `uk_rs_snapshot(snapshot_id)`, `uk_rs_period_revision(period_start, revision)`를 둔다. 입력 건수와 상품 수는0 이상이고 product_count는 input_count 이하이다. 주간 날짜·ID/기간/revision 일치·상태별 manifest 필수값은 생성/상태 메서드에서 지킨다. enum은 STRING 매핑을 사용한다([§9.10](#910-entity-매핑쓰기-소유권과-최초-스키마-준비)). 이번에 별도 DB CHECK 목록을 추가하지 않으며 필수값/유일성은 명시한 열 제약을 따른다. READY를 만들 때 유효 manifest를 모두 준비하며 임시0건/가짜 checksum으로 commit하지 않는다.

### 9.3 복사 입력 — ranking_snapshot_input

|열|SQL 타입·null|의미|
|---|---|---|
|snapshot_id|VARCHAR(64), NOT NULL|부모 업무 식별자. 물리 FK 없음|
|order_id / product_id|BIGINT, NOT NULL|원본 양수 long 식별자|
|quantity|BIGINT, NOT NULL|원본의 양수 long 수량. 합계 score와 다른 타입|
|confirmed_at|DATETIME(6), NOT NULL|해당 주문의 최초 확정 순간|

`uk_rsi_snapshot_order_product(snapshot_id, order_id, product_id)`는 같은 snapshot의 구매 품목 중복을 막고 입력 checksum의 정렬 읽기에 맞춘다. `idx_rsi_snapshot_product(snapshot_id, product_id)`는 DISTINCT 상품 ID Reader와 해당 상품 SUM 조회에 맞춘다. 두 키는 열 순서와 목적이 다르다. 실제 index 선택·정렬 비용·성능은 검증하지 않았다. 원본 데이터와 같은 양수 ID/quantity 제약을 유지하는 안이며 snapshot 입력의 수정·품목 추가 경로는 제공하지 않는다.

### 9.4 계산 중 점수 — ranking_score_stage

|열|SQL 타입·null|의미|
|---|---|---|
|snapshot_id|VARCHAR(64), NOT NULL|해당 입력/계산 묶음|
|product_id|BIGINT, NOT NULL|상품 식별자|
|score|DECIMAL(65,0), NOT NULL|기합의 절대 점수|

`uk_rss_snapshot_product(snapshot_id, product_id)`를 둔다. Writer는 같은 키에 절대 점수를 upsert한다. 원본 구매 수량 재합산으로 stage 값을 재검증하는 운영 검사는 추가하지 않는다. 추가 score 양수 CHECK를 사용자가 승인한 것으로 해석하지 않는다. snapshot 정보와 물리 FK를 두지 않으며 테이블은 API 응답 대상으로 사용하지 않는다.

### 9.5 완성 결과 — ranking_result

|열|SQL 타입·null|의미|
|---|---|---|
|snapshot_id|VARCHAR(64), NOT NULL|완성된 계산 묶음|
|product_id|BIGINT, NOT NULL|상품 식별자|
|score|DECIMAL(65,0), NOT NULL|저장한 계산 점수|
|calculated_rank|BIGINT, NOT NULL|score DESC·productId ASC의1부터 시작하는 순번|

`uk_rr_snapshot_product(snapshot_id, product_id)`, `uk_rr_snapshot_rank(snapshot_id, calculated_rank)`를 둔다. 전자는 snapshot 내 같은 상품의 중복 결과, 후자는 같은 계산 순위 중복을 막는다. rank 유일 키는 snapshot의 순위순 읽기에도 사용한다. 현재 상품명·노출 상태를 복사하지 않는다. SQL 열 이름은 예약어/함수명 rank와 구분해 calculated_rank로 제안하고 API 필드 이름은 합의한 rank를 유지한다. snapshot 정보와 물리 FK는 두지 않는다. 순위 생성 SQL·완성 재실행 확인은 [§6.5~6.9](calculation-publication.md#65-공개-전-완성-여부-확인-sql)를 따른다.

### 9.6 기간별 공개본 — ranking_publication

|열|SQL 타입·null|의미|
|---|---|---|
|period_start|DATE, NOT NULL|대상 주|
|published_snapshot_id|VARCHAR(64), NOT NULL|현재 공개 snapshotId. 미공개 주에는 pointer 행 자체가 없음|
|published_at|DATETIME(6), NOT NULL|현재 pointer 변경을 기록한 순간|

`uk_rp_period(period_start)`로 기간별1행을 보장한다. revision을 pointer에 중복 저장하지 않고 연결한 snapshot의 숫자 revision으로 비교한다. 문자열 snapshotId의 사전식 순서로 신구를 비교하지 않는다. snapshot 정보와 물리 FK는 두지 않는다. 사용자 합의에 따라 최초 공개 TX에서 유효 snapshotId로 INSERT하고 이후 조건부 UPDATE한다. nullable 빈 기간 행을 미리 만들자는 제안은 채택하지 않았다. INSERT·UPDATE0행 분류·SQL/호출 순서는 [§6.7~6.9](calculation-publication.md#67-현재-pointer의-조건부-update와0행-분류)를 따른다.

### 9.7 입력 복사 SQL과 읽기 시점 — 기본 격리 유지

사용자는 격리 수준을 바꾸지 않고 기본 REPEATABLE READ를 유지하라고 명시했다. 앞선 입력 tasklet만 READ COMMITTED로 바꾸자는 제안은 철회했다. 전역·DataSource·Step·TransactionTemplate 어느 위치에서도 이번 기능을 위해 격리를 변경하지 않는다. 같은 사유의 변경 승인을 반복 요청하지 않는다.

원문 §2의 일관된 DB 읽기와 입력/manifest의 같은 TX 요구를 기존 단일 INSERT SELECT와 잠금 보호로 구체화한다. 일반 SELECT의 과거 MVCC read view를 복사한다고 설명하지 않는다. MySQL 8.0 Reference Manual의 Locks Set by Different SQL Statements in InnoDB는 기본 격리의 INSERT SELECT가 원본에 shared next-key lock을 사용한다고 명시한다. Phantom Rows는 next-key lock이 읽은 인덱스 레코드와 gap을 보호해 해당 범위의 새 삽입을 막는 이유를 설명한다. Locking Reads는 잠금 해제가 TX commit/rollback 경계임을 설명한다. 이는 공식 규칙이며 현재 DB에서 재현한 실행 결과는 아니다.

설계 판단: 기존 원본 구매 활동은 commit 뒤 수정·삭제 경로가 없는 불변 사실이고, 이번 TX는 원본을 수정하지 않는다. 단일 복사 SQL이 성공하면 복사한 범위의 기존 행·삽입 gap은 그 TX의 잠금으로 보호된다. 따라서 복사 완료 뒤 잠금을 보유한 시점의 원본 대상 집합과 복사본을 대응시키고, 그 복사본에서 manifest를 만들어 같은 TX로 commit하는 계약을 채택한다. 이 대응은 공식 잠금 규칙과 불변 원본 계약으로부터의 설계상 추론이며 이번에 실제 잠금을 관찰한 결과는 아니다. 별도 잠금 경쟁·대기 실험을 원문 필수 검증으로 추가하지 않는다. 원문이 특정 격리명이나 잠금 없는 MVCC만을 명시한 것은 아니다. 모든 조회를 TX 시작 시각의 동일 read view로 취급하거나 max(id)를 cutoff로 삼지 않는다.

```sql
INSERT INTO ranking_snapshot_input
    (snapshot_id, order_id, product_id, quantity, confirmed_at,
     created_at, updated_at, deleted_at)
SELECT ?, order_id, product_id, quantity, confirmed_at,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL
FROM product_purchase_activity
WHERE confirmed_at >= ? AND confirmed_at < ?
```

snapshotId와 UTC의 주 시작/미포함 끝을 [§9.12](#912-native-jdbc의-날짜utc-시각-바인딩읽기)의 LocalDateTime/setObject 계약으로 prepared parameter에 전달한다. 현재 Product/Brand와 조인해 입력을 줄이지 않는다. 고정한 입력에 뒤늦게 도착한 구매를 추가하지 않는다. 대상 숫자 PK는 AUTO_INCREMENT로 생성하며 원본 활동 PK·감사 시각을 내용 checksum에 섞지 않는다. JdbcTemplate native INSERT는 BaseEntity callback을 실행하지 않으므로 감사 열을 SQL에서 명시한다. 원본 복사는 단일 SQL로 수행하고 잠긴 원본을 건너뛰는 SKIP LOCKED·IGNORE·페이지별 추가 복사로 정상 결과를 만들지 않는다.

건수·예상 상품 수는 현재 구매 원본을 다시 COUNT하지 않고 자기 TX에서 복사한 입력을 조회한다.

```sql
SELECT COUNT(*) AS input_count, COUNT(DISTINCT product_id) AS product_count
FROM ranking_snapshot_input
WHERE snapshot_id = ?
```

checksum은 같은 TX에서 복사 입력을 다음 순서로 읽어 순차 계산한다.

```sql
SELECT order_id, product_id, quantity, confirmed_at
FROM ranking_snapshot_input
WHERE snapshot_id = ?
ORDER BY order_id, product_id
```

위 count/checksum 조회는 자기 TX에서 삽입한 입력을 대상으로 한다. 원본의 일반 SELECT 결과와 locking read 결과를 섞지 않는다. 기존 snapshot을 먼저 조회해 TX의 일반 read view가 이미 만들어졌더라도 자기 입력 insert는 읽을 수 있다. 같은 snapshot의 쓰기를 병행하지 않는 원문 직렬 실행 전제와 입력 유일 키를 유지한다. checksum에서 실제 읽은 행 수도 input_count와 맞는지 처리 경로 안에서 확인한다. 이는 원본 점수 재계산 대조가 아니라 manifest와 그 checksum 대상 일치 확인이다.

비용·대안: 복사본 checksum 재조회·전송·직렬화와 원본 잠금 보유 시간이 필요하다. 일반 SELECT를 기본 REPEATABLE READ에서 순차 읽으며 checksum과 애플리케이션 batch insert를 함께 하는 대안도 있지만 원본 전송·쓰기 조립을 추가하게 된다. 이 작업에서는 기존 INSERT SELECT를 유지하는 방향을 우선했다. 성능 우열을 측정하지 않았으며 별도 최적화 실험을 추가한 것이 아니다.

사용자는 inputFixedAt을 복사 SQL 성공 직후 잠금을 보유한 같은 TX 연결에서 `SELECT UTC_TIMESTAMP(6)`으로 얻은 입력 고정 기준 기록 시각으로 확정했다. TX 시작이나 MVCC read-view 생성 시각·commit 완료 시각으로 표현하지 않는다. 복사본과 원본 집합의 대응은 위 잠금 보호/불변 원본 계약에 따른다. 이 시각 전에 업무상 확정된 모든 사건의 도착을 보장하지는 않으며, 복사 뒤 아직 저장되지 않은 구매는 새 revision 대상이다. API에는 이 저장 시각을 UTC로 표시한다. 이 시각의 선택이 원본 재조회나 격리 변경을 요구하는 것은 아니다.

검증 범위: 사용자는 별도 consumer 잠금 대기/경쟁·잠금 범위·대기 시간 실험이 원문 요구인지 질문하며 불필요한 테스트 확대를 지적했다. 원문 §2·§7·§8은 입력 불변·다품목·원자성·재시작·늦은 구매·실제 연결을 요구하지만, InnoDB next-key lock 자체를 재현하거나 consumer 대기 시간을 측정하는 별도 테스트는 명시하지 않는다. 이 별도 실험을 기본 테스트/완료 조건에 추가하지 않는다. 앞서 제시했던 복사 중 경쟁 commit 통제·잠금 관찰 실험은 승인된 기본 검증 계획이 아니다.

잠금의 가능성과 유지 기간은 공식 규칙에 따른 설계 설명으로 남긴다. 현재 source Entity에는 기간 검색용 confirmed_at 인덱스가 없으므로 현 매핑에서는 기간 조건에 맞는 인덱스 범위 검색을 기대할 수 없다. MySQL 8.0 Reference Manual의 Locks Set by Different SQL Statements in InnoDB는 적합한 인덱스 없이 전체 테이블을 스캔하면 전체 행 잠금과 다른 INSERT 차단으로 이어질 수 있음을 설명한다. 이를 현재 DB에서 전체 consumer 대기가 관찰됐다는 결과로 쓰지 않는다. 사용자는 [§9.11](#911-조회저장-sql에-맞춘-인덱스-구성)의 confirmed_at 단일 인덱스 추가를 선택했다. 인덱스를 사용하더라도 gap/경계 잠금이나 대기가 없어지는 것은 아니며 실제 경로는 optimizer가 결정한다. 복사 뒤 checksum/metadata까지 TX가 이어지는 비용과 실제 대기 영향은 미측정이다. 격리 변경·추가 잠금/성능 실험은 도입하지 않고 원문의 검증 요구를 유지한다.

동일 TX에서 오류가 전파되면 입력/manifest 모두 rollback하고 잠금을 해제한다. deadlock/lock timeout을 입력 누락이나 정상 빈 주로 바꾸지 않는다. 자동 재시작 없이 원인·실제 저장 상태를 확인하는 기존 복구 원칙을 따른다. 실제 구현·SQL 실행·기동·테스트는 아직 하지 않았다.

### 9.8 Freeze 처리와 실패 경계

실제 호출 순서·기존 입력 재사용/손상 분기·빈 입력은 [§8.2](#82-입력-고정과-재사용의-분기)를 따른다. 기존 개략 흐름의 “스냅샷 정보 생성”은 먼저 메모리 객체를 구성한다는 의미이며, DB metadata INSERT는 복사·시각 기록·count·checksum 완료 뒤 수행한다. 물리 FK가 없고 한 TX이므로 가짜 checksum/임시건수로 부모를 선행 저장하지 않는다.

복사·count·checksum·정보 저장·Step context의 commit 전 확정 오류는 한 TX를 rollback한다. SQL unique 실패·deadlock·timeout을 정상 재사용이나 빈 입력으로 바꾸지 않으며 실패 TX에서 계속 읽어 수선하지 않는다. commit 결과 불명은 [§5.5](execution-recovery.md#55-명시-재시작과-상태-확인-후-수동-복구)의 수동 상태 확인 경계로 보낸다.

### 9.9 이번 묶음의 완료·미완료

작성 완료: 다섯 테이블의 주요 열·SQL 타입·업무 유일 키·입력 인덱스·감사 열 처리·단일 SQL/복사본 manifest 계산의 구체적인 추천 초안. 기존 원본 테이블에 새 인덱스를 실제 추가하지 않았다. 별도 범위 스캔/EXPLAIN·성능 측정 실험은 필수 검증으로 추가하지 않으며 성능 개선량을 주장하지 않는다.

정리한 읽기 설계: 기본 REPEATABLE READ 유지, 단일 INSERT SELECT·원본 next-key 잠금 보호·복사본 count/checksum·manifest의 같은 TX commit. 원문의 일관된 입력 요구를 이 경계로 구체화했고 원본의 과거 MVCC read view 복사로 설명하지 않는다. 이번 정적 검토를 실제 테스트 통과로 보고하지 않으며, 별도 경쟁/잠금 실험은 기본 범위에서 제외한다. 입력/manifest의 원자성 등 원문 계약의 검증은 유지한다.

후속 상세화: inputFixedAt 기록 위치는 사용자 확인으로 [§9.7](#97-입력-복사-sql과-읽기-시점--기본-격리-유지)에 확정했고 Entity/DDL 매핑은 [§9.10](#910-entity-매핑쓰기-소유권과-최초-스키마-준비)에 작성했다. publication 최초 INSERT와 이후 조건부 UPDATE는 [§6.4~6.9](calculation-publication.md#64-최초-공개-행-생성)를 따른다. 실행 옵션은 [§4.7](execution-recovery.md#47-실행-옵션과-최초-준비-전제), 날짜 검증은 [§7.7](api-contract.md#77-요청자-식별과-입력-값-객체), 원문 대응은 §10을 따른다. 입력 복사의 격리/SQL 선택 쟁점은 정적으로 정리했지만 DB·입력 설계 승인과 실제 구현/실행 검증 완료는 구분한다.

검증: 원문 §2와 기존 설정·원본 Entity·BaseEntity·MySQL 공식 읽기 규칙을 정적으로 대조했다. DDL/SQL 실행·EXPLAIN·DB 잠금 관찰·앱 기동·테스트/실험·Git stage/ignore 변경은 하지 않았다. 후속 공개/API 상세화의 작성 현황과 남은 작업은 §10을 따른다.

### 9.10 Entity 매핑·쓰기 소유권과 최초 스키마 준비

commerce-batch의 domain/ranking에 아래 다섯 Entity를 두고 공통 BaseEntity를 상속한다. 클래스 이름은 기존 streamer의 ProductPurchaseActivityModel 관례에 맞춘다. 각 @Table 이름·업무 unique/index는 [§9.2~9.6](#92-snapshot-정보--ranking_snapshot)을 그대로 사용한다. 각 @UniqueConstraint/@Index에는 그 절에 적힌 이름을 명시하고 테스트 SQL도 동일한 이름·열 순서·유일성으로 선언한다. Hibernate 자동 생성 이름에 맡기지 않는다. 원본의 uk_ppa_order_product와 추가 idx_ppa_confirmed_at도 매핑/준비 SQL 사이에서 같은 이름을 사용한다. 기존 앱의 Entity를 이동하거나 공통 모듈에 업무 Entity를 넣지 않는다.

|Entity|테이블|필드 매핑|
|---|---|---|
|RankingSnapshotModel|ranking_snapshot|String snapshotId·LocalDate periodStart/periodEnd·Long revision·SnapshotStatus status·LocalDateTime inputFixedAt/completedAt·Long inputCount/productCount·String inputChecksum/resultChecksum|
|RankingSnapshotInputModel|ranking_snapshot_input|String snapshotId·Long orderId/productId/quantity·LocalDateTime confirmedAt|
|RankingScoreStageModel|ranking_score_stage|String snapshotId·Long productId·BigInteger score|
|RankingResultModel|ranking_result|String snapshotId·Long productId·BigInteger score·Long calculatedRank|
|RankingPublicationModel|ranking_publication|LocalDate periodStart·String publishedSnapshotId·LocalDateTime publishedAt|

SnapshotStatus는 domain/ranking의 READY/COMPLETED enum이며 @Enumerated(STRING), length=16으로 매핑한다. 날짜는 DATE, 업무 시각은 명시 DATETIME(6)이다. LocalDateTime은 JDBC UTC 값의 표현이며 JVM 기본 시간대로 재해석하지 않는다. 공통 감사 필드는 기존 BaseEntity 타입을 유지한다. 업무 시각을 바꾸려고 BaseEntity나 전역 Jackson 설정을 수정하지 않는다.

score는 @Column(precision=65, scale=0, nullable=false)와 DECIMAL(65,0)의 BigInteger 매핑으로 정의한다. 업무 ID는 VARCHAR(64), checksum은 CHAR(64)이며 ASCII binary collation이 필요하다. columnDefinition으로 해당 문자셋/정렬을 명시하고 snapshot_id/published_snapshot_id의 연결 열 모두 같은 정의를 사용한다. 모든 비선택 열은 nullable=false, 완료 전 resultChecksum/completedAt만 nullable이다. @ManyToOne/@OneToMany/JoinColumn을 두지 않고 scalar ID만 매핑한다. 이번에 별도 score 양수 CHECK나 새 상태를 추가하지 않는다.

이 Entity들은 매핑/최초 스키마 준비의 기준이다. 제품의 저장 경로는 §8/§6의 JDBC 포트 구현으로 통일한다. Entity를 먼저 조회해 managed 상태로 남긴 뒤 같은 행을 JDBC로 갱신하는 경로는 만들지 않는다. 네이티브 쓰기에서는 @PrePersist/@PreUpdate가 실행되지 않으므로 감사 열을 각 SQL에서 직접 채운다. 불변식 검증은 Entity callback만 믿지 않고 서비스의 요청/manifest 검사와 DB NOT NULL·업무 유일 키로 수행한다. BaseEntity.delete/restore를 신규 랭킹 기능의 API/서비스로 노출하지 않는다.

원본 product_purchase_activity의 쓰기와 매핑은 streamer가 계속 소유한다. batch는 원본을 JDBC로 읽기만 하고 원본 Entity를 복제해 등록하지 않는다. API는 ranking 테이블의 JDBC 조회만 구현하고 신규 ranking Entity를 등록하지 않는다. 따라서 같은 DB를 쓰더라도 source·랭킹 쓰기·고객 조회의 변경 주체와 초기화 책임을 구분한다.

랭킹 테스트의 API/batch 준비 context는 [§11.3.1](verification.md#1131-단독-테스트의-준비-파일순서소유권)의 JPA update로 소유 매핑을 준비하고 Batch 기본 metadata를 최초 준비한다. 기존 streamer/W7 소유 테이블의 기동/정리 방식은 변경하지 않는다. 그 뒤 이어 실행/재시작에는 모든 참여 앱에서 JPA ddl-auto=none과 Batch initialize-schema=never를 명시해 보존한다. 배치에는 API/streamer 테이블을 생성할 Entity가 없으므로 빈 DB에서 batch만 띄워 원본/상품까지 준비됐다고 가정하지 않는다. 새 마이그레이션 도구·자동 복구 DDL을 도입하지 않는다. 실행 플래그는 [§4.7](execution-recovery.md#47-실행-옵션과-최초-준비-전제), 테스트 준비 순서/소유권은 [§11.5~11.6](verification.md#115-앱-간-실행-연결과-테스트-전용-실패-주입)을 따른다. 현재 local/test의 create/always 기본값으로 서로 다른 실행을 연속 기동하는 것은 보존 절차가 아니다.

RankingInputRepository의 기존 행 조회는 snapshot_id 또는 (period_start, revision)의 업무 unique로 수행하고 soft delete를 WHERE로 숨기지 않는다. hasOrphanData는 metadata가 없을 때만 input/stage/result의 snapshot_id와 publication.published_snapshot_id에 EXISTS를 적용한다. 새 요청의 ID 범위만 확인하며 전체 DB 고아 검사를 하지 않는다. 기존 행 손상/충돌을 발견하면 삭제하지 않고 실패한다.

새 metadata 저장 SQL의 parameter 순서는 ID·시작·끝·revision·inputFixedAt·입력 수·상품 수·checksum이다.

```sql
INSERT INTO ranking_snapshot
    (snapshot_id, period_start, period_end, revision, status,
     input_fixed_at, input_count, product_count, input_checksum,
     result_checksum, completed_at, created_at, updated_at, deleted_at)
VALUES (?, ?, ?, ?, 'READY', ?, ?, ?, ?,
        NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)
```

insertReadySnapshot은 이 SQL을 실행해 영향 행 수1을 요구하며 unique 실패를 정상 재사용으로 잡아 바꾸지 않는다. copyInput은 JDBC가 반환하는 int를 전체 입력 수의 기준으로 삼지 않고 [§9.7](#97-입력-복사-sql과-읽기-시점--기본-격리-유지)의 COUNT 결과를 long으로 저장한다. count/정렬 읽기는 [§9.7](#97-입력-복사-sql과-읽기-시점--기본-격리-유지) SQL을 공용화한다. hash 입력의 confirmedAt은 DATETIME(6)의 LocalDateTime을 UTC로 해석해 [§8.6](calculation-publication.md#86-클래스-배치포트와-호출-순서) formatter로 직렬화한다. 결과 hash와 마찬가지로 행 전체를 하나의 문자열/List로 모으지 않는다.

이번에는 매핑·정적 설정을 설계했으며 실제 Hibernate 생성 DDL/컬럼·collation·InnoDB·strict mode·JDBC 왕복을 확인한 결과가 아니다. 이 확인은 최초 DB 연결 검증에서 수행하고 잘못된 매핑을 기대값에 맞춰 완화하지 않는다.

### 9.11 조회·저장 SQL에 맞춘 인덱스 구성

사용자는 원본 구매 활동의 confirmed_at 단일 인덱스 추가와 아래 신규 테이블의 기존 인덱스 구성을 유지하는 방향을 확인했다. 모든 신규 테이블에는 [§9.1](#91-다섯-테이블과-공통-열)의 숫자 PK가 있고 아래는 업무 키/조회 인덱스다. UNIQUE도 물리 인덱스이므로 같은 열의 일반 INDEX를 중복 추가하지 않는다.

|테이블|인덱스|사용 SQL/책임|
|---|---|---|
|product_purchase_activity — 기존 원본|INDEX idx_ppa_confirmed_at(confirmed_at) 추가|[§9.7](#97-입력-복사-sql과-읽기-시점--기본-격리-유지) 주간 시작/끝 범위 복사|
|ranking_snapshot|UNIQUE(snapshot_id), UNIQUE(period_start, revision)|ID 조회·충돌 확인·해당 기간 MAX(revision)|
|ranking_snapshot_input|UNIQUE(snapshot_id, order_id, product_id), INDEX(snapshot_id, product_id)|품목 중복 방지·정렬 checksum·DISTINCT 상품 Reader·상품별 SUM|
|ranking_score_stage|UNIQUE(snapshot_id, product_id)|절대값 upsert·snapshot별 준비 상품/건수 확인|
|ranking_result|UNIQUE(snapshot_id, product_id), UNIQUE(snapshot_id, calculated_rank)|상품/순위 중복 방지·전체 결과 checksum·API 순위순 조회|
|ranking_publication|UNIQUE(period_start)|기간별 공개본 하나·조회/조건부 변경|

MySQL 8.0 Reference Manual의 Multiple-Column Indexes에 따라 복합 인덱스의 선두 열로도 검색할 수 있다. 따라서 위 키와 용도가 겹치는 snapshot_id 단독 또는 period_start 단독 인덱스를 별도로 늘리지 않는다. 실제 선택/정렬 생략/성능 개선은 실행 확인 전 보장하지 않는다.

원본 인덱스의 선언 주체는 streamer의 ProductPurchaseActivityModel이다. batch에 원본 Entity를 복제하거나 배치 실행마다 CREATE INDEX를 수행하지 않는다. 신규 생성 DB에는 매핑을 통해 반영하고, 이미 존재하는 DB에는 별도 ALTER/CREATE INDEX 적용이 필요하다. 인덱스 존재 확인 후 소유 DB에 적용하는 절차는 구현 단위에서 구체화하며 현재 DB를 다시 생성해 과거 활동을 지우는 방식으로 적용하지 않는다. 이번 합의는 설계 반영이고 Entity 수정/실제 DDL 실행 승인이 아니다.

선택 이유/비용: 주간 복사는 confirmed_at 범위 조건만 사용하므로 우선 단일 인덱스를 선택한다. 전체 복사 열을 넣는 넓은 covering index는 source INSERT 비용과 공간이 늘어 이번 기본안에 넣지 않는다. stage의 (snapshot_id, score DESC, product_id ASC) 정렬용 인덱스도 우선 두지 않고 공개 시 정렬 비용을 수용한다. 이 인덱스는 순위 생성에 도움을 줄 여지는 있으나 score 쓰기의 유지 비용과 함께 판단해야 하므로 필수라는 근거 없이 추가하지 않는다.

publication.published_snapshot_id의 역조회 인덱스는 기간별 공개 조회에 필요하지 않아 우선 추가하지 않는다. [§8.2](#82-입력-고정과-재사용의-분기) 고아 확인에서는 공개본 테이블을 스캔할 수 있지만 기간별 한 행인 보조 조회라는 비용을 수용한다. status/감사 시각 단독 인덱스도 대응하는 주 사용 쿼리 없이 추가하지 않는다. 원본/테스트 준비 DDL에는 합의한 인덱스가 빠지지 않아야 하며 최초 스키마 확인에 존재/열 순서·유일성을 포함한다. 별도 성능/잠금 측정 실험·배치 metadata 인덱스 변경은 추가하지 않는다.

### 9.12 Native JDBC의 날짜·UTC 시각 바인딩/읽기

원본 confirmed_at은 streamer의 ProductPurchaseActivityModel에서 ZonedDateTime·DATETIME(6)으로 매핑하며 공통 jpa.yml의 hibernate.timezone.default_storage=NORMALIZE_UTC·hibernate.jdbc.time_zone=UTC를 사용한다. 이 설정이 원본 저장의 UTC 해석 근거다. 그러나 native JdbcTemplate의 parameter/ResultSet 처리까지 Hibernate 설정이 대신해 주는 것은 아니다. 배치 main/테스트의 JVM 기본 시간대 Asia/Seoul을 바꾸지 않고 아래 바인딩을 명시한다.

|SQL 대상|Java 값/입력|JDBC 호출/출력|
|---|---|---|
|period_start/period_end의 DATE|LocalDate|PreparedStatement.setObject(index, localDate), ResultSet.getObject(column, LocalDate.class)|
|복사 WHERE의 confirmed_at 시작/끝|기간 LocalDate.atStartOfDay()의 LocalDateTime. UTC 날짜의00:00이라는 업무 의미|setObject(index, localDateTime). 시스템 기본 시간대를 거친 Timestamp로 변환하지 않음|
|confirmed_at/input_fixed_at/completed_at/published_at 등 native DATETIME(6)|UTC 필드를 가진 LocalDateTime|setObject(index, localDateTime), getObject(column, LocalDateTime.class)|
|SELECT UTC_TIMESTAMP(6)의 결과|DB UTC 필드|행 mapper에서 getObject(1, LocalDateTime.class)로 읽음. 문자열/서버 시간대 추정으로 재해석하지 않음|

DATETIME에는 시간대 자체가 저장되지 않는다. 여기서 LocalDateTime은 “UTC 기준의 날짜/시각 필드”라는 계약이며 시스템 기본 시간대의 현지 시각이라는 의미가 아니다. SQL이 source confirmed_at을 복사 테이블의 같은 DATETIME(6) 열로 복사하는 경로는 Java 변환 없이 DB 값을 보존한다. 만약 fixture/호출 경계의 값이 Instant라면 LocalDateTime.ofInstant(value, ZoneOffset.UTC)로 명시 변환하고, 다른 zone의 ZonedDateTime도 같은 순간을 UTC로 바꾼 뒤 필드를 사용한다. KST 값에서 zone 정보만 떼는 toLocalDateTime 호출은 사용하지 않는다.

[§9.7](#97-입력-복사-sql과-읽기-시점--기본-격리-유지) 복사 SQL의 prepared parameter 순서는 다음처럼 고정한다.

```java
ps.setString(1, request.snapshotId());
ps.setObject(2, request.periodStart().atStartOfDay()); // UTC 00:00 필드
ps.setObject(3, request.periodEnd().atStartOfDay());   // 다음 월요일 미포함
```

ranking JDBC 구현의 날짜·시각 바인딩은 이 타입/호출을 사용하고, 시각 열을 setTimestamp/getTimestamp 또는 Timestamp/Date/Calendar의 기본 시간대 변환을 거쳐 전달하지 않는다. Instant/ZonedDateTime을 DATETIME parameter에 직접 넣지 않는다. 명시적인 typed RowMapper로 LocalDateTime을 읽어 도메인 projection에 반환하며 JDBC API가 이 변환을 지원하지 않는 경우 다른 타입으로 조용히 fallback하지 않고 실패를 전파한다. 다른 기능의 기존 JPA/JDBC 경로를 일괄 변경하는 범위는 아니다.

API의 inputFixedAt과 입력 checksum의 confirmedAt은 위 LocalDateTime을 UTC로 해석해 atOffset(ZoneOffset.UTC) 뒤 합의한6자리 formatter를 적용한다. 이 단계는9시간을 더하거나 빼는 변환이 아니라 DB 필드에 UTC 의미를 명시하는 것이다. DB 생성 시각을 Java now()로 대체하거나 마이크로초를 밀리초로 잘라 hash/API에 넣지 않는다. 상태가 READY일 때 completed_at/result_checksum처럼 nullable인 값은 null을 유지하고 JDBC 읽기의0/현재 시각으로 보정하지 않는다.

기술 근거는 캐시의 Connector/J9.1.0 sources.jar에서 NativeQueryBindings가 LocalDate/LocalDateTime을 DATE/DATETIME에 매핑하고, LocalDateTimeValueEncoder가 날짜/시각 필드를 인코딩하며, ResultSetImpl·LocalDateTimeValueFactory가 필드로 LocalDateTime을 생성하는 흐름을 읽은 것이다. 현재 runtimeClasspath/driver 버전을 새로 해석하거나 실제 왕복한 결과는 아니다. 구현 첫 의존성 확인과 실제 DB 검증에서 적용 드라이버/변환을 확인한다.

검증은 기존 기간 경계 fixture/API/checksum 검증 안에서 수행한다. 시작 직전·시작·끝 직전·끝의 source 값을 사용해 구간 포함/제외와9시간 이동 오류가 드러나게 하고, 조회/응답/checksum의 UTC6자리 값이 기존 기대값과 맞는지 확인한다. 원문의4행 정상/재시작 fixture는 바꾸지 않고 경계용 subcase에서만 경계 행을 준비한다. 새 PG 주문·별도 시각 성능 실험·테스트 클래스를 추가하는 요구가 아니다. 이번에는 바인딩 코드/SQL/앱/테스트를 실행하지 않았다.
