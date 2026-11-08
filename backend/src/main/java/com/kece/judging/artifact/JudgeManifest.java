package com.kece.judging.artifact;

import java.util.List;

/** Immutable execution data for one version of a question. */
public record JudgeManifest(
        int version,
        String submissionType,
        Integer timeLimitMs,
        Integer memoryLimitMb,
        SqlSpec sql,
        List<CaseSpec> cases) {

    public record Artifact(String digest, long size) {}

    public record SqlSpec(
            String dialect,
            String type,
            Artifact schema,
            Artifact reference,
            Artifact verify,
            boolean orderSensitive) {}

    public record CaseSpec(
            long caseId,
            int caseNum,
            int scoreWeight,
            boolean sample,
            Artifact input,
            Artifact output,
            Artifact seed) {}
}
