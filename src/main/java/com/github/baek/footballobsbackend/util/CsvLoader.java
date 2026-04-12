package com.github.baek.footballobsbackend.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 앱 시작 시 src/main/resources/data/ 하위 CSV 4개를 Map으로 메모리에 올리는 유틸.
 *
 * [왜 메모리에 올리는가?]
 * Render 배포 환경의 파일시스템은 휘발성이기 때문에 런타임에 CSV를 수정할 수 없음.
 * 대신 CSV를 GitHub에 커밋 → Render 자동 재배포로 반영하는 방식을 쓰므로,
 * 앱 시작 시 한 번 읽어 메모리에 올려두고 요청마다 Map 조회만 함.
 *
 * [CSV 구조]
 * players.csv   : player_id, name_short, name_long, position, nationality, name_ko_long, name_ko_short
 * logos.csv     : team_id, team_name_ko, logo_url, fa_url
 * coaches.csv   : coach_id, name_short, name_long, nationality, name_ko_long, name_ko_short
 * referees.csv  : referee_name, referee_country, name_ko
 * venues.csv    : venue_id, venue_name, venue_city, venue_name_ko, city_name_ko
 *
 * [한글 이름이 없을 경우]
 * 조회 메서드가 null을 반환하면 호출부(FixtureService)에서 API Football 영문 이름으로 fallback 처리.
 * Render 로그에서 [KO_NAME_NEEDED] 키워드로 미번역 항목을 추적할 수 있음.
 */
@Slf4j
@Component
public class CsvLoader {

    // index: 0=player_id, 1=name_short, 2=name_long, 3=position, 4=nationality, 5=name_ko_long, 6=name_ko_short
    private final Map<Long, String[]> players = new HashMap<>();

    // index: 0=team_id, 1=team_name, 2=ko_name, 3=ko_name_short
    private final Map<Long, String[]> teams = new HashMap<>();
    // key: team_name 소문자 → teams 배열과 동일한 String[] 공유 (역방향 이름 조회용)
    private final Map<String, String[]> teamsByName = new HashMap<>();

    // index: 0=team_id, 1=team_name_ko, 2=logo_url, 3=fa_url
    private final Map<Long, String[]> logos = new HashMap<>();

    // index: 0=coach_id, 1=name_short, 2=name_long, 3=nationality, 4=name_ko_long, 5=name_ko_short
    private final Map<Long, String[]> coaches = new HashMap<>();

    // key: "referee_name, referee_country" 또는 "referee_name", value: name_ko
    private final Map<String, String> referees = new HashMap<>();

    // key: "referee_name, referee_country" 또는 "referee_name", value: referee_country
    private final Map<String, String> refereeCountries = new HashMap<>();

    // index: 0=venue_id(빈 문자열이면 미등록), 1=venue_name, 2=venue_city, 3=venue_name_ko, 4=city_name_ko
    // key: venue_name 소문자 (대소문자 무관 검색을 위해)
    private final Map<String, String[]> venues = new HashMap<>();
    // key: venue_id (venue_id가 비어있는 행은 등록 안 됨)
    private final Map<Long, String[]> venuesById = new HashMap<>();

    // index: 0=league_id, 1=league_name, 2=league_name_ko, 3=logo_url
    private final Map<Long, String[]> leagues = new HashMap<>();

    /**
     * Spring 빈 초기화 직후 CSV 4개를 순서대로 로딩.
     * 파일이 비어있거나 없어도 예외 없이 진행 (빈 Map 상태 유지).
     */
    @PostConstruct
    public void load() {
        loadPlayers();
        loadTeams();
        loadLogos();
        loadCoaches();
        loadReferees();
        loadVenues();
        loadLeagues();
        log.info("CSV loaded — players:{} teams:{} logos:{} coaches:{} referees:{} venues:{} leagues:{}",
                players.size(), teams.size(), logos.size(), coaches.size(), referees.size(), venues.size(), leagues.size());
    }

