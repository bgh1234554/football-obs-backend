package com.github.baek.footballobsbackend.web;

import com.github.baek.footballobsbackend.dto.stats.PlayerProfileStatResponseDto;
import com.github.baek.footballobsbackend.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 선수 데이터 관련 API 엔드포인트를 제공하는 컨트롤러.
 *
 * [엔드포인트 목록]
 * GET /api/playerStats/{playerId} — 선수 대회 별 스탯
 *
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class PlayerController {

    private final PlayerService playerService;

    /**
     * 경기 포메이션에서 선수 이름 클릭시 나오는 메뉴 중 "선수 스탯" 조회 시,
     * 해당 경기 이전까지의 선수의 대회별 스탯을 시즌별로 반환. (친선경기는 제일 나중에)
     *
     * 7월 1일 이전이면 전 시즌 + 현 시즌 두 key, 이후면 현 시즌 한 key만 포함.
     * player는 최상위에 한 번만 내려주고,
     * statistics는 { "2025": [PlayerSeasonStatDto...], "2026": [PlayerSeasonStatDto...] } 형태로 반환.
     *
     * @param playerId API Football 선수 ID (path variable)
     * @return 200 OK + PlayerProfileStatResponseDto | 404 Not Found (선수 또는 스탯 없음)
     */
    @GetMapping("/playerStats/{playerId}")
    public ResponseEntity<PlayerProfileStatResponseDto> getStats(@PathVariable("playerId") long playerId) {
        PlayerProfileStatResponseDto dto = playerService.getPlayerStats(playerId);
        return ResponseEntity.ok(dto);
    }
}
