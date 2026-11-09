package com.kece.judging.control;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ApiResponse;
import com.kece.common.response.ResultCode;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class JudgeAdminController {
    private final JudgeNodes nodes;
    private final JudgeRouteService routeService;
    private final JudgeReconciliationService reconciliationService;
    private final JudgeConnections connections;
    @Value("${kece.judging.legacy-admin-enrollment-enabled:false}")
    private boolean legacyAdminEnrollmentEnabled;

    record HostRequest(String name, int maxSlots) {}

    record HostUpdate(long revision, int maxSlots) {}

    record RegisterRequest(
            String name, String pool, String hostGroupId, int maxSlots, String baseUrl) {}

    record NodeUpdate(long revision, String state, Integer maxSlots) {}

    record Revision(long revision) {}

    record ConnectRequest(String apiUrl) {}

    @PostMapping("/admin/judgers/connections")
    public ApiResponse<JudgeConnections.ConnectionView> connect(
            @RequestBody ConnectRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.success(connections.request(body.apiUrl(), idempotencyKey));
    }

    @GetMapping("/admin/judgers/connections")
    public ApiResponse<List<JudgeConnections.ConnectionView>> connections() {
        return ApiResponse.success(connections.list());
    }

    @GetMapping("/admin/judgers/connections/{id}")
    public ApiResponse<JudgeConnections.ConnectionView> connection(@PathVariable long id) {
        return ApiResponse.success(connections.get(id));
    }

    @PostMapping("/admin/judgers/connections/{id}/cancel")
    public ApiResponse<JudgeConnections.ConnectionView> cancelConnection(@PathVariable long id) {
        return ApiResponse.success(connections.cancel(id));
    }

    @GetMapping("/admin/judge-host-groups")
    public ApiResponse<List<JudgeNodes.HostView>> hosts() {
        return ApiResponse.success(nodes.hosts());
    }

    @PostMapping("/admin/judge-host-groups")
    public ApiResponse<JudgeNodes.HostView> host(@RequestBody HostRequest body) {
        requireLegacyEnrollment();
        return ApiResponse.success(nodes.createHost(body.name(), body.maxSlots()));
    }

    @PatchMapping("/admin/judge-host-groups/{id}")
    public ApiResponse<Void> changeHost(@PathVariable long id, @RequestBody HostUpdate body) {
        nodes.changeHost(id, body.revision(), body.maxSlots());
        return ApiResponse.success();
    }

    @GetMapping("/admin/judgers")
    public ApiResponse<List<JudgeNodes.NodeView>> list(
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(nodes.list(afterId, limit));
    }

    @GetMapping("/admin/judgers/{id}")
    public ApiResponse<JudgeNodes.Detail> detail(@PathVariable long id) {
        return ApiResponse.success(nodes.detail(id));
    }

    @PostMapping("/admin/judgers")
    public ResponseEntity<ApiResponse<JudgeNodes.Enrollment>> register(
            @RequestBody RegisterRequest body) {
        requireLegacyEnrollment();
        long host;
        try {
            host = JudgeNodes.parseId(body.hostGroupId());
        } catch (JudgeProtocolException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的宿主机 ID");
        }
        return secret(
                nodes.register(body.name(), body.pool(), host, body.maxSlots(), body.baseUrl()));
    }

    @PatchMapping("/admin/judgers/{id}")
    public ApiResponse<JudgeNodes.NodeView> change(
            @PathVariable long id, @RequestBody NodeUpdate body) {
        return ApiResponse.success(
                nodes.change(id, body.revision(), body.state(), body.maxSlots()));
    }

    @PostMapping("/admin/judgers/{id}/checks")
    public ApiResponse<JudgeNodes.CheckView> check(@PathVariable long id) {
        var node = nodes.find(id, false);
        return ApiResponse.success(nodes.requestCheck(node));
    }

    @PostMapping("/admin/judgers/{id}/enrollment")
    public ResponseEntity<ApiResponse<JudgeNodes.Enrollment>> reset(
            @PathVariable long id, @RequestBody Revision body) {
        requireLegacyEnrollment();
        return secret(nodes.reset(id, body.revision()));
    }

    @PostMapping("/admin/judgers/{id}/revoke")
    public ApiResponse<Void> revoke(@PathVariable long id, @RequestBody Revision body) {
        nodes.revoke(id, body.revision());
        return ApiResponse.success();
    }

    record RouteUpdate(String dispatchMode) {}

    @GetMapping("/admin/judge-routes")
    public ApiResponse<List<JudgeRouteVO>> routes() {
        return ApiResponse.success(routeService.listRoutes());
    }

    @PutMapping("/admin/judge-routes/{pool}/{scene}")
    public ApiResponse<JudgeRouteVO> updateRoute(
            @PathVariable String pool, @PathVariable String scene, @RequestBody RouteUpdate body) {
        return ApiResponse.success(routeService.setRoute(pool, scene, body.dispatchMode()));
    }

    @GetMapping("/admin/judgers/reconciliation")
    public ResponseEntity<?> reconcile(
            @RequestParam(required = false) String pool,
            @RequestParam(required = false) Long minRevision,
            @RequestParam(defaultValue = "json") String format) {
        var report = reconciliationService.reconcile(pool, minRevision);
        if ("csv".equalsIgnoreCase(format)) {
            String csv = reconciliationService.toCsv(report);
            return ResponseEntity.ok()
                    .header(
                            "Content-Disposition",
                            "attachment; filename=\"judge-reconciliation.csv\"")
                    .contentType(
                            org.springframework.http.MediaType.parseMediaType(
                                    "text/csv;charset=UTF-8"))
                    .body(csv);
        }
        return ResponseEntity.ok(ApiResponse.success(report));
    }

    private static ResponseEntity<ApiResponse<JudgeNodes.Enrollment>> secret(
            JudgeNodes.Enrollment enrollment) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(enrollment));
    }

    private void requireLegacyEnrollment() {
        if (!legacyAdminEnrollmentEnabled) {
            throw new BusinessException(ResultCode.FORBIDDEN, "评测机只能通过 API 地址接入");
        }
    }
}
