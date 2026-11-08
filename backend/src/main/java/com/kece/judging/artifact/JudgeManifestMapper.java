package com.kece.judging.artifact;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface JudgeManifestMapper {

    record Current(long id, int totalCases) {}

    record ArtifactLease(String digest, long size) {}

    @Select(
            """
            SELECT m.id, m.total_cases
            FROM judge_question_manifest q JOIN judge_manifest m ON m.id = q.manifest_id
            WHERE q.submission_type = #{type} AND q.question_id = #{questionId}
            """)
    Current current(@Param("type") String type, @Param("questionId") long questionId);

    @Select(
            """
            SELECT q.id FROM question q
            WHERE q.id > #{afterId} AND NOT EXISTS (SELECT 1 FROM judge_question_manifest m
                    WHERE m.submission_type = 'ALGO' AND m.question_id = q.id)
            ORDER BY q.id LIMIT #{limit}
            """)
    java.util.List<Long> missingCode(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select(
            """
            SELECT q.id FROM db_question q
            WHERE q.id > #{afterId} AND NOT EXISTS (SELECT 1 FROM judge_question_manifest m
                    WHERE m.submission_type = 'DATABASE' AND m.question_id = q.id)
            ORDER BY q.id LIMIT #{limit}
            """)
    java.util.List<Long> missingSql(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select(
            """
            SELECT id FROM judge_manifest
            WHERE submission_type = #{type} AND question_id = #{questionId} AND digest = #{digest}
            FOR UPDATE
            """)
    Long findVersion(
            @Param("type") String type,
            @Param("questionId") long questionId,
            @Param("digest") String digest);

    @Insert(
            """
            INSERT INTO judge_manifest (submission_type, question_id, digest, document, total_cases, created_at)
            VALUES (#{type}, #{questionId}, #{digest}, #{document}, #{totalCases}, UTC_TIMESTAMP(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ManifestRow row);

    @Insert(
            """
            INSERT INTO judge_artifact (digest, byte_size, created_at)
            VALUES (#{digest}, #{size}, UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE digest = judge_artifact.digest
            """)
    void register(JudgeManifest.Artifact artifact);

    @Insert(
            "INSERT INTO judge_manifest_artifact (manifest_id, digest) VALUES (#{manifestId}, #{digest})")
    void attach(@Param("manifestId") long manifestId, @Param("digest") String digest);

    @Insert(
            """
            INSERT INTO judge_question_manifest (submission_type, question_id, manifest_id)
            VALUES (#{type}, #{questionId}, #{manifestId}) AS incoming
            ON DUPLICATE KEY UPDATE manifest_id = incoming.manifest_id
            """)
    void pointTo(
            @Param("type") String type,
            @Param("questionId") long questionId,
            @Param("manifestId") long manifestId);

    @Delete(
            "DELETE FROM judge_question_manifest WHERE submission_type = #{type} AND question_id = #{questionId}")
    void clearCurrent(@Param("type") String type, @Param("questionId") long questionId);

    @Select(
            """
            SELECT a.digest, a.byte_size AS size
            FROM judge_job j
            JOIN judge_node n ON n.id=j.node_id AND n.session_epoch=j.session_epoch
                AND n.credential_hash IS NOT NULL AND n.state <> 'DISABLED'
            JOIN judge_manifest_artifact ma ON ma.manifest_id = j.manifest_id
            JOIN judge_artifact a ON a.digest = ma.digest
            WHERE j.id = #{jobId} AND a.digest = #{digest}
              AND j.state = 'RUNNING' AND j.node_id IS NOT NULL AND j.session_epoch > 0
              AND j.lease_epoch > 0 AND j.lease_until > UTC_TIMESTAMP(6)
              AND j.artifact_token_hash = #{tokenHash}
              AND ((j.submission_type = 'ALGO' AND EXISTS (
                    SELECT 1 FROM submission s WHERE s.id = j.submission_id AND s.current_judge_job_id = j.id))
                OR (j.submission_type = 'DATABASE' AND EXISTS (
                    SELECT 1 FROM db_submission s WHERE s.id = j.submission_id AND s.current_judge_job_id = j.id)))
            """)
    ArtifactLease findLeaseArtifact(
            @Param("jobId") long jobId,
            @Param("digest") String digest,
            @Param("tokenHash") String tokenHash);

    @lombok.Data
    class ManifestRow {
        private Long id;
        private String type;
        private long questionId;
        private String digest;
        private String document;
        private int totalCases;
    }
}
