package com.kece.judging.artifact;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ApiResponse;
import com.kece.common.response.ResultCode;
import com.kece.common.security.RequireRole;
import com.kece.common.security.Role;
import com.kece.judging.JudgeDispatch.Kind;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class JudgeManifestAdminController {

    private final JudgeManifestMapper manifests;
    private final JudgeManifestService service;
    private final TransactionTemplate transaction;

    @PostMapping("/admin/judging/manifests/backfill")
    public ApiResponse<BackfillResult> backfill(
            @RequestParam Kind type,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "5") int limit) {
        if (afterId < 0 || limit < 1 || limit > 20) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的回填游标或批量大小");
        }
        var ids =
                type == Kind.ALGO
                        ? manifests.missingCode(afterId, limit)
                        : manifests.missingSql(afterId, limit);
        var failures = new ArrayList<Failure>();
        int published = 0;
        for (long id : ids) {
            try {
                transaction.executeWithoutResult(status -> service.publish(type, id));
                published++;
            } catch (BusinessException failure) {
                failures.add(new Failure(id, failure.getMessage()));
            }
        }
        long next = ids.isEmpty() ? afterId : ids.get(ids.size() - 1);
        return ApiResponse.success(
                new BackfillResult(published, next, ids.size() == limit, failures));
    }

    public record BackfillResult(
            int published, long nextAfterId, boolean more, List<Failure> failures) {}

    public record Failure(long questionId, String reason) {}
}
