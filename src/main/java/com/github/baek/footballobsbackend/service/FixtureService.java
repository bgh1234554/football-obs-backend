package com.github.baek.footballobsbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.baek.footballobsbackend.client.ApiFootballClient;
import com.github.baek.footballobsbackend.dto.*;
import com.github.baek.footballobsbackend.dto.Layer1.*;
import com.github.baek.footballobsbackend.dto.Layer1.Layer2.CoachDto;
import com.github.baek.footballobsbackend.dto.Layer1.Layer2.PlayerDto;
import com.github.baek.footballobsbackend.error.ApiException;
import com.github.baek.footballobsbackend.error.ErrorCode;
import com.github.baek.footballobsbackend.util.CsvLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.baek.footballobsbackend.error.ErrorCode.FIXTURE_NOT_FOUND;

/**
 * API Football에서 받아온 JsonNode를 프론트가 쓰기 좋은 DTO로 조립하는 서비스.
 *
 * [전체 흐름]
 * 1. ApiFootballClient → JsonNode (BunnyCDN 경유 API 호출)
 * 2. CsvLoader → 한글 이름/커스텀 로고 조회
 * 3. JsonNode + CSV 데이터 합쳐서 FixtureResponseDto 조립
 *
 * [한글 이름 처리 원칙]
 * CSV에 한글 이름이 있으면 사용, 없으면 API 영문 이름 그대로 사용.
 * 없는 경우 Render 로그에 [KO_NAME_NEEDED] / [LOGO_NEEDED] 로 기록 → CSV 업데이트 후 재배포.
 *
 * [미디어 URL 처리 원칙]
 * API Football 미디어 URL(https://media.api-sports.io/...)을
 * 자체 Media CDN URL로 치환해서 내려줌 (CORS + 캐시 이점).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixtureService {

    private final ApiFootballClient apiClient;
    private final CsvLoader csvLoader;
    private final Set<String> loggedShortNameDiffs = ConcurrentHashMap.newKeySet();

    @Value("${api.media-cdn-url}")
    private String mediaCdnUrl;

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    /**
     * 경기 ID 하나로 프론트에 필요한 모든 데이터를 한 번에 조립해서 반환.
     * 내부적으로 API Football /fixtures + /injuries 두 엔드포인트를 호출함.
     *
     * @param fixtureId API Football 경기 ID
     * @return 조립된 FixtureResponseDto. 경기 데이터 없으면 null.
     */
    public FixtureResponseDto getFixture(long fixtureId) {
        // 1. BunnyCDN 경유 API Football /fixtures 호출 → response[0] JsonNode
        JsonNode data = apiClient.getFixture(fixtureId);
        if (data == null) return null;

        // 2. 홈/원정 팀 ID 추출 (이후 여러 메서드에서 side 구분에 사용)
        long homeTeamId = data.path("teams").path("home").path("id").asLong();
        long awayTeamId = data.path("teams").path("away").path("id").asLong();

        // 3. 부상/결장 전체 목록 먼저 조립 후 홈/원정으로 분리 (InjuryDto.teamId 기준)
        List<InjuryDto> allInjuries = buildInjuries(fixtureId);
        List<InjuryDto> homeInjuries = allInjuries.stream()
                .filter(i -> i.getTeamId() == homeTeamId).toList();
        List<InjuryDto> awayInjuries = allInjuries.stream()
                .filter(i -> i.getTeamId() != homeTeamId).toList();

        // 4. 각 섹션별로 DTO 조립 후 한 번에 반환
        FixtureResponseDto result = FixtureResponseDto.builder()
                .matchInfo(buildMatchInfo(data, homeTeamId, awayTeamId))       // 경기 기본 정보
                .events(buildEvents(data.path("events"), homeTeamId))           // 골/카드/교체 이벤트
                .teamStats(buildTeamStats(data.path("statistics"), homeTeamId)) // 팀 스탯
                .homeLineup(buildLineup(data.path("lineups"), homeTeamId))      // 홈 라인업
                .awayLineup(buildLineup(data.path("lineups"), awayTeamId))      // 원정 라인업
                .playerStats(buildPlayerStats(data.path("players"), homeTeamId))          // 선수 개인 스탯
                .homeInjuries(homeInjuries)                                     // 홈팀 부상/결장
                .awayInjuries(awayInjuries)                                     // 원정팀 부상/결장
                .build();

        if(result==null){
            throw new ApiException(FIXTURE_NOT_FOUND);
        }

        return result;
    }

    // ──────────────────────────────────────────────
    // injuries
    // ──────────────────────────────────────────────

    /**
     * 해당 경기의 부상/결장 선수 목록을 조립.
     * API Football /injuries는 양팀 결장 선수를 하나의 배열로 반환함.
     */
    private List<InjuryDto> buildInjuries(long fixtureId) {
        // 1. BunnyCDN 경유 API Football /injuries 호출
        JsonNode response = apiClient.getInjuries(fixtureId);
        if (response == null || !response.isArray()) return List.of();

        List<InjuryDto> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : response) {
            JsonNode player = item.path("player");
            JsonNode team = item.path("team");

            // 2. 선수 ID로 한글 이름 조회, 없으면 영문 이름 사용
            long playerId = player.path("id").asLong();
            String apiName = player.path("name").asText();
            String nameKo = csvLoader.getPlayerNameKo(playerId);
            if (nameKo == null) {
                log.info("[KO_NAME_NEEDED] id={}, name={}", playerId, apiName);
            }

            // 3. 팀 이름 한글화 (팀 ID 기반 조회)
            long teamId = team.path("id").asLong();
            String teamApiName = team.path("name").asText();
            String teamNameKo = csvLoader.getTeamNameKo(teamId);
            String injuryType = player.path("type").asText();
            String injuryReason = player.path("reason").asText();

            // 4. API가 동일 부상 항목을 중복 반환하는 경우가 있어 복합키로 한 번만 반영
            String dedupeKey = teamId + "|" + playerId + "|" + injuryType + "|" + injuryReason;
            if (!seen.add(dedupeKey)) {
                continue;
            }

            // 5. 선수 사진 URL을 Media CDN URL로 치환 후 DTO 조립
            result.add(InjuryDto.builder()
                    .playerId(playerId)
                    .playerName(resolvePlayerDisplayName(playerId, apiName))
                    .playerPhotoUrl(toMediaCdnUrl(player.path("photo").asText()))
                    .type(injuryType)     // ex. "Missing Fixture"
                    .reason(injuryReason) // ex. "Knee Injury"
                    .teamId(teamId)
                    .teamName(teamNameKo != null ? teamNameKo : teamApiName)
                    .teamLogo(toMediaCdnUrl(team.path("logo").asText()))
                    .build());
        }
        return result;
    }

    // ──────────────────────────────────────────────
    // matchInfo
    // ──────────────────────────────────────────────

    /**
     * 경기 기본 정보(경기 상태, 점수, 팀 정보, 심판)를 MatchInfoDto로 조립.
     * 팀 색상은 lineups 섹션에서 추출하고, 로고는 logos.csv 우선 사용.
     */
    private MatchInfoDto buildMatchInfo(JsonNode data, long homeTeamId, long awayTeamId) {
        // 1. 자주 쓰는 최상위 노드 미리 꺼내기
        JsonNode fixture = data.path("fixture");
        JsonNode teams = data.path("teams");
        JsonNode goals = data.path("goals");
        JsonNode score = data.path("score");
        JsonNode status = fixture.path("status");

        // 2. 경기 상태 파싱 (API short status → 내부 status 코드로 변환)
        String shortStatus = status.path("short").asText();
        int elapsed = status.path("elapsed").asInt();
        // extra는 없을 수 있어서 null 허용
        JsonNode extraNode = status.path("extra");
        Integer extra = extraNode.isNull() ? null : extraNode.asInt();

        // 3. 페널티 슛아웃 점수 추출 — score.penalty가 null인 경기(정규/연장 종료)는 null
        //    연장전 점수(score.extratime)는 goals.home/away에 이미 합산되어 있으므로 별도 추출 불필요
        //    API 응답: score.penalty = { home: 4, away: 1 } 또는 { home: null, away: null }
        JsonNode penaltyNode = score.path("penalty");
        Integer homePenaltyScore = penaltyNode.path("home").isNull() ? null : penaltyNode.path("home").asInt();
        Integer awayPenaltyScore = penaltyNode.path("away").isNull() ? null : penaltyNode.path("away").asInt();

        JsonNode homeTeam = teams.path("home");
        JsonNode awayTeam = teams.path("away");

        // 4. 팀 이름 한글화 — teams.csv에 없으면 API 영문 이름 사용 + 로그
        String homeApiName = homeTeam.path("name").asText();
        String homeNameKo = csvLoader.getTeamNameKo(homeTeamId);
        if (homeNameKo == null) {
            log.info("[KO_TEAM_NAME_NEEDED] id={}, name={}", homeTeamId, homeApiName);
        }

        String awayApiName = awayTeam.path("name").asText();
        String awayNameKo = csvLoader.getTeamNameKo(awayTeamId);
        if (awayNameKo == null) {
            log.info("[KO_TEAM_NAME_NEEDED] id={}, name={}", awayTeamId, awayApiName);
        }

        // 5. 팀 색상 추출 — lineups[].team.colors.player 에서 홈/원정 각각 꺼냄
        JsonNode[] colors = extractTeamColors(data.path("lineups"), homeTeamId, awayTeamId);
        JsonNode homeColors = colors[0];
        JsonNode awayColors = colors[1];

        // 6. 심판 이름 파싱 — "Anthony Taylor, England" 형식에서 이름/국적 분리 후 표시용 문자열 조립
        //    한글 이름이 있으면 "앤서니 테일러 (England)", 없으면 "Anthony Taylor (England)"
        String refereeStr = fixture.path("referee").asText(null);
        String refereeName = buildRefereeName(refereeStr);

        // 7. 경기장 이름/도시 한글화
        JsonNode venueNode = fixture.path("venue");
        String[] venueRow = csvLoader.getVenueRow(venueNode.path("name").asText(null));
        String venueName = resolveVenueName(venueNode);
        // city_name_ko(index 4)가 있으면 사용, 없으면 API 영문 도시명 fallback
        String venueCityKo = (venueRow != null && venueRow.length > 4) ? venueRow[4].trim() : "";
        String venueCity = venueCityKo.isEmpty() ? venueNode.path("city").asText(null) : venueCityKo;

        // 8. DTO 조립
        return MatchInfoDto.builder()
                .fixtureId(fixture.path("id").asLong())
                .status(mapStatus(shortStatus, elapsed))
                .elapsed(elapsed)
                .extra(extra)
                .homeTeamId(homeTeamId)
                .homeTeamName(homeNameKo != null ? homeNameKo : homeApiName)
                .homeTeamLogo(resolveLogoUrl(homeTeamId, homeTeam.path("logo").asText()))
                .homeTeamFlagUrl(csvLoader.getFlagUrl(homeTeamId))  // 클럽팀이면 null
                .homeScore(goals.path("home").asInt())              // 정규+연장 득점 합계 (goals 필드)
                .homePenaltyScore(homePenaltyScore)                 // 페널티 슛아웃 점수, 비해당 경기는 null
                .homePrimaryColor(colorOf(homeColors, "primary"))   // 유니폼 바탕색
                .homeNumberColor(colorOf(homeColors, "number"))     // 등번호 색
                .awayTeamId(awayTeamId)
                .awayTeamName(awayNameKo != null ? awayNameKo : awayApiName)
                .awayTeamLogo(resolveLogoUrl(awayTeamId, awayTeam.path("logo").asText()))
                .awayTeamFlagUrl(csvLoader.getFlagUrl(awayTeamId))
                .awayScore(goals.path("away").asInt())              // 정규+연장 득점 합계 (goals 필드)
                .awayPenaltyScore(awayPenaltyScore)                 // 페널티 슛아웃 점수, 비해당 경기는 null
                .awayPrimaryColor(colorOf(awayColors, "primary"))
                .awayNumberColor(colorOf(awayColors, "number"))
                .refereeName(refereeName)
                .venueName(venueName)
                .venueCity(venueCity)
                .build();
    }

    /**
     * lineups 배열에서 홈/원정 팀의 유니폼 색상(player 컬러) JsonNode를 추출.
     * lineups[].team.colors.player 경로에 { primary, number, border } 필드가 있음.
     *
     * @return [homeColors, awayColors] — 라인업이 아직 없는 경기(NS 등)면 null이 들어올 수 있음
     */
    private JsonNode[] extractTeamColors(JsonNode lineups, long homeTeamId, long awayTeamId) {
        JsonNode homeColors = null;
        JsonNode awayColors = null;

        if (lineups.isArray()) {
            for (JsonNode lu : lineups) {
                // 각 lineup entry의 팀 ID로 홈/원정 구분
                long id = lu.path("team").path("id").asLong();
                JsonNode playerColors = lu.path("team").path("colors").path("player");
                if (id == homeTeamId) homeColors = playerColors;
                else if (id == awayTeamId) awayColors = playerColors;
            }
        }
        return new JsonNode[]{homeColors, awayColors};
    }

    /**
     * colors JsonNode에서 특정 색상 값(hex 문자열)을 꺼냄.
     * colors 자체가 null이거나 key가 없으면 null 반환.
     */
    private String colorOf(JsonNode colors, String key) {
        if (colors == null || colors.isMissingNode()) return null;
        JsonNode n = colors.path(key);
        return n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    // ──────────────────────────────────────────────
    // events
    // ──────────────────────────────────────────────

    /**
     * 경기 이벤트(골, 카드, 교체, VAR) 목록을 조립.
     *
     * [이벤트 타입]
     * - "Goal"  : player=득점자, assist=어시스트
     * - "Card"  : player=카드받은 선수, assist=null
     * - "subst" : player=교체아웃 선수, assist=교체인 선수
     * - "Var"   : VAR 판정 (득점 취소 등)
     */
    private List<EventDto> buildEvents(JsonNode eventsNode, long homeTeamId) {
        List<EventDto> result = new ArrayList<>();
        if (!eventsNode.isArray()) return result;

        for (JsonNode e : eventsNode) {
            // 1. 주 관여 선수 이름 → 한글 우선
            long playerId = e.path("player").path("id").asLong();
            String apiPlayerName = e.path("player").path("name").asText();
            String playerNameKo = csvLoader.getPlayerNameKo(playerId);
            String playerNameKoLong = csvLoader.getPlayerNameKoLong(playerId);

            // 2. assist 선수 처리 (null 가능 — 카드 이벤트 등)
            JsonNode assistNode = e.path("assist");
            Long assistId = assistNode.path("id").isNull() ? null : assistNode.path("id").asLong();
            String assistName = null;
            String assistNameKoLong = null;
            if (assistId != null) {
                String assistApiName = assistNode.path("name").asText(null);
                assistNameKoLong = csvLoader.getPlayerNameKoLong(assistId);
                assistName = resolvePlayerDisplayName(assistId, assistApiName);
            }

            // 3. 팀 ID를 홈 팀 ID와 비교해서 side("home"/"away") 결정
            //    comments는 페널티 슛아웃 이벤트에서 "Penalty Shootout"으로 오고, 일반 이벤트는 null
            JsonNode commentsNode = e.path("comments");
            result.add(EventDto.builder()
                    .elapsed(e.path("time").path("elapsed").asInt())
                    .extra(e.path("time").path("extra").isNull() ? null : e.path("time").path("extra").asInt())
                    .side(e.path("team").path("id").asLong() == homeTeamId ? "home" : "away")
                    .teamId(e.path("team").path("id").asLong())
                    .playerId(playerId)
                    .playerName(resolvePlayerDisplayName(playerId, apiPlayerName))
                    .playerNameKoLong(playerNameKoLong)
                    .assistId(assistId)
                    .assistName(assistName)
                    .assistNameKoLong(assistNameKoLong)
                    .type(e.path("type").asText())
                    .detail(e.path("detail").asText())
                    .comments(commentsNode.isNull() ? null : commentsNode.asText())
                    .build());
        }
        return result;
    }

    // ──────────────────────────────────────────────
    // teamStats
    // ──────────────────────────────────────────────

    /**
     * 홈/원정 팀 스탯을 List로 조립. 결과 리스트는 항상 2개 (홈, 원정 순).
     *
     * [API 응답 구조]
     * statistics: [ { team: {...}, statistics: [ {type: "Shots on Goal", value: 3}, ... ] }, ... ]
     * type-value 배열을 Map으로 변환 후 각 필드에 매핑.
     *
     * [value 타입 주의]
     * 대부분은 Integer이지만 "Ball Possession", "Passes %" 는 "31%" 같은 String으로 옴.
     * 해당 필드는 String 타입으로 선언되어 있음.
     */
    private List<TeamStatsDto> buildTeamStats(JsonNode statsNode, long homeTeamId) {
        List<TeamStatsDto> result = new ArrayList<>();
        if (!statsNode.isArray()) return result;

        for (JsonNode entry : statsNode) {
            // 1. 팀 ID로 side 결정
            long teamId = entry.path("team").path("id").asLong();

            // 2. [ {type, value}, ... ] 배열을 type → value Map으로 변환
            Map<String, JsonNode> m = toStatMap(entry.path("statistics"));

            // 3. Map에서 각 스탯 값 꺼내 DTO 필드에 매핑
            result.add(TeamStatsDto.builder()
                    .teamId(teamId)
                    .side(teamId == homeTeamId ? "home" : "away")
                    .shotsOnGoal(intStat(m, "Shots on Goal"))
                    .shotsOffGoal(intStat(m, "Shots off Goal"))
                    .totalShots(intStat(m, "Total Shots"))
                    .blockedShots(intStat(m, "Blocked Shots"))
                    .shotsInsidebox(intStat(m, "Shots insidebox"))
                    .shotsOutsidebox(intStat(m, "Shots outsidebox"))
                    .fouls(intStat(m, "Fouls"))
                    .cornerKicks(intStat(m, "Corner Kicks"))
                    .offsides(intStat(m, "Offsides"))
                    .ballPossession(strStat(m, "Ball Possession"))  // "31%" 형식
                    .yellowCards(intStat(m, "Yellow Cards"))
                    .redCards(intStat(m, "Red Cards"))
                    .goalkeeperSaves(intStat(m, "Goalkeeper Saves"))
                    .totalPasses(intStat(m, "Total passes"))
                    .passesAccurate(intStat(m, "Passes accurate"))
                    .passesPercent(strStat(m, "Passes %"))          // "60%" 형식
                    .build());
        }
        return result;
    }

    // ──────────────────────────────────────────────
    // lineups
    // ──────────────────────────────────────────────

    /**
     * lineups 배열에서 targetTeamId에 해당하는 팀의 라인업을 조립.
     * 홈/원정 각각 한 번씩 호출됨.
     *
     * 경기 전(NS) 또는 라인업 미발표 상태이면 lineups 배열이 비어있어 null 반환.
     */
    private LineupDto buildLineup(JsonNode lineups, long targetTeamId) {
        if (!lineups.isArray()) return null;

        for (JsonNode lu : lineups) {
            // 1. 현재 entry가 대상 팀인지 확인
            if (lu.path("team").path("id").asLong() != targetTeamId) continue;

            // 2. 감독 이름 → 한글 우선
            JsonNode coachNode = lu.path("coach");
            long coachId = coachNode.path("id").asLong();
            String coachApiName = coachNode.path("name").asText();
            String coachKo = csvLoader.getCoachNameKo(coachId);
            String coachKoLong = csvLoader.getCoachNameKoLong(coachId);
            if (coachKo == null && coachKoLong == null) {
                log.info("[KO_COACH_NAME_NEEDED] id={}, name={}", coachId, coachApiName);
            } else if (coachKo == null) {
                log.info("[KO_COACH_NAME_SHORT_NEEDED] id={}, name={}", coachId, coachApiName);
            } else if (coachKoLong == null) {
                log.info("[KO_COACH_NAME_LONG_NEEDED] id={}, name={}", coachId, coachApiName);
            }

            // 3. 선발 + 벤치 선수 리스트 조립 후 DTO 반환
            return LineupDto.builder()
                    .formation(lu.path("formation").asText(null))   // ex. "4-2-3-1"
                    .startXi(buildPlayerList(lu.path("startXI")))   // API 응답 키는 "startXI" (대문자)
                    .substitutes(buildPlayerList(lu.path("substitutes")))
                    .coach(CoachDto.builder()
                            .coachId(coachId)
                            .name(resolveCoachDisplayName(coachId, coachApiName))
                            .nameKoLong(coachKoLong)
                            .build())
                    .build();
        }
        return null;
    }

    /**
     * startXI 또는 substitutes 배열을 PlayerDto 리스트로 변환.
     * 각 item은 { player: { id, name, number, pos, grid } } 구조.
     * grid는 선발만 있고 벤치는 null임 (ex. "2:3" = 2행 3열 포지션).
     */
    private List<PlayerDto> buildPlayerList(JsonNode listNode) {
        List<PlayerDto> result = new ArrayList<>();
        if (!listNode.isArray()) return result;

        for (JsonNode item : listNode) {
            JsonNode p = item.path("player");

            // 1. 선수 ID로 한글 이름 조회, 없으면 영문 + 로그
            long playerId = p.path("id").asLong();
            String apiName = p.path("name").asText();
            String nameKo = csvLoader.getPlayerNameKo(playerId);
            String nameKoLong = csvLoader.getPlayerNameKoLong(playerId);
            if (nameKo == null && nameKoLong == null) {
                log.info("[KO_NAME_NEEDED] id={}, name={}, pos={}", playerId, apiName, p.path("pos").asText());
            } else if (nameKo == null) {
                log.info("[KO_NAME_SHORT_NEEDED] id={}, name={}, pos={}", playerId, apiName, p.path("pos").asText());
            } else if (nameKoLong == null) {
                log.info("[KO_NAME_LONG_NEEDED] id={}, name={}, pos={}", playerId, apiName, p.path("pos").asText());
            }

            // 2. grid는 벤치 선수면 null → isNull() 체크 후 처리
            JsonNode gridNode = p.path("grid");
            result.add(PlayerDto.builder()
                    .playerId(playerId)
                    .name(resolvePlayerDisplayName(playerId, apiName))
                    .nameKoLong(nameKoLong)
                    .number(p.path("number").asInt())
                    .pos(p.path("pos").asText())            // "G" | "D" | "M" | "F"
                    .grid(gridNode.isNull() ? null : gridNode.asText(null))
                    .build());
        }
        return result;
    }

    // ──────────────────────────────────────────────
    // playerStats
    // ──────────────────────────────────────────────

    /**
     * 선수 개인 스탯을 조립. 프론트에서 선수 클릭 시 팝업에 사용될 데이터.
     *
     * [API 응답 구조]
     * players: [
     *   { team: {...}, players: [ { player: {id,name,photo}, statistics: [{games,shots,...}] }, ... ] },
     *   { team: {...}, players: [ ... ] }
     * ]
     * 홈팀 entry와 원정팀 entry 두 개가 배열로 오고, 각 팀의 선수 배열이 안에 있음.
     */
    private List<PlayerStatsDto> buildPlayerStats(JsonNode playersNode, long homeTeamId) {
        List<PlayerStatsDto> result = new ArrayList<>();
        if (!playersNode.isArray()) return result;

        // 1. 홈팀/원정팀 entry 순회
        for (JsonNode teamEntry : playersNode) {
            long teamId = teamEntry.path("team").path("id").asLong();
            String side = teamId == homeTeamId ? "home" : "away";

            // 2. 해당 팀 선수 목록 순회
            for (JsonNode item : teamEntry.path("players")) {
                JsonNode p = item.path("player");
                long playerId = p.path("id").asLong();
                String apiName = p.path("name").asText();
                String nameKo = csvLoader.getPlayerNameKo(playerId);
                String nameKoLong = csvLoader.getPlayerNameKoLong(playerId);

                // 3. statistics는 배열이지만 항상 1개만 들어있음 → get(0) 사용
                JsonNode stats = item.path("statistics").get(0);
                if (stats == null) continue;

                // 4. 스탯 섹션별로 노드 미리 꺼내기 (가독성 + 중복 path 호출 방지)
                JsonNode games = stats.path("games");
                JsonNode shots = stats.path("shots");
                JsonNode goals = stats.path("goals");
                JsonNode passes = stats.path("passes");
                JsonNode tackles = stats.path("tackles");
                JsonNode duels = stats.path("duels");
                JsonNode dribbles = stats.path("dribbles");
                JsonNode fouls = stats.path("fouls");
                JsonNode cards = stats.path("cards");

                // 5. DTO 조립 — 대부분의 스탯은 null 가능이므로 nullableInt() 사용
                result.add(PlayerStatsDto.builder()
                        .playerId(playerId)
                        .playerName(resolvePlayerDisplayName(playerId, apiName))
                        .playerNameKoLong(nameKoLong)
                        .playerPhotoUrl(toMediaCdnUrl(p.path("photo").asText()))
                        .side(side)
                        .minutes(nullableInt(games.path("minutes")))
                        .number(games.path("number").asInt())
                        .position(games.path("position").asText())
                        .rating(games.path("rating").isNull() ? null : games.path("rating").asText()) // "7.5" 형식
                        .captain(games.path("captain").asBoolean(false))
                        .substitute(games.path("substitute").asBoolean(false))
                        .shotsTotal(nullableInt(shots.path("total")))
                        .shotsOn(nullableInt(shots.path("on")))
                        .goalsScored(nullableInt(goals.path("total")))
                        .goalsConceded(nullableInt(goals.path("conceded")))
                        .assists(nullableInt(goals.path("assists")))
                        .saves(nullableInt(goals.path("saves")))    // GK 전용
                        .passesTotal(nullableInt(passes.path("total")))
                        .passesKey(nullableInt(passes.path("key")))
                        .passesAccuracy(passes.path("accuracy").isNull() ? null : passes.path("accuracy").asText())
                        .tacklesTotal(nullableInt(tackles.path("total")))
                        .tacklesBlocks(nullableInt(tackles.path("blocks")))
                        .tacklesInterceptions(nullableInt(tackles.path("interceptions")))
                        .duelsTotal(nullableInt(duels.path("total")))
                        .duelsWon(nullableInt(duels.path("won")))
                        .dribblesAttempts(nullableInt(dribbles.path("attempts")))
                        .dribblesSuccess(nullableInt(dribbles.path("success")))
                        .foulsDrawn(nullableInt(fouls.path("drawn")))
                        .foulsCommitted(nullableInt(fouls.path("committed")))
                        .yellowCards(cards.path("yellow").asInt(0))
                        .redCards(cards.path("red").asInt(0))
                        .build());
            }
        }
        return result;
    }

    // ──────────────────────────────────────────────
    // 공통 유틸 메서드
    // ──────────────────────────────────────────────

    /**
     * 경기장 이름을 한글화.
     *
     * [검색 전략]
     * id 유무 무관하게 항상 이름으로 먼저 검색.
     * 이유: CSV에 이름은 있지만 venue_id가 아직 미등록인 행이 존재할 수 있기 때문.
     *
     * [케이스별 동작]
     * 1. CSV에 이름이 있고, API에 id가 있는데 CSV의 id가 비어있음
     *    → [VENUE_ID_NEEDED] 로그 + 한글 이름 반환
     * 2. CSV에 이름이 있음 (id 일치 또는 API id 없음)
     *    → 한글 이름 반환 (한글 이름이 비어있으면 API 영문 이름 fallback)
     * 3. CSV에 이름이 없음
     *    → [KO_VENUE_NAME_NEEDED] 로그 + API 영문 이름 반환
     */
    private String resolveVenueName(JsonNode venueNode) {
        String apiName = venueNode.path("name").asText(null);
        String city = venueNode.path("city").asText(null);
        JsonNode idNode = venueNode.path("id");
        boolean hasApiId = !idNode.isNull() && !idNode.isMissingNode();

        // 1. 이름으로 CSV 검색 (id 유무 무관)
        String[] row = csvLoader.getVenueRow(apiName);

        if (row != null) {
            // 2. CSV에 해당 경기장이 있음
            String csvId = row[0].trim();
            if (hasApiId && csvId.isEmpty()) {
                // API에는 id가 있는데 CSV에는 id가 없음 → id 등록 필요 로그
                log.info("[VENUE_ID_NEEDED] name={}, apiId={}", apiName, idNode.asLong());
            }
            String nameKo = row.length > 3 ? row[3].trim() : "";
            return nameKo.isEmpty() ? apiName : nameKo;
        }

        // 3. CSV에 없음 → 로그 + API 이름 그대로 반환
        if (hasApiId) {
            log.info("[KO_VENUE_NAME_NEEDED] id={}, name={}, city={}", idNode.asLong(), apiName, city);
        } else {
            log.info("[KO_VENUE_NAME_NEEDED] name={}, city={}", apiName, city);
        }
        return apiName;
    }

    /**
     * 선수 표시 이름 우선순위.
     * 1. players.csv name_ko_short
     * 2. players.csv name_short
     * 3. API Football name
     */
    private String resolvePlayerDisplayName(long playerId, String apiName) {
        String nameKo = csvLoader.getPlayerNameKo(playerId);
        if (nameKo != null) return nameKo;

        String csvShort = csvLoader.getPlayerNameShort(playerId);
        if (csvShort != null) {
            logShortNameDiffOnce("player", playerId, apiName, csvShort);
            return csvShort;
        }
        return apiName;
    }

    /**
     * 감독 표시 이름 우선순위.
     * 1. coaches.csv name_ko_short
     * 2. coaches.csv name_short
     * 3. API Football name
     */
    private String resolveCoachDisplayName(long coachId, String apiName) {
        String nameKo = csvLoader.getCoachNameKo(coachId);
        if (nameKo != null) return nameKo;

        String csvShort = csvLoader.getCoachNameShort(coachId);
        if (csvShort != null) {
            logShortNameDiffOnce("coach", coachId, apiName, csvShort);
            return csvShort;
        }
        return apiName;
    }

    /**
     * API name과 CSV short가 다르면 CSV 값을 우선 사용하고 차이를 로그로 남긴다.
     * 같은 차이는 앱 실행 중 1번만 출력한다.
     */
    private void logShortNameDiffOnce(String type, long id, String apiName, String csvShort) {
        if (apiName == null || apiName.isBlank() || csvShort == null || csvShort.isBlank()) return;

        String apiTrimmed = apiName.trim();
        String csvTrimmed = csvShort.trim();
        if (apiTrimmed.equals(csvTrimmed)) return;

        String key = type + "|" + id + "|" + apiTrimmed + "|" + csvTrimmed;
        if (loggedShortNameDiffs.add(key)) {
            log.info("[CSV_SHORT_NAME_DIFF] type={}, id={}, api={}, csv={}", type, id, apiTrimmed, csvTrimmed);
        }
    }

    /**
     * API Football의 심판 문자열("Anthony Taylor, England")을 프론트 표시용으로 변환.
     * 국가는 API 응답 우선, 없으면 referees.csv의 referee_country를 fallback으로 사용.
     * 심판 정보 자체가 없는 경기(null/blank)는 null 반환.
     */
    private String buildRefereeName(String refereeStr) {
        if (refereeStr == null || refereeStr.isBlank()) return null;

        // 1. ", " 기준으로 이름과 국적 분리 (API Football 형식: "Anthony Taylor, England")
        int sep = refereeStr.indexOf(", ");
        if (sep < 0) {
            // 국적 없이 이름만 오는 경우 → CSV 국가 fallback 사용 가능
            String nameKo = csvLoader.getRefereeNameKo(refereeStr);
            String country = csvLoader.getRefereeCountry(refereeStr);
            if (nameKo == null) {
                log.info("[KO_REFEREE_NAME_NEEDED] referee={}", refereeStr);
                return country != null ? refereeStr + " (" + country + ")" : refereeStr;
            }
            return country != null ? nameKo + " (" + country + ")" : nameKo;
        }

        String name = refereeStr.substring(0, sep).trim();
        String country = refereeStr.substring(sep + 2).trim();
        if (country.isEmpty()) {
            country = csvLoader.getRefereeCountry(refereeStr);
        }

        // 2. referees.csv에서 한글 이름 조회 (key는 원본 문자열 전체)
        String nameKo = csvLoader.getRefereeNameKo(refereeStr);
        if (nameKo == null) {
            log.info("[KO_REFEREE_NAME_NEEDED] referee={}", refereeStr);
        }

        // 3. 국가가 있으면 "이름 (국가)", 없으면 이름만 반환
        String displayName = nameKo != null ? nameKo : name;
        return (country == null || country.isBlank()) ? displayName : displayName + " (" + country + ")";
    }

    /**
     * API Football의 short status 값을 프론트가 사용하는 내부 코드로 변환.
     *
     * ET(연장전)는 경과 시간으로 ET1(전반)/ET2(후반) 구분:
     *   elapsed <= 105 → ET1 (연장 전반, 90~105분)
     *   elapsed >  105 → ET2 (연장 후반, 105~120분)
     *
     * 승부차기 진행 중("P")은 PSO, 승부차기 포함 종료("PEN")는 FT.
     */
    private String mapStatus(String shortStatus, int elapsed) {
        return switch (shortStatus) {
            case "1H"              -> "1H";
            case "HT"              -> "HT";
            case "2H"              -> "2H";
            case "ET"              -> elapsed <= 105 ? "ET1" : "ET2";
            case "P"               -> "PSO";            // 승부차기 진행 중
            case "FT", "AET", "PEN" -> "FT";            // 정규/연장/승부차기 종료 모두 FT
            case "NS"              -> "NS";
            default                -> shortStatus;      // BT(휴식), SUSP 등 예외 상황 그대로 전달
        };
    }

    /**
     * 팀 로고 URL을 우선순위에 따라 결정.
     *
     * [우선순위]
     * 1순위: logos.csv에 등록된 커스텀 URL (직접 관리하는 고화질 로고)
     * 2순위: API Football URL을 Media CDN URL로 치환 (CORS + 캐시 확보)
     *
     * 로고가 아예 없으면 [LOGO_NEEDED] 로그 → logos.csv 수동 추가 필요.
     */
    private String resolveLogoUrl(long teamId, String apiLogoUrl) {
        // 1. logos.csv 커스텀 로고 확인
        String custom = csvLoader.getLogoUrl(teamId);
        if (custom != null) return custom;

        // 2. logos.csv에 커스텀 URL 없음 → 로그 남기기
        log.info("[LOGO_NEEDED] id={}", teamId);

        // 3. API 로고가 아예 없는 경우 → null 반환
        if (apiLogoUrl == null || apiLogoUrl.isBlank()) {
            return null;
        }

        // 4. API Football URL을 Media CDN URL로 도메인 치환
        return toMediaCdnUrl(apiLogoUrl);
    }

    /**
     * API Football 미디어 URL의 도메인을 자체 Media CDN으로 치환.
     * ex) https://media.api-sports.io/football/teams/85.png
     *  → https://media-handle-obsoverlay.b-cdn.net/football/teams/85.png
     *
     * Media CDN에서 CORS 허용 + 캐시 처리를 해주므로 프론트에서 Canvas 픽셀 읽기 가능.
     */
    private String toMediaCdnUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) return null;
        return apiUrl.replace("https://media.api-sports.io", mediaCdnUrl);
    }

    /**
     * statistics 배열 [ {type: "...", value: ...}, ... ] 을
     * type → value JsonNode Map으로 변환.
     * buildTeamStats에서 intStat/strStat 조회 시 사용.
     */
    private Map<String, JsonNode> toStatMap(JsonNode statsArray) {
        Map<String, JsonNode> map = new HashMap<>();
        if (statsArray.isArray()) {
            for (JsonNode s : statsArray) {
                map.put(s.path("type").asText(), s.path("value"));
            }
        }
        return map;
    }

    /**
     * 스탯 Map에서 Integer 값을 꺼냄.
     * value가 null이거나 key가 없으면 null 반환 (API에서 null로 오는 경우 많음).
     */
    private Integer intStat(Map<String, JsonNode> m, String key) {
        JsonNode n = m.get(key);
        return (n == null || n.isNull()) ? null : n.asInt();
    }

    /**
     * 스탯 Map에서 String 값을 꺼냄.
     * "Ball Possession"(ex. "31%"), "Passes %"(ex. "60%") 등 퍼센트 형식에 사용.
     */
    private String strStat(Map<String, JsonNode> m, String key) {
        JsonNode n = m.get(key);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    /**
     * JsonNode에서 Integer를 꺼내되, null/missing이면 null 반환.
     * API Football 선수 스탯은 뛰지 않은 항목은 null로 오기 때문에 일괄 처리용으로 사용.
     */
    private Integer nullableInt(JsonNode node) {
        return (node == null || node.isNull() || node.isMissingNode()) ? null : node.asInt();
    }
}
