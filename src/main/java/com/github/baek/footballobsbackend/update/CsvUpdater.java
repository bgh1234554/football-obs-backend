package com.github.baek.footballobsbackend.update;

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

    // ANSI 색상 — IntelliJ 터미널에서 색상 표시됨
    private static final String ANSI_RESET   = "\u001B[0m";
    private static final String ANSI_BOLD    = "\u001B[1m";
    private static final String ANSI_YELLOW  = "\u001B[33m";
    private static final String ANSI_CYAN    = "\u001B[36m";
    private static final String ANSI_MAGENTA = "\u001B[35m"; // DIFF 로그 통일 색상
    private static final String ANSI_PINK    = "\u001B[95m"; // nationality diff 전용

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
                } else if (selection.mode() == CsvUpdaterUi.Mode.COACHES) {
                    processSelectedCoaches(apiClient, selection.coachIds());
                } else if (selection.mode() == CsvUpdaterUi.Mode.TEAM) {
                    for (long teamId : selection.teamIds()) {
                        processTeamOnly(apiClient, teamId);
                    }
                } else if (selection.mode() == CsvUpdaterUi.Mode.TEAM_NAMES) {
                    processTeamNamesOnly(apiClient, selection, sc);
                } else {
                    processAll(apiClient, selection, sc);
                }
                Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
                System.out.printf(ANSI_YELLOW + ANSI_BOLD + "%n  작업 완료까지 %d분 %d초 %d밀리초가 소요되었습니다.%n" + ANSI_RESET,
                        duration.toMinutesPart(), duration.toSecondsPart(), duration.toMillisPart());
                System.out.println(ANSI_CYAN + "  메인 화면으로 돌아갑니다." + ANSI_RESET);
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
    private static void processAll(ApiFootballClient apiClient, CsvUpdaterUi.SelectionResult selection, Scanner sc)
            throws Exception {

        // 기존 CSV에서 이미 등록된 항목 로드 (중복 추가 방지)
        Set<Long>   existingTeamIds      = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/teams.csv");
        Set<Long>   existingPlayerIds    = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/players.csv");
        Set<Long>   existingCoachIds     = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/coaches.csv");
        Set<String> existingVenueNames   = CsvUpdaterCsvHelper.loadVenueNames(DATA_DIR + "/venues.csv");
        Set<Long>   existingLeagueIds    = CsvUpdaterCsvHelper.loadLongIds(DATA_DIR + "/leagues.csv");

        // diff 비교용 맵 (기존 CSV 이름 vs API 이름)
        // teams.csv: col 1 = team_name (splitLimit 4)
        // coaches.csv: col 1 = name_short (splitLimit 6)
        // venues.csv: key=col 1(venue_name), value=col 2(venue_city) (splitLimit 5)
        Map<Long, String>   existingTeamNameMap   = CsvUpdaterCsvHelper.loadIdToColumn(DATA_DIR + "/teams.csv",   1, 4);
        Map<Long, String>   existingCoachNameMap  = CsvUpdaterCsvHelper.loadIdToColumn(DATA_DIR + "/coaches.csv", 1, 6);
        Map<String, String> existingVenueCityMap  = CsvUpdaterCsvHelper.loadKeyToColumn(DATA_DIR + "/venues.csv", 1, 2, 5);

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

            boolean cont = processLeague(apiClient, leagueId, selection.season(), sc,
                    existingTeamIds, existingPlayerIds, existingCoachIds, existingVenueNames,
                    existingTeamNameMap, existingCoachNameMap, existingVenueCityMap,
                    newTeamRows, newPlayerRows, newCoachRows, newVenueRows);
            if (!cont) {
                System.out.println(ANSI_CYAN + "  메인 화면으로 돌아갑니다." + ANSI_RESET);
                return; // 저장하지 않고 메인 메뉴로 복귀
            }
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
     * 특정 선수만 재조회해 players.csv를 upsert한다.
     * - 이미 있는 player_id: 해당 행을 그 자리에서 교체 (한글 이름 컬럼 보존)
     * - 없는 player_id: 새 행으로 추가
     * 중복 행이 생기지 않는다.
     */
    private static void processSelectedPlayers(ApiFootballClient apiClient, List<Long> playerIds)
            throws Exception {

        CsvUpdaterCsvHelper.CsvTable table =
                CsvUpdaterCsvHelper.loadCsvTable(DATA_DIR + "/players.csv", PLAYER_COLUMN_COUNT);

        // 기존 행을 인덱스로 빠르게 찾기 위한 맵 (player_id → 행 인덱스)
        Map<Long, Integer> idToIndex = new HashMap<>();
        List<String[]> rows = new ArrayList<>(table.rows());
        for (int i = 0; i < rows.size(); i++) {
            try { idToIndex.put(Long.parseLong(rows.get(i)[0].trim()), i); }
            catch (NumberFormatException ignored) {}
        }

        int updatedCount = 0;
        int insertedCount = 0;

        for (long playerId : playerIds) {
            Thread.sleep(REQUEST_DELAY_MS);

            JsonNode profileResp = apiClient.getPlayerProfile(playerId);
            if (profileResp == null || !profileResp.isArray() || profileResp.isEmpty()) {
                System.out.printf("  [PLAYER!] %d profiles 응답 없음, 건너뜀%n", playerId);
                continue;
            }

            JsonNode p           = profileResp.get(0).path("player");
            Integer existingIdx  = idToIndex.get(playerId);
            String[] existingRow = existingIdx != null ? rows.get(existingIdx) : null;

            String apiNameShort = p.path("name").asText("");
            String firstName    = p.path("firstname").asText("");
            String lastName     = p.path("lastname").asText("");
            String apiNationality = p.path("nationality").asText("");
            String csvNationality = getColumn(existingRow, 4);
            if (existingRow != null && !csvNationality.isBlank() && !apiNationality.isBlank()
                    && !csvNationality.equals(apiNationality)) {
                System.out.printf(ANSI_PINK + ANSI_BOLD
                        + "  [NAT_DIFF] %d  csv=%s  api=%s%n" + ANSI_RESET,
                        playerId, csvNationality, apiNationality);
            }
            String nationality  = firstNonBlank(apiNationality, csvNationality);
            String csvNameShort = getColumn(existingRow, 1);
            String nameShort;
            if (existingRow != null && !csvNameShort.isBlank()) {
                // 기존 값이 있으면 유지 — 수동 수정값(한국 선수 표기 등) 보존
                // API 값과 다를 때만 참고용 로그 출력
                if (!csvNameShort.equals(apiNameShort) && !apiNameShort.isBlank()) {
                    System.out.printf(ANSI_MAGENTA+ANSI_BOLD+"  [NAME_DIFF] %d  csv=%s  api=%s%n"+ANSI_RESET, playerId, csvNameShort, apiNameShort);
                }
                nameShort = csvNameShort;
            } else {
                // 기존 값이 없으면 국적 기반 약식으로 변환해서 채움
                nameShort = buildNameShortAbbrev(apiNameShort, firstName, lastName, nationality);
                if (!nameShort.isBlank()) {
                    System.out.printf(ANSI_YELLOW+ANSI_BOLD+"  [NAME_FILL] %d name_short 채움: %s%n"+ANSI_RESET, playerId, nameShort);
                }
            }

            // position, nameLong은 항상 API 값으로 upsert
            String position = firstNonBlank(p.path("position").asText(""), getColumn(existingRow, 3));
            String nameLong = buildPlayerLongName(firstName, lastName, nationality);
            nameLong = firstNonBlank(nameLong, nameShort, getColumn(existingRow, 2));

            // 한글 이름 컬럼은 기존 값을 그대로 보존
            String nameKoLong  = getColumn(existingRow, 5);
            String nameKoShort = getColumn(existingRow, 6);
            String[] newRow = {
                    String.valueOf(playerId),
                    nameShort, nameLong, position, nationality, nameKoLong, nameKoShort
            };

            if (existingIdx != null) {
                rows.set(existingIdx, newRow);
                updatedCount++;
                System.out.printf("  [PLAYER~] %d %s / %s (updated)%n", playerId, nameShort, nationality);
            } else {
                rows.add(newRow);
                insertedCount++;
                System.out.printf("  [PLAYER+] %d %s / %s%n", playerId, nameShort, nationality);
            }
        }

        CsvUpdaterCsvHelper.overwriteCsv(DATA_DIR + "/players.csv", table.header(), rows);
        System.out.printf("%n[CsvUpdater] 특정 선수 갱신 완료 — updated:%d inserted:%d%n", updatedCount, insertedCount);
    }

    /**
     * 특정 감독만 재조회해 coaches.csv를 upsert한다.
     * - 이미 있는 coach_id: 해당 행을 그 자리에서 교체 (한글 이름 컬럼 보존)
     * - 없는 coach_id: 새 행으로 추가
     */
    private static void processSelectedCoaches(ApiFootballClient apiClient, List<Long> coachIds)
            throws Exception {

        final int COACH_COLUMN_COUNT = 6; // coach_id, name_short, name_long, nationality, name_ko_long, name_ko_short
        CsvUpdaterCsvHelper.CsvTable table =
                CsvUpdaterCsvHelper.loadCsvTable(DATA_DIR + "/coaches.csv", COACH_COLUMN_COUNT);

        // coach_id → 행 인덱스 맵
        Map<Long, Integer> idToIndex = new HashMap<>();
        List<String[]> rows = new ArrayList<>(table.rows());
        for (int i = 0; i < rows.size(); i++) {
            try { idToIndex.put(Long.parseLong(rows.get(i)[0].trim()), i); }
            catch (NumberFormatException ignored) {}
        }

        int updatedCount = 0;
        int insertedCount = 0;

        for (long coachId : coachIds) {
            Thread.sleep(REQUEST_DELAY_MS);

            JsonNode coachResp = apiClient.getCoachById(coachId);
            if (coachResp == null || !coachResp.isArray() || coachResp.isEmpty()) {
                System.out.printf("  [COACH!] %d 응답 없음, 건너뜀%n", coachId);
                continue;
            }

            JsonNode c = coachResp.get(0);
            Integer existingIdx = idToIndex.get(coachId);
            String[] existingRow = existingIdx != null ? rows.get(existingIdx) : null;

            String apiNameShort = c.path("name").asText("");
            String csvNameShort = getColumn(existingRow, 1);
            String nameShort;
            if (existingRow != null && !csvNameShort.isBlank()) {
                // 기존 값 유지 — API 값과 다르면 참고용 로그
                if (!csvNameShort.equals(apiNameShort) && !apiNameShort.isBlank()) {
                    System.out.printf(ANSI_MAGENTA + ANSI_BOLD
                            + "  [COACH_NAME_DIFF] %d  csv=%s  api=%s%n" + ANSI_RESET,
                            coachId, csvNameShort, apiNameShort);
                }
                nameShort = csvNameShort;
            } else {
                nameShort = apiNameShort;
            }

            // nationality, nameLong은 항상 API 값으로 upsert
            String nationality = firstNonBlank(c.path("nationality").asText(""), getColumn(existingRow, 3));
            String nameLong = firstNonBlank(
                    (c.path("firstname").asText("") + " " + c.path("lastname").asText("")).trim(),
                    getColumn(existingRow, 2)
            );

            // 한글 이름 컬럼은 기존 값을 그대로 보존
            String nameKoLong  = getColumn(existingRow, 4);
            String nameKoShort = getColumn(existingRow, 5);
            String[] newRow = {
                    String.valueOf(coachId),
                    nameShort, nameLong, nationality, nameKoLong, nameKoShort
            };

            if (existingIdx != null) {
                rows.set(existingIdx, newRow);
                updatedCount++;
                System.out.printf("  [COACH~] %d %s / %s (updated)%n", coachId, nameShort, nationality);
            } else {
                rows.add(newRow);
                insertedCount++;
                System.out.printf("  [COACH+] %d %s / %s%n", coachId, nameShort, nationality);
            }
        }

        CsvUpdaterCsvHelper.overwriteCsv(DATA_DIR + "/coaches.csv", table.header(), rows);
        System.out.printf("%n[CsvUpdater] 특정 감독 갱신 완료 — updated:%d inserted:%d%n", updatedCount, insertedCount);
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

        Map<Long, String>   existingTeamNameMap  = CsvUpdaterCsvHelper.loadIdToColumn(DATA_DIR + "/teams.csv",   1, 4);
        Map<Long, String>   existingCoachNameMap = CsvUpdaterCsvHelper.loadIdToColumn(DATA_DIR + "/coaches.csv", 1, 6);
        Map<String, String> existingVenueCityMap = CsvUpdaterCsvHelper.loadKeyToColumn(DATA_DIR + "/venues.csv", 1, 2, 5);

        List<String> newTeamRows   = new ArrayList<>();
        List<String> newVenueRows  = new ArrayList<>();
        List<String> newPlayerRows = new ArrayList<>();
        List<String> newCoachRows  = new ArrayList<>();

        // 1. 팀 정보 + 홈구장 수집 (/teams?id=X)
        JsonNode teamResp = apiClient.getTeam(teamId);
        if (teamResp != null && teamResp.isArray() && !teamResp.isEmpty()) {
            collectTeamsAndVenues(teamResp, existingTeamIds, existingVenueNames,
                    existingTeamNameMap, existingVenueCityMap, newTeamRows, newVenueRows);
        } else {
            System.out.printf("  [TEAM!] %d 팀 정보 응답 없음%n", teamId);
        }

        // 2. 선수 스쿼드 + 프로필 수집
        Thread.sleep(REQUEST_DELAY_MS);
        collectPlayersForTeam(apiClient, teamId, existingPlayerIds, newPlayerRows);

        // 3. 감독 수집
        Thread.sleep(REQUEST_DELAY_MS);
        collectCoachForTeam(apiClient, teamId, existingCoachIds, existingCoachNameMap, newCoachRows);

        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/teams.csv",   newTeamRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/venues.csv",  newVenueRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/players.csv", newPlayerRows);
        CsvUpdaterCsvHelper.appendRows(DATA_DIR + "/coaches.csv", newCoachRows);

        System.out.printf("%n[CsvUpdater] Team %d 완료 — teams:%d  venues:%d  players:%d  coaches:%d%n",
                teamId, newTeamRows.size(), newVenueRows.size(), newPlayerRows.size(), newCoachRows.size());
    }

    /**
     * 선택된 리그의 팀 이름(team_name)만 teams.csv에 upsert한다.
     * - 이미 있는 team_id: team_name을 API 값으로 교체, ko_name 보존, 변경 시 [TEAM_DIFF] 로그
     * - 없는 team_id: 신규 행 추가 (ko_name 빈 값)
     * - teams.csv만 건드리며 players/coaches/venues는 변경하지 않음
     */
    private static void processTeamNamesOnly(ApiFootballClient apiClient, CsvUpdaterUi.SelectionResult selection, Scanner sc)
            throws Exception {

        // teams.csv를 통째로 로딩해 upsert 준비
        final int TEAM_COLUMN_COUNT = 4; // team_id, team_name, ko_name, ko_name_short
        CsvUpdaterCsvHelper.CsvTable table =
                CsvUpdaterCsvHelper.loadCsvTable(DATA_DIR + "/teams.csv", TEAM_COLUMN_COUNT);

        // team_id → 행 인덱스 맵
        Map<Long, Integer> idToIndex = new HashMap<>();
        List<String[]> rows = new ArrayList<>(table.rows());
        for (int i = 0; i < rows.size(); i++) {
            try { idToIndex.put(Long.parseLong(rows.get(i)[0].trim()), i); }
            catch (NumberFormatException ignored) {}
        }

        int insertedCount = 0;

        for (int leagueId : selection.leagueIds()) {
            int season = selection.leagueSeasonMap().getOrDefault(leagueId, CsvUpdaterUi.FALL_SEASON);
            System.out.printf("%n[CsvUpdater] League %d (season %d) 팀 이름 조회 중...%n", leagueId, season);

            JsonNode teamsResp = apiClient.getTeams(leagueId, season);
            if (teamsResp == null || !teamsResp.isArray()) {
                System.out.printf("  응답 없음, 건너뜀%n");
                continue;
            }

            // 팀이 없으면 연도를 1씩 줄여가며 팀이 있는 시즌 탐색 (국제대회 대응)
            if (teamsResp.isEmpty()) {
                System.out.printf("  season %d 에 등록된 팀 없음, 이전 시즌 탐색 중...%n", season);

                final int MAX_FALLBACK_YEARS = 10;
                JsonNode foundResp = null;
                int foundSeason = -1;

                for (int i = 1; i <= MAX_FALLBACK_YEARS; i++) {
                    int trySeason = season - i;
                    Thread.sleep(REQUEST_DELAY_MS);
                    JsonNode resp = apiClient.getTeams(leagueId, trySeason);
                    if (resp != null && resp.isArray() && !resp.isEmpty()) {
                        foundResp = resp;
                        foundSeason = trySeason;
                        break;
                    }
                }

                if (foundResp == null) {
                    System.out.printf("  최근 %d년 내에 팀 데이터 없음, 건너뜀%n", MAX_FALLBACK_YEARS);
                    continue;
                }

                System.out.println();
                System.out.printf(ANSI_YELLOW + ANSI_BOLD + "  ⚠  season %d 에서 팀 %d개를 찾았습니다.%n" + ANSI_RESET, foundSeason, foundResp.size());
                System.out.print(ANSI_YELLOW + "     이 시즌의 팀을 사용하시겠습니까? (y/n) > " + ANSI_RESET);

                String answer = sc.nextLine().trim();
                System.out.println();
                if (!answer.equalsIgnoreCase("y")) {
                    System.out.println(ANSI_CYAN + "  메인 화면으로 돌아갑니다." + ANSI_RESET);
                    return; // 저장하지 않고 메인 메뉴로 복귀
                }

                teamsResp = foundResp;
            }

            for (JsonNode entry : teamsResp) {
                JsonNode team   = entry.path("team");
                long teamId     = team.path("id").asLong();
                String apiName  = team.path("name").asText("");
                Integer existingIdx = idToIndex.get(teamId);

                if (existingIdx != null) {
                    String csvName = rows.get(existingIdx)[1].trim();
                    if (!csvName.equals(apiName) && !apiName.isBlank()) {
                        System.out.printf(ANSI_MAGENTA + ANSI_BOLD
                                + "  [TEAM_DIFF] %d  csv=%s  api=%s%n" + ANSI_RESET, teamId, csvName, apiName);
                    }
                } else {
                    // 신규 팀
                    rows.add(new String[]{String.valueOf(teamId), apiName, "", ""});
                    idToIndex.put(teamId, rows.size() - 1);
                    insertedCount++;
                    System.out.printf("  [TEAM+] %d %s%n", teamId, apiName);
                }
            }
        }

        CsvUpdaterCsvHelper.overwriteCsv(DATA_DIR + "/teams.csv", table.header(), rows);
        System.out.printf("%n[CsvUpdater] 팀 이름 조회 완료 — inserted:%d%n", insertedCount);
    }

    // ──────────────────────────────────────────────
    // 리그 단위 처리
    // ──────────────────────────────────────────────

    /**
     * 리그 한 개를 처리한다.
     * 1) 리그 소속 팀 + 홈구장 수집
     * 2) 팀별 선수 스쿼드 + 감독 수집
     */
    /**
     * @return false 이면 사용자가 n을 눌러 전체 작업을 중단한다는 뜻 — processAll()이 즉시 반환한다.
     */
    private static boolean processLeague(
            ApiFootballClient apiClient, int leagueId, int season, Scanner sc,
            Set<Long> existingTeamIds, Set<Long> existingPlayerIds, Set<Long> existingCoachIds,
            Set<String> existingVenueNames,
            Map<Long, String> existingTeamNameMap, Map<Long, String> existingCoachNameMap,
            Map<String, String> existingVenueCityMap,
            List<String> newTeamRows, List<String> newPlayerRows,
            List<String> newCoachRows, List<String> newVenueRows) throws InterruptedException {

        System.out.printf("%n[CsvUpdater] League %d 처리 중...%n", leagueId);

        // 1. 리그 소속 팀 + 홈구장 조회
        JsonNode teamsResp = apiClient.getTeams(leagueId, season);
        if (teamsResp == null || !teamsResp.isArray()) {
            System.out.printf("[CsvUpdater] League %d 응답 없음, 건너뜀%n", leagueId);
            return true; // 이 리그만 건너뜀, 다음 리그 계속
        }

        // 팀이 없으면 연도를 1씩 줄여가며 팀이 있는 시즌 탐색 (국제대회 대응)
        if (teamsResp.isEmpty()) {
            System.out.printf("[CsvUpdater] League %d season %d 에 등록된 팀 없음, 이전 시즌 탐색 중...%n", leagueId, season);

            final int MAX_FALLBACK_YEARS = 10;
            JsonNode foundResp = null;
            int foundSeason = -1;

            for (int i = 1; i <= MAX_FALLBACK_YEARS; i++) {
                int trySeason = season - i;
                Thread.sleep(REQUEST_DELAY_MS);
                JsonNode resp = apiClient.getTeams(leagueId, trySeason);
                if (resp != null && resp.isArray() && !resp.isEmpty()) {
                    foundResp = resp;
                    foundSeason = trySeason;
                    break;
                }
            }

            if (foundResp == null) {
                System.out.printf("[CsvUpdater] League %d 최근 %d년 내에 팀 데이터 없음, 건너뜀%n", leagueId, MAX_FALLBACK_YEARS);
                return true; // 이 리그만 건너뜀, 다음 리그 계속
            }

            System.out.println();
            System.out.printf(ANSI_YELLOW + ANSI_BOLD + "  ⚠  season %d 에서 팀 %d개를 찾았습니다.%n" + ANSI_RESET, foundSeason, foundResp.size());
            System.out.print(ANSI_YELLOW + "     이 시즌의 팀을 사용하시겠습니까? (y/n) > " + ANSI_RESET);

            String answer = sc.nextLine().trim();
            System.out.println();
            if (!answer.equalsIgnoreCase("y")) {
                return false; // 전체 작업 중단 → processAll이 메인 메뉴로 복귀
            }

            teamsResp = foundResp;
        }

        List<Long> teamIds = collectTeamsAndVenues(
                teamsResp, existingTeamIds, existingVenueNames,
                existingTeamNameMap, existingVenueCityMap, newTeamRows, newVenueRows);

        // 2. 팀별 선수 + 감독 조회
        for (long teamId : teamIds) {
            Thread.sleep(REQUEST_DELAY_MS);
            collectPlayersForTeam(apiClient, teamId, existingPlayerIds, newPlayerRows);
            Thread.sleep(REQUEST_DELAY_MS);
            collectCoachForTeam(apiClient, teamId, existingCoachIds, existingCoachNameMap, newCoachRows);
        }
        return true;
    }

    /**
     * /teams 응답에서 팀과 홈구장을 수집한다. 신규 항목만 추가.
     * @return 해당 리그의 팀 ID 목록 (선수/감독 조회에 사용)
     */
    private static List<Long> collectTeamsAndVenues(
            JsonNode teamsResp,
            Set<Long> existingTeamIds, Set<String> existingVenueNames,
            Map<Long, String> existingTeamNameMap, Map<String, String> existingVenueCityMap,
            List<String> newTeamRows, List<String> newVenueRows) {

        List<Long> teamIds = new ArrayList<>();

        for (JsonNode entry : teamsResp) {
            // 팀
            JsonNode team   = entry.path("team");
            long teamId     = team.path("id").asLong();
            String teamName = team.path("name").asText("");

            if (!existingTeamIds.contains(teamId)) {
                existingTeamIds.add(teamId);
                // columns: team_id, team_name, ko_name(수동), ko_name_short(수동)
                newTeamRows.add(teamId + "," + CsvUpdaterCsvHelper.esc(teamName) + ",,");
                System.out.printf("  [TEAM+] %d %s%n", teamId, teamName);
            } else {
                // 이미 있는 팀 — 이름이 다르면 diff 로그
                String csvName = existingTeamNameMap.get(teamId);
                if (csvName != null && !csvName.equals(teamName) && !teamName.isBlank()) {
                    System.out.printf(ANSI_MAGENTA + ANSI_BOLD
                            + "  [TEAM_DIFF] %d  csv=%s  api=%s%n" + ANSI_RESET,
                            teamId, csvName, teamName);
                }
            }
            teamIds.add(teamId);

            // 홈구장 — 이름 기준 중복 체크
            JsonNode venue   = entry.path("venue");
            String venueName = venue.path("name").asText(null);
            if (venueName == null) continue;

            String venueKey = venueName.toLowerCase();
            if (!existingVenueNames.contains(venueKey)) {
                existingVenueNames.add(venueKey);
                JsonNode venueIdNode = venue.path("id");
                String venueIdStr    = (venueIdNode.isNull() || venueIdNode.isMissingNode())
                        ? "" : String.valueOf(venueIdNode.asLong());
                String venueCity = venue.path("city").asText("");
                // columns: venue_id, venue_name, venue_city, venue_name_ko(수동), city_name_ko(수동)
                newVenueRows.add(venueIdStr + "," + CsvUpdaterCsvHelper.esc(venueName)
                        + "," + CsvUpdaterCsvHelper.esc(venueCity) + ",,");
                System.out.printf("  [VENUE+] %s (%s)%n", venueName, venueCity);
            } else {
                // 이미 있는 구장 — 도시가 다르면 diff 로그
                String csvCity  = existingVenueCityMap.get(venueKey);
                String apiCity  = venue.path("city").asText("");
                if (csvCity != null && !csvCity.equals(apiCity) && !apiCity.isBlank()) {
                    System.out.printf(ANSI_MAGENTA + ANSI_BOLD
                            + "  [VENUE_DIFF] %s  csv_city=%s  api_city=%s%n" + ANSI_RESET,
                            venueName, csvCity, apiCity);
                }
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
                String firstName   = p.path("firstname").asText("");
                String lastName    = p.path("lastname").asText("");
                String nationality = p.path("nationality").asText("");
                String nameShort   = buildNameShortAbbrev(p.path("name").asText(""), firstName, lastName, nationality);
                String position    = p.path("position").asText("");
                String nameLong    = buildPlayerLongName(firstName, lastName, nationality);
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
            Set<Long> existingCoachIds, Map<Long, String> existingCoachNameMap,
            List<String> newCoachRows) {

        JsonNode coachResp = apiClient.getCoach(teamId);
        if (coachResp == null || !coachResp.isArray()) return;

        for (JsonNode coach : coachResp) {
            long coachId     = coach.path("id").asLong();
            String nameShort = coach.path("name").asText("");
            String nameLong  = (coach.path("firstname").asText("") + " " + coach.path("lastname").asText("")).trim();

            if (existingCoachIds.contains(coachId)) {
                // 이미 있는 감독 — name_short가 다르면 diff 로그
                String csvName = existingCoachNameMap.get(coachId);
                if (csvName != null && !csvName.equals(nameShort) && !nameShort.isBlank()) {
                    System.out.printf(ANSI_MAGENTA + ANSI_BOLD
                            + "  [COACH_DIFF] %d  csv=%s  api=%s%n" + ANSI_RESET,
                            coachId, csvName, nameShort);
                }
                continue;
            }
            existingCoachIds.add(coachId);

            String nationality = coach.path("nationality").asText("");
            // columns: coach_id, name_short, name_long, nationality, name_ko_long(수동), name_ko_short(수동)
            newCoachRows.add(coachId + "," + CsvUpdaterCsvHelper.esc(nameShort)
                    + "," + CsvUpdaterCsvHelper.esc(nameLong)
                    + "," + CsvUpdaterCsvHelper.esc(nationality) + ",,");
            System.out.printf("  [COACH+] %d %s%n", coachId, nameLong);
        }
    }

    /**
     * API Football name 필드를 name_short 규칙에 맞게 약식으로 변환한다.
     * - 이미 "." 포함: 그대로 반환 (이미 약식)
     * - 단일 단어(닉네임): 그대로 반환
     * - 한국: Lastname H.M. (하이픈 분리 이니셜)
     * - 중국/대만/홍콩/마카오: Lastname L.
     * - 베트남: Ng. Quang Hai (성 앞 2글자 이니셜 + 이름 전체)
     * - 기타(서양, 일본, 헝가리 포함): F. Lastname
     */
    private static String buildNameShortAbbrev(String apiName, String firstName, String lastName, String nationality) {
        if (apiName == null || apiName.isBlank()) return apiName == null ? "" : apiName;
        if (apiName.contains(".")) return apiName;   // already abbreviated
        if (!apiName.contains(" ")) return apiName;  // single word (nickname)

        String fn  = firstName  != null ? firstName.trim()  : "";
        String ln  = lastName   != null ? lastName.trim()   : "";
        String nat = nationality != null ? nationality.trim().toLowerCase() : "";

        // firstname/lastname 없으면 apiName에서 split
        if (fn.isEmpty() || ln.isEmpty()) {
            String[] parts = apiName.split("\\s+", 2);
            if (fn.isEmpty()) fn = parts[0];
            if (ln.isEmpty() && parts.length > 1) ln = parts[1];
        }
        if (ln.isEmpty()) return apiName;

        // 한국: Son H.M.
        if (nat.equals("south korea") || nat.equals("korea republic") || nat.equals("korea dpr") || nat.equals("north korea")) {
            String initials = buildHyphenatedInitials(fn);
            return ln + (initials.isEmpty() ? "" : " " + initials);
        }

        // 중국권: Wu L.
        if (nat.equals("china") || nat.equals("china pr") || nat.equals("pr china")
                || nat.equals("taiwan") || nat.equals("chinese taipei")
                || nat.equals("hong kong") || nat.equals("macao")) {
            if (fn.isEmpty()) return apiName;
            return ln + " " + Character.toUpperCase(fn.charAt(0)) + ".";
        }

        // 베트남: Ng. Quang Hai
        if (nat.equals("vietnam") || nat.equals("viet nam")) {
            if (fn.isEmpty()) return apiName;
            // 성 앞 2글자가 자음이면 2글자 이니셜 (Ng., Tr., Ph. 등)
            String surnameInitial;
            if (ln.length() >= 2 && Character.isLetter(ln.charAt(1)) && "aeiouAEIOU".indexOf(ln.charAt(1)) < 0) {
                surnameInitial = ln.substring(0, 2) + ".";
            } else {
                surnameInitial = ln.charAt(0) + ".";
            }
            return surnameInitial + " " + fn;
        }

        // 기타(서양, 일본, 헝가리 등): F. Lastname
        return Character.toUpperCase(fn.charAt(0)) + ". " + ln;
    }

    /**
     * 하이픈 또는 공백으로 구분된 이름에서 각 파트의 첫 글자를 이니셜로 조합한다.
     * 예: "Heung-Min" → "H.M.", "Ji-Sung" → "J.S.", "Jeong" → "J."
     */
    private static String buildHyphenatedInitials(String name) {
        if (name == null || name.isBlank()) return "";
        String[] parts = name.split("[-\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append('.');
        }
        return sb.toString();
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

    private static final Set<String> FAMILY_NAME_FIRST_NATIONALITIES = Set.of(
            "japan",
            "korea republic", "korea dpr", "south korea", "north korea",
            "hungary",
            "china", "china pr", "pr china", "taiwan", "chinese taipei", "hong kong", "macao",
            "vietnam", "viet nam"
    );

    private static boolean isFamilyNameFirstNationality(String nationality) {
        if (nationality == null) return false;
        return FAMILY_NAME_FIRST_NATIONALITIES.contains(nationality.trim().toLowerCase());
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
