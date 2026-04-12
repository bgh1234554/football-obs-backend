package com.github.baek.footballobsbackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * BunnyCDN 프록시를 통해 API Football v3를 호출하는 HTTP 클라이언트.
 *
 * [왜 BunnyCDN을 거치는가?]
 * API Football의 API 키(x-apisports-key)를 프론트에 노출하지 않기 위해
 * BunnyCDN Edge Script에서 헤더를 주입함. 따라서 이 클래스에서는 API 키를 직접 다루지 않음.
 *
 * [역할 범위]
 * 이 클래스는 API 호출 + 응답의 "response" 배열 추출까지만 담당.
 * JSON 파싱 및 DTO 조립은 FixtureService에서 처리.
 */
@Slf4j
@Component
public class ApiFootballClient {

    private final RestClient restClient;

    /**
     * RestClient를 CDN baseUrl로 초기화.
     * application.yaml의 api.cdn-url을 주입받아 모든 요청의 base가 됨.
     */
    public ApiFootballClient(@Value("${api.cdn-url}") String cdnUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(cdnUrl)
                .build();
    }

    /**
     * 경기 상세 정보를 가져온다 (이벤트, 라인업, 스탯, 선수 스탯 포함).
     * API Football /fixtures 엔드포인트는 배열로 응답하지만 ID 단건 조회이므로 response[0]만 반환.
     *
     * @param fixtureId API Football 경기 ID
     * @return response[0] JsonNode. 경기 데이터 없으면 null.
     */
    public JsonNode getFixture(long fixtureId) {
        log.info("Fetching fixture id={}", fixtureId);

        // 1. CDN에 GET /fixtures?id={fixtureId} 요청
        JsonNode root = restClient.get()
                .uri("/fixtures?id=" + fixtureId)
                .retrieve()
                .body(JsonNode.class);

        // 2. 응답 자체가 null이면 중단
        if (root == null) return null;

        // 3. "response" 배열 확인 — 결과가 없으면 null 반환
        JsonNode response = root.path("response");
        if (!response.isArray() || response.isEmpty()) return null;

        // 4. 단건 조회이므로 첫 번째 원소만 꺼내서 반환
        return response.get(0);
    }

    /**
     * 해당 경기의 부상/결장 선수 목록을 가져온다.
     * API Football /injuries 엔드포인트는 fixtureId 기준으로 양팀 결장 선수를 배열로 반환.
     *
     * @param fixtureId API Football 경기 ID
     * @return injuries response 배열 JsonNode. 응답 없으면 null.
     */
    public JsonNode getInjuries(long fixtureId) {
        log.info("Fetching injuries fixtureId={}", fixtureId);
        return fetchArray("/injuries?ids=" + fixtureId);
    }

    // ──────────────────────────────────────────────
    // CsvUpdater 전용 — CSV 초기화 시 사용
    // ──────────────────────────────────────────────

    /**
     * 특정 리그+시즌의 팀 목록을 가져온다. (팀 정보 + 홈구장 정보 포함)
     * CsvUpdater에서 teams.csv / venues.csv 초기화에 사용.
     *
     * @return response 배열 JsonNode. 응답 없으면 null.
     */
    public JsonNode getTeams(int leagueId, int season) {
        log.info("Fetching teams leagueId={} season={}", leagueId, season);
        return fetchArray("/teams?league=" + leagueId + "&season=" + season);
    }

    /**
     * 특정 팀 단건 정보를 가져온다. (팀 이름 + 홈구장 정보 포함)
     * CsvUpdater Mode.TEAM 에서 teams.csv / venues.csv 업데이트에 사용.
     *
     * @return response 배열 JsonNode. 응답 없으면 null.
     */
    public JsonNode getTeam(long teamId) {
        log.info("Fetching team teamId={}", teamId);
        return fetchArray("/teams?id=" + teamId);
    }

    /**
     * 특정 팀의 현재 스쿼드(선수 목록)를 가져온다.
     * CsvUpdater에서 players.csv 초기화에 사용.
     *
     * @return response 배열 JsonNode. 응답 없으면 null.
     */
    public JsonNode getPlayerSquad(long teamId) {
        log.info("Fetching squad teamId={}", teamId);
        return fetchArray("/players/squads?team=" + teamId);
    }

    /**
     * 특정 선수의 상세 프로필(풀네임, 국적 등)을 가져온다.
     * CsvUpdater에서 /players/squads만으로는 얻을 수 없는 nationality 등을 보완하는 데 사용.
     *
     * @return response 배열 JsonNode. 응답 없으면 null.
     */
    public JsonNode getPlayerProfile(long playerId) {
        log.info("Fetching player profile playerId={}", playerId);
        return fetchArray("/players/profiles?player=" + playerId);
    }

    /**
     * 특정 선수의 상세 스탯을 가져온다.
     * CsvUpdater에서 /players/squads만으로는 얻을 수 없는 nationality 등을 보완하는 데 사용.
     *
     * @return response 배열 JsonNode. 응답 없으면 null.
     */
    public JsonNode getPlayerStats(long playerId, int season) {
        log.info("Fetching player stats playerId={}, season={}", playerId, season);
        return fetchArray("/players?id=" + playerId + "&season=" + season);
    }

    /**
     * 특정 리그의 이름을 가져온다.
     * CsvUpdater에서 미리스트 리그 ID 입력 시 이름 확인에 사용.
     *
     * @return 리그 이름 문자열. 응답 없으면 null.
     */
    public String getLeagueName(int leagueId) {
        log.info("Fetching league name leagueId={}", leagueId);
        JsonNode arr = fetchArray("/leagues?id=" + leagueId);
        if (arr == null || arr.isEmpty()) return null;
        return arr.get(0).path("league").path("name").asText(null);
    }

    /**
     * 특정 팀의 현재 감독 정보를 가져온다.
     * CsvUpdater에서 coaches.csv 초기화에 사용.
     *
     * @return response 배열 JsonNode. 응답 없으면 null.
     */
    public JsonNode getCoach(long teamId) {
        log.info("Fetching coach teamId={}", teamId);
        return fetchArray("/coachs?team=" + teamId);
    }

    /**
     * 특정 감독 ID의 상세 정보를 가져온다.
     * CsvUpdater 개별 감독 갱신(Mode.COACHES)에서 사용.
     *
     * @return response 배열 JsonNode. 응답 없으면 null.
     */
    public JsonNode getCoachById(long coachId) {
        log.info("Fetching coach coachId={}", coachId);
        return fetchArray("/coachs?id=" + coachId);
    }

    // ──────────────────────────────────────────────
    // 공통 헬퍼
    // ──────────────────────────────────────────────

    /**
     * CDN에 GET 요청을 보내고 "response" 배열을 반환하는 공통 헬퍼.
     * 응답이 null이거나 "response"가 배열이 아니면 null 반환.
     */
    private JsonNode fetchArray(String path) {
        JsonNode root = restClient.get()
                .uri(path)
                .retrieve()
                .body(JsonNode.class);
        if (root == null) return null;
        JsonNode response = root.path("response");
        return response.isArray() ? response : null;
    }
}
