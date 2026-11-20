# W9 주간 구매 랭킹과 배치 재시작 기준

2026-11-17 사용자가 제공한 [과제 원문](assignment-original.md)과 [학습 자료 원문](learning-original.md)을 첨부 그대로 보존했다. 이번 주의 목표는 W6·W7 구매 활동으로 주간 랭킹을 만들고, 중단된 계산을 같은 입력과 같은 작업으로 이어 가며 완성본만 공개하는 것이다.

아래 W9-C01~09는 원문 Checklist의 순서에 붙인 추적용 ID다. 원문 문구를 유지하며, 확인 증거는 본문의 요구와 연결한다. 배점·최소 테스트 개수·성능 합격선은 원문에 제시되지 않았다. 구현 상태와 실행 근거는 [구현 체크리스트](implementation-checklist.md)에 기록한다.

## 원문 9항목과 확인 증거

| ID | 원문 체크 항목 | 충족을 확인할 증거 |
| --- | --- | --- |
| W9-C01 | W6·W7의 실제 구매 활동을 주간 snapshot으로 고정해 랭킹 API까지 연결했다. | 실제 PG 주문 확정 → Outbox → Kafka → streamer 구매 활동 → 입력 고정 → weeklyRanking → 고객 HTTP 조회를 한 연결 테스트에서 확인한다. 같은 주문 이벤트를 재발행해도 원본 활동과 랭킹 점수는 증가하지 않는다. 합성 입력만 사용하는 검증에 실제 경로 검증을 더한다. |
| W9-C02 | 상품별 수량 합·동점 규칙·같은 주문의 여러 품목을 정확히 반영한다. | 원문 4개 활동에서 서로 다른 상품 3개를 처리한다. 상품 7은 5점·1위, 9는 5점·2위, 8은 1점·3위다. eventId가 같은 주문의 두 품목도 모두 계산한다. |
| W9-C03 | 늦은 구매는 기존 입력을 바꾸지 않고 원래 주의 새 revision으로 계산한다. | 입력 고정 뒤 같은 주의 활동을 실제로 추가해도 기존 snapshot의 행·건수·checksum과 재시작 결과는 같다. 새 snapshot·revision·instance에만 늦은 구매를 포함하고 공개 전후 API를 대조한다. |
| W9-C04 | 같은 instance·새 execution·같은 영속 metadata로 마지막 commit부터 이어 간다. | chunk=2에서 두 상품의 점수·reader 위치가 commit된 뒤 세 번째 상품 직전에 실패시킨다. JobExplorer로 영속 위치와 FAILED를 읽고 같은 식별 parameter로 재실행한다. instance ID는 같고 execution ID는 다르며 세 번째 상품부터 처리한다. |
| W9-C05 | 안정된 reader 순서와 writer 절대값/중복 보호, 실제 DB TX를 확인했다. | 고정 입력의 productId 오름차순과 reader state를 확인한다. 절대 점수 재쓰기 또는 선택한 증분 중복 보호를 실제 DB로 검증한다. 결과와 checkpoint가 같은 TX에 참여하며 chunk commit 전 실패 때 함께 rollback되는지 확인한다. |
| W9-C06 | 정상 실행과 실패·재시작의 기대 점수·순위·checksum이 같다. | 별도 비교용 snapshot 또는 DB의 정상 실행과 재시작 결과를 비교한다. 입력·결과 checksum뿐 아니라 손으로 계산한 점수·순위도 assert한다. 재시작 대상의 stage·입력·metadata는 실행 사이에 보존한다. |
| W9-C07 | 계산 실패·공개 TX rollback에서는 이전 완성본을 유지하며, commit 뒤 metadata 실패는 실제 공개 상태와 구분한다. | 기존 공개본을 준비하고 계산 실패와 공개 SQL 후 rollback을 각각 주입한다. API는 이전 snapshot만 반환한다. 공개 commit 뒤 metadata가 미완료인 상황에서는 실제 pointer·완성본을 확인하고 안전하게 이어 간다. |
| W9-C08 | 같은 공개 재실행은 안전하고 오래된 revision이 새 공개본을 덮지 않는다. | 같은 snapshot 공개를 반복해도 결과가 늘거나 바뀌지 않는다. 새 revision 공개 뒤 오래된 공개 실행을 재시도해도 pointer가 후퇴하지 않는다. 같은 기간의 계산은 기본 범위에서 직렬 실행한다. |
| W9-C09 | 미공개·빈 주·삭제 상품 조건과 관련 회귀·lint·ArchUnit을 확인했다. | 미공개와 구매 0건의 완성본, UTC 기간 경계, 날짜·limit 입력, 현재 상품 공개 조건을 HTTP로 검증한다. 공개 조건 후 limit을 적용하고 저장된 순위의 간격을 유지한다. 관련 Batch/API 및 기존 기능 회귀·Checkstyle·ArchUnit의 실제 결과와 소유 자원 정리를 남긴다. |

