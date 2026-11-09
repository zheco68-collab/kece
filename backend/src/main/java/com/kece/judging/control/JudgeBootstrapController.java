package com.kece.judging.control;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/judgers/bootstrap")
@RequiredArgsConstructor
public class JudgeBootstrapController {
    private final JdbcTemplate db;

    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> context() {
        String id =
                db.queryForObject(
                        "SELECT control_plane_id FROM judge_control_identity WHERE id=1",
                        String.class);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("controlPlaneId", id, "discoveryVersion", 1));
    }
}
