# D 실험 가설과 판정

D 실험(정상 10 + 장애 10)을 실행하기 **전에** 증상·가설·예상을 썼고, "실제 관찰"과 "판정"은 실행한 뒤에 채웠다.

## 증상

운영자가 처음 보는 것만 쓴다.

> health는 UP이다. UNKNOWN 결제가 3건이고 줄지 않는다. 해당 주문 3건은 `PAYMENT_PENDING`, 예약은 `RESERVED`다.

## 가설 1. PG가 아직 결제를 처리 중이다

| 항목 | 내용 |
| --- | --- |
| 맞다면 보일 것 | 주문 번호로 PG를 조회하면 거래가 `PENDING`이다. 생성 응답을 받았다면 202·PENDING일 수 있다. 생성 응답의 수신 여부와 조회 시점의 거래 상태는 별도로 확인한다 |
| 틀리다면 보일 것 | PG 조회 결과가 `SUCCESS` 또는 `FAILED`다 |
| 확인 방법 | 주문 번호로 읽기 전용 PG 조회, 생성 경계 로그 |
| 실제 관찰 | 주문 번호로 읽기 전용 조회하자 세 주문 모두 거래 1건, `SUCCESS`, 1,000원이었다(`TEST-TX-13`·`TEST-TX-16`·`TEST-TX-19`). 주문 13·16·19의 생성 로그는 모두 `PG 응답 없음: op=create ... result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException`이었고 `httpStatus=202` 줄은 없었다. `PaymentIncidentExperimentTest` |
| 판정 | **틀림.** 맞았다면 PG 조회 결과가 `PENDING`이어야 했지만 실제로는 `SUCCESS`였다. 이 판정은 진단 조회 시점에 해당한다. |

## 가설 2. PG는 결제를 끝냈는데, 생성 응답이 우리에게 오지 않았다 (응답 유실)

| 항목 | 내용 |
| --- | --- |
| 맞다면 보일 것 | 생성 요청할 때 응답 없음 로그가 남는다(`PG 응답 없음: op=create ...`). PG를 조회하면 PG 거래는 성공했다고 뜬다(거래 1건, `SUCCESS`). 내부 시도에는 미확인 결과가 저장되고 주문·예약은 대기한다. |
| 틀리다면 보일 것 | 응답을 받았다는 말이니, 생성 요청할 때 응답을 받았다는 로그가 남았을 것이다(`PG 응답: op=create ... httpStatus=202`). |
| 확인 방법 | 생성 경계 로그, 주문 번호로 읽기 전용 PG 조회 |
| 실제 관찰 | 주문 13·16·19의 생성 로그는 모두 `PG 응답 없음: op=create ... result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException`이었고 `httpStatus=202` 줄은 없었다. 주문 번호로 읽기 전용 조회하자 세 주문 모두 거래 1건, `SUCCESS`, 1,000원이었다(`TEST-TX-13`·`TEST-TX-16`·`TEST-TX-19`). `결제 결과 저장 실패` 로그는 BASELINE·FAULT·RECOVERY 기록 어디에도 없었다. `PaymentIncidentExperimentTest` |
| 판정 | **맞음.** 세 건 모두 생성 경계에서 `SocketTimeoutException`을 기록했고, 진단 조회에서 같은 주문 번호·금액의 성공 거래가 한 건씩 나왔다. DB는 아직 `UNKNOWN`·`RESERVED`였다. `PG 응답 없음`만으로는 요청이 PG에 닿지 않은 경우와 구분되지 않아, PG 조회의 `SUCCESS`까지 함께 봐야 판정할 수 있다. |

PG 거래가 `SUCCESS`라는 것은 미리 아는 사실이 아니라, 조회해서 확인할 내용이다.

## 가설 3. 응답은 받았는데, DB에 저장하다 실패했다

| 항목 | 내용 |
| --- | --- |
| 맞다면 보일 것 | 같은 주문에 `PG 응답: ... SUCCESS`와 `결제 결과 저장 실패` 로그가 둘 다 있다 |
| 경쟁 설명을 지지할 관찰 | 같은 생성 시도에서 응답 대기 timeout이 명시되고 이후 PG 조회에 같은 거래가 남아 있다 |
| 대조 실험 | `PaymentBoundaryLogTest`에서 TX B에 예외를 주면 같은 주문의 조회 응답과 `step=TX_B:RECONCILE`이 함께 남았다. 수집 누락 가능성까지 배제하는 실험은 아니다. |
| 확인 방법 | 주문 번호(`LP-ORD-...`)와 `orderId`로 경계 로그 검색 |
| 실제 관찰 | 주문 13·16·19의 생성 로그는 모두 `PG 응답 없음: op=create ... result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException`이었고 `httpStatus=202` 줄은 없었다. `결제 결과 저장 실패` 로그는 BASELINE·FAULT·RECOVERY 기록 어디에도 없었다. 같은 실행의 나머지 17건은 응답을 받고 정상 저장·확정됐다. `PaymentIncidentExperimentTest` |
| 판정 | **세 건의 최초 생성 장애 원인으로는 틀림.** 응답을 받아 저장했다는 가설의 전제와 달리, 같은 생성 시도에서 응답 대기 timeout을 직접 관찰했다. 나머지 17건은 응답을 받고 확정됐다. |

