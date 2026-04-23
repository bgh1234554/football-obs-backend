package com.github.baek.footballobsbackend.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.baek.footballobsbackend.dto.fixtures.FixtureResponseDto;
import com.github.baek.footballobsbackend.dto.hth.HthResponseDto;
import com.github.baek.footballobsbackend.service.FixtureService;
import com.github.baek.footballobsbackend.service.HeadtoheadService;

import lombok.RequiredArgsConstructor;

/**
 * 경기(fixture) 관련 API 엔드포인트를 제공하는 컨트롤러.
 *
 * [엔드포인트 목록]
 * GET /api/fixtures/{fixtureId} — 경기 전체 데이터 (이벤트, 라인업, 스탯, 부상 포함)
 *
 * [설계 원칙]
 * 컨트롤러는 요청/응답 처리만 담당하고, 비즈니스 로직은 모두 FixtureService에 위임.
 * 경기 데이터가 없으면 404 Not Found를 반환.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class FixtureController {

    private final FixtureService fixtureService;
    private final HeadtoheadService headtoheadService;

    /**
     * 경기 ID 하나로 프론트에 필요한 모든 데이터를 조회.
     * 내부적으로 API Football /fixtures, /injuries 두 엔드포인트를 호출해 조립함.
     *
     * @param fixtureId API Football 경기 ID (path variable)
     * @return 200 OK + FixtureResponseDto | 404 Not Found (경기 없음)
     */
    @GetMapping("/fixtures/{fixtureId}")
    public ResponseEntity<FixtureResponseDto> getFixture(@PathVariable("fixtureId") long fixtureId) {
        // 1. FixtureService에 조립 위임 — null이면 해당 ID의 경기가 없는 것
        FixtureResponseDto dto = fixtureService.getFixture(fixtureId);
        return ResponseEntity.ok(dto);
    }

    /**
     * teamId 2개를 받아, 해당 두 팀에 대한 역대 상대 전적을 날짜, 대회, 심판 정보와 함께 조회.
     *
     * @param teamA 홈팀 (query parameter)
     * @param teamB 원정팀 (query parameter)
     * (다만, 순서가 바뀌어도 리턴 값은 동일함)
     * 
     * 20260423 @PathVariable -> @RequestParam으로 변경
     * @PathVariable은 **조회 대상의 정체성 자체가 URL에 들어갈 때** 적합
     * @RequestParam은 **조회 조건, 필터, 옵션**일 때 적합
     * @return 200 OK + HthResponseDto | 404 Not Found (데이터 없음)
     */
    @GetMapping("/hth")
    public ResponseEntity<HthResponseDto> getHth(@RequestParam("teamA") long teamA, @RequestParam("teamB") long teamB) {
        // 1. FixtureService에 조립 위임 — null이면 해당 ID의 경기가 없는 것
        HthResponseDto dto = headtoheadService.getHeadtoHead(teamA,teamB);
        return ResponseEntity.ok(dto);
    }
}
