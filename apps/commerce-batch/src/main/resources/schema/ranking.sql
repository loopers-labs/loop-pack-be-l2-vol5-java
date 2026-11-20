create table if not exists ranking_snapshot (
  snapshot_id    varchar(40) not null,
  period_start   date        not null,
  revision       int         not null,
  status         varchar(20) not null,
  frozen_at      datetime(6) not null,
  input_count    bigint      not null,
  input_checksum char(64)    not null,
  primary key (snapshot_id),
  unique key uk_ranking_snapshot_period_revision (period_start, revision)
) engine=InnoDB;

create table if not exists ranking_snapshot_input (
  snapshot_id  varchar(40) not null,
  product_id   bigint      not null,
  order_id     bigint      not null,
  quantity     int         not null,
  confirmed_at datetime(6) not null,
  primary key (snapshot_id, product_id, order_id)
) engine=InnoDB;

create table if not exists ranking_score_stage (
  snapshot_id varchar(40) not null,
  product_id  bigint      not null,
  score       bigint      not null,
  primary key (snapshot_id, product_id)
) engine=InnoDB;

create table if not exists weekly_ranking (
  snapshot_id varchar(40) not null,
  rank_no     int         not null,
  product_id  bigint      not null,
  score       bigint      not null,
  primary key (snapshot_id, rank_no)
) engine=InnoDB;

create table if not exists weekly_ranking_publication (
  period_start date        not null,
  snapshot_id  varchar(40) not null,
  revision     int         not null,
  published_at datetime(6) not null,
  frozen_at    datetime(6) not null,
  primary key (period_start)
) engine=InnoDB;