오류 줄이 없다는 사실만으로 별도 시점의 저장 실패까지 배제할 수는 없다. 조회 응답과 TX B 실패 로그가 함께 있으면 그 조회가 DB에 반영되지 못한 경계를 특정할 수 있다.

## 판정 기준

판정은 맞음·틀림·미확정 중 하나다. 가진 증거로 가를 수 없으면 미확정으로 두고, 무엇이 더 있어야 가를 수 있는지 쓴다.

## 결론

세 주문의 최초 생성 요청이 timeout됐고 진단 조회 때는 각각 같은 금액의 성공 거래가 한 건이었다. 내부 상태는 여전히 미확인이어서 같은 세 주문만 reconcile했다. PG가 생성 응답을 보류하기 전에 성공을 저장한 순서는 fixture가 보장한다. 운영 로그와 나중의 조회만으로 PG 내부에서 성공한 정확한 시각까지 정할 수는 없다.

## 주문 13으로 본 비교: TX B 실패였다면 어떤 기록이 달랐을까

TX B(결과 저장)가 실패하는 경우는 두 가지다. 어느 응답을 저장하다 실패했는지에 따라 DB에 남는 상태가 다르다.

- ㉠ 생성 응답(202·PENDING)을 받았는데 그 응답을 저장하다 실패(`TX_B:CREATE_RESPONSE`)
- ㉡ 생성 응답은 유실됐고, 나중에 reconcile 조회로 SUCCESS를 받았는데 그 결과를 저장하다 실패(`TX_B:RECONCILE`)

| 보는 곳 | 실제 (응답 유실) | ㉠ 생성 응답 저장 실패 | ㉡ reconcile 결과 저장 실패 |
| --- | --- | --- | --- |
| 생성 경계 로그 | `PG 응답 없음: op=create providerOrderId=LP-ORD-0000000013 ... error=SocketTimeoutException elapsedMs=1003` | `PG 응답: op=create ... httpStatus=202 result=OBSERVED transactions=[TEST-TX-13:LP-ORD-0000000013:PENDING:1000]` | 실제와 같다 |
| 저장 실패 로그 | 없음 | `결제 결과 저장 실패: orderId=13 step=TX_B:CREATE_RESPONSE ...` | reconcile 때 `결제 결과 저장 실패: orderId=13 step=TX_B:RECONCILE ...` |
| DB 결제 시도 (FAULT 시점) | `UNKNOWN`, 거래 키 없음 | `REQUESTED`, 거래 키 없음 (TX A가 저장한 상태 그대로) | `UNKNOWN`, 거래 키 없음 (reconcile 뒤에도 그대로) |
| UNKNOWN gauge | 1건으로 셈 | **세지 않음** (`REQUESTED`는 대상이 아니다) | 1건으로 셈 |
| PG 조회 | 거래 1건 `SUCCESS` | 같다 | 같다 |

- 실제 열은 `PaymentIncidentExperimentTest`의 D 실행 결과다.
- ㉡은 `PaymentBoundaryLogTest`에서 실제로 만든 경우다. 결제 시도 상태만 보면 응답 유실과 같다. 같은 조회의 SUCCESS 응답과 저장 실패 로그를 함께 대조한다.
- ㉠은 `PaymentBoundaryLogTest.createResponseSaveFailureLeavesRequestedOutsideUnknownCount`에서 실제로 만들었다. TX A가 결제 시도를 `REQUESTED`로 저장하고(`OrderPaymentTransactions.start`), 생성 응답을 저장하는 TX B를 실패시키자 `REQUESTED`·거래 키 없음이 그대로 남았다. DB 집계는 UNKNOWN 0건, REQUESTED 1건이었다. 저장 실패를 풀고 reconcile하자 주문 번호 조회로 `SUCCESS`를 읽어 `CONFIRMED`가 됐고, PG 생성 호출은 1회 그대로였다. `PaymentBoundaryLogTest.createResponseSaveFailureLeavesRequestedOutsideUnknownCount` 이 실험은 D 실험과 별도 실행이며 주문 번호는 `LP-ORD-0000000001`이다.

PG `SUCCESS`와 내부 상태 두 값만으로는 ㉡과 응답 유실이 갈리지 않는다. 두 경우 모두 `SUCCESS`와 `UNKNOWN`이다. ㉠은 DB 상태가 `REQUESTED`로 달라서 구분되지만, **현재 UNKNOWN gauge에는 보이지 않는다.** 응답을 받고도 저장에 실패한 주문은 지표상 0건으로 조용히 멈춰 있을 수 있다.