원문의 작은 probe, 테이블 이름, 클래스 이름은 책임과 관찰 방법의 예시다. 해당 이름을 모든 구현에 강제하지 않는다. 표의 요구를 구현한 뒤 실행 증거로 충족·부분 충족·미충족·확인 불가를 판단한다. 문서 작성과 기존 W8 검사 통과를 W9 구현 완료로 계산하지 않는다.

## 원문이 정한 업무·조회 계약

| 항목 | 적용할 기준 |
| --- | --- |
| 기간 | UTC 월요일 00:00 이상, 다음 월요일 00:00 미만인 반개방 구간 |
| 기간 소속 | 상품 구매 활동의 최초 주문 `confirmedAt` |
| 점수 | 상품별 구매 `quantity`의 정수 합 |
| 정렬과 순위 | `score DESC, productId ASC`. 원문 예시의 동점 상품도 1·2위로 구분 |
| 입력 식별 | W6·W7 consumer가 저장한 `orderId·productId`별 활동 |
| API | `GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10` |
| 요청 검증 | 날짜 형식·월요일 검증, 양수이며 상한을 명시한 limit. 최대 100은 원문 예시이며 정확한 기본값·범위·오류 계약은 설계에 기록 |
| 응답 사실 | `periodStart`, `periodEnd`, `snapshotId`, 입력 고정 시점, 상품별 점수·순위 포함 |
| 미공개·빈 주 | 공개본 없음과 구매 0건의 완성본을 구분. 원문은 `NOT_PUBLISHED` / `PUBLISHED`와 빈 items를 예시로 제시 |
| 상품 공개 조건 | 기존 고객 조회의 삭제·비노출 조건을 적용한 뒤 limit 적용. 현재 이름 등 표시 정보도 기존 조회 규칙 사용 |
| 순위·과거 보존 | 저장된 계산 순위를 유지하므로 제외된 상품에 따라 1·3위처럼 간격이 생길 수 있음. 원본 활동·점수는 보존 |
| revision | 늦은 구매를 같은 기간의 새 revision으로 명시적으로 재계산. 새 결과 공개 전에는 기존 공개본 제공 |

현재 공통 테스트 JVM의 기본 시간대가 Asia/Seoul이므로 기간 판정과 checksum 시각 형식의 UTC 적용을 명시적으로 검증해야 한다. 고객에게 보여 줄 날짜와 DB에서 읽은 시각을 시스템 기본 시간대에 의존해 변환하지 않는다.

## 입력 고정·재시작의 저장 경계

| 경계 | 지켜야 할 조건 | 확인할 실패 장면 |
| --- | --- | --- |
| 원본 → 입력 snapshot | 일관된 DB 읽기로 활동을 복사하고 입력 행과 manifest의 기간·revision·건수·checksum을 함께 commit | 복사 도중 실패 후 반쪽 입력이 계산에 사용되지 않음. 낮은 ID를 먼저 받은 TX가 늦게 commit해도 단순 max(id)를 읽기 시점으로 오인하지 않음 |
| 기존 snapshot ID 재사용 | 같은 ID의 기간·입력·revision이 다르면 충돌로 처리하고 기존 입력을 보존 | 같은 ID에 다른 내용의 저장을 시도해도 기존 manifest·입력 행·checksum이 바뀌지 않음. C01·C03의 입력 고정 근거로 확인 |
| 입력 고정 완료 → 계산 재시작 | 완료한 입력 Step을 재실행하며 활동을 추가하거나 덮어쓰지 않음 | 새 활동 도착 후 같은 instance를 재시작해도 입력 불변 |
| chunk writer → checkpoint | 업무 결과와 영속 reader 위치가 실제 같은 데이터 소스·transaction manager의 DB TX에 참여 | 실제 점수 SQL 뒤 commit 전 실패에서 점수와 위치가 함께 rollback |
| 계산 결과 → 공개본 | 모든 상품의 점수·예상 상품 수를 확인하고 순위를 구성. 완성 조건과 기간 pointer 변경을 한 TX로 보호 | 일부 순위 저장 뒤 실패하면 이전 공개본 유지 |
| 공개 commit → Batch 최종 상태 | 이미 공개된 snapshot·pointer와 JobExecution 상태를 함께 확인 | metadata가 미완료여도 정상 공개본을 보존하며 재실행이 같은 결과로 수렴 |

`weeklyRanking`은 입력 고정 → 상품별 점수 계산 → 완성본 공개의 단계로 구성한다. 상품 하나가 계산 item 하나이며 reader는 고정 입력의 서로 다른 productId를 오름차순으로 읽는다. writer는 고정 입력의 합을 절대값으로 쓰는 방식이 제시돼 있다. 증분 방식을 선택하면 활동별 적용 기록과 점수 증가를 같은 TX로 보호해야 한다.

`periodStart`, `snapshotId` 등 업무 식별 parameter를 유지해 같은 JobInstance를 다시 실행한다. 매번 달라지는 `run.id`·현재 시각·실패 스위치를 식별 parameter로 추가해 새 작업을 만드는 것은 재시작 증거를 제공하지 않는다. 완료된 입력·stage·Batch metadata를 재시도 사이에 비우지 않는다.

