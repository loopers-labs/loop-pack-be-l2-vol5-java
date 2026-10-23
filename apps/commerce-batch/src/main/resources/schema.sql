create table if not exists payment_reconciliations (
    payment_id bigint primary key,
    status varchar(30) not null,
    local_status varchar(40) not null,
    pg_status varchar(40),
    pg_response text,
    attempt_count integer not null,
    last_checked_at timestamp not null
);
