package com.kece.judging.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class JudgeArtifactController {

    private final JudgeManifestMapper manifests;
    private final JudgeArtifactStore artifacts;

    @GetMapping("/internal/judgers/jobs/{jobId}/artifacts/{digest}")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable long jobId,
            @PathVariable String digest,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) {
            return ResponseEntity.status(401).build();
        }
        if (jobId <= 0 || !digest.matches("[0-9a-f]{64}")) {
            return ResponseEntity.notFound().build();
        }
        // The control plane binds a fresh capability hash to each lease.
        String tokenHash =
                JudgeArtifactStore.digest(
                        authorization.substring(7).getBytes(StandardCharsets.US_ASCII));
        var lease = manifests.findLeaseArtifact(jobId, digest, tokenHash);
        if (lease == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            var stream = artifacts.openVerified(new JudgeManifest.Artifact(digest, lease.size()));
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(lease.size())
                    .header("X-Content-SHA256", digest)
                    .body(new InputStreamResource(stream));
        } catch (IOException damaged) {
            return ResponseEntity.status(503).build();
        }
    }
}
