package com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatLeagueDto {
    private Integer id;    // null 가능 (일부 대회는 id 없음)
    private String name;   // 한글 우선 (leagues.csv league_name_ko), 없으면 API 영문
    private String logo;   // leagues.csv 커스텀 URL 우선, 없으면 API URL CDN 치환. id가 null이면 null
    private int season;
}
