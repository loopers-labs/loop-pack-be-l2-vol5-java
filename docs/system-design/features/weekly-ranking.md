# 주간 구매 수량 랭킹 설계

## 1. 문서 상태와 읽기 기준

2026-11-17 사용자가 전체 설계를 최종 승인했다. Claude의 R1/R2/R3 확인 결과는 모두 반영됐고 새 구현 전 필수 결함을 찾지 못했다는 읽기 전용 의견이며, 사용자 승인은 이 의견과 구분한다. 이 문서는 승인된 제품/구현/검증 설계 기준이고 구현 착수·코드/테스트 실행 승인은 아니다. 실제 bean/Gradle/DDL/JDBC/재시작 동작은 구현 단계에서 확인한다. 새 계약 판단이나 기존 계약 변경이 필요하면 사용자에게 확인한다.

원문 근거는 「Implementation Quest」 §1~8과 「같은 입력의 계산을 이어 하는 것과, 새 입력으로 다시 계산하는 것은 다릅니다」다. 멘토 설명은 실습 계약을 자동 변경하지 않는다.

기존 입력 계약은 [주문 이벤트 소비 상세설계](order-events/consumption.md), 현재 상품 공개 조건은 [카탈로그](catalog.md), 공통 응답·요청자 식별은 [API 공통 규칙](../common/api-contract.md)을 따른다. W7의 실제 전달 실행과 한계는 [주문 이벤트 실행 기록](../verification/order-events.md)의 W7 절을 따른다. 과거 실행을 이번 랭킹의 검증 결과로 사용하지 않는다.

## 2. 원문이 정한 계산·조회 요구

- 주간 구간은 UTC 월요일 00:00 이상, 다음 월요일 00:00 미만이다. 한국 시간으로는 월요일 09:00 경계다.
- 주간 소속은 구매 활동의 최초 주문 confirmedAt으로 판단한다. 소비 DB 도착 시각을 대신 쓰지 않는다.
- W6·W7 consumer가 저장한 주문·상품별 구매 활동의 quantity를 상품별로 합한다. 같은 주문의 여러 상품을 모두 포함한다. 조회·좋아요·매출·쿠폰을 점수에 섞지 않는다.
- 점수 내림차순, 동점 productId 오름차순으로 순서를 정한다. 원문 fixture의 순위는 상품 7이 5점·1위, 상품 9가 5점·2위, 상품 8이 1점·3위다.
- 조회 API는 `GET /api/v1/rankings/weekly`다. 완성된 공개본만 읽으며 미공개와 구매 0건인 공개 완료를 구분한다. 다른 주 결과로 대신 채우지 않는다.
- 현재 고객 공개 조건을 적용한 뒤 limit을 적용한다. 기존 코드의 공개 조건은 Product·Brand 모두 존재하고 deletedAt이 null인 것이다. 재고 0은 제외 조건이 아니며, 별도 비노출 플래그 추가는 합의하지 않았다.
- 제외한 상품 때문에 순위 간격이 생겨도 저장된 계산 순위를 유지한다. 상품 9를 숨기면 상품 7은 1위, 상품 8은 3위다. 응답 배열의 위치로 순위를 다시 만들지 않는다.

입력 고정·영속 재시작·결과와 checkpoint의 같은 실제 DB commit·완성본 원자적 공개·오래된 revision 역전 방지·늦은 구매의 새 revision 반영·실습 §7 실제 연결은 원문의 필수 요구로 유지한다. 상세 처리 계약은 §3~9, 원문 요구 대응은 §10, 검증 구성은 §11을 따른다. 필수 요구를 축소하지 않는다.

## 상세 문서 읽기 순서

진입 문서는 전체 승인 상태·제품 계약·읽기 안내를 맡고 상세 기준은 아래 문서에 한 번씩 둔다. 이번 분리는 승인된 내용을 이동한 것이며 구현/테스트 실행 승인이 아니다.

