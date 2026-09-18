-- 실습 사용자 ID는 단일 fixture 매핑이 지정한다. 초기 잔액은 애플리케이션이 생성한다.
CREATE TABLE `user` (
    `id` BIGINT NOT NULL,
    `point_balance` BIGINT NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `ck_user_nonnegative_balance` CHECK (`point_balance` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_general_ci;
