package com.github.baek.footballobsbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.baek.footballobsbackend.client.ApiFootballClient;
import com.github.baek.footballobsbackend.dto.stats.PlayerProfileStatResponseDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.PlayerInfoDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.PlayerSeasonStatDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.*;
import com.github.baek.footballobsbackend.error.ApiException;
import com.github.baek.footballobsbackend.error.ErrorCode;
import com.github.baek.footballobsbackend.util.CsvLoader;
import com.github.baek.footballobsbackend.util.KoResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 선수 스탯 조회 서비스.
 *
 * [시즌 결정 규칙]
 * 7월 1일 이전: 전 시즌 + 현 시즌 모두 호출 (예: 4월이면 2025 + 2026)
 * 7월 1일 이후: 현 시즌만 호출 (예: 8월이면 2026만)
 * 결과 Map의 key는 시즌 연도 문자열 ("2025", "2026").
 *
 * [한글화 우선순위]
 * 선수 단축명 : name_ko_short → name_short(CSV) → API name
 * 선수 풀네임  : name_ko_long  → name_long(CSV)  → API firstname + lastname
 * 국적        : players.csv nationality → teams.csv 한글명 → CSV 영문 → API 값
 * 팀명        : ko_name(id 조회) → team_name(CSV English) → API name
 * 리그명      : league_name_ko  → league_name(CSV English) → API name
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerService {

    private final ApiFootballClient apiClient;
    private final CsvLoader csvLoader;
    private final KoResolver koResolver;

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    /**
     * 선수 ID로 선수 기본 정보 + 시즌별 대회 스탯을 반환.
     * statistics key: 시즌 연도 문자열, value: 해당 시즌의 대회별 스탯 리스트
     *
     * @param playerId API Football 선수 ID
     * @return 선수 프로필 + 시즌별 스탯. 데이터 없으면 PLAYER_NOT_FOUND 예외.
     */
    public PlayerProfileStatResponseDto getPlayerStats(long playerId) {
        if (playerId == 0) throw new ApiException(ErrorCode.PLAYER_NOT_FOUND);

        // 1. 9월 1일 기준으로 호출할 시즌 결정 (추춘제 시즌 초반에 지난시즌 스탯 제공 도움)
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        boolean callBoth = today.isBefore(LocalDate.of(year, 9, 1));
        List<Integer> seasons = callBoth ? List.of(year - 1, year) : List.of(year);

        // 2. 시즌별로 API 호출 → DTO 조립
        // 삽입 순서 보장을 위해 LinkedHashMap으로 구현
        PlayerInfoDto player = null;
        Map<String, List<PlayerSeasonStatDto>> result = new LinkedHashMap<>();
        for (int season : seasons) {
            JsonNode response = apiClient.getPlayerStats(playerId, season);
            if (response == null || !response.isArray() || response.isEmpty()) continue;
            JsonNode item = response.get(0);
            if (player == null) {
                player = buildPlayerInfo(item.path("player"), playerId);
            }
            result.put(String.valueOf(season), buildSeasonStats(item.path("statistics")));
        }

        if (result.isEmpty()) throw new ApiException(ErrorCode.STAT_NOT_AVAILABLE);

        return PlayerProfileStatResponseDto.builder()
                .player(player)
                .statistics(result)
                .build();
    }

    // ──────────────────────────────────────────────
    // 선수 기본 정보
    // ──────────────────────────────────────────────

    //선수 프로필 빌더
    private PlayerInfoDto buildPlayerInfo(JsonNode playerNode, long playerId) {
        String apiName      = playerNode.path("name").asText();
        String apiFirstname = playerNode.path("firstname").asText("");
        String apiLastname  = playerNode.path("lastname").asText("");
        String apiNationality = playerNode.path("nationality").asText(null);

        // name: name_ko_short → name_short(CSV) → API name
        String name = koResolver.resolvePlayerDisplayName(playerId, apiName);

        // fullName: name_ko_long → name_long(CSV) → API firstname + " " + lastname
        String nameKoLong = csvLoader.getPlayerNameKoLong(playerId);
        String nameLong   = csvLoader.getPlayerNameLong(playerId);
        String fullName;
        if (nameKoLong != null)      fullName = nameKoLong;
        else if (nameLong != null)   fullName = nameLong;
        else fullName = (apiFirstname + " " + apiLastname).strip();

        // nationality: players.csv 값 우선 → teams.csv 한글명 조회 → CSV 영문 → API 값
        String csvNationality   = csvLoader.getPlayerNationality(playerId);
        String canonicalEnglish = csvNationality != null ? csvNationality : apiNationality;
        String nationalityKo    = canonicalEnglish != null ? csvLoader.getTeamNameKoByName(canonicalEnglish) : null;
        String nationality      = nationalityKo != null ? nationalityKo : canonicalEnglish;

        JsonNode birthNode = playerNode.path("birth");
        // birth.country: nationality와 동일하게 teams.csv 한글명 조회 후 fallback
        String birthCountryApi = nullableStr(birthNode.path("country"));
        String birthCountryKo  = birthCountryApi != null ? csvLoader.getTeamNameKoByName(birthCountryApi) : null;
        String birthCountry    = birthCountryKo != null ? birthCountryKo : birthCountryApi;

        return PlayerInfoDto.builder()
                .id(playerNode.path("id").asLong())
                .name(name)
                .fullName(fullName)
                .age(playerNode.path("age").asInt())
                .birth(PlayerBirthDto.builder()
                        .date(nullableStr(birthNode.path("date")))
                        .place(nullableStr(birthNode.path("place")))
                        .country(birthCountry)
                        .build())
                .nationality(nationality)
                .height(nullableStr(playerNode.path("height")))
                .weight(nullableStr(playerNode.path("weight")))
                .photoUrl(koResolver.toMediaCdnUrl(playerNode.path("photo").asText(null)))
                .build();
    }

    // ──────────────────────────────────────────────
    // 대회별 스탯 목록
    // ──────────────────────────────────────────────

    /**
     * 통계 배열을 DTO 리스트로 변환. 친선경기(Friendlies)는 맨 뒤로 정렬.
     */
    private List<PlayerSeasonStatDto> buildSeasonStats(JsonNode statsNode) {
        List<PlayerSeasonStatDto> officialGames = new ArrayList<>();
        List<PlayerSeasonStatDto> friendlies = new ArrayList<>();
        if (!statsNode.isArray()) return officialGames;

        for (JsonNode stat : statsNode) {
            // 친선경기 판별은 API 원문 이름 기준 (한글 변환 전)
            Integer leagueId     = stat.path("league").path("id").isNull()
                    ? null : stat.path("league").path("id").asInt();
            String apiLeagueName = stat.path("league").path("name").asText("");

            if (isFriendlyLeague(leagueId, apiLeagueName)) {
                friendlies.add(buildSeasonStatEntry(stat));
            } else {
                officialGames.add(buildSeasonStatEntry(stat));
            }
        }

        //리그 id 순으로 정렬
        officialGames.sort(Comparator.comparingInt(a -> a.getLeague().getId()));
        friendlies.sort(Comparator.comparingInt(a -> a.getLeague().getId()));

        officialGames.addAll(friendlies);
        return officialGames;
    }

    //스탯 부문별 빌더
    private PlayerSeasonStatDto buildSeasonStatEntry(JsonNode stat) {
        // team
        JsonNode teamNode   = stat.path("team");
        long teamId         = teamNode.path("id").asLong();
        String apiTeamName  = teamNode.path("name").asText();

        // league
        JsonNode leagueNode  = stat.path("league");
        Integer leagueId     = leagueNode.path("id").isNull() ? null : leagueNode.path("id").asInt();
        String apiLeagueName = leagueNode.path("name").asText();

        // stat section nodes
        JsonNode games      = stat.path("games");
        JsonNode substitutes = stat.path("substitutes");
        JsonNode shots      = stat.path("shots");
        JsonNode goals      = stat.path("goals");
        JsonNode passes     = stat.path("passes");
        JsonNode tackles    = stat.path("tackles");
        JsonNode duels      = stat.path("duels");
        JsonNode dribbles   = stat.path("dribbles");
        JsonNode fouls      = stat.path("fouls");
        JsonNode cards      = stat.path("cards");
        JsonNode penalty    = stat.path("penalty");

        return PlayerSeasonStatDto.builder()
                .team(StatTeamDto.builder()
                        .id(teamId)
                        .name(koResolver.resolveTeamName(teamId, apiTeamName))
                        .logo(koResolver.resolveLogoUrl(teamId, teamNode.path("logo").asText(null), apiTeamName))
                        .build())
                .league(StatLeagueDto.builder()
                        .id(leagueId)
                        .name(leagueId != null ? koResolver.resolveLeagueName(leagueId, apiLeagueName) : apiLeagueName)
                        .logo(koResolver.resolveLeagueLogoUrl(leagueId, leagueNode.path("logo").asText(null)))
                        .season(leagueNode.path("season").asInt())
                        .build())
                .games(StatGamesDto.builder()
                        .appearences(nullableInt(games.path("appearences")))
                        .lineups(nullableInt(games.path("lineups")))
                        .minutes(nullableInt(games.path("minutes")))
                        .number(nullableInt(games.path("number")))
                        .position(games.path("position").asText(null))
                        .rating(games.path("rating").isNull() ? null : games.path("rating").asText())
                        .captain(games.path("captain").asBoolean(false))
                        .build())
                .substitutes(StatSubstitutesDto.builder()
                        .in(nullableInt(substitutes.path("in")))
                        .out(nullableInt(substitutes.path("out")))
                        .bench(nullableInt(substitutes.path("bench")))
                        .build())
                .shots(StatShotsDto.builder()
                        .total(nullableInt(shots.path("total")))
                        .on(nullableInt(shots.path("on")))
                        .build())
                .goals(StatGoalsDto.builder()
                        .total(nullableInt(goals.path("total")))
                        .conceded(nullableInt(goals.path("conceded")))
                        .assists(nullableInt(goals.path("assists")))
                        .saves(nullableInt(goals.path("saves")))
                        .build())
                .passes(StatPassesDto.builder()
                        .total(nullableInt(passes.path("total")))
                        .key(nullableInt(passes.path("key")))
                        .accuracy(nullableInt(passes.path("accuracy")))
                        .build())
                .tackles(StatTacklesDto.builder()
                        .total(nullableInt(tackles.path("total")))
                        .blocks(nullableInt(tackles.path("blocks")))
                        .interceptions(nullableInt(tackles.path("interceptions")))
                        .build())
                .duels(StatDuelsDto.builder()
                        .total(nullableInt(duels.path("total")))
                        .won(nullableInt(duels.path("won")))
                        .build())
                .dribbles(StatDribblesDto.builder()
                        .attempts(nullableInt(dribbles.path("attempts")))
                        .success(nullableInt(dribbles.path("success")))
                        .past(nullableInt(dribbles.path("past")))
                        .build())
                .fouls(StatFoulsDto.builder()
                        .drawn(nullableInt(fouls.path("drawn")))
                        .committed(nullableInt(fouls.path("committed")))
                        .build())
                .cards(StatCardsDto.builder()
                        .yellow(nullableInt(cards.path("yellow")))
                        .yellowred(nullableInt(cards.path("yellowred")))
                        .red(nullableInt(cards.path("red")))
                        .build())
                .penalty(StatPenaltyDto.builder()
                        .won(nullableInt(penalty.path("won")))
                        .committed(nullableInt(penalty.path("commited")))  // API 원문 오타: "commited"
                        .scored(nullableInt(penalty.path("scored")))
                        .missed(nullableInt(penalty.path("missed")))
                        .saved(nullableInt(penalty.path("saved")))
                        .build())
                .build();
    }

    private final List<Integer> FRIENDLIES_LEAGUES = List.of(10,666,667);
    /** 친선경기 판별: 리그 id 667 또는 API 이름에 "friend" 포함 (대소문자 무관). */
    private boolean isFriendlyLeague(Integer leagueId, String apiLeagueName) {
        if (leagueId != null && FRIENDLIES_LEAGUES.contains(leagueId)) return true;
        return apiLeagueName != null && apiLeagueName.toLowerCase().contains("friend");
    }

    // ──────────────────────────────────────────────
    // 기타 유틸
    // ──────────────────────────────────────────────

    private Integer nullableInt(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return node.asInt();
    }

    private String nullableStr(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String v = node.asText();
        return v.isEmpty() ? null : v;
    }
}
