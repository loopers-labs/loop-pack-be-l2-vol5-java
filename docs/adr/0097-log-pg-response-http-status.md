# ADR 0097. PG의 5xx·생성 422·정상 생성 202·조회 응답에서 받은 HTTP 상태를 결과 로그에 기록한다

Status: Accepted
Follow-up: 반환 거래별 주문 번호 기록은 [ADR 0098](0098-log-pg-returned-order-number-per-transaction.md)에서 확장한다.
Extends: [ADR 0096](0096-log-pg-boundary-and-failed-result-save.md)
Related: [PG 경계 로그 설계](../week10/design.md#2-pg-경계-로그), [503과 read timeout 검증 기록](../week10/implementation-log.md#a-pg-503-응답과-read-timeout-구분), [Week 10 설계](../week10/design.md#정상-조회-결과를-한-줄로-남기는-이유)

PG 응답 종류별로 나눠 내린 결정을 한 문서로 모았다. 각 절은 해당 응답에 대한 배경·결정·검증이다.

범위: 생성 응답이 202가 아니거나 본문이 PENDING 거래가 아닌 경우(형식 오류 포함)는 현재 HTTP 상태 없이 `PG 응답 없음 ... cause=UNEXPECTED_RESPONSE`로 남는다. 이 경로의 상태 기록은 결정 대기다.

## 1. 5xx 응답

### 배경

거래 조회에서 503을 받은 경우와 응답 대기 시간이 초과된 경우는 모두 결제 결과가 `UNKNOWN`이다. 두 경우 모두 `PG 응답 없음`으로 기록하면 운영자는 해당 문구로 응답 유실을 식별할 수 없다.

### 결정과 근거

생성·조회 시 PG의 5xx 응답을 받은 경우 WARN 로그에 `PG 응답`과 실제 `httpStatus`를 남긴다. 결제 결과와 원인도 함께 기록한다. 도메인 결과에 HTTP 상태를 추가하는 방법도 검토했으나, 상태 코드는 어댑터에서 확인할 수 있고 결제 판정에는 기존 `PaymentObservation`으로 충분하다.

### 검증

`PaymentProviderContractTest.lookupTimeoutAndServerErrorAreTemporaryFailures`에서 실제 HTTP 503과 read timeout을 각각 발생시키고, 두 조회의 결제 판정과 경계 로그를 확인한다. 생성 503의 경계 로그는 `serverErrorAndUnprovenRejectionAreUnknownWithoutRetries`에서 확인한다.

## 2. 결제 생성 422 응답

### 배경

거절 근거를 갖춘 422 응답은 기존 INFO 로그에 HTTP 상태가 빠졌다. 본문이 빈 422는 WARN 로그에 `PG 응답 없음`으로 남아서 read timeout과 혼동할 수 있었다.

### 결정과 근거

어댑터가 보존한 HTTP 오류 응답의 상태 코드를 로그에 사용한다. 결제 생성 422에서는 기존 계약의 거절 코드, `transactionCreated=false`, 주문 번호와 금액이 모두 맞으면 INFO에 `httpStatus=422 result=REJECTED`를 남긴다. 근거가 부족하면 WARN에 `httpStatus=422 result=UNKNOWN cause=UNEXPECTED_RESPONSE`를 남긴다.

422를 일괄 거절로 처리하면 본문이 비어 있는 경우에도 거래 미생성을 단정하게 된다. 기존 판정을 사용하면 응답 본문을 로그에 출력하지 않고도 무엇을 확인했는지 드러난다. [Week 5 결제 계약](../week5/design.md)

### 검증

`PaymentProviderContractTest`에서 같은 HTTP 422에 대해 거래 미생성이 입증된 응답과 본문이 빈 응답의 결제 판정 및 로그를 각각 검증한다. [Week 10 구현 기록](../week10/implementation-log.md#a-결제-생성-422의-두-판정)

## 3. 정상 생성 응답 202

### 결정과 근거

PG 생성 API가 202와 유효한 PENDING 거래를 반환하면 어댑터의 INFO 로그 한 줄에 응답에서 읽은 `httpStatus=202`, 판정 `result=OBSERVED`, 거래 키·PG가 준 주문 번호·상태·금액을 남긴다. 202는 생성 요청이 접수됐다는 뜻이고, PENDING은 그때 응답 본문에 담긴 거래 상태다. 이후 거래가 성공했는지는 같은 거래의 조회 결과로 확인한다.

로그에 계약상 기대값 202를 상수로 적는 방법도 있지만, 실제로 수신한 코드는 HTTP 응답에서 바로 얻을 수 있다. 어댑터 내부에서 그 값을 로그로 전달한다.

### 검증

`PaymentProviderContractTest.createsPendingTransactionUsingTheExactHttpContractAndLooksUpSuccess`에서 실제 생성 응답 로그에 상태 코드·판정·PENDING 거래가 함께 있는지 검사한 뒤, 같은 거래를 조회해 SUCCESS를 확인한다. [구현 기록](../week10/implementation-log.md#a-정상-생성-접수-202)

## 4. 정상 조회 응답 200

### 결정과 이유

생성 응답을 받으면 거래 키로 조회할 수 있고, 응답을 잃어 키가 없으면 PG 주문 번호로 조회한다. 두 경로 모두 정상 응답에서 읽은 HTTP 상태를 기존 INFO 결과 로그에 넣는다. 같은 주문에서 여러 번 조회할 수 있으므로 한 줄에서 해당 호출의 조회 키, HTTP 상태, 관찰 결과, 반환 거래 목록, 소요 시간을 함께 읽는다.

HTTP 200은 조회의 성공을 알려 준다. 주문이 결제 확인 중일 때 조사 방향을 가르는 값은 당시 PG가 반환한 거래 상태다. PENDING이면 그 시점에 PG에서도 처리 중이었다. SUCCESS이면 내부 결과 저장과 주문 반영 기록을 이어서 확인한다. 나중에 PG를 다시 조회하면 거래 상태가 달라질 수 있다.

### 관측 범위

현재 필드로 당시 어느 키로 조회해 어떤 거래 키·주문 번호·상태·금액을 받았는지 확인할 수 있다. 주문 번호로 조회할 때 로그 앞의 `providerOrderId`는 요청값이고, 거래 목록의 주문 번호는 PG 응답 본문의 값이다([ADR 0098](0098-log-pg-returned-order-number-per-transaction.md)). 그래서 PG가 다른 주문을 반환한 경우도 이 줄에서 대조할 수 있다. 내부 반영 실패는 같은 주문의 TX 실패 기록과 대조한다. [Week 10 설계](../week10/design.md#정상-조회-결과를-한-줄로-남기는-이유)

## 5. 조회 본문을 해석할 수 없는 응답

### 결정과 이유

거래 키 조회에서 테스트 PG가 200과 `not-json`을 보냈는데 로그에는 `PG 응답 없음`이 남았다. 기존 호출은 Spring이 본문을 변환한 뒤 HTTP 상태를 읽어서, JSON 변환 예외가 발생하면 그 상태가 경계 로그까지 전달되지 않았다.

두 조회 경로 모두 응답 헤더에서 상태를 먼저 읽고 Spring의 기존 메시지 변환기로 본문을 해석한다. 200을 받았어도 JSON 문법이나 거래 형식이 잘못됐으면 실제 상태 코드, `UNKNOWN(UNEXPECTED_RESPONSE)`, 예외 종류를 한 줄에 기록한다. 거래 키로는 거래 하나를, 주문 번호로는 거래 목록을 기대한다.

예외 종류로 200을 추정하면 실제 수신값을 확인할 수 없다. 본문을 문자열로 먼저 받아 직접 파싱하는 방법도 있지만, 상태를 먼저 확보하면 메시지 변환기를 교체하거나 원문을 로그에 넣을 필요가 없다. [검증 기록](../week10/implementation-log.md#a-pg-조회-본문-해석-실패-시-http-상태-보존)
