package com.kece.user.service;

import com.kece.common.exception.BusinessException;
import com.kece.common.response.ResultCode;
import com.kece.common.security.AuthContextHolder;
import com.kece.exam.dto.ExamRankVO;
import com.kece.exam.entity.Exam;
import com.kece.exam.entity.ExamSubmission;
import com.kece.exam.mapper.ExamMapper;
import com.kece.exam.mapper.ExamSubmissionMapper;
import com.kece.exam.service.ExamRankService;
import com.kece.user.dto.ChangePasswordRequest;
import com.kece.user.dto.HeatmapDayVO;
import com.kece.user.dto.HeatmapVO;
import com.kece.user.dto.UpdateProfileRequest;
import com.kece.user.dto.UserExamRecordVO;
import com.kece.user.dto.UserProfileVO;
import com.kece.user.entity.User;
import com.kece.user.entity.UserDailyAc;
import com.kece.user.mapper.UserMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 个人中心业务逻辑：名片、密码安全、近一年 AC 热力图、战绩档案与最近动态。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final Set<String> ALLOWED_AVATAR_EXTS =
            Set.of(".png", ".jpg", ".jpeg", ".webp", ".gif");
    private static final long MAX_AVATAR_BYTES = 5 * 1024 * 1024; // 5MB

    private final UserMapper userMapper;
    private final ExamMapper examMapper;
    private final ExamSubmissionMapper examSubmissionMapper;
    private final ExamRankService examRankService;
    private final PasswordEncoder passwordEncoder;
    private final UserDailyAcService userDailyAcService;

    /** 获取用户资料（传 null 默认获取本人）。 */
    public UserProfileVO getProfile(Long targetUserId) {
        Long userId = targetUserId != null ? targetUserId : requireUserId();
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return toProfileVO(user);
    }

    /** 更新个性签名与 GitHub 社交链接。 */
    @Transactional
    public UserProfileVO updateProfile(UpdateProfileRequest request) {
        Long userId = requireUserId();
        userMapper.updateProfile(userId, request.getBio(), request.getGithubUrl());
        User user = userMapper.findById(userId);
        return toProfileVO(user);
    }

    /** 上传本地头像并生成访问 URL。 */
    @Transactional
    public String uploadAvatar(MultipartFile file) {
        Long userId = requireUserId();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择要上传的头像图片");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "头像图片大小不能超过 5MB");
        }
        String originalFilename = file.getOriginalFilename();
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }
        if (!ALLOWED_AVATAR_EXTS.contains(ext)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅支持 PNG、JPG、JPEG、WEBP、GIF 格式图片");
        }

        try {
            User currentUser = userMapper.findById(userId);
            String oldAvatarUrl = currentUser != null ? currentUser.getAvatarUrl() : null;

            Path uploadDir = Paths.get("uploads", "avatars").toAbsolutePath();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
            String filename = "avatar_" + userId + "_" + System.currentTimeMillis() + ext;
            Path targetFile = uploadDir.resolve(filename);
            Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);

            String avatarUrl = "/api/v1/uploads/avatars/" + filename;
            userMapper.updateAvatar(userId, avatarUrl);

            if (oldAvatarUrl != null
                    && oldAvatarUrl.startsWith("/api/v1/uploads/avatars/avatar_")) {
                String oldFilename = oldAvatarUrl.substring("/api/v1/uploads/avatars/".length());
                if (!oldFilename.contains("/")
                        && !oldFilename.contains("\\")
                        && !oldFilename.equals(filename)) {
                    try {
                        Files.deleteIfExists(uploadDir.resolve(oldFilename));
                    } catch (IOException ignored) {
                    }
                }
            }
            return avatarUrl;
        } catch (IOException e) {
            log.error("保存头像文件失败", e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "保存头像文件失败");
        }
    }

    /** 安全修改密码（强校验原密码）。 */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Long userId = requireUserId();
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "原密码输入错误");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码不能与原密码相同");
        }
        String newHash = passwordEncoder.encode(request.getNewPassword());
        userMapper.updatePassword(userId, newHash);
    }

    /** 近 365 天每日 AC 活跃打卡热力图统计（查询预聚合表 user_daily_ac，毫秒级响应）。 */
    public HeatmapVO getHeatmap(Long targetUserId) {
        Long userId = targetUserId != null ? targetUserId : requireUserId();
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(364);

        Map<String, Integer> acMap = new HashMap<>();
        List<UserDailyAc> dailyList = userDailyAcService.findByUserAndStartDate(userId, startDate);
        if (dailyList != null) {
            for (UserDailyAc r : dailyList) {
                if (r.getAcDate() != null) {
                    acMap.put(
                            r.getAcDate().toString(),
                            r.getTotalCount() != null ? r.getTotalCount() : 0);
                }
            }
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<HeatmapDayVO> days = new ArrayList<>(365);
        int totalAc = 0;
        int maxStreak = 0;
        int currentStreak = 0;

        for (int i = 0; i < 365; i++) {
            LocalDate d = startDate.plusDays(i);
            String dateStr = d.format(fmt);
            int count = acMap.getOrDefault(dateStr, 0);
            days.add(HeatmapDayVO.builder().date(dateStr).count(count).build());
            totalAc += count;

            if (count > 0) {
                currentStreak++;
                if (currentStreak > maxStreak) {
                    maxStreak = currentStreak;
                }
            } else {
                currentStreak = 0;
            }
        }

        // 计算截至当天的实际当前连续打卡（如果今天未打卡，检查昨天是否算连续）
        int activeCurrentStreak = 0;
        LocalDate cur = today;
        while (!cur.isBefore(startDate)) {
            int c = acMap.getOrDefault(cur.format(fmt), 0);
            if (c > 0) {
                activeCurrentStreak++;
                cur = cur.minusDays(1);
            } else if (cur.equals(today)) {
                // 今天还没做题，看昨天是否连续
                cur = cur.minusDays(1);
            } else {
                break;
            }
        }

        return HeatmapVO.builder()
                .days(days)
                .totalAc(totalAc)
                .maxStreak(maxStreak)
                .currentStreak(activeCurrentStreak)
                .build();
    }

    /** 考试战绩档案列表（按交卷时间倒序）。 */
    public List<UserExamRecordVO> getExams(Long targetUserId) {
        Long userId = targetUserId != null ? targetUserId : requireUserId();
        List<Long> examIds = examSubmissionMapper.findSubmittedExamIdsByUserId(userId);
        if (examIds == null || examIds.isEmpty()) {
            return List.of();
        }

        List<UserExamRecordVO> records = new ArrayList<>();
        for (Long examId : examIds) {
            Exam exam = examMapper.findById(examId);
            if (exam == null) {
                continue;
            }
            List<ExamSubmission> userSubmissions =
                    examSubmissionMapper.findByExamAndUser(examId, userId);
            LocalDateTime submittedAt = null;
            if (userSubmissions != null && !userSubmissions.isEmpty()) {
                submittedAt = userSubmissions.get(0).getSubmittedAt();
                if (submittedAt == null) {
                    submittedAt = userSubmissions.get(0).getCreatedAt();
                }
            }

            Integer userScore = null;
            Integer userRank = null;
            Integer totalParticipants = 0;

            try {
                List<ExamRankVO> rankList = examRankService.ranking(examId);
                totalParticipants = rankList.size();
                for (int idx = 0; idx < rankList.size(); idx++) {
                    ExamRankVO r = rankList.get(idx);
                    if (r.getUserId().equals(userId)) {
                        userScore =
                                "ACM".equalsIgnoreCase(exam.getMode())
                                        ? r.getSolved()
                                        : r.getTotalScore();
                        userRank = idx + 1;
                        break;
                    }
                }
            } catch (Exception ignored) {
                // 排名服务容错
            }

            records.add(
                    UserExamRecordVO.builder()
                            .examId(exam.getId())
                            .title(exam.getTitle())
                            .description(exam.getDescription())
                            .mode(exam.getMode())
                            .durationMinutes(exam.getDurationMinutes())
                            .score(userScore)
                            .rank(userRank)
                            .totalParticipants(totalParticipants)
                            .submittedAt(submittedAt)
                            .build());
        }

        records.sort(
                Comparator.comparing(
                        UserExamRecordVO::getSubmittedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return records;
    }

    private Long requireUserId() {
        Long userId = AuthContextHolder.currentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    private UserProfileVO toProfileVO(User user) {
        List<String> roles = List.of();
        if (user.getRoles() != null && !user.getRoles().isBlank()) {
            roles =
                    Arrays.stream(user.getRoles().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
        }
        return UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .bio(user.getBio())
                .githubUrl(user.getGithubUrl())
                .roles(roles)
                .createdAt(user.getCreatedAt())
                .build();
    }
}
