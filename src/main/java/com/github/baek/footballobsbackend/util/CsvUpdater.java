package com.github.baek.footballobsbackend.util;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.baek.footballobsbackend.FootballObsBackendApplication;
import com.github.baek.footballobsbackend.client.ApiFootballClient;

/**
 * players.csv, coaches.csv, teams.csv, venues.csv를 자동으로 업데이트하는 독립 실행 유틸.
 * Spring 빈이 아님 — 로컬에서 main()으로 직접 실행.
 *
 * [실행 전 확인]
 * - 프로젝트 루트의 .env에 CDN_URL이 설정되어 있어야 함 (spring-dotenv가 자동 로드)
 *
 * [처리 흐름]
 * 1. CsvUpdaterUi로 업데이트 대상 리그/시즌 선택
 * 2. /teams?league=X&season=Y       → teams.csv + venues.csv 업데이트
 * 3. /players/squads?team=X         → 신규 선수 ID 수집
 * 4. /players/profiles?player=X     → name_long, nationality 포함 상세 정보 → players.csv
 * 5. /coachs?team=X                 → coaches.csv 업데이트
 *
 * [주의]
 * - 기존 CSV에 이미 있는 ID(players/coaches/teams) 또는 이름(venues)은 건너뜀
 * - name_ko_* / ko_name / venue_name_ko / city_name_ko 컬럼은 수동 입력
 * - profiles 호출 실패 시 squads 데이터로 fallback (nationality 빈 값)
 * - API rate limit 대비 API 호출 사이에 REQUEST_DELAY_MS 딜레이 적용
 */
public class CsvUpdater {

    static final String DATA_DIR = "src/main/resources/data";
    private static final int REQUEST_DELAY_MS = 300; // Pro Plan 300 req/min에서 백엔드 동시 사용 여유분 확보 (200 req/min)
    private static final int PLAYER_COLUMN_COUNT = 7;

    // ──────────────────────────────────────────────
    // 진입점
    // ──────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        // 1. Spring 컨텍스트 시작 → ApiFootballClient 빈 획득
        //    application.yaml + spring-dotenv(.env) 자동 로드, lazy-init으로 불필요한 빈 초기화 방지
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(FootballObsBackendApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.lazy-initialization=true")
                .run(args);
        ApiFootballClient apiClient = ctx.getBean(ApiFootballClient.class);

        // Scanner는 루프 전체에서 하나만 생성 (System.in은 공유 스트림이므로 재생성 금지)
        Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8);

