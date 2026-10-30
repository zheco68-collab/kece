package com.kece.announcement.service;

import com.kece.announcement.dto.AnnouncementBoardItemVO;
import com.kece.announcement.dto.AnnouncementDetailVO;
import com.kece.announcement.dto.AnnouncementRequest;
import com.kece.common.response.PageResult;

/**
 * 公告业务服务接口。
 */
public interface AnnouncementService {

    /**
     * 前台分页查询看板列表（走 Redis 缓存 notice:board:page:{page}:{size}）。
     */
    PageResult<AnnouncementBoardItemVO> listBoard(int page, int size);

    /**
     * 获取公开公告详情（原子自增 view_count，限 PUBLISHED 状态）。
     */
    AnnouncementDetailVO getDetail(Long id);

    /**
     * 管理后台获取公告详情（不限状态）。
     */
    AnnouncementDetailVO getAdminDetail(Long id);

    /**
     * 管理后台分页查询公告列表（无分类/无搜索，支持按 status 过滤）。
     */
    PageResult<AnnouncementDetailVO> listAdmin(String status, int page, int size);

    /**
     * 发布新公告。
     */
    AnnouncementDetailVO create(AnnouncementRequest request);

    /**
     * 编辑公告。
     */
    AnnouncementDetailVO update(Long id, AnnouncementRequest request);

    /**
     * 切换或更新置顶状态。
     */
    AnnouncementDetailVO updatePinned(Long id, Integer isPinned);

    /**
     * 修改公告上下架状态。
     */
    AnnouncementDetailVO updateStatus(Long id, String status);

    /**
     * 删除公告。
     */
    void delete(Long id);
}
