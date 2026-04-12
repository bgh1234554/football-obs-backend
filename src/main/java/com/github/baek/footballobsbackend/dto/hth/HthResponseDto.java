package com.github.baek.footballobsbackend.dto.hth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 두 팀 간 상대 전적 응답 DTO.
 * matches 리스트는 API Football 응답 순서 그대로 반환 (최신 경기가 앞쪽).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HthResponseDto {
    List<HthMatchDto> matches;
}