|순서|상세 문서|내용|
|---|---|---|
|1|[입력 고정·저장 구조·checksum](weekly-ranking/input-storage.md)|고정 입력/manifest·DB 매핑/인덱스·UTC JDBC·freeze 처리|
|2|[Job 실행·식별·재시작·복구](weekly-ranking/execution-recovery.md)|식별값/실행 옵션·등록/공통 시작·실패 확인/재시작·보존|
|3|[상품별 계산·완성본 공개](weekly-ranking/calculation-publication.md)|Reader/Processor/Writer·chunk/TX·순위/완성 검증·조건부 공개|
|4|[랭킹 API 계약](weekly-ranking/api-contract.md)|입력/오류·응답/DTO·현재 공개 조건·두 조회/같은 snapshot|
|5|[원문 대응·검증 구성](weekly-ranking/verification.md)|과제 요구 추적·fixture/실제 연결·시나리오 격리·준비/정리·증거|

## 절 번호 안내

기존 §번호와 SQL·의사코드·판단 근거·기대값은 유지했다. 소수 절 참조는 상세 문서의 해당 위치로 연결하고, 원문 과제의 §1~8 표기는 제품 설계 절과 구분해 읽는다.

|기존 절|기준 위치|
|---|---|
|[§1](#1-문서-상태와-읽기-기준) · [§2](#2-원문이-정한-계산조회-요구)|진입 문서|
|[§3](weekly-ranking/input-storage.md#3-입력-고정)|입력 고정·저장 구조·checksum|
|[§4](weekly-ranking/execution-recovery.md#4-snapshotid와-revision)|Job 실행·식별·재시작·복구|
|[§5](weekly-ranking/calculation-publication.md#5-상품별-점수-계산)|상품별 계산·완성본 공개|
|[§5.5](weekly-ranking/execution-recovery.md#55-명시-재시작과-상태-확인-후-수동-복구) · [§5.6](weekly-ranking/execution-recovery.md#56-재시작-전-확인-순서와-지원-범위) · [§5.7](weekly-ranking/execution-recovery.md#57-읽기-전용-확인-자료와-보존정리) · [§5.8](weekly-ranking/execution-recovery.md#58-unknowncommit-결과-불명에서의-중단-절차)|Job 실행·식별·재시작·복구|
|[§6](weekly-ranking/calculation-publication.md#6-완성본과-공개-pointer)|상품별 계산·완성본 공개|
|[§7](weekly-ranking/api-contract.md#7-api-응답)|랭킹 API 계약|
|[§8](weekly-ranking/calculation-publication.md#8-입력-고정계산의-처리-계약)|상품별 계산·완성본 공개|
|[§8.1](weekly-ranking/input-storage.md#81-메서드의-입력과-책임) · [§8.2](weekly-ranking/input-storage.md#82-입력-고정과-재사용의-분기)|입력 고정·저장 구조·checksum|
|[§8.3](weekly-ranking/calculation-publication.md#83-상품별-계산과-실패-분기) · [§8.4](weekly-ranking/calculation-publication.md#84-stepreader-상태와-자원-수명) · [§8.5](weekly-ranking/calculation-publication.md#85-상품-합계와-절대-점수-저장-sql) · [§8.6](weekly-ranking/calculation-publication.md#86-클래스-배치포트와-호출-순서) · [§8.7](weekly-ranking/calculation-publication.md#87-이번-상세화의-검토-기준)|상품별 계산·완성본 공개|
|[§9](weekly-ranking/input-storage.md#9-db입력-고정-상세설계)|입력 고정·저장 구조·checksum|
|[§10](weekly-ranking/verification.md#10-원문-요구와-설계검증-대응) · [§11](weekly-ranking/verification.md#11-검증-구성--작은-fixture와-주문-두-건의-실제-연결)|원문 대응·검증 구성|

§5의 실행 복구(5.5~5.8)와 §8의 입력 처리(8.1~8.2)는 책임에 맞는 문서로 옮겼다. 숫자 절은 설계 세트 전체에서 유일하며, 포괄 참조의 세부 위치는 위 표를 따른다.
