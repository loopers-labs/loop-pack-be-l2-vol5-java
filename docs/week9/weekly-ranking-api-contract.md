# 주간 구매 랭킹 API 계약

## 조회

특정 UTC 주간의 완성된 상품 구매 랭킹을 조회한다. 주간은 월요일 00:00부터 다음 월요일 00:00 직전까지다.

```http
GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10
X-USER-ID: 1
```


| 항목            | 위치  | 필수  | 기본값  | 규칙                                             |
| ------------- | --- | ---: | ----: | ---------------------------------------------- |
| `X-USER-ID`   | 헤더  | 예   | 없음   | 기존 고객 API 식별 규칙을 따른다.                          |
| `periodStart` | 쿼리  | 예   | 없음   | `YYYY-MM-DD` 형식의 UTC 월요일 날짜다. 해당 주의 시작일을 지정한다. |
| `limit`       | 쿼리  | 아니오 | `10` | 양의 정수이며 1~100 범위다.                             |


`periodStart=2026-08-17`은 `[2026-08-17T00:00:00Z, 2026-08-24T00:00:00Z)` 기간을 뜻한다. 날짜가 잘못된 형식이거나 월요일이 아니면 `400 INVALID_REQUEST`를 반환한다. `limit`이 정수가 아니거나 1~100 범위를 벗어나도 `400 INVALID_REQUEST`를 반환한다.



## 성공 응답

요청이 유효하면 `200 OK`를 반환한다. `data.status`는 해당 주간 결과의 공개 여부를 나타낸다.


| 필드                         | 설명                                    |
| -------------------------- | ------------------------------------- |
| `data.status`              | 공개 상태. `PUBLISHED` 또는 `NOT_PUBLISHED` |
| `data.periodStart`         | 조회한 주간 시작일(UTC, 포함)                   |
| `data.periodEnd`           | 주간 종료일(UTC, 미포함)                      |
| `data.snapshotId`          | 공개된 결과를 만든 snapshot 식별자               |
| `data.inputFixedAt`        | 공개 snapshot의 입력을 고정한 시각(UTC)          |
| `data.items`               | 상품 랭킹 목록. 미공개이거나 결과가 비어 있으면 빈 배열      |
| `data.items[].productId`   | 상품 식별자                                |
| `data.items[].productName` | 현재 고객에게 공개 가능한 상품명                    |
| `data.items[].score`       | 해당 주간의 구매 수량 합                        |
| `data.items[].rank`        | 계산 시 저장한 순위                           |


### 공개 결과 예시

```json
{
  "meta": {
    "result": "SUCCESS",
    "errorCode": null,
    "message": null
  },
  "data": {
    "status": "PUBLISHED",
    "periodStart": "2026-08-17",
    "periodEnd": "2026-08-24",
    "snapshotId": "weekly-2026-08-17-r1",
    "inputFixedAt": "2026-08-24T00:05:00Z",
    "items": [
      {
        "productId": 7,
        "productName": "상품 7",
        "score": 5,
        "rank": 1
      },
      {
        "productId": 9,
        "productName": "상품 9",
        "score": 5,
        "rank": 2
      }
    ]
  }
}
```


| `data.status`   | 의미                                     | snapshot 필드                                    | `data.items`        |
| --------------- | -------------------------------------- | ---------------------------------------------- | ------------------- |
| `PUBLISHED`     | 해당 주간의 계산과 공개가 완료됐다. 구매가 없었던 주간도 포함한다. | 공개 snapshot 정보 반환                              | 랭킹 목록. 구매가 없으면 빈 배열 |
| `NOT_PUBLISHED` | 해당 주간에 공개된 결과가 없다.                     | `snapshotId`와 `inputFixedAt` 모두 `null`         | 빈 배열                |


미공개 상태 응답은 다음과 같다.

```json
{
  "meta": {
    "result": "SUCCESS",
    "errorCode": null,
    "message": null
  },
  "data": {
    "status": "NOT_PUBLISHED",
    "periodStart": "2026-08-17",
    "periodEnd": "2026-08-24",
    "snapshotId": null,
    "inputFixedAt": null,
    "items": []
  }
}
```

## 오류


| 상황                                                        | HTTP 상태 | 오류 코드                 |
| --------------------------------------------------------- | -------: | --------------------- |
| `periodStart` 누락·날짜 형식 오류·월요일이 아닌 날짜, `limit` 형식 또는 범위 오류 | `400`   | `INVALID_REQUEST`     |
| `X-USER-ID` 누락·형식 오류·존재하지 않는 사용자                          | `401`   | `USER_NOT_IDENTIFIED` |
| 처리되지 않은 서버 오류                                             | `500`   | `INTERNAL_ERROR`      |


공통 응답 형식과 고객 식별 규칙은 [commerce-api API 계약](../week2/api-contract.md)을 따른다.
