package com.kece.database.mapper;

import com.kece.database.entity.DbTestCase;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 数据库测试点 Mapper。 */
@Mapper
public interface DbTestCaseMapper {

    int insert(DbTestCase testCase);

    int deleteByQuestionId(Long questionId);

    List<DbTestCase> findByQuestionId(Long questionId);

    List<DbTestCase> findForManifest(Long questionId);

    /** 该题目的样例测试点 id（数据本就随题面公开）。 */
    List<Long> findSampleCaseIds(Long questionId);
}
