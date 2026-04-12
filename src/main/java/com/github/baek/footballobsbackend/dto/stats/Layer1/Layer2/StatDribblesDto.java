package com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatDribblesDto {
    private Integer attempts;
    private Integer success;  // null 가능
    private Integer past;     // null 가능 (상대가 나를 드리블한 횟수)
}
