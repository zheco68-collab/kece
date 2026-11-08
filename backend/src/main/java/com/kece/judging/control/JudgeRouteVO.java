package com.kece.judging.control;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeRouteVO {
    private String pool;
    private String scene;
    private String dispatchMode;
    private long revision;
}
