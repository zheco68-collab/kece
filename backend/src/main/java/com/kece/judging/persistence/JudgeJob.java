package com.kece.judging.persistence;

import lombok.Data;

@Data
public class JudgeJob {
    private Long id;
    private Long manifestId;
    private String submissionType;
    private Long submissionId;
    private int generation;
    private String pool;
    private String scene;
    private long routeRevision;
    private String runtimeSpec;
    private String requestSnapshot;
    private String requestDigest;
    private String state;
    private String progressPhase;
    private int totalCases;
    private int finishedCases;
}
