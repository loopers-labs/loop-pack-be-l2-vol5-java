-- Optional offline import from a restored development schema. Run on a prepared, stopped target.
-- The source stays read-only. Historical Spring Batch job names, keys, steps and checkpoints stay intact.
USE loopers;

DROP PROCEDURE IF EXISTS import_completed_weekly_order_partitions;
DELIMITER //
CREATE PROCEDURE import_completed_weekly_order_partitions()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
    START TRANSACTION WITH CONSISTENT SNAPSHOT;
    IF EXISTS (SELECT 1 FROM loopers_pre_order_partition.weekly_ranking_plans WHERE state='OPEN')
       OR EXISTS (SELECT 1 FROM loopers_pre_order_partition.weekly_ranking_shards
                  WHERE state IN ('READY','RUNNING','RECOVERY_REQUIRED'))
       OR EXISTS (SELECT 1 FROM loopers_pre_order_partition.BATCH_JOB_INSTANCE i
                  JOIN loopers_pre_order_partition.BATCH_JOB_EXECUTION e USING (JOB_INSTANCE_ID)
                  WHERE i.JOB_NAME='weeklyRankingShard' AND e.STATUS IN ('STARTING','STARTED','STOPPING','UNKNOWN')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Finish or recover open work on the previous binary first';
    END IF;
    IF EXISTS (SELECT 1 FROM weekly_ranking_plans) OR EXISTS (SELECT 1 FROM weekly_ranking_schedule_cursor)
       OR EXISTS (SELECT 1 FROM weekly_ranking_members) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='The target coordination tables must be empty and all schedulers stopped';
    END IF;
    IF EXISTS (SELECT 1 FROM loopers_pre_order_partition.BATCH_JOB_INSTANCE old
               LEFT JOIN BATCH_JOB_INSTANCE copied ON copied.JOB_INSTANCE_ID=old.JOB_INSTANCE_ID
               WHERE old.JOB_NAME='weeklyRankingShard' AND (copied.JOB_INSTANCE_ID IS NULL OR
                     BINARY copied.JOB_NAME<>BINARY old.JOB_NAME OR BINARY copied.JOB_KEY<>BINARY old.JOB_KEY))
       OR EXISTS (SELECT 1 FROM loopers_pre_order_partition.weekly_ranking_publications old
                  LEFT JOIN weekly_ranking_publications copied USING (period_start)
                  WHERE copied.period_start IS NULL OR NOT (copied.snapshot_id<=>old.snapshot_id AND copied.revision<=>old.revision)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Restore original Batch history and ranking data on the target before importing';
    END IF;
    INSERT INTO weekly_ranking_schedule_cursor (schedule_id,last_period)
        SELECT schedule_id,last_period FROM loopers_pre_order_partition.weekly_ranking_schedule_cursor;
    INSERT INTO weekly_ranking_plans (snapshot_id,period_start,revision,order_partition_count,state)
        SELECT snapshot_id,period_start,revision,shard_count,state FROM loopers_pre_order_partition.weekly_ranking_plans;
    INSERT INTO weekly_ranking_order_partitions
        (snapshot_id,order_partition_id,order_min,order_max,is_empty,preferred_node,local_grid,state,owner_id,owner_session,
         generation,execution_id,frozen_at,input_count,input_checksum,last_error,attempts)
        SELECT snapshot_id,shard_id,order_min,order_max,is_empty,preferred_node,local_grid,state,owner_id,owner_session,
               generation,execution_id,frozen_at,input_count,input_checksum,last_error,attempts
        FROM loopers_pre_order_partition.weekly_ranking_shards;
    INSERT INTO weekly_ranking_partial_scores (snapshot_id,order_partition_id,product_id,score)
        SELECT snapshot_id,shard_id,product_id,score FROM loopers_pre_order_partition.weekly_ranking_partial_scores;
    INSERT INTO weekly_ranking_score_conflicts
        (snapshot_id,order_partition_id,product_id,stored_score,attempted_score,owner_id,generation,recorded_at)
        SELECT snapshot_id,shard_id,product_id,stored_score,attempted_score,owner_id,generation,recorded_at
        FROM loopers_pre_order_partition.weekly_ranking_score_conflicts;
    COMMIT;
END//
DELIMITER ;
CALL import_completed_weekly_order_partitions();
DROP PROCEDURE import_completed_weekly_order_partitions;
