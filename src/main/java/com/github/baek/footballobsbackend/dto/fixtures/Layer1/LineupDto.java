package com.github.baek.footballobsbackend.dto.fixtures.Layer1;

import com.github.baek.footballobsbackend.dto.fixtures.Layer1.Layer2.CoachDto;
import com.github.baek.footballobsbackend.dto.fixtures.Layer1.Layer2.PlayerDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineupDto {
    private String formation;
    private List<PlayerDto> startXi;
    private List<PlayerDto> substitutes;
    private CoachDto coach;
}
