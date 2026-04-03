package com.github.baek.footballobsbackend.dto.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoachDto {
    private long coachId;
    private String name;    // 한글 우선, 없으면 영문
}
