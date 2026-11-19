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
