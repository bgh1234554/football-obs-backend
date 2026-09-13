package com.github.baek.footballobsbackend.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.baek.footballobsbackend.client.ApiFootballClient;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.PlayerBirthDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatCardsDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatDribblesDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatDuelsDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatFoulsDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatGamesDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatGoalsDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatLeagueDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatPassesDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatPenaltyDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatShotsDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatSubstitutesDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatTacklesDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.StatTeamDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.PlayerInfoDto;
import com.github.baek.footballobsbackend.dto.stats.Layer1.PlayerSeasonStatDto;
import com.github.baek.footballobsbackend.dto.stats.PlayerProfileStatResponseDto;
import com.github.baek.footballobsbackend.error.ApiException;
import com.github.baek.footballobsbackend.error.ErrorCode;
import com.github.baek.footballobsbackend.util.CsvLoader;
import com.github.baek.footballobsbackend.util.KoResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 선수 스탯 조회 서비스.
 *
 * [시즌 결정 규칙]
 * 추춘제(유럽 빅리그 등, 8월 개막)와 춘추제(K리그 등, 3월 개막) 리그가 섞여 있어
 * fixture 하나만 보고 어느 쪽인지 판별하기 어렵다 - 그래서 리그 종류를 따지지 않고
 * "지난 시즌은 최소 하나는 항상 보이게" 하는 걸 우선해 연도 3개(6월까지)/2개(7월부터)로 단순화한다.
 * 6월 30일까지: 올해 + 작년 + 재작년 3시즌 호출 (예: 2027년 3월이면 2027+2026+2025)
 * 7월 1일부터: 올해 + 작년 2시즌 호출 (예: 2027년 9월이면 2027+2026)
 * 데이터 없는 시즌은 API 응답이 비어서(response.isEmpty()) 결과 Map에서 자동으로 빠진다.
 * 결과 Map의 key는 시즌 연도 문자열 ("2025", "2026"), 오래된 시즌부터 순서대로 삽입.
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
        // DEBUG 시작/종료 로그. 자세한 설계는 docs/logging.md.
        log.debug("getPlayerStats start playerId={}", playerId);
        long startedAt = System.nanoTime();

        if (playerId == 0) throw new ApiException(ErrorCode.PLAYER_NOT_FOUND);

        // 1. 6월 30일 기준으로 호출할 시즌 개수 결정. 리그가 추춘제인지 춘추제인지 안 가리고
        //    올해(year)와 작년(year-1)은 항상 포함하고, 상반기(1~6월)에는 추춘제의
        //    지난 시즌도 볼 수 있도록 재작년(year-2)을 추가로 호출한다.
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        boolean includeTwoYearsAgo = today.isBefore(LocalDate.of(year, 7, 1));
        List<Integer> seasons = includeTwoYearsAgo
                ? List.of(year - 2, year - 1, year)
                : List.of(year - 1, year);

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

        // 시즌 스탯이 없을 때: /players/profiles 로 선수 프로필만이라도 반환
        // (이름·사진이 있어야 ID 연결 팝업이 정상 동작함)
        if (result.isEmpty()) {
            JsonNode profileResponse = apiClient.getPlayerProfile(playerId);
            if (profileResponse != null && profileResponse.isArray() && !profileResponse.isEmpty()) {
                player = buildPlayerInfo(profileResponse.get(0).path("player"), playerId);
                log.debug("getPlayerStats done playerId={} seasons=0 profileFallback=true durationMs={}",
                        playerId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return PlayerProfileStatResponseDto.builder()
                        .player(player)
                        .statistics(result)   // 빈 map — 프론트에서 "스탯 없음" 처리
                        .build();
            }
            log.debug("getPlayerStats done playerId={} found=false durationMs={}",
                    playerId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            throw new ApiException(ErrorCode.STAT_NOT_AVAILABLE);
        }

        log.debug("getPlayerStats done playerId={} seasons={} durationMs={}",
                playerId, result.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
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
            Integer leagueId     = nullableInt(stat.path("league").path("id"));
            String apiLeagueName = stat.path("league").path("name").asText("");

            if (isFriendlyLeague(leagueId, apiLeagueName)) {
                friendlies.add(buildSeasonStatEntry(stat));
            } else {
                officialGames.add(buildSeasonStatEntry(stat));
            }
        }

        //리그 id 순으로 정렬
        officialGames.sort(Comparator.comparingInt(this::leagueSortId));
        friendlies.sort(Comparator.comparingInt(this::leagueSortId));

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
        Integer leagueId     = nullableInt(leagueNode.path("id"));
        int responseLeagueId = leagueId != null ? leagueId : 0;
        String apiLeagueName = leagueNode.path("name").asText("");

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
                        .id(responseLeagueId)
                        .name(koResolver.resolveLeagueName(leagueId, apiLeagueName))
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

    private int leagueSortId(PlayerSeasonStatDto stat) {
        if (stat == null || stat.getLeague() == null || stat.getLeague().getId() == null) return Integer.MAX_VALUE;
        int leagueId = stat.getLeague().getId();
        return leagueId == 0 ? Integer.MAX_VALUE : leagueId;
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
