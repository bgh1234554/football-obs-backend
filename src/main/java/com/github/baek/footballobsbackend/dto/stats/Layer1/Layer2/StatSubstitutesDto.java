package com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatSubstitutesDto {
    private Integer in;
    private Integer out;   // null 가능
    private Integer bench; // null 가능
}
