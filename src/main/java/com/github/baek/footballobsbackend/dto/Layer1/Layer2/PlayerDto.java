package com.github.baek.footballobsbackend.dto.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerDto {
    private long playerId;
    private String name;    // 한글 우선, 없으면 영문
    private int number;
    private String pos;     // "G" | "D" | "M" | "F"
    private String grid;    // null for substitutes (e.g. "2:3")
}
