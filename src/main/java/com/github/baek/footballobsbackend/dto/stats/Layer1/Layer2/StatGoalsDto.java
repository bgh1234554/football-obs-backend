package com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatGoalsDto {
    private Integer total;
    private Integer conceded;
    private Integer assists;
    private Integer saves;    // null 가능 (골키퍼 외에는 보통 null)
}
