package com.kece.announcement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kece.announcement.controller.AdminAnnouncementController;
import com.kece.announcement.controller.AnnouncementController;
import com.kece.announcement.dto.AnnouncementBoardItemVO;
import com.kece.announcement.dto.AnnouncementDetailVO;
import com.kece.announcement.dto.AnnouncementRequest;
import com.kece.announcement.service.AnnouncementService;
import com.kece.common.response.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnnouncementControllerTest {

    private AnnouncementService service;
    private MockMvc publicMockMvc;
    private MockMvc adminMockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = mock(AnnouncementService.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        AnnouncementController publicController = new AnnouncementController(service);
        AdminAnnouncementController adminController = new AdminAnnouncementController(service);

        publicMockMvc = MockMvcBuilders.standaloneSetup(publicController).build();
        adminMockMvc = MockMvcBuilders.standaloneSetup(adminController).build();
    }

    @Test
    @DisplayName("前台看板 GET /announcements 返回分页列表")
    void listBoard_publicEndpoint() throws Exception {
        AnnouncementBoardItemVO item = AnnouncementBoardItemVO.builder()
                .id(1L)
                .title("机考公告")
                .summary("摘要说明")
                .authorName("admin")
                .isPinned(1)
                .priority(10)
                .viewCount(20)
                .createdAt(LocalDateTime.now())
                .build();
        PageResult<AnnouncementBoardItemVO> pageResult = new PageResult<>(List.of(item), 1L, 1, 10);
        when(service.listBoard(1, 10)).thenReturn(pageResult);

        publicMockMvc.perform(get("/announcements?page=1&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].title").value("机考公告"))
                .andExpect(jsonPath("$.data.list[0].authorName").value("admin"));
    }

    @Test
    @DisplayName("前台公告详情 GET /announcements/{id} 返回正文")
    void getDetail_publicEndpoint() throws Exception {
        AnnouncementDetailVO detail = AnnouncementDetailVO.builder()
                .id(1L)
                .title("机考通知")
                .content("# 考试说明正文")
                .authorName("admin")
                .status("PUBLISHED")
                .viewCount(35)
                .build();
        when(service.getDetail(1L)).thenReturn(detail);

        publicMockMvc.perform(get("/announcements/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").value("# 考试说明正文"));
    }

    @Test
    @DisplayName("管理端发布公告 POST /admin/announcements")
    void create_adminEndpoint() throws Exception {
        AnnouncementRequest req = AnnouncementRequest.builder()
                .title("新发布的公告")
                .content("公告正文内容")
                .build();

        AnnouncementDetailVO created = AnnouncementDetailVO.builder()
                .id(2L)
                .title("新发布的公告")
                .content("公告正文内容")
                .authorName("admin")
                .build();
        when(service.create(any(AnnouncementRequest.class))).thenReturn(created);

        adminMockMvc.perform(post("/admin/announcements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(2L));
    }

    @Test
    @DisplayName("管理端置顶切换 PUT /admin/announcements/{id}/pinned")
    void updatePinned_adminEndpoint() throws Exception {
        AnnouncementDetailVO vo = AnnouncementDetailVO.builder()
                .id(3L)
                .title("置顶测试")
                .isPinned(1)
                .build();
        when(service.updatePinned(eq(3L), any())).thenReturn(vo);

        adminMockMvc.perform(put("/admin/announcements/3/pinned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.isPinned").value(1));
    }

    @Test
    @DisplayName("管理端删除公告 DELETE /admin/announcements/{id}")
    void delete_adminEndpoint() throws Exception {
        adminMockMvc.perform(delete("/admin/announcements/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(service).delete(4L);
    }
}
