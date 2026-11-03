package com.kece.objective.service;

import com.kece.objective.dto.AnswerResultVO;
import com.kece.objective.entity.ObjectiveQuestion;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 客观题判分器（纯逻辑，无 IO，便于单元测试）。
 *
 * <ul>
 *   <li>单选：精确匹配（忽略首尾空格与大小写）。
 *   <li>多选：完全匹配得满分；漏选（所选均正确但未选全）得一半分（向上取整）；含错误选项不得分。
 *   <li>填空：按 matchType 精确 / 忽略大小写 / 正则匹配。
 * </ul>
 */
@Component
public class ObjectiveGrader {

    public AnswerResultVO grade(ObjectiveQuestion question, String submitted) {
        String type = question.getType();
        int fullScore = question.getScore() == null ? 5 : question.getScore();
        String correctAnswer = question.getAnswer() == null ? "" : question.getAnswer();

        boolean correct;
        int score;
        switch (type) {
            case "SINGLE" -> {
                correct = normalizeSingle(correctAnswer).equals(normalizeSingle(submitted));
                score = correct ? fullScore : 0;
            }
            case "JUDGE" -> {
                String normCorrect = normalizeJudge(correctAnswer);
                String normSubmitted = normalizeJudge(submitted);
                correct = !normCorrect.isEmpty() && normCorrect.equals(normSubmitted);
                score = correct ? fullScore : 0;
            }
            case "MULTIPLE" -> {
                Set<String> correctSet = splitOptions(correctAnswer);
                Set<String> submittedSet = splitOptions(submitted);
                if (submittedSet.equals(correctSet)) {
                    correct = true;
                    score = fullScore;
                } else if (!submittedSet.isEmpty() && correctSet.containsAll(submittedSet)) {
                    // 漏选：所选全对但未选全，得一半分（向上取整）
                    correct = false;
                    score = (fullScore + 1) / 2;
                } else {
                    correct = false;
                    score = 0;
                }
            }
            default -> { // FILL
                correct = matchFill(correctAnswer, submitted, question.getMatchType());
                score = correct ? fullScore : 0;
            }
        }

        return AnswerResultVO.builder()
                .correct(correct)
                .score(score)
                .fullScore(fullScore)
                .correctAnswer(correctAnswer)
                .build();
    }

    private String normalizeJudge(String value) {
        if (value == null) return "";
        String s = value.trim();
        if (s.equals("对")
                || s.equals("正确")
                || s.equalsIgnoreCase("TRUE")
                || s.equalsIgnoreCase("T")
                || s.equalsIgnoreCase("A")
                || s.equals("1")
                || s.equalsIgnoreCase("YES")
                || s.equalsIgnoreCase("Y")
                || s.equals("✓")
                || s.equals("√")) {
            return "对";
        }
        if (s.equals("错")
                || s.equals("错误")
                || s.equalsIgnoreCase("FALSE")
                || s.equalsIgnoreCase("F")
                || s.equalsIgnoreCase("B")
                || s.equals("0")
                || s.equalsIgnoreCase("NO")
                || s.equalsIgnoreCase("N")
                || s.equals("✕")
                || s.equals("×")
                || s.equalsIgnoreCase("X")) {
            return "错";
        }
        return s;
    }

    private String normalizeSingle(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private Set<String> splitOptions(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private boolean matchFill(String correct, String submitted, String matchType) {
        String expected = correct == null ? "" : correct.trim();
        String actual = submitted == null ? "" : submitted.trim();
        String safeType =
                (matchType == null || matchType.isBlank())
                        ? "EXACT"
                        : matchType.trim().toUpperCase();
        return switch (safeType) {
            case "IGNORE_CASE" -> expected.equalsIgnoreCase(actual);
            case "REGEX" -> regexMatches(expected, actual);
            default -> expected.equals(actual);
        };
    }

    private boolean regexMatches(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
