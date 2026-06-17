package com.github.baek.footballobsbackend.dto.fixtures.Layer1.Layer2;

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
    private String name;         // 한글 우선, 없으면 영문
    private String nameKoLong;   // 한글 풀네임. players.csv에 있을 때만 제공
    private String origName;     // API 원문 그대로(영문, 미가공) — 프런트의 id 불일치 자동 매칭용
    private String photoUrl;     // media CDN URL
    private int number;
    private String pos;          // "G" | "D" | "M" | "F"
    private String grid;         // null for substitutes (e.g. "2:3")
}
