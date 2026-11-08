package com.kece.judging.control;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JudgeRouteService {

    private static final Set<String> ALLOWED_POOLS =
            Set.of("cpp", "java", "python", "mysql", "arkts");
    private static final Set<String> ALLOWED_SCENES =
            Set.of("PROBLEM", "PRACTICE", "EXAM", "REJUDGE");
    private static final Set<String> ALLOWED_MODES = Set.of("PERSISTENT");

    private final JdbcTemplate db;

    public List<JudgeRouteVO> listRoutes() {
        var existing =
                db.query(
                        "SELECT pool, scene, dispatch_mode, revision FROM judge_pool_route ORDER BY pool, scene",
                        (rs, rowNum) ->
                                JudgeRouteVO.builder()
                                        .pool(rs.getString("pool"))
                                        .scene(rs.getString("scene"))
                                        .dispatchMode(rs.getString("dispatch_mode"))
                                        .revision(rs.getLong("revision"))
                                        .build());

        Map<String, JudgeRouteVO> map = new HashMap<>();
        for (var r : existing) {
            map.put(r.getPool() + ":" + r.getScene(), r);
        }

        List<JudgeRouteVO> all = new ArrayList<>();
        for (String pool : List.of("cpp", "java", "python", "mysql", "arkts")) {
            for (String scene : List.of("PROBLEM", "PRACTICE", "EXAM", "REJUDGE")) {
                String key = pool + ":" + scene;
                if (map.containsKey(key)) {
                    all.add(map.get(key));
                } else {
                    all.add(
                            JudgeRouteVO.builder()
                                    .pool(pool)
                                    .scene(scene)
                                    .dispatchMode("PERSISTENT")
                                    .revision(1)
                                    .build());
                }
            }
        }
        return all;
    }

    @Transactional
    public JudgeRouteVO setRoute(String pool, String scene, String dispatchMode) {
        String p = pool == null ? "" : pool.trim().toLowerCase();
        String s = scene == null ? "" : scene.trim().toUpperCase();
        String m = dispatchMode == null ? "" : dispatchMode.trim().toUpperCase();

        if (!ALLOWED_POOLS.contains(p)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的评测专池: " + pool);
        }
        if (!ALLOWED_SCENES.contains(s)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的业务场景: " + scene);
        }
        if (!ALLOWED_MODES.contains(m)) {
            throw new BusinessException(
                    ResultCode.PARAM_ERROR, "旧评测队列已退役，仅支持 PERSISTENT 路由模式: " + dispatchMode);
        }

        db.update(
                """
                INSERT INTO judge_pool_route (pool, scene, dispatch_mode, revision)
                VALUES (?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE dispatch_mode = VALUES(dispatch_mode), revision = revision + 1
                """,
                p,
                s,
                m);

        return db.queryForObject(
                "SELECT pool, scene, dispatch_mode, revision FROM judge_pool_route WHERE pool = ? AND scene = ?",
                (rs, rowNum) ->
                        JudgeRouteVO.builder()
                                .pool(rs.getString("pool"))
                                .scene(rs.getString("scene"))
                                .dispatchMode(rs.getString("dispatch_mode"))
                                .revision(rs.getLong("revision"))
                                .build(),
                p,
                s);
    }
}
