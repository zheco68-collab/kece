package com.kece.question.mapper;

import com.kece.question.entity.TestCase;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 测试用例 Mapper。 */
@Mapper
public interface TestCaseMapper {

    int insert(TestCase testCase);

    int batchInsert(@org.apache.ibatis.annotations.Param("list") List<TestCase> list);

    int update(TestCase testCase);

    int deleteById(Long id);

    int deleteByQuestionId(Long questionId);

    TestCase findById(Long id);

    List<TestCase> findByQuestionId(Long questionId);

    List<TestCase> findSamplesByQuestionId(Long questionId);

    List<TestCase> findUnmigrated();

    com.kece.question.dto.TestCaseSummaryVO getSummaryByQuestionId(Long questionId);
}
