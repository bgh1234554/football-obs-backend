package com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatPassesDto {
    private Integer total;
    private Integer key;       // null 가능
    private Integer accuracy;  // null 가능 (패스 성공률 %)
}