        try {
            // 2. 메인 루프 — q 입력 시에만 종료, 서브메뉴 취소는 메인으로 복귀
            while (true) {
                CsvUpdaterUi.SelectionResult selection = CsvUpdaterUi.promptSelection(sc, apiClient::getLeagueName);
                if (selection == null) {
                    System.out.println("[CsvUpdater] 종료합니다.");
                    break;
                }

                // 3. 작업 실행 + 소요 시간 출력 후 메인으로 복귀
                long startTime = System.currentTimeMillis();
                if (selection.mode() == CsvUpdaterUi.Mode.PLAYERS) {
                    processSelectedPlayers(apiClient, selection.playerIds());
                } else if (selection.mode() == CsvUpdaterUi.Mode.TEAM) {
                    processTeamOnly(apiClient, selection.teamId());
                } else {
                    processAll(apiClient, selection);
                }
                Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
                System.out.printf("%n  작업 완료까지 %d분 %d초 %d밀리초가 소요되었습니다.%n",
                        duration.toMinutesPart(), duration.toSecondsPart(), duration.toMillisPart());
                System.out.println("  메인 화면으로 돌아갑니다.");
            }
        } finally {
            ctx.close();
        }
    }

    // ──────────────────────────────────────────────
    // 전체 처리
    // ──────────────────────────────────────────────

    /**
     * 선택된 리그 목록을 순회하며 CSV를 업데이트한다.
     */
    private static void processAll(ApiFootballClient apiClient, CsvUpdaterUi.SelectionResult selection)
            throws Exception {

        // 기존 CSV에서 이미 등록된 항목 로드 (중복 추가 방지)
        Set<Long>   existingTeamIds      = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/teams.csv");
        Set<Long>   existingPlayerIds    = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/players.csv");
        Set<Long>   existingCoachIds     = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/coaches.csv");
        Set<String> existingVenueNames   = CsvUpdaterCsvHelper.loadVenueNames(DATA_DIR + "/venues.csv");
        Set<Long>   existingLeagueIds    = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/leagues.csv");

        // 리그 이름 조회용 맵 (preset 목록 기반)
        Map<Integer, String> leagueNameMap = new HashMap<>();
        for (CsvUpdaterUi.LeagueEntry e : CsvUpdaterUi.FALL_LEAGUES) leagueNameMap.put(e.id(), e.name());
        for (CsvUpdaterUi.LeagueEntry e : CsvUpdaterUi.SPRING_LEAGUES) leagueNameMap.put(e.id(), e.name());

        List<String> newTeamRows   = new ArrayList<>();
        List<String> newPlayerRows = new ArrayList<>();
        List<String> newCoachRows  = new ArrayList<>();
        List<String> newVenueRows  = new ArrayList<>();
        List<String> newLeagueRows = new ArrayList<>();

        for (int leagueId : selection.leagueIds()) {
            // leagues.csv에 없으면 신규 행 추가 (league_name_ko / logo_url 은 수동 입력)
            if (!existingLeagueIds.contains((long) leagueId)) {
                existingLeagueIds.add((long) leagueId);
                String leagueName = leagueNameMap.getOrDefault(leagueId, "");
                newLeagueRows.add(leagueId + "," + CsvUpdaterCsvHelper.esc(leagueName) + ",,");
                System.out.printf("  [LEAGUE+] %d %s%n", leagueId, leagueName);
            }

            processLeague(apiClient, leagueId, selection.season(),
                    existingTeamIds, existingPlayerIds, existingCoachIds, existingVenueNames,
                    newTeamRows, newPlayerRows, newCoachRows, newVenueRows);
        }

        // 각 CSV에 신규 행 append
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/leagues.csv", newLeagueRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/teams.csv",   newTeamRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/players.csv", newPlayerRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/coaches.csv", newCoachRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/venues.csv",  newVenueRows);

        System.out.printf("%n[CsvUpdater] 완료 — leagues:%d  teams:%d  players:%d  coaches:%d  venues:%d%n",
                newLeagueRows.size(), newTeamRows.size(), newPlayerRows.size(), newCoachRows.size(), newVenueRows.size());
    }

    /**
     * 특정 선수만 재조회해 players.csv 끝에 최신 행을 append 한다.
     * 같은 player_id가 이미 있어도 기존 행은 유지하며, 한글 이름 컬럼은 마지막 기존 값을 이어받는다.
     */
    private static void processSelectedPlayers(ApiFootballClient apiClient, List<Long> playerIds)
            throws Exception {

        CsvUpdaterCsvHelper.CsvTable table =
                CsvUpdaterCsvHelper.loadCsvTable(DATA_DIR + "/players.csv", PLAYER_COLUMN_COUNT);

        List<String> newPlayerRows = new ArrayList<>();
        int appendedCount = 0;
        int insertedCount = 0;

        for (long playerId : playerIds) {
            Thread.sleep(REQUEST_DELAY_MS);

            JsonNode profileResp = apiClient.getPlayerProfile(playerId);
            if (profileResp == null || !profileResp.isArray() || profileResp.isEmpty()) {
                System.out.printf("  [PLAYER!] %d profiles 응답 없음, 건너뜀%n", playerId);
                continue;
            }

            JsonNode p           = profileResp.get(0).path("player");
            String[] existingRow = findPlayerRow(table.rows(), playerId);

            String nameShort     = firstNonBlank(p.path("name").asText(""), getColumn(existingRow, 1));
            String position      = firstNonBlank(p.path("position").asText(""), getColumn(existingRow, 3));
            String nationality   = firstNonBlank(p.path("nationality").asText(""), getColumn(existingRow, 4));
            String nameLong      = buildPlayerLongName(
                    p.path("firstname").asText(""),
                    p.path("lastname").asText(""),
                    nationality
            );
            nameLong = firstNonBlank(nameLong, nameShort, getColumn(existingRow, 2));

            String nameKoLong    = getColumn(existingRow, 5);
            String nameKoShort   = getColumn(existingRow, 6);
            String row = playerId + "," + CsvUpdaterCsvHelper.esc(nameShort)
                    + "," + CsvUpdaterCsvHelper.esc(nameLong)
                    + "," + CsvUpdaterCsvHelper.esc(position)
                    + "," + CsvUpdaterCsvHelper.esc(nationality)
                    + "," + CsvUpdaterCsvHelper.esc(nameKoLong)
                    + "," + CsvUpdaterCsvHelper.esc(nameKoShort);
            newPlayerRows.add(row);

            if (existingRow == null) {
                insertedCount++;
                System.out.printf("  [PLAYER+] %d %s / %s%n", playerId, nameShort, nationality);
            } else {
                appendedCount++;
                System.out.printf("  [PLAYER~] %d %s / %s (append)%n", playerId, nameShort, nationality);
            }
        }

        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/players.csv", newPlayerRows);
        System.out.printf("%n[CsvUpdater] 특정 선수 갱신 완료 — appended:%d inserted:%d%n", appendedCount, insertedCount);
    }

    /**
     * 특정 팀 ID 하나만 지정해 팀 정보 + 선수 + 감독을 수집한다.
     * 리그 전체를 순회하지 않고 단일 팀만 처리하고 싶을 때 사용.
     * 이미 등록된 ID(팀/선수/감독) 또는 이름(경기장)은 건너뜀.
     */
    private static void processTeamOnly(ApiFootballClient apiClient, long teamId) throws Exception {
        System.out.printf("%n[CsvUpdater] Team %d 처리 중...%n", teamId);

        Set<Long>   existingTeamIds    = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/teams.csv");
        Set<Long>   existingPlayerIds  = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/players.csv");
        Set<Long>   existingCoachIds   = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/coaches.csv");
        Set<String> existingVenueNames = CsvUpdaterCsvHelper.loadVenueNames(DATA_DIR + "/venues.csv");

        List<String> newTeamRows   = new ArrayList<>();
        List<String> newVenueRows  = new ArrayList<>();
        List<String> newPlayerRows = new ArrayList<>();
        List<String> newCoachRows  = new ArrayList<>();

        // 1. 팀 정보 + 홈구장 수집 (/teams?id=X)
        JsonNode teamResp = apiClient.getTeam(teamId);
        if (teamResp != null && teamResp.isArray() && !teamResp.isEmpty()) {
            collectTeamsAndVenues(teamResp, existingTeamIds, existingVenueNames, newTeamRows, newVenueRows);
        } else {
            System.out.printf("  [TEAM!] %d 팀 정보 응답 없음%n", teamId);
        }

        // 2. 선수 스쿼드 + 프로필 수집
        Thread.sleep(REQUEST_DELAY_MS);
        collectPlayersForTeam(apiClient, teamId, existingPlayerIds, newPlayerRows);

        // 3. 감독 수집
        Thread.sleep(REQUEST_DELAY_MS);
        collectCoachForTeam(apiClient, teamId, existingCoachIds, newCoachRows);

        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/teams.csv",   newTeamRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/venues.csv",  newVenueRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/players.csv", newPlayerRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/coaches.csv", newCoachRows);

        System.out.printf("%n[CsvUpdater] Team %d 완료 — teams:%d  venues:%d  players:%d  coaches:%d%n",
                teamId, newTeamRows.size(), newVenueRows.size(), newPlayerRows.size(), newCoachRows.size());
    }

    // ──────────────────────────────────────────────
    // 리그 단위 처리
    // ──────────────────────────────────────────────

    /**
     * 리그 한 개를 처리한다.
     * 1) 리그 소속 팀 + 홈구장 수집
     * 2) 팀별 선수 스쿼드 + 감독 수집
     */
    private static void processLeague(
            ApiFootballClient apiClient, int leagueId, int season,
            Set<Long> existingTeamIds, Set<Long> existingPlayerIds, Set<Long> existingCoachIds,
            Set<String> existingVenueNames,
            List<String> newTeamRows, List<String> newPlayerRows,
            List<String> newCoachRows, List<String> newVenueRows) throws InterruptedException {

        System.out.printf("%n[CsvUpdater] League %d 처리 중...%n", leagueId);

        // 1. 리그 소속 팀 + 홈구장 조회
        JsonNode teamsResp = apiClient.getTeams(leagueId, season);
        if (teamsResp == null || !teamsResp.isArray()) {
            System.out.printf("[CsvUpdater] League %d 응답 없음, 건너뜀%n", leagueId);
            return;
        }
        if (teamsResp.isEmpty()) {
            System.out.printf("[CsvUpdater] League %d season %d 에 등록된 팀 없음 (해당 시즌 미개최), 건너뜀%n", leagueId, season);
            return;
        }

        List<Long> teamIds = collectTeamsAndVenues(
                teamsResp, existingTeamIds, existingVenueNames, newTeamRows, newVenueRows);

        // 2. 팀별 선수 + 감독 조회
        for (long teamId : teamIds) {
            Thread.sleep(REQUEST_DELAY_MS);
            collectPlayersForTeam(apiClient, teamId, existingPlayerIds, newPlayerRows);
            Thread.sleep(REQUEST_DELAY_MS);
            collectCoachForTeam(apiClient, teamId, existingCoachIds, newCoachRows);
        }
    }

    /**
     * /teams 응답에서 팀과 홈구장을 수집한다. 신규 항목만 추가.
     * @return 해당 리그의 팀 ID 목록 (선수/감독 조회에 사용)
     */
    private static List<Long> collectTeamsAndVenues(
            JsonNode teamsResp,
            Set<Long> existingTeamIds, Set<String> existingVenueNames,
            List<String> newTeamRows, List<String> newVenueRows) {

        List<Long> teamIds = new ArrayList<>();

        for (JsonNode entry : teamsResp) {
            // 팀
            JsonNode team     = entry.path("team");
            long teamId       = team.path("id").asLong();
            String teamName   = team.path("name").asText("");

            if (!existingTeamIds.contains(teamId)) {
                existingTeamIds.add(teamId);
                // columns: team_id, team_name, ko_name(수동)
                newTeamRows.add(teamId + "," + CsvUpdaterCsvHelper.esc(teamName) + ",");
                System.out.printf("  [TEAM+] %d %s%n", teamId, teamName);
            }
            teamIds.add(teamId);

            // 홈구장 — 이름 기준 중복 체크
            JsonNode venue    = entry.path("venue");
            String venueName  = venue.path("name").asText(null);
            if (venueName != null && !existingVenueNames.contains(venueName.toLowerCase())) {
                existingVenueNames.add(venueName.toLowerCase());
                JsonNode venueIdNode = venue.path("id");
                String venueIdStr    = (venueIdNode.isNull() || venueIdNode.isMissingNode())
                        ? "" : String.valueOf(venueIdNode.asLong());
                String venueCity     = venue.path("city").asText("");
                // columns: venue_id, venue_name, venue_city, venue_name_ko(수동), city_name_ko(수동)
                newVenueRows.add(venueIdStr + "," + CsvUpdaterCsvHelper.esc(venueName)
                        + "," + CsvUpdaterCsvHelper.esc(venueCity) + ",,");
                System.out.printf("  [VENUE+] %s (%s)%n", venueName, venueCity);
            }
        }

        return teamIds;
    }

    /**
     * 팀 스쿼드를 조회해 신규 선수를 players.csv 행으로 수집한다.
     * /players/profiles 호출로 name_long, nationality 보완. 실패 시 squads 데이터로 fallback.
     */
    private static void collectPlayersForTeam(
            ApiFootballClient apiClient, long teamId,
            Set<Long> existingPlayerIds, List<String> newPlayerRows) throws InterruptedException {

        JsonNode squadResp = apiClient.getPlayerSquad(teamId);
        if (squadResp == null || !squadResp.isArray() || squadResp.isEmpty()) return;

        for (JsonNode player : squadResp.get(0).path("players")) {
            long playerId = player.path("id").asLong();
            if (existingPlayerIds.contains(playerId)) continue;
            existingPlayerIds.add(playerId);

            Thread.sleep(REQUEST_DELAY_MS);
            JsonNode profileResp = apiClient.getPlayerProfile(playerId);

            if (profileResp != null && profileResp.isArray() && !profileResp.isEmpty()) {
                JsonNode p         = profileResp.get(0).path("player");
                String nameShort   = p.path("name").asText("");
                String position    = p.path("position").asText("");
                String nationality = p.path("nationality").asText("");
                String nameLong    = buildPlayerLongName(
                        p.path("firstname").asText(""),
                        p.path("lastname").asText(""),
                        nationality
                );
                // columns: player_id, name_short, name_long, position, nationality, name_ko_long(수동), name_ko_short(수동)
                newPlayerRows.add(playerId + "," + CsvUpdaterCsvHelper.esc(nameShort)
                        + "," + CsvUpdaterCsvHelper.esc(nameLong)
                        + "," + CsvUpdaterCsvHelper.esc(position)
                        + "," + CsvUpdaterCsvHelper.esc(nationality) + ",,");
                System.out.printf("  [PLAYER+] %d %s / %s%n", playerId, nameShort, nationality);
            } else {
                // profiles 실패 시 squads 데이터로 fallback
                String name     = player.path("name").asText("");
                String position = player.path("position").asText("");
                newPlayerRows.add(playerId + "," + CsvUpdaterCsvHelper.esc(name)
                        + "," + CsvUpdaterCsvHelper.esc(name)
                        + "," + CsvUpdaterCsvHelper.esc(position) + ",,,");
                System.out.printf("  [PLAYER+] %d %s (profiles 실패, fallback)%n", playerId, name);
            }
        }
    }

    /**
     * 팀 감독을 조회해 신규 감독, 코치를 coaches.csv 행으로 수집한다.
     */
    private static void collectCoachForTeam(
            ApiFootballClient apiClient, long teamId,
            Set<Long> existingCoachIds, List<String> newCoachRows) {

        JsonNode coachResp = apiClient.getCoach(teamId);
        if (coachResp == null || !coachResp.isArray()) return;

        for (JsonNode coach : coachResp) {
            long coachId = coach.path("id").asLong();
            if (existingCoachIds.contains(coachId)) continue;
            existingCoachIds.add(coachId);

            String nameShort   = coach.path("name").asText("");
            String nameLong    = (coach.path("firstname").asText("") + " " + coach.path("lastname").asText("")).trim();
            String nationality = coach.path("nationality").asText("");
            // columns: coach_id, name_short, name_long, nationality, name_ko_long(수동), name_ko_short(수동)
            newCoachRows.add(coachId + "," + CsvUpdaterCsvHelper.esc(nameShort)
                    + "," + CsvUpdaterCsvHelper.esc(nameLong)
                    + "," + CsvUpdaterCsvHelper.esc(nationality) + ",,");
            System.out.printf("  [COACH+] %d %s%n", coachId, nameLong);
        }
    }

    /**
     * 선수 full name 저장 규칙.
     * 동아시아권 및 헝가리권 표기 선수는 성-이름 순서를 유지하고, 그 외는 이름-성 순서를 사용한다.
     */
    private static String buildPlayerLongName(String firstName, String lastName, String nationality) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();

        if (first.isEmpty()) return last;
        if (last.isEmpty()) return first;

        if (isFamilyNameFirstNationality(nationality)) {
            return last + " " + first;
        }
        return first + " " + last;
    }

    private static boolean isFamilyNameFirstNationality(String nationality) {
        if (nationality == null) return false;
        String normalized = nationality.trim();
        return normalized.equalsIgnoreCase("Japan")
                || normalized.equalsIgnoreCase("Korea Republic")
                || normalized.equalsIgnoreCase("Korea DPR")
                || normalized.equalsIgnoreCase("South Korea")
                || normalized.equalsIgnoreCase("North Korea")
                || normalized.equalsIgnoreCase("Hungary")
                || normalized.equalsIgnoreCase("China")
                || normalized.equalsIgnoreCase("China PR")
                || normalized.equalsIgnoreCase("PR China")
                || normalized.equalsIgnoreCase("Taiwan")
                || normalized.equalsIgnoreCase("Chinese Taipei")
                || normalized.equalsIgnoreCase("Hong Kong")
                || normalized.equalsIgnoreCase("Macao")
                || normalized.equalsIgnoreCase("Vietnam")
                || normalized.equalsIgnoreCase("Viet Nam");
    }

    private static String[] findPlayerRow(List<String[]> rows, long playerId) {
        String target = String.valueOf(playerId);
        for (String[] row : rows) {
            if (row.length > 0 && row[0].trim().equals(target)) {
                return row;
            }
        }
        return null;
    }

    private static String getColumn(String[] row, int index) {
        if (row == null || row.length <= index || row[index] == null) return "";
        return row[index].trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
