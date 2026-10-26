package com.kece.user.mapper;

import com.kece.user.entity.User;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 用户表 Mapper。 */
@Mapper
public interface UserMapper {

    User findById(Long id);

    List<User> findByIds(@Param("ids") Collection<Long> ids);

    User findByUsername(String username);

    User findByEmail(String email);

    int insert(User user);

    /** 后台用户分页：keyword 模糊匹配用户名或邮箱，role 按角色匹配，status 精确匹配，条件均可为空。 */
    List<User> findPage(
            @Param("keyword") String keyword,
            @Param("role") String role,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("size") int size);

    long count(
            @Param("keyword") String keyword,
            @Param("role") String role,
            @Param("status") Integer status);

    int updateRoles(@Param("id") Long id, @Param("roles") String roles);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    int updateProfile(
            @Param("id") Long id, @Param("bio") String bio, @Param("githubUrl") String githubUrl);

    int updateAvatar(@Param("id") Long id, @Param("avatarUrl") String avatarUrl);
}