    /**
     * players.csv를 읽어 player_id → String[] 형태로 Map에 저장.
     * split(",", 7) 로 최대 7개 컬럼으로 분리 (한글 이름에 콤마가 없다고 가정).
     */
    private void loadPlayers() {
        try (BufferedReader reader = openCsv("data/players.csv")) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                // 1. 첫 줄은 헤더이므로 건너뜀
                if (first) { first = false; continue; }
                // 2. 빈 줄 스킵
                if (line.isBlank()) continue;
                // 3. 최대 7개 컬럼으로 분리 (name_ko_short가 마지막 컬럼)
                String[] parts = line.split(",", 7);
                if (parts.length < 2) continue;
                // 4. player_id(index 0)를 키로 전체 배열 저장 (id 컬럼이 비어있으면 0으로 저장)
                String idStr = parts[0].trim();
                players.put(idStr.isEmpty() ? 0L : Long.parseLong(idStr), parts);
            }
        } catch (IOException e) {
            log.warn("Could not load players.csv: {}", e.getMessage());
        }
    }

    /**
     * teams.csv를 읽어 team_id → [team_id, team_name, ko_name] 형태로 저장.
     * ko_name이 비어있으면 Map에는 저장하되, getTeamNameKo에서 null을 반환함.
     */
    private void loadTeams() {
        try (BufferedReader reader = openCsv("data/teams.csv")) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                // 1. 헤더 건너뜀
                if (first) { first = false; continue; }
                // 2. 빈 줄 스킵
                if (line.isBlank()) continue;
                // 3. 최대 4개 컬럼으로 분리 (team_id, team_name, ko_name, ko_name_short)
                String[] parts = line.split(",", 4);
                if (parts.length < 2) continue;
                // 4. team_id를 키로 저장, team_name 소문자를 역방향 키로도 저장 (id 비어있으면 0으로 저장)
                String idStr = parts[0].trim();
                teams.put(idStr.isEmpty() ? 0L : Long.parseLong(idStr), parts);
                if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                    teamsByName.put(parts[1].trim().toLowerCase(), parts);
                }
            }
        } catch (IOException e) {
            log.warn("Could not load teams.csv: {}", e.getMessage());
        }
    }

    /**
     * logos.csv를 읽어 team_id → [team_id, team_name_ko, logo_url, fa_url] 형태로 저장.
     * fa_url은 국가대표팀만 있고 클럽팀은 비어있음 (getFaUrl에서 null 처리).
     */
    private void loadLogos() {
        try (BufferedReader reader = openCsv("data/logos.csv")) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                // 1. 헤더 건너뜀
                if (first) { first = false; continue; }
                // 2. 빈 줄 스킵
                if (line.isBlank()) continue;
                // 3. 최대 4개 컬럼으로 분리 (team_id, team_name_ko, logo_url, fa_url)
                String[] parts = line.split(",", 4);
                if (parts.length < 2) continue;
                // 4. team_id를 키로 저장 (id 비어있으면 0으로 저장)
                String idStr = parts[0].trim();
                logos.put(idStr.isEmpty() ? 0L : Long.parseLong(idStr), parts);
            }
        } catch (IOException e) {
            log.warn("Could not load logos.csv: {}", e.getMessage());
        }
    }

    /**
     * coaches.csv를 읽어 coach_id → String[] 형태로 저장.
     */
    private void loadCoaches() {
        try (BufferedReader reader = openCsv("data/coaches.csv")) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                // 1. 헤더 건너뜀
                if (first) { first = false; continue; }
                // 2. 빈 줄 스킵
                if (line.isBlank()) continue;
                // 3. 최대 6개 컬럼으로 분리
                String[] parts = line.split(",", 6);
                if (parts.length < 2) continue;
                // 4. coach_id를 키로 저장 (id 비어있으면 0으로 저장)
                String idStr = parts[0].trim();
                coaches.put(idStr.isEmpty() ? 0L : Long.parseLong(idStr), parts);
            }
        } catch (IOException e) {
            log.warn("Could not load coaches.csv: {}", e.getMessage());
        }
    }

    /**
     * referees.csv를 읽어 "심판이름, 국가" → 한글이름 형태로 저장.
     *
     * [주의] referee.csv 형식: referee_name,referee_country,name_ko  (CSV 구분자는 콤마, 공백 없음)
     * API Football의 fixture.referee 값은 "Anthony Taylor, England" 처럼 콤마+공백으로 연결된 문자열.
     * 따라서 CSV를 split(",", 3)으로 파싱한 뒤 key를 "name + ", " + country" 로 재조합해야
     * getRefereeNameKo()에 들어오는 API 원본 문자열과 일치함.
     */
    private void loadReferees() {
        try (BufferedReader reader = openCsv("data/referees.csv")) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                // 1. 헤더 건너뜀
                if (first) { first = false; continue; }
                // 2. 빈 줄 스킵
                if (line.isBlank()) continue;

                // 3. referee_name, referee_country, name_ko 3개 컬럼으로 분리
                String[] parts = line.split(",", 3);
                if (parts.length < 3) continue;

                // 4. API Football 형식 "Anthony Taylor, England"로 key 재조합 (콤마+공백)
                String name = parts[0].trim();
                String country = parts[1].trim();
                String nameKo = parts[2].trim();

                // 5. 국가는 이름-only / 이름+국가 양쪽 키로 저장해 API 포맷 차이를 흡수
                if (!country.isEmpty()) {
                    refereeCountries.put(name, country);
                    refereeCountries.put(name + ", " + country, country);
                }

                // 6. 한글 이름이 있는 경우에만 등록
                if (!nameKo.isEmpty()) {
                    referees.put(name, nameKo);
                    if (!country.isEmpty()) {
                        referees.put(name + ", " + country, nameKo);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not load referees.csv: {}", e.getMessage());
        }
    }

    /**
     * venues.csv를 읽어 venue_name(소문자) → String[] 형태로 저장.
     * venue_id는 null(빈 문자열)일 수 있음 — id 없이 이름만 등록된 경기장 처리용.
     * key를 소문자로 저장해 대소문자 무관 검색 지원.
     */
    private void loadVenues() {
        try (BufferedReader reader = openCsv("data/venues.csv")) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                // 1. 헤더 건너뜀
                if (first) { first = false; continue; }
                // 2. 빈 줄 스킵
                if (line.isBlank()) continue;
                // 3. 최대 5개 컬럼으로 분리 (venue_id, venue_name, venue_city, venue_name_ko, city_name_ko)
                String[] parts = line.split(",", 5);
                if (parts.length < 2) continue;
                // 4. venue_name 소문자를 키로 저장 (대소문자 무관 검색)
                venues.put(parts[1].trim().toLowerCase(), parts);
                // 5. venue_id가 있으면 ID 맵에도 등록 (id 1순위 검색용)
                String idStr = parts[0].trim();
                if (!idStr.isEmpty()) {
                    try { venuesById.put(Long.parseLong(idStr), parts); }
                    catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException e) {
            log.warn("Could not load venues.csv: {}", e.getMessage());
        }
    }

    /**
     * leagues.csv를 읽어 league_id → [league_id, league_name, league_name_ko, logo_url] 형태로 저장.
     * logo_url은 커스텀 URL이 있으면 채워져 있고, 없으면 비어있음.
     */
    private void loadLeagues() {
        try (BufferedReader reader = openCsv("data/leagues.csv")) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                // 1. 헤더 건너뜀
                if (first) { first = false; continue; }
                // 2. 빈 줄 스킵
                if (line.isBlank()) continue;
                // 3. 최대 4개 컬럼으로 분리 (league_id, league_name, league_name_ko, logo_url)
                String[] parts = line.split(",", 4);
                if (parts.length < 2) continue;
                // 4. league_id를 키로 저장 (id 비어있으면 0으로 저장)
                String idStr = parts[0].trim();
                leagues.put(idStr.isEmpty() ? 0L : Long.parseLong(idStr), parts);
            }
        } catch (IOException e) {
            log.warn("Could not load leagues.csv: {}", e.getMessage());
        }
    }

    /**
     * classpath 기준으로 CSV 파일을 열어 UTF-8 인코딩으로 읽는 BufferedReader 반환.
     * 한글 이름이 포함된 CSV가 깨지지 않도록 UTF-8 명시.
     */
    private BufferedReader openCsv(String path) throws IOException {
        return new BufferedReader(
                new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8)
        );
    }

    // ──────────────────────────────────────────────────────────────
    // 조회 메서드 — null 반환 시 호출부에서 영문 fallback 처리할 것
    // ──────────────────────────────────────────────────────────────

    /**
     * 선수 한글 단축 이름(name_ko_short) 조회.
     * 예: "Philippe Sandler" → "P. 산들러"
     * players.csv에 없거나 name_ko_short 컬럼이 비어있으면 null 반환.
     */
    public String getPlayerNameKo(long playerId) {
        String[] row = players.get(playerId);
        if (row == null || row.length < 7) return null;
        String v = row[6].trim();   // index 6 = name_ko_short
        return v.isEmpty() ? null : v;
    }

    /**
     * 선수 영문/원문 short 이름(name_short) 조회.
     * 한글 short가 없을 때 영어 fallback 우선순위로 사용.
     */
    public String getPlayerNameShort(long playerId) {
        String[] row = players.get(playerId);
        if (row == null || row.length < 2) return null;
        String v = row[1].trim();   // index 1 = name_short
        return v.isEmpty() ? null : v;
    }

    /**
     * 선수 한글 풀네임(name_ko_long) 조회.
     * players.csv에 없거나 name_ko_long 컬럼이 비어있으면 null 반환.
     */
    public String getPlayerNameKoLong(long playerId) {
        String[] row = players.get(playerId);
        if (row == null || row.length < 6) return null;
        String v = row[5].trim();   // index 5 = name_ko_long
        return v.isEmpty() ? null : v;
    }

    /**
     * 선수 영문 풀네임(name_long) 조회.
     * name_ko_long이 없을 때 fullName 표시용 English fallback으로 사용.
     */
    public String getPlayerNameLong(long playerId) {
        String[] row = players.get(playerId);
        if (row == null || row.length < 3) return null;
        String v = row[2].trim();   // index 2 = name_long
        return v.isEmpty() ? null : v;
    }

    /**
     * 선수 국적(nationality) 조회.
     * API Football nationality가 오래된 경우를 대비해 CSV 값을 우선 사용하는 용도.
     */
    public String getPlayerNationality(long playerId) {
        String[] row = players.get(playerId);
        if (row == null || row.length < 5) return null;
        String v = row[4].trim();   // index 4 = nationality
        return v.isEmpty() ? null : v;
    }

    /**
     * 팀 한글 이름(ko_name) 조회.
     * teams.csv에 없거나 ko_name 컬럼이 비어있으면 null 반환.
     * null이면 FixtureService에서 API Football 영문 팀 이름으로 fallback 처리.
     */
    public String getTeamNameKo(long teamId) {
        String[] row = teams.get(teamId);
        if (row == null || row.length < 3) return null;
        String v = row[2].trim();   // index 2 = ko_name
        return v.isEmpty() ? null : v;
    }

    /**
     * 팀 영문 이름(team_name)으로 한글 이름(ko_name) 역방향 조회.
     * API Football이 오래된 이름을 반환할 때 CSV의 최신 영문명을 searchKey로 사용하는 용도.
     * 대소문자 무관 검색. teams.csv에 없거나 ko_name이 비어있으면 null 반환.
     */
    public String getTeamNameKoByName(String englishName) {
        if (englishName == null || englishName.isBlank()) return null;
        String[] row = teamsByName.get(englishName.trim().toLowerCase());
        if (row == null || row.length < 3) return null;
        String v = row[2].trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * 팀 영문 이름(team_name)으로 CSV에 저장된 영문 팀명 조회.
     * API Football이 오래된 이름을 반환할 때 CSV의 최신 영문명을 우선 사용하기 위한 용도.
     * 대소문자 무관 검색. teams.csv에 없으면 null 반환.
     */
    public String getTeamNameFromCsv(String englishName) {
        if (englishName == null || englishName.isBlank()) return null;
        String[] row = teamsByName.get(englishName.trim().toLowerCase());
        if (row == null || row.length < 2) return null;
        String v = row[1].trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * 팀 ID로 teams.csv의 영문 팀명(team_name) 조회.
     * ko_name이 없을 때 API가 오래된 이름을 반환하는 경우 CSV 영문명을 우선 fallback으로 사용하는 용도.
     */
    public String getTeamEnglishName(long teamId) {
        String[] row = teams.get(teamId);
        if (row == null || row.length < 2) return null;
        String v = row[1].trim();   // index 1 = team_name
        return v.isEmpty() ? null : v;
    }

    /**
     * 팀 한글 단축 이름(ko_name_short) 조회.
     * teams.csv에 없거나 ko_name_short 컬럼이 비어있으면 null 반환.
     * null이면 FixtureService에서 ko_name 또는 API Football 영문 이름으로 fallback 처리.
     */
    public String getTeamNameKoShort(long teamId) {
        String[] row = teams.get(teamId);
        if (row == null || row.length < 4) return null;
        String v = row[3].trim();   // index 3 = ko_name_short
        return v.isEmpty() ? null : v;
    }

    /**
     * 감독 한글 단축 이름(name_ko_short) 조회.
     * coaches.csv에 없거나 컬럼이 비어있으면 null 반환.
     */
    public String getCoachNameKo(long coachId) {
        String[] row = coaches.get(coachId);
        if (row == null || row.length < 6) return null;
        String v = row[5].trim();   // index 5 = name_ko_short
        return v.isEmpty() ? null : v;
    }

    /**
     * 감독 영문/원문 short 이름(name_short) 조회.
     * 한글 short가 없을 때 영어 fallback 우선순위로 사용.
     */
    public String getCoachNameShort(long coachId) {
        String[] row = coaches.get(coachId);
        if (row == null || row.length < 2) return null;
        String v = row[1].trim();   // index 1 = name_short
        return v.isEmpty() ? null : v;
    }

    /**
     * 감독 한글 풀네임(name_ko_long) 조회.
     * coaches.csv에 없거나 컬럼이 비어있으면 null 반환.
     */
    public String getCoachNameKoLong(long coachId) {
        String[] row = coaches.get(coachId);
        if (row == null || row.length < 5) return null;
        String v = row[4].trim();   // index 4 = name_ko_long
        return v.isEmpty() ? null : v;
    }

    /**
     * 팀 커스텀 로고 URL 조회.
     * logos.csv에 등록된 커스텀 URL이 있으면 반환, 없으면 null.
     * null이면 FixtureService에서 API Football URL을 Media CDN URL로 치환하여 사용.
     */
    public String getLogoUrl(long teamId) {
        String[] row = logos.get(teamId);
        if (row == null || row.length < 3) return null;
        String v = row[2].trim();   // index 2 = logo_url
        return v.isEmpty() ? null : v;
    }

    /**
     * 팀 협회 로고 URL 조회.
     * 국가대표팀만 logos.csv에 fa_url이 채워져 있고, 클럽팀은 비어있어 null 반환.
     * 프론트에서 faUrl != null 이면 기본 국기 대신 협회 로고로 바꿀 수 있는 옵션을 표시하도록 설계됨.
     */
    public String getFaUrl(long teamId) {
        String[] row = logos.get(teamId);
        if (row == null || row.length < 4) return null;
        String v = row[3].trim();   // index 3 = fa_url
        return v.isEmpty() ? null : v;
    }

    /**
     * 심판 한글 이름 조회.
     * @param refereeString fixture.referee 원본 문자열 (예: "Anthony Taylor, England")
     *                      API Football 응답값을 그대로 넘기면 됨.
     * @return 한글 이름. referees.csv에 없으면 null.
     */
    public String getRefereeNameKo(String refereeString) {
        if (refereeString == null || refereeString.isBlank()) return null;
        return referees.get(refereeString.trim());
    }

    /**
     * 심판 국가 조회.
     * @param refereeString fixture.referee 원본 문자열 또는 이름-only 문자열
     * @return 국가명. referees.csv에 없거나 country 컬럼이 비어있으면 null.
     */
    public String getRefereeCountry(String refereeString) {
        if (refereeString == null || refereeString.isBlank()) return null;
        return refereeCountries.get(refereeString.trim());
    }

    /**
     * 경기장 이름으로 venues.csv 행 전체를 조회.
     * 대소문자 무관 검색. 없으면 null 반환.
     *
     * 반환 배열 인덱스:
     *   0 = venue_id      (빈 문자열이면 미등록)
     *   1 = venue_name
     *   2 = venue_city
     *   3 = venue_name_ko (빈 문자열이면 한글 미등록)
     *   4 = city_name_ko  (빈 문자열이면 한글 미등록)
     */
    public String[] getVenueRow(String venueName) {
        if (venueName == null || venueName.isBlank()) return null;
        return venues.get(venueName.trim().toLowerCase());
    }

    /**
     * 경기장 ID로 venues.csv 행 전체를 조회.
     * venue_id가 비어있는 행은 등록되지 않으므로 null 반환될 수 있음.
     */
    public String[] getVenueRowById(long venueId) {
        return venuesById.get(venueId);
    }

    /**
     * 리그 한글 이름(league_name_ko) 조회.
     * leagues.csv에 없거나 컬럼이 비어있으면 null 반환.
     * null이면 FixtureService에서 API Football 영문 리그 이름으로 fallback 처리.
     */
    public String getLeagueNameKo(long leagueId) {
        String[] row = leagues.get(leagueId);
        if (row == null || row.length < 3) return null;
        String v = row[2].trim();   // index 2 = league_name_ko
        return v.isEmpty() ? null : v;
    }

    /**
     * 리그 영문 이름(league_name) 조회.
     * league_name_ko가 없을 때 CSV 영문명을 우선 fallback으로 사용하는 용도.
     */
    public String getLeagueName(long leagueId) {
        String[] row = leagues.get(leagueId);
        if (row == null || row.length < 2) return null;
        String v = row[1].trim();   // index 1 = league_name
        return v.isEmpty() ? null : v;
    }

    /**
     * 리그 커스텀 로고 URL 조회.
     * leagues.csv에 등록된 커스텀 URL이 있으면 반환, 없으면 null.
     * null이면 FixtureService에서 API Football URL을 Media CDN URL로 치환하여 사용.
     */
    public String getLeagueLogoUrl(long leagueId) {
        String[] row = leagues.get(leagueId);
        if (row == null || row.length < 4) return null;
        String v = row[3].trim();   // index 3 = logo_url
        return v.isEmpty() ? null : v;
    }
}
