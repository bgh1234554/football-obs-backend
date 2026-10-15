package com.github.baek.footballobsbackend.web;

import com.github.baek.footballobsbackend.dto.fixtures.TeamFixturesResponseDto;
import com.github.baek.footballobsbackend.error.ErrorResult;
import com.github.baek.footballobsbackend.service.TeamFixturesService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fixtures")
public class TeamFixturesController {
    private final TeamFixturesService service;

    @GetMapping("/last")
    public ResponseEntity<TeamFixturesResponseDto> getLast(@RequestParam("teamId") long teamId) {
        return getFixtures(teamId, false);
    }

    @GetMapping("/next")
    public ResponseEntity<TeamFixturesResponseDto> getNext(@RequestParam("teamId") long teamId) {
        return getFixtures(teamId, true);
    }

    private ResponseEntity<TeamFixturesResponseDto> getFixtures(long teamId, boolean upcoming) {
        if (teamId <= 0) throw new IllegalArgumentException("teamId는 양의 정수여야 합니다.");
        return ResponseEntity.ok()
                .body(service.getTeamFixtures(teamId, upcoming));
    }

    // 새 조회 API의 누락/형식 오류만 400으로 처리한다.
    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResult> invalidTeamId(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResult("INVALID_TEAM_ID",
                "teamId는 필수이며 양의 정수여야 합니다.", 400, request.getRequestURI(), Instant.now()));
    }
}
