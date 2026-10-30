package com.kece.announcement.mapper;

import com.kece.announcement.dto.AnnouncementBoardItemVO;
import com.kece.announcement.entity.Announcement;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 公告数据持久层访问接口。
 */
@Mapper
public interface AnnouncementMapper {

    /**
     * 前台看板公开列表（仅 PUBLISHED 状态，按置顶、优先级、ID 降序）。
     */
    List<AnnouncementBoardItemVO> findBoardPage(@Param("offset") int offset, @Param("size") int size);

    /**
     * 前台看板公开公告总数。
     */
    long countBoard();

    /**
     * 根据主键查询公告详情。
     */
    Announcement findById(@Param("id") Long id);

    /**
     * 原子自增浏览次数。
     */
    int incrementViewCount(@Param("id") Long id);

    /**
     * 后台管理分页列表（支持可选状态过滤，无分类与模糊匹配）。
     */
    List<Announcement> findAdminPage(
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("size") int size);

    /**
     * 后台管理公告总数统计。
     */
    long countAdmin(@Param("status") String status);

    /**
     * 插入新公告。
     */
    int insert(Announcement announcement);

    /**
     * 全量更新公告（标题、摘要、正文、发布者、置顶、权重、状态）。
     */
    int update(Announcement announcement);

    /**
     * 修改置顶状态。
     */
    int updatePinned(@Param("id") Long id, @Param("isPinned") Integer isPinned);

    /**
     * 修改公告状态（PUBLISHED / DRAFT / ARCHIVED）。
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 根据主键删除公告。
     */
    int deleteById(@Param("id") Long id);
}
