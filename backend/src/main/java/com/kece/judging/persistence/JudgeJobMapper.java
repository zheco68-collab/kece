package com.kece.judging.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JudgeJobMapper {

    @Select(
            """
            SELECT revision FROM judge_pool_route
            WHERE pool = #{pool} AND scene = #{scene} AND dispatch_mode = 'PERSISTENT'
            """)
    Long findPersistentRevision(@Param("pool") String pool, @Param("scene") String scene);

    @Insert(
            """
            INSERT INTO judge_job
                (submission_type, submission_id, generation, pool, scene, route_revision,
                 runtime_spec, manifest_id, request_snapshot, request_digest, total_cases, available_at, created_at)
            VALUES
                (#{submissionType}, #{submissionId}, #{generation}, #{pool}, #{scene}, #{routeRevision},
                 #{runtimeSpec}, #{manifestId}, #{requestSnapshot}, #{requestDigest}, #{totalCases},
                 UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JudgeJob job);

    @Select(
            """
            SELECT id, generation, pool, route_revision, state, progress_phase, total_cases, finished_cases
            FROM judge_job WHERE id = #{id}
            """)
    JudgeJob findState(Long id);

    @Update(
            """
            UPDATE judge_job j
            SET j.state = 'CANCELLED',
                j.artifact_token_hash = NULL,
                j.lease_until = NULL,
                j.finished_at = UTC_TIMESTAMP(6),
                j.status_version = j.status_version + 1
            WHERE j.state = 'QUEUED'
              AND NOT ((j.submission_type = 'ALGO' AND EXISTS (
                         SELECT 1 FROM submission s WHERE s.id = j.submission_id AND s.current_judge_job_id = j.id))
                    OR (j.submission_type = 'DATABASE' AND EXISTS (
                         SELECT 1 FROM db_submission s WHERE s.id = j.submission_id AND s.current_judge_job_id = j.id)))
            """)
    int cancelOrphanedQueued();
}
