package com.kece.judging.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kece.judging.artifact.JudgeManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JudgeProtocolContractTest {

    private final ObjectMapper strictMapper =
            new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                    .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    private Path contractsDir() {
        Path direct = Path.of("..", "docs", "contracts");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return Path.of("docs", "contracts");
    }

    private JsonNode loadFixture(String filename) throws IOException {
        Path file = contractsDir().resolve("samples").resolve(filename);
        assertThat(file).exists();
        return strictMapper.readTree(Files.readString(file));
    }

    @Test
    void openApiSpecCoversAllMachineControlEndpoints() throws IOException {
        Path specFile = contractsDir().resolve("judger-v1.openapi.yaml");
        assertThat(specFile).exists();
        String spec = Files.readString(specFile);
        assertThat(spec)
                .contains(
                        "/internal/judgers/join:",
                        "/internal/judgers/session:",
                        "/internal/judgers/heartbeat:",
                        "/internal/judgers/jobs/claim:",
                        "/internal/judgers/jobs/renew:",
                        "/internal/judgers/jobs/{id}/artifacts/{digest}:",
                        "/internal/judgers/jobs/{id}/result:");
    }

    @Test
    void joinAndHeartbeatFixturesMatchServerDtos() throws IOException {
        JsonNode join = loadFixture("join.json");
        var joinReq =
                strictMapper.treeToValue(
                        join.get("request"), JudgeMachineController.JoinRequest.class);
        var joinResp = strictMapper.treeToValue(join.get("response"), JudgeNodes.Joined.class);
        assertThat(joinReq.protocolVersion()).isEqualTo(1);
        assertThat(joinReq.bootId()).matches("[A-Za-z0-9_-]{16,64}");
        assertThat(joinResp.nodeId()).isEqualTo("12");
        assertThat(joinResp.pool()).isEqualTo("python");
        assertThat(joinResp.runtimeSpec()).isEqualTo("python-v1");

        JsonNode hb = loadFixture("heartbeat.json");
        var hbReq =
                strictMapper.treeToValue(
                        hb.get("request"), JudgeMachineController.HeartbeatRequest.class);
        var hbResp = strictMapper.treeToValue(hb.get("response"), JudgeNodes.Heartbeat.class);
        assertThat(hbReq.checkResult().checkId()).isEqualTo("7");
        assertThat(hbReq.checkResult().passed()).isTrue();
        assertThat(hbResp.state()).isEqualTo("ENABLED");
        assertThat(hbResp.check().checkId()).isEqualTo("8");
    }

    @Test
    void claimAndRenewFixturesMatchServerDtosAndManifests() throws IOException {
        JsonNode claimAlgo = loadFixture("claim-algo.json");
        var claimReq =
                strictMapper.treeToValue(
                        claimAlgo.get("request"), JudgeMachineController.ClaimRequest.class);
        var claimBatch =
                strictMapper.treeToValue(
                        claimAlgo.get("response"), JudgeScheduler.ClaimBatch.class);
        assertThat(claimReq.slots()).isEqualTo(2);
        assertThat(claimBatch.jobs()).hasSize(1);
        var algoJob = claimBatch.jobs().get(0);
        assertThat(algoJob.submissionType()).isEqualTo("ALGO");
        assertThat(algoJob.submissionId()).isEqualTo("204");
        var algoManifest = strictMapper.treeToValue(algoJob.manifest(), JudgeManifest.class);
        assertThat(algoManifest.cases()).hasSize(2);
        assertThat(algoManifest.cases().get(0).output().size()).isZero();

        JsonNode claimSql = loadFixture("claim-sql.json");
        var sqlBatch =
                strictMapper.treeToValue(claimSql.get("response"), JudgeScheduler.ClaimBatch.class);
        assertThat(sqlBatch.jobs()).hasSize(1);
        var sqlJob = sqlBatch.jobs().get(0);
        assertThat(sqlJob.submissionType()).isEqualTo("DATABASE");
        assertThat(sqlJob.submissionId()).isEqualTo("204");
        var sqlManifest = strictMapper.treeToValue(sqlJob.manifest(), JudgeManifest.class);
        assertThat(sqlManifest.sql().type()).isEqualTo("DML");

        JsonNode renew = loadFixture("renew.json");
        var renewReq =
                strictMapper.treeToValue(
                        renew.get("request"), JudgeMachineController.RenewRequest.class);
        assertThat(renewReq.jobs()).hasSize(1);
        assertThat(renewReq.jobs().get(0).phase()).isEqualTo("JUDGING");
    }

    @Test
    void resultFixturesSatisfyVerdictCompileErrorAndInfraFailureContracts() throws IOException {
        JsonNode ac = loadFixture("result-accepted.json");
        var acReq = strictMapper.treeToValue(ac.get("request"), JudgeScheduler.ResultRequest.class);
        var acResp = strictMapper.treeToValue(ac.get("response"), JudgeScheduler.Receipt.class);
        assertThat(acReq.status()).isEqualTo("ACCEPTED");
        assertThat(acReq.errorCode()).isNull();
        assertThat(acReq.result().getScore()).isEqualTo(100);
        assertThat(acReq.result().getTestCasesReport()).hasSize(2);
        assertThat(acResp.status()).isEqualTo("COMMITTED");

        JsonNode ce = loadFixture("result-compile-error.json");
        var ceReq = strictMapper.treeToValue(ce.get("request"), JudgeScheduler.ResultRequest.class);
        assertThat(ceReq.status()).isEqualTo("COMPILE_ERROR");
        assertThat(ceReq.result().getScore()).isZero();
        assertThat(ceReq.result().getTestCasesReport()).isNotNull().isEmpty();

        JsonNode infra = loadFixture("result-infra-error.json");
        var infraReq =
                strictMapper.treeToValue(infra.get("request"), JudgeScheduler.ResultRequest.class);
        var infraResp =
                strictMapper.treeToValue(infra.get("response"), JudgeScheduler.Receipt.class);
        assertThat(infraReq.status()).isNull();
        assertThat(infraReq.result()).isNull();
        assertThat(infraReq.errorCode()).matches("[A-Z0-9_]{1,64}");
        assertThat(infraResp.status()).isEqualTo("RETRY_SCHEDULED");
    }
}
