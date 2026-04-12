package com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatPenaltyDto {
    private Integer won;       // null 가능
    private Integer committed; // null 가능 (API 원문은 "commited" 오타)
    private Integer scored;
    private Integer missed;
    private Integer saved;     // null 가능 (골키퍼 외에는 보통 null)
}