영속 JobRepository와 실제 DB transaction manager를 연결하고, reader가 실행의 snapshot parameter를 사용하도록 한다. 설정된 job 선택과 테스트 자동 실행 여부를 확인해 명시적 launch와 겹치지 않게 한다. 원문 연결 예시는 starter의 Boot 3.4.4·Batch 5.2.2를 기준으로 하므로 의존성을 바꿨다면 해석된 버전을 확인한다. C04·C05는 실제 reader 상태와 writer·metadata의 공동 commit/rollback으로 판정한다.

## 작은 재시작 실험의 기대값

원문 구간은 `[2026-08-17T00:00:00Z, 2026-08-24T00:00:00Z)`다.

| orderId | productId | quantity | confirmedAt |
| --- | --- | --- | --- |
| 42 | 7 | 2 | 2026-08-17T01:00:00Z |
| 42 | 8 | 1 | 2026-08-17T01:00:00Z |
| 43 | 7 | 3 | 2026-08-18T01:00:00Z |
| 44 | 9 | 5 | 2026-08-19T01:00:00Z |

| 시점 | 독립 DB 조회·metadata·HTTP로 확인할 값 |
| --- | --- |
| 입력 고정 | 구매 활동 4행, 서로 다른 상품 3개 |
| 첫 chunk commit 후 상품 9 직전 실패 | 상품 7의 점수 5, 상품 8의 점수 1, stage 2행, 영속 reader 위치 2, 첫 execution FAILED, 기존 공개본 유지 |
| 같은 instance 재실행 | 새 execution에서 상품 9부터 처리. 완료한 입력과 첫 두 상품을 다시 계산하지 않음 |
| 완성본 | 상품 7은 5점·1위, 상품 9는 5점·2위, 상품 8은 1점·3위 |
| 정상 실행과 비교 | 위 점수·순위 일치, 고정된 직렬화 규칙의 입력 checksum과 결과 checksum 일치 |

원문 probe의 reader 이름은 `purchaseProductProbe`, 저장 key는 `purchaseProductProbe.read.count`다. 실제 랭킹 reader에서는 그 구현의 영속 key와 값을 직접 확인한다. 입력 checksum은 orderId·productId 순서의 `orderId, productId, quantity, confirmedAt`, 결과 checksum은 rank 순서의 `productId, score, rank`를 대상으로 한다. TAB·LF·마지막 LF·UTC 시각 형식 등을 설계에 고정하며 실행 ID와 실행 시각은 결과 checksum에서 제외한다.

먼저 수행하는 작은 DB 연결 실험에서는 첫 실패 뒤 확인 행 7·8과 영속 reader 위치 2를 조회하고, 같은 instance의 새 execution이 9만 추가해 최종 7·8·9가 되는지 확인한다. 이후 주간 job의 stage·점수·순위·공개 API에서도 위 기대값을 검증한다. 두 실행 사이의 DB와 metadata를 보존하고, 테스트 전체의 자동 rollback으로 첫 chunk의 외부 조회를 가리지 않는다.

## 구현자가 선택할 설계

구매 활동·snapshot·ranking·Batch metadata의 DB 배치, snapshot ID·revision 발급과 직렬 실행 방법, API JSON·limit 범위·오류 계약, checksum 직렬화·점수 자료형은 [W9-Q01~04](implementation-checklist.md#구현-전에-비교할-선택)에서 대안과 이유를 정한다. 선택한 방식이 위 업무 결과와 저장 경계를 만족하는지 채점한다. 구현 전 선택이 필요한 항목은 미정으로 기록하고 사용자와 합의한 뒤 설계·API·정책·TDD 문서에 반영한다.

## 회귀와 제출 근거

W5 PG·주문·예약, W6 소비 중복 방지, W7 Outbox·relay, W8 쿠폰 기능을 유지한다. W8 쿠폰 발급 수와 W4 좋아요 수는 주간 구매 점수에 포함하지 않는다. 이번 주의 `commerce-batch` job과 `commerce-api` 랭킹 조회는 새 구현 범위다.

검사는 실제 영향 범위에 맞춰 실행한다. Batch·API의 `check`, 관련 streamer 회귀와 Java Checkstyle·ArchUnit 결과를 기록하며, 기존 모든 앱의 코드를 한 테스트 classpath에 무조건 합치지 않는다. 재시작 검증은 같은 격리 DB와 Batch metadata를 보존한다. 테스트가 소유한 DB·topic/group·컨테이너만 정리한다.

PR에는 같은 instance의 실패→재시작 ID, checkpoint, 입력·결과 checksum, 기대 점수·순위, 공개 revision과 늦은 구매 결과를 남긴다. 처리량과 소요 시간은 직접 측정한 경우에만 기재한다. 코드·검증 완료와 PR·기술 글 제출 상태를 각각 확인한다.
