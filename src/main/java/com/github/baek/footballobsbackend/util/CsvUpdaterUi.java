package com.github.baek.footballobsbackend.util;

import java.nio.charset.StandardCharsets;
import java.util.*;
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

    /** 리그/대회 항목 하나. enabled=true 이면 '0 전체 실행'에 포함된다. */
    record LeagueEntry(int id, String name, boolean enabled) {}

    // ── 클럽 리그 / 클럽 대회 ────────────────────────────────
    static final int SEASON = 2025;

    static final List<LeagueEntry> LEAGUES = List.of(
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
//            new LeagueEntry(2,    "UEFA Champions League",                  true),
//            new LeagueEntry(3,    "UEFA Europa League",                     true),
//            new LeagueEntry(848,  "UEFA Europa Conference League",          true),
            new LeagueEntry(292,  "K League 1",                            true),
            new LeagueEntry(293,  "K League 2",                            true),
            new LeagueEntry(273,  "AFC Champions League Elite",             true),
//            new LeagueEntry(18,   "AFC Champions League Two",               true),
//            new LeagueEntry(1132, "AFC Challenge League",                   true),
//            new LeagueEntry(12,   "CAF Champions League",                   true),
//            new LeagueEntry(20,   "CAF Confederations Cup",                 true),
            new LeagueEntry(71,   "Brasileirao (Serie A)",                  true),
            new LeagueEntry(15,   "FIFA Club World Cup",                    true)
    );

    // ── 국제 대회 ─────────────────────────────────────────────
    static final int INT_SEASON = 2026;

    static final List<LeagueEntry> INT_COMPETITIONS = List.of(
            new LeagueEntry(4,    "Euro Championship",                              true),
            new LeagueEntry(6,    "Africa Cup of Nations",                          true),
            new LeagueEntry(7,    "Asian Cup",                                      true),
            new LeagueEntry(22,   "CONCACAF Gold Cup",                              true),
            new LeagueEntry(27,   "OFC Champions League",                           true),
            new LeagueEntry(9,    "Copa America",                                   true),
            new LeagueEntry(23,   "EAFF E-1 Football Championship",                 true),
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
            new LeagueEntry(1168, "FIFA Intercontinental Cup",                      true)
//            new LeagueEntry(1169, "EAFF E-1 FC - Qualification",                   true),
//            new LeagueEntry(1188, "EAFF E-1 FC - Women",                           true),
//            new LeagueEntry(860,  "FIFA Arab Cup",                                  true),
//            new LeagueEntry(1,    "World Cup",                                      true)
    );
    // ─────────────────────────────────────────────────────────

    /** 처리할 리그 ID 목록 + 적용할 시즌 연도 */
    record SelectionResult(List<Integer> leagueIds, int season) {}

    /**
     * 콘솔 텍스트 UI로 업데이트 대상을 선택받아 반환한다.
     * 'q' 입력 또는 빈 입력 시 null 반환 (취소).
     */
    static SelectionResult promptSelection() {
        Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8);

        // 1단계: 카테고리 선택
        System.out.println("========================================");
        System.out.println("  CsvUpdater - 업데이트 대상 선택");
        System.out.println("========================================");
        System.out.println("  1. 클럽 리그 / 클럽 대회  (season " + SEASON + ")");
        System.out.println("  2. 국제 대회               (season " + INT_SEASON + ")");
        System.out.println("  q. 취소");
        System.out.print("> ");

        String categoryInput = sc.nextLine().trim();
        if (categoryInput.equals("q") || categoryInput.isEmpty()) return null;

        boolean isInternational;
        List<LeagueEntry> entries;
        int season;

        if (categoryInput.equals("1")) {
            isInternational = false;
            entries = LEAGUES;
            season  = SEASON;
        } else if (categoryInput.equals("2")) {
            isInternational = true;
            entries = INT_COMPETITIONS;
            season  = INT_SEASON;
        } else {
            System.out.println("[CsvUpdater] 잘못된 입력: " + categoryInput);
            return null;
        }

        // 2단계: 목록 표시 및 ID 선택
        printLeagueList(entries, isInternational);
        System.out.print("> ");

        String idInput = sc.nextLine().trim();
        if (idInput.equals("q") || idInput.isEmpty()) return null;

        List<Integer> selectedIds = parseIdInput(idInput, entries);
        if (selectedIds == null) return null;

        // 3단계: 실행 확인
        System.out.println();
        System.out.println("--- 실행 확인 ---");
        System.out.printf("  카테고리 : %s%n", isInternational ? "국제 대회" : "클럽 리그");
        System.out.printf("  시즌     : %d%n", season);
        System.out.printf("  대상 IDs : %s%n", selectedIds);
        System.out.println();
        System.out.print("  실행하시겠습니까? (y/n) > ");

        if (!sc.nextLine().trim().equalsIgnoreCase("y")) return null;

        return new SelectionResult(selectedIds, season);
    }

    private static void printLeagueList(List<LeagueEntry> entries, boolean isInternational) {
        System.out.println();
        System.out.println("--- " + (isInternational ? "국제 대회" : "클럽 리그") + " 목록 ---");
        System.out.printf("  %-6s  %s%n", "ID", "이름");
        System.out.println("  ------  ----------------------------------------");
        for (LeagueEntry e : entries) {
            String marker = e.enabled() ? " *" : "  ";
            System.out.printf("%s%-6d  %s%n", marker, e.id(), e.name());
        }
        System.out.println();
        System.out.println("  * = 기본 목록 포함 (0 입력 시 자동 선택)");
        System.out.println("  0 = 기본 목록 전체 실행");
        System.out.println("  ID 직접 입력 (콤마 구분, 예: 39,78,140) — 목록에 없는 ID는 거부됨");
        System.out.println("  q = 취소");
    }

    /**
     * ID 입력 문자열을 파싱한다.
     * "0"이면 enabled=true 항목 전체를 반환하고, 그 외엔 콤마 구분 ID를 파싱한다.
     * 목록에 없는 ID가 하나라도 있으면 null 반환 (취소).
     */
    private static List<Integer> parseIdInput(String idInput, List<LeagueEntry> entries) {
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
                System.out.println("[CsvUpdater] 잘못된 ID 형식: " + token);
                return null;
            }
            if (!validIds.contains(id)) {
                System.out.printf("[CsvUpdater] ID %d 는 목록에 없습니다. 취소합니다.%n", id);
                return null;
            }
            selectedIds.add(id);
        }

        if (selectedIds.isEmpty()) {
            System.out.println("[CsvUpdater] 선택된 ID가 없습니다. 취소합니다.");
            return null;
        }
        return selectedIds;
    }
}
