package com.github.baek.footballobsbackend.util;

import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * CsvUpdater 텍스트 UI.
 * 업데이트 대상 리그/시즌을 콘솔에서 선택받아 SelectionResult로 반환한다.
 *
 * [리그/대회 추가 방법]
 * LEAGUES 또는 INT_COMPETITIONS 리스트에 한 줄만 추가하면 된다.
 *   new LeagueEntry(ID, "이름", true/false)
 *   true  = 기본 목록 포함 (0 입력 시 자동 선택)
 *   false = 목록에는 보이지만 0 입력 시 제외 (커스텀 선택 전용)
 */
class CsvUpdaterUi {

    // ANSI 색상 코드
    private static final String ANSI_RESET  = "\u001B[0m";
    private static final String ANSI_BOLD   = "\u001B[1m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RED    = "\u001B[31m";
    private static final String ANSI_CYAN   = "\u001B[36m";

    /** 리그/대회 항목 하나. enabled=true 이면 '0 전체 실행'에 포함된다. */
    record LeagueEntry(int id, String name, boolean enabled) {}
    enum Mode { LEAGUE, PLAYERS, TEAM }

    // ── 추춘제 대회 ────────────────────────────────
    static final int FALL_SEASON = 2025;

    static final List<LeagueEntry> FALL_LEAGUES = List.of(
            new LeagueEntry(39,   "Premier League",                         true),
            new LeagueEntry(40,   "EFL Championship",                       true),
            new LeagueEntry(135,  "Serie A",                                true),
            new LeagueEntry(140,  "La Liga",                                true),
            new LeagueEntry(78,   "Bundesliga",                             true),
            new LeagueEntry(61,   "Ligue 1",                                true),
            new LeagueEntry(94,   "Liga Portugal (Primeira Liga)",          true),
            new LeagueEntry(88,   "Eredivisie",                             true),
            new LeagueEntry(144,  "Belgian Pro League (Jupiler Pro League)", true),
            new LeagueEntry(203,  "Turkish Super Lig",                      true),
            new LeagueEntry(235,  "Russian Premier League",                 true),
            new LeagueEntry(237,  "Russian Football National League",       true),
            new LeagueEntry(2,    "UEFA Champions League",                  true),
            new LeagueEntry(3,    "UEFA Europa League",                     true),
            new LeagueEntry(848,  "UEFA Europa Conference League",          true),
            new LeagueEntry(273,  "AFC Champions League Elite",             true)
//            new LeagueEntry(18,   "AFC Champions League Two",               true),
//            new LeagueEntry(1132, "AFC Challenge League",                   true),
//            new LeagueEntry(12,   "CAF Champions League",                   true),
//            new LeagueEntry(20,   "CAF Confederations Cup",                 true),
    );

    // ── 춘추제 대회 ─────────────────────────────────────────────
    static final int SPRING_SEASON = 2026;

    static final List<LeagueEntry> SPRING_LEAGUES = List.of(
            new LeagueEntry(292,  "K League 1",                            true),
            new LeagueEntry(293,  "K League 2",                            true),
            new LeagueEntry(71,   "Brasileirao (Serie A)",                  true),
            new LeagueEntry(15,   "FIFA Club World Cup",                    true),
            new LeagueEntry(4,    "Euro Championship",                              true),
            new LeagueEntry(6,    "Africa Cup of Nations",                          true),
            new LeagueEntry(7,    "Asian Cup",                                      true),
            new LeagueEntry(22,   "CONCACAF Gold Cup",                              true),
            new LeagueEntry(27,   "OFC Champions League",                           true),
            new LeagueEntry(9,    "Copa America",                                   true),
            new LeagueEntry(23,   "EAFF E-1 Football Championship",                 true),
//            new LeagueEntry(1169, "EAFF E-1 FC - Qualification",                   true),
//            new LeagueEntry(1188, "EAFF E-1 FC - Women",                           true),
//            new LeagueEntry(25,   "Gulf Cup of Nations",                            true),
//            new LeagueEntry(35,   "Asian Cup - Qualification",                      true),
//            new LeagueEntry(36,   "AFCON - Qualification",                          true),
//            new LeagueEntry(858,  "Gold Cup - Qualification",                       true),
//            new LeagueEntry(960,  "Euro - Qualification",                           true),
            new LeagueEntry(29,   "World Cup - Qualification Africa",               true),
            new LeagueEntry(30,   "World Cup - Qualification Asia",                 true),
            new LeagueEntry(31,   "World Cup - Qualification CONCACAF",             true),
            new LeagueEntry(32,   "World Cup - Qualification Europe",               true),
            new LeagueEntry(33,   "World Cup - Qualification Oceania",              true),
            new LeagueEntry(34,   "World Cup - Qualification South America",        true),
            new LeagueEntry(37,   "World Cup - Qualification Intercontinental P/O", true),
            new LeagueEntry(1168, "FIFA Intercontinental Cup",                      true),
//            new LeagueEntry(860,  "FIFA Arab Cup",                                  true),
//            new LeagueEntry(1,    "World Cup",                                      true),
            new LeagueEntry(11, "CONMEBOL Sudamericana", true),
            new LeagueEntry(13, "CONMEBOL Libertadores", true)
    );
    // ─────────────────────────────────────────────────────────

    /** 처리할 리그 ID 목록 + 적용할 시즌 연도 또는 특정 선수/팀 ID */
    record SelectionResult(Mode mode, List<Integer> leagueIds, int season, List<Long> playerIds, long teamId) {
        static SelectionResult forLeagues(List<Integer> leagueIds, int season) {
            return new SelectionResult(Mode.LEAGUE, leagueIds, season, List.of(), 0L);
        }

        static SelectionResult forPlayers(List<Long> playerIds) {
            return new SelectionResult(Mode.PLAYERS, List.of(), 0, playerIds, 0L);
        }

        static SelectionResult forTeam(long teamId) {
            return new SelectionResult(Mode.TEAM, List.of(), 0, List.of(), teamId);
        }
    }

    /**
     * 콘솔 텍스트 UI로 업데이트 대상을 선택받아 반환한다.
     * 메인 메뉴에서 'q' 입력 시에만 null 반환 (종료 신호).
     * 서브메뉴에서 q/n/잘못된 입력 시에는 메인 메뉴로 복귀한다.
     *
     * @param sc                CsvUpdater.main()에서 생성한 Scanner (System.in 공유 스트림)
     * @param leagueNameFetcher 미리스트에 없는 리그 ID 입력 시 API로 이름을 조회하는 함수.
     *                          null이면 이름 조회 없이 ID만 표시한다.
     */
    static SelectionResult promptSelection(Scanner sc, IntFunction<String> leagueNameFetcher) {
        while (true) {
            // 1단계: 카테고리 선택
            System.out.println();
            System.out.println("========================================");
            System.out.println("  CsvUpdater - 업데이트 대상 선택");
            System.out.println("========================================");
            System.out.println("  1. 추춘제 리그 / 대회  (season " + FALL_SEASON + ")");
            System.out.println("  2. 춘추제 리그 / 대회  (season " + SPRING_SEASON + ")");
            System.out.println("  3. 특정 선수(player_id) 갱신");
            System.out.println("  4. 특정 팀(team_id) 선수/감독 갱신");
            System.out.println("  q. 종료");
            System.out.print("> ");

            String categoryInput = sc.nextLine().trim();
            if (categoryInput.equals("q")) return null;  // 메인 메뉴 q만 종료
            if (categoryInput.isEmpty()) continue;

            boolean isSpring;
            List<LeagueEntry> entries;
            int season;

            if (categoryInput.equals("1")) {
                isSpring = false;
                entries = FALL_LEAGUES;
                season  = FALL_SEASON;
            } else if (categoryInput.equals("2")) {
                isSpring = true;
                entries = SPRING_LEAGUES;
                season  = SPRING_SEASON;
            } else if (categoryInput.equals("3")) {
                SelectionResult result = promptPlayerSelection(sc);
                if (result == null) { printBackToMain(); continue; }
                return result;
            } else if (categoryInput.equals("4")) {
                SelectionResult result = promptTeamSelection(sc);
                if (result == null) { printBackToMain(); continue; }
                return result;
            } else {
                System.out.println(ANSI_RED + "  잘못된 입력: " + categoryInput + ANSI_RESET);
                continue;
            }

            // 2단계: 목록 표시 및 ID 선택
            printLeagueList(entries, isSpring);
            System.out.print("> ");

            String idInput = sc.nextLine().trim();
            if (idInput.equals("q") || idInput.isEmpty()) { printBackToMain(); continue; }

            List<Integer> selectedIds = parseIdInput(idInput, entries, sc, leagueNameFetcher);
            if (selectedIds == null) { printBackToMain(); continue; }

            // 3단계: 실행 확인
            System.out.println();
            System.out.println("--- 실행 확인 ---");
            System.out.printf("  카테고리 : %s%n", isSpring ? "춘추제 리그 / 대회" : "추춘제 리그 / 대회");
            System.out.printf("  시즌     : %d%n", season);
            System.out.printf("  대상 IDs : %s%n", selectedIds);
            System.out.println();
            System.out.print("  실행하시겠습니까? (y/n) > ");

            if (!sc.nextLine().trim().equalsIgnoreCase("y")) { printBackToMain(); continue; }

            return SelectionResult.forLeagues(selectedIds, season);
        }
    }

    private static void printBackToMain() {
        System.out.println(ANSI_CYAN + "  메인 화면으로 돌아갑니다." + ANSI_RESET);
    }

    private static void printLeagueList(List<LeagueEntry> entries, boolean isSpring) {
        System.out.println();
        System.out.println("--- " + (isSpring ? "춘추제 리그 / 대회" : "추춘제 리그 / 대회") + " 목록 ---");
        System.out.printf("  %-6s  %s%n", "ID", "이름");
        System.out.println("  ------  ----------------------------------------");
        for (LeagueEntry e : entries) {
            String marker = e.enabled() ? " *" : "  ";
            System.out.printf("%s%-6d  %s%n", marker, e.id(), e.name());
        }
        System.out.println();
        System.out.println("  * = 기본 목록 포함 (0 입력 시 자동 선택)");
        System.out.println("  0 = 기본 목록 전체 실행");
        System.out.println("  ID 직접 입력 (콤마 구분, 예: 39,78,140) — 목록에 없는 ID는 확인 후 허용");
        System.out.println("  q = 취소");
    }

    /**
     * ID 입력 문자열을 파싱한다.
     * "0"이면 enabled=true 항목 전체를 반환하고, 그 외엔 콤마 구분 ID를 파싱한다.
     * 목록에 없는 ID는 API로 이름을 조회한 뒤 경고를 표시하고 y/n 개별 확인한다.
     *
     * @param idInput           사용자가 입력한 문자열 (예: "39,78" 또는 "0")
     * @param entries           현재 카테고리의 리그 목록 (preset에 있는 ID 집합 판별에 사용)
     * @param sc                사용자 입력을 읽는 Scanner.
     *                          미리스트 외 ID 발견 시 "계속할까요?" 확인 입력을 받아야 해서 추가됨.
     *                          기존에는 이 메서드 안에서 I/O가 없었으므로 Scanner가 필요 없었음.
     * @param leagueNameFetcher int(leagueId) -> String(리그 이름) 함수.
     *                          미리스트에 없는 ID가 들어왔을 때 API Football에서 리그 이름을 조회하는 데 쓰임.
     *                          구체적으로는 CsvUpdater.main()에서 apiClient::getLeagueName 을 넘겨준다.
     *                          IntFunction<String>은 Java 표준 함수형 인터페이스로,
     *                          "int 하나를 받아서 String을 돌려주는 함수"를 뜻한다.
     *                          이렇게 함수를 파라미터로 넘기면 UI 클래스(CsvUpdaterUi)가
     *                          ApiFootballClient를 직접 import하지 않아도 되고,
     *                          테스트할 때도 가짜 함수로 쉽게 대체할 수 있다.
     */
    private static List<Integer> parseIdInput(
            String idInput,
            List<LeagueEntry> entries,
            Scanner sc,
            IntFunction<String> leagueNameFetcher) {

        if (idInput.equals("0")) {
            return entries.stream()
                    .filter(LeagueEntry::enabled)
                    .map(LeagueEntry::id)
                    .collect(Collectors.toList());
        }

        Set<Integer> validIds = entries.stream()
                .map(LeagueEntry::id)
                .collect(Collectors.toSet());

        List<Integer> selectedIds = new ArrayList<>();
        for (String token : idInput.split(",")) {
            token = token.trim();
            int id;
            try {
                id = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                System.out.println(ANSI_RED + "[CsvUpdater] 잘못된 ID 형식: " + token + ANSI_RESET);
                return null;
            }

            if (!validIds.contains(id)) {
                // 미리스트에 없는 ID — API로 이름 조회 후 경고
                // leagueNameFetcher.apply(id) 가 실제로 ApiFootballClient.getLeagueName(id)를 호출한다
                String leagueName = null;
                if (leagueNameFetcher != null) {
                    System.out.print(ANSI_CYAN + "  [API 조회 중] league id=" + id + "..." + ANSI_RESET + " ");
                    try {
                        leagueName = leagueNameFetcher.apply(id);
                    } catch (Exception ex) {
                        // 네트워크 오류 등 조회 실패 시 이름 없이 경고만 표시하고 계속 진행
                    }
                    System.out.println();
                }

                System.out.println();
                System.out.println(ANSI_YELLOW + ANSI_BOLD
                        + "  ⚠  ID " + id + " 는 목록에 없는 리그입니다." + ANSI_RESET);
                if (leagueName != null) {
                    System.out.println(ANSI_YELLOW
                            + "     리그 이름 : " + leagueName + ANSI_RESET);
                } else {
                    System.out.println(ANSI_RED
                            + "     리그 이름을 조회하지 못했습니다. (ID가 유효하지 않을 수 있습니다)" + ANSI_RESET);
                }
                System.out.print(ANSI_YELLOW + "     이 리그를 업데이트하시겠습니까? (y/n) > " + ANSI_RESET);

                String answer = sc.nextLine().trim();
                System.out.println();
                if (!answer.equalsIgnoreCase("y")) {
                    // n 입력 시 이 ID만 건너뜀 — 나머지 ID는 계속 처리
                    System.out.println("  ID " + id + " 를 건너뜁니다.");
                    continue;
                }
            }

            selectedIds.add(id);
        }

        if (selectedIds.isEmpty()) {
            System.out.println("[CsvUpdater] 선택된 ID가 없습니다. 취소합니다.");
            return null;
        }
        return selectedIds;
    }

    private static SelectionResult promptPlayerSelection(Scanner sc) {
        System.out.println();
        System.out.println("--- 특정 선수(player_id) 갱신 ---");
        System.out.println("  players.csv에 최신 행을 append 합니다. (기존 행 삭제/수정 없음)");
        System.out.println("  같은 player_id가 이미 있으면 기존 한글 이름 컬럼을 보존한 채 새 행을 맨 아래에 추가합니다.");
        System.out.println("  입력 방식:");
        System.out.println("    - ID 직접 입력: 152953,63577");
        System.out.println("    - 로그 붙여넣기: [KO_NAME_NEEDED] id=152953, name=L. Colwill");
        System.out.println("  q = 취소");
        System.out.print("> ");

        String rawInput = sc.nextLine().trim();
        if (rawInput.equals("q") || rawInput.isEmpty()) return null;

        List<Long> playerIds = parsePlayerIds(rawInput);
        if (playerIds == null || playerIds.isEmpty()) {
            System.out.println("[CsvUpdater] player_id를 찾지 못했습니다. 취소합니다.");
            return null;
        }

        System.out.println();
        System.out.println("--- 실행 확인 ---");
        System.out.println("  모드     : 특정 선수 갱신");
        System.out.printf("  playerIds: %s%n", playerIds);
        System.out.println();
        System.out.print("  실행하시겠습니까? (y/n) > ");

        if (!sc.nextLine().trim().equalsIgnoreCase("y")) return null;
        return SelectionResult.forPlayers(playerIds);
    }

    private static SelectionResult promptTeamSelection(Scanner sc) {
        System.out.println();
        System.out.println("--- 특정 팀(team_id) 선수/감독 갱신 ---");
        System.out.println("  해당 팀의 스쿼드와 감독을 players.csv / coaches.csv에 append 합니다.");
        System.out.println("  이미 등록된 선수/감독 ID는 건너뜁니다.");
        System.out.println("  team_id 입력 (예: 33)");
        System.out.println("  q = 취소");
        System.out.print("> ");

        String input = sc.nextLine().trim();
        if (input.equals("q") || input.isEmpty()) return null;

        long teamId;
        try {
            teamId = Long.parseLong(input);
        } catch (NumberFormatException e) {
            System.out.println("[CsvUpdater] 잘못된 team_id 형식: " + input);
            return null;
        }

        System.out.println();
        System.out.println("--- 실행 확인 ---");
        System.out.printf("  모드    : 특정 팀 선수/감독 갱신%n");
        System.out.printf("  team_id : %d%n", teamId);
        System.out.println();
        System.out.print("  실행하시겠습니까? (y/n) > ");

        if (!sc.nextLine().trim().equalsIgnoreCase("y")) return null;
        return SelectionResult.forTeam(teamId);
    }

    private static List<Long> parsePlayerIds(String rawInput) {
        LinkedHashSet<Long> playerIds = new LinkedHashSet<>();

        java.util.regex.Matcher logMatcher = java.util.regex.Pattern
                .compile("id\\s*=\\s*(\\d+)")
                .matcher(rawInput);
        while (logMatcher.find()) {
            playerIds.add(Long.parseLong(logMatcher.group(1)));
        }
        if (!playerIds.isEmpty()) {
            return new ArrayList<>(playerIds);
        }

        for (String token : rawInput.split("[,\\s]+")) {
            if (token.isBlank()) continue;
            try {
                playerIds.add(Long.parseLong(token.trim()));
            } catch (NumberFormatException e) {
                System.out.println("[CsvUpdater] 잘못된 player_id 형식: " + token);
                return null;
            }
        }
        return new ArrayList<>(playerIds);
    }
}
