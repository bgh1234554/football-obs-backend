package com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatCardsDto {
    private Integer yellow;     // null 가능
    private Integer yellowred;  // null 가능 (경고 누적 퇴장)
    private Integer red;        // null 가능
}
