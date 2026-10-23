| 상황 (같은 멱등키·같은 요청 기준) | `TossPgFixture.withScenarios()`의 요청 상수 | WireMock 응답 |
| --- | --- | --- |
| 최초 승인 성공 | `SUCCESS_REQUEST` | `200 DONE` |
| 최초 승인 실패 | `REJECTED_REQUEST` | `403 REJECT_CARD_PAYMENT` |
| 성공 후 재요청 | `SUCCESS_REQUEST` 재사용 | 최초 성공 응답 그대로 반환 |
| 실패 후 재요청 | `REJECTED_REQUEST` 재사용 | 최초 실패 응답 그대로 반환 |
| 타임아웃 | `TIMEOUT_REQUEST` | 성공 응답을 3초 지연. 클라이언트 대기 1초 설정 시 `Unknown` |
| PG 5xx | `SERVER_ERROR_REQUEST` | `500 FAILED_INTERNAL_SYSTEM_PROCESSING` → 어댑터에서 `Unknown` |
