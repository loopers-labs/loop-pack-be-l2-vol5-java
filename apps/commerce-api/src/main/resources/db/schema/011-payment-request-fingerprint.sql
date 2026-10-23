-- 010까지 적용한 DB에 한 번 적용한다. 기존 행의 결제 사실과 NULL 비교 자료를 보존한다.
-- 카드 번호·원문 요청·HMAC 비밀키는 저장하지 않는다. 지문은 내부 비교 자료이며 외부 응답에 노출하지 않는다.
-- BINARY의 짧은 값 패딩을 피하고 정확히 32바이트인 값만 받기 위해 VARBINARY와 CHECK를 함께 사용한다.
ALTER TABLE `payment_attempt`
    ADD COLUMN `request_fingerprint` VARBINARY(32) NULL,
    ADD COLUMN `fingerprint_version` INT NULL,
    ADD COLUMN `key_version` INT NULL,
    ADD CONSTRAINT `ck_payment_attempt_fingerprint` CHECK (
        (`request_fingerprint` IS NULL AND `fingerprint_version` IS NULL AND `key_version` IS NULL)
        OR (
            `request_fingerprint` IS NOT NULL
            AND `fingerprint_version` IS NOT NULL
            AND `key_version` IS NOT NULL
            AND OCTET_LENGTH(`request_fingerprint`) = 32
            AND `fingerprint_version` > 0
            AND `key_version` > 0
        )
    );
