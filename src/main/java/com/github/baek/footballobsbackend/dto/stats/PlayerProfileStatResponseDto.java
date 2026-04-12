package com.github.baek.footballobsbackend.dto.stats;

import com.github.baek.footballobsbackend.dto.stats.Layer1.PlayerInfoDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.PlayerSeasonStatDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerProfileStatResponseDto {
    private PlayerInfoDto player; //선수 프로필
    private Map<String, List<PlayerSeasonStatDto>> statistics; //선수의 시즌 별 대회 스탯
}
