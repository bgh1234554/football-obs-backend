package com.github.baek.footballobsbackend.util;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Football 응답의 이름/URL 값을 한글화하거나 CDN URL로 변환하는 유틸리티.
 *
 * [원칙]
 * - 한글 이름이 있으면 사용, 없으면 CSV 영문 이름 우선, 그것도 없으면 API 영문 이름.
 * - CSV 영문 이름과 API 영문 이름이 다르면 [CSV_SHORT_NAME_DIFF] 로그 (앱 실행 중 1회).
 * - 한글 이름이 없을 때마다 [KO_XXX_NEEDED] 로그 → CSV 업데이트 신호.
 * - 모든 미디어 URL은 자체 Media CDN으로 도메인 치환.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KoResolver {

    private final CsvLoader csvLoader;
    private final Set<String> loggedShortNameDiffs = ConcurrentHashMap.newKeySet();

    @Value("${api.media-cdn-url}")
    private String mediaCdnUrl;

    // ──────────────────────────────────────────────
    // CDN URL 치환
    // ──────────────────────────────────────────────

    /**
     * API Football 미디어 URL의 도메인을 자체 Media CDN으로 치환.
     * ex) https://media.api-sports.io/... → https://media-handle-obsoverlay.b-cdn.net/...
     */
    public String toMediaCdnUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) return null;
        return apiUrl.replace("https://media.api-sports.io", mediaCdnUrl);
    }

    /**
     * 선수 ID로 Media CDN 프로필 사진 URL을 직접 구성.
     * lineup API 응답엔 photo 필드가 없어 ID 기반으로 빌드.
     */
    public String buildPlayerPhotoUrl(long playerId) {
        return mediaCdnUrl + "/football/players/" + playerId + ".png";
    }

    // ──────────────────────────────────────────────
    // 팀 이름 / 로고
    // ──────────────────────────────────────────────

    /**
     * 팀 표시 이름 우선순위.
     * 1. teamId가 없으면 teams.csv team_name으로 ko_name 조회, 없으면 null
     * 2. teams.csv ko_name (한글 풀네임)
     * 3. teams.csv team_name (CSV 영문 — API와 다르면 diff 로그)
     * 4. API Football name + [KO_TEAM_NAME_NEEDED] 로그
     */
    public String resolveTeamName(long teamId, String apiName) {
        if (teamId <= 0) {
            String koByName = csvLoader.getTeamNameKoByName(apiName);
            if (koByName != null) return koByName;
            log.info("[KO_TEAM_NAME_NEEDED] id={}, name={}", teamId, apiName);
            return null;
        }

        String ko = csvLoader.getTeamNameKo(teamId);
        if (ko != null) return ko;
        String csvEnglish = csvLoader.getTeamEnglishName(teamId);
        if (csvEnglish != null) {
            logShortNameDiffOnce("team", teamId, apiName, csvEnglish);
            return csvEnglish;
        }
        log.info("[KO_TEAM_NAME_NEEDED] id={}, name={}", teamId, apiName);
        return apiName;
    }

    /**
     * 팀 단축명 우선순위.
     * ko_name_short → ko_name → API 영문명 순 fallback.
     */
    public String resolveTeamNameShort(long teamId, String apiName) {
        String koShort = csvLoader.getTeamNameKoShort(teamId);
        if (koShort != null) return koShort;
        String ko = csvLoader.getTeamNameKo(teamId);
        return ko != null ? ko : apiName;
    }

    /**
     * 팀 로고 URL 우선순위.
     * 1. logos.csv 커스텀 URL
     * 2. API URL을 Media CDN으로 치환 + [LOGO_NEEDED] 로그
     */
    public String resolveLogoUrl(long teamId, String apiLogoUrl, String teamApiName) {
        String custom = csvLoader.getLogoUrl(teamId);
        if (custom != null) return custom;
        log.info("[LOGO_NEEDED] id={}, name={}", teamId, teamApiName);
        if (apiLogoUrl == null || apiLogoUrl.isBlank()) return null;
        return toMediaCdnUrl(apiLogoUrl);
    }

    // ──────────────────────────────────────────────
    // 리그 이름 / 로고
    // ──────────────────────────────────────────────

    /**
     * 리그 표시 이름 우선순위.
     * 1. leagues.csv league_name_ko
     * 2. leagues.csv league_name (CSV 영문 — API와 다르면 diff 로그)
     * 3. API Football name + [KO_LEAGUE_NAME_NEEDED] 로그
     */
    public String resolveLeagueName(long leagueId, String apiName) {
        if (leagueId <= 0) {
            String byName = resolveLeagueNameByName(apiName);
            if (byName != null) return byName;
            log.info("[KO_LEAGUE_NAME_NEEDED] id={}, name={}", leagueId, apiName);
            return apiName;
        }

        String ko = csvLoader.getLeagueNameKo(leagueId);
        if (ko != null) return ko;
        String csvEnglish = csvLoader.getLeagueName(leagueId);
        if (csvEnglish != null) {
            logShortNameDiffOnce("league", leagueId, apiName, csvEnglish);
            return csvEnglish;
        }
        String byName = resolveLeagueNameByName(apiName);
        if (byName != null) return byName;
        log.info("[KO_LEAGUE_NAME_NEEDED] id={}, name={}", leagueId, apiName);
        return apiName;
    }

    public String resolveLeagueName(Integer leagueId, String apiName) {
        return leagueId == null ? resolveLeagueName(0L, apiName) : resolveLeagueName(leagueId.longValue(), apiName);
    }

    private String resolveLeagueNameByName(String apiName) {
        String ko = csvLoader.getLeagueNameKoByName(apiName);
        if (ko != null) return ko;
        return csvLoader.getLeagueNameByName(apiName);
    }

    /**
     * 리그 로고 URL 우선순위.
     * 1. leagues.csv logo_url 커스텀 URL
     * 2. API URL을 Media CDN으로 치환 + [LEAGUE_LOGO_NEEDED] 로그
     *
     * leagueId가 null인 비공식 대회는 커스텀 조회를 건너뛰고 CDN 치환만 적용.
     */
    public String resolveLeagueLogoUrl(Integer leagueId, String apiLogoUrl) {
        if (leagueId != null) {
            String custom = csvLoader.getLeagueLogoUrl(leagueId);
            if (custom != null) return custom;
            log.info("[LEAGUE_LOGO_NEEDED] id={}", leagueId);
        }
        return toMediaCdnUrl(apiLogoUrl);
    }

    // ──────────────────────────────────────────────
    // 경기장
    // ──────────────────────────────────────────────

    /**
     * 경기장 이름 한글화.
     *
     * [검색 전략]
     * 1. venue_id로 검색 (API 이름이 바뀌어도 id로 매칭)
     * 2. 이름으로 검색 (venue_id가 없는 경기장 대응)
     * 3. CSV에 없으면 [KO_VENUE_NAME_NEEDED] 로그 + API 영문명 반환
     */
    public String resolveVenueName(JsonNode venueNode) {
        String apiName = venueNode.path("name").asText(null);
        String city    = venueNode.path("city").asText(null);
        JsonNode idNode = venueNode.path("id");
        boolean hasApiId = !idNode.isNull() && !idNode.isMissingNode();

        // 1. ID 검색
        if (hasApiId) {
            String[] row = csvLoader.getVenueRowById(idNode.asLong());
            if (row != null) {
                String nameKo = row.length > 3 ? row[3].trim() : "";
                return nameKo.isEmpty() ? apiName : nameKo;
            }
        }
        // 2. 이름 검색
        String[] row = csvLoader.getVenueRow(apiName);
        if (row != null) {
            String nameKo = row.length > 3 ? row[3].trim() : "";
            return nameKo.isEmpty() ? apiName : nameKo;
        }
        // 3. CSV 미등록
        if (hasApiId) {
            log.info("[KO_VENUE_NAME_NEEDED] id={}, name={}, city={}", idNode.asLong(), apiName, city);
        } else {
            log.info("[KO_VENUE_NAME_NEEDED] id not available, name={}, city={}", apiName, city);
        }
        return apiName;
    }

    /**
     * 경기장 도시명 한글화.
     * venues.csv city_name_ko(index 4)가 있으면 사용, 없으면 API 영문 도시명.
     */
    public String resolveVenueCity(JsonNode venueNode) {
        String[] row = csvLoader.getVenueRow(venueNode.path("name").asText(null));
        String koCity = (row != null && row.length > 4) ? row[4].trim() : "";
        return koCity.isEmpty() ? venueNode.path("city").asText(null) : koCity;
    }

    // ──────────────────────────────────────────────
    // 심판
    // ──────────────────────────────────────────────

    /**
     * API Football 심판 문자열("Anthony Taylor, England")을 표시용으로 변환.
     * 심판 정보 없으면 null 반환.
     */
    public String buildRefereeName(String refereeStr) {
        if (refereeStr == null || refereeStr.isBlank()) return null;

        int sep = refereeStr.indexOf(", ");
        if (sep < 0) {
            String nameKo  = csvLoader.getRefereeNameKo(refereeStr);
            String country = csvLoader.getRefereeCountry(refereeStr);
            if (nameKo == null) {
                nameKo = csvLoader.getRefereeNameKoFuzzy(refereeStr, country);
            }
            if (nameKo == null) {
                log.info("[KO_REFEREE_NAME_NEEDED] referee={}", refereeStr);
                return country != null ? refereeStr + " (" + country + ")" : refereeStr;
            }
            return country != null ? nameKo + " (" + country + ")" : nameKo;
        }

        String name    = refereeStr.substring(0, sep).trim();
        String country = refereeStr.substring(sep + 2).trim();
        if (country.isEmpty()) country = csvLoader.getRefereeCountry(refereeStr);

        String nameKo = csvLoader.getRefereeNameKo(refereeStr);
        if (nameKo == null) {
            nameKo = csvLoader.getRefereeNameKoFuzzy(name, country);
        }
        if (nameKo == null) {
            log.info("[KO_REFEREE_NAME_NEEDED] referee={}", refereeStr);
        }
        String displayName = nameKo != null ? nameKo : name;
        return (country == null || country.isBlank()) ? displayName : displayName + " (" + country + ")";
    }

    // ──────────────────────────────────────────────
    // 선수 / 감독
    // ──────────────────────────────────────────────

    /**
     * fixture 응답에서 내려줄 표시 이름과 풀네임 fallback 쌍.
     * displayName은 name 계열 필드, longName은 nameKoLong 계열 필드에 사용한다.
     */
    public record ResolvedName(String displayName, String longName) {}

    /**
     * 선수 이름 fallback 우선순위.
     * [id=0 선수] players.csv name_short/name_long 이름 역매칭으로 한글화 시도.
     * 1. players.csv name_ko_short  → (ko_short, ko_long)
     * 2. players.csv name_short     → (csv short, ko_long)  + diff 로그
     * 3. PersonNameFormatter로 API name 약식 변환
     *    - 실제로 약식화가 일어나면 longName은 (ko_long → csv long → API name) 순으로 채움
     *    - 이미 "A. Gomes" 같은 형태라 변환이 안 일어나면 longName은 ko_long 그대로 (없으면 null)
     */
    public ResolvedName resolvePlayerName(long playerId, String apiName) {
        // id=0: ID 조회가 불가능하므로 API name으로 players.csv 역방향 매칭 시도
        if (playerId == 0) {
            String[] row = csvLoader.getPlayerRowByName(apiName);
            if (row != null) {
                String koShort = (row.length > 6) ? row[6].trim() : "";
                String koLong  = (row.length > 5) ? row[5].trim() : "";
                if (!koShort.isEmpty()) {
                    return new ResolvedName(koShort, koLong.isEmpty() ? null : koLong);
                }
                if (!koLong.isEmpty()) {
                    // ko_long만 있을 때: 표시 이름은 API 이름 포맷 유지, longName만 한글
                    String display = PersonNameFormatter.buildNameShortAbbrev(apiName, null, null, null);
                    return new ResolvedName(display, koLong);
                }
            }
            // CSV 매칭 실패 — API 이름 그대로 반환 (로그는 FixtureService.printMissedPlayerLog가 담당)
            String display = PersonNameFormatter.buildNameShortAbbrev(apiName, null, null, null);
            return new ResolvedName(display, null);
        }

        String nameKo = csvLoader.getPlayerNameKo(playerId);
        String nameKoLong = csvLoader.getPlayerNameKoLong(playerId);
        if (nameKo != null) return new ResolvedName(nameKo, nameKoLong);

        String csvShort = csvLoader.getPlayerNameShort(playerId);
        if (csvShort != null) {
            logShortNameDiffOnce("player", playerId, apiName, csvShort);
            return new ResolvedName(csvShort, nameKoLong);
        }

        String nationality = csvLoader.getPlayerNationality(playerId);
        String displayName = PersonNameFormatter.buildNameShortAbbrev(apiName, null, null, nationality);
        String longName = nameKoLong;
        if (longName == null && wasShortened(apiName, displayName)) {
            longName = firstNonBlank(csvLoader.getPlayerNameLong(playerId), blankToNull(apiName));
        }
        return new ResolvedName(displayName, longName);
    }

    /**
     * 감독 이름 fallback 우선순위. 선수와 동일한 규칙을 coaches.csv 기준으로 적용.
     */
    public ResolvedName resolveCoachName(long coachId, String apiName) {
        String nameKo = csvLoader.getCoachNameKo(coachId);
        String nameKoLong = csvLoader.getCoachNameKoLong(coachId);
        if (nameKo != null) return new ResolvedName(nameKo, nameKoLong);

        String csvShort = csvLoader.getCoachNameShort(coachId);
        if (csvShort != null) {
            logShortNameDiffOnce("coach", coachId, apiName, csvShort);
            return new ResolvedName(csvShort, nameKoLong);
        }

        String nationality = csvLoader.getCoachNationality(coachId);
        String displayName = PersonNameFormatter.buildNameShortAbbrev(apiName, null, null, nationality);
        String longName = nameKoLong;
        if (longName == null && wasShortened(apiName, displayName)) {
            longName = firstNonBlank(csvLoader.getCoachNameLong(coachId), blankToNull(apiName));
        }
        return new ResolvedName(displayName, longName);
    }

    /**
     * 표시 이름만 필요한 호출자(PlayerService 등)를 위한 위임.
     */
    public String resolvePlayerDisplayName(long playerId, String apiName) {
        return resolvePlayerName(playerId, apiName).displayName();
    }

    public String resolveCoachDisplayName(long coachId, String apiName) {
        return resolveCoachName(coachId, apiName).displayName();
    }

    // ──────────────────────────────────────────────
    // 공통
    // ──────────────────────────────────────────────

    /**
     * formatter가 원본 API 이름을 실제로 줄였을 때만 true.
     */
    private static boolean wasShortened(String original, String resolved) {
        if (original == null || original.isBlank() || resolved == null || resolved.isBlank()) return false;
        return !original.trim().equals(resolved.trim());
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    /**
     * CSV 값과 API 값이 다를 때 diff 로그를 앱 실행 중 1회만 출력.
     */
    public void logShortNameDiffOnce(String type, long id, String apiName, String csvShort) {
        if (apiName == null || apiName.isBlank() || csvShort == null || csvShort.isBlank()) return;
        String apiTrimmed = apiName.trim();
        String csvTrimmed = csvShort.trim();
        if (apiTrimmed.equals(csvTrimmed)) return;
        String key = type + "|" + id + "|" + apiTrimmed + "|" + csvTrimmed;
        if (loggedShortNameDiffs.add(key)) {
            log.info("[CSV_SHORT_NAME_DIFF] type={}, id={}, api={}, csv={}", type, id, apiTrimmed, csvTrimmed);
        }
    }
}
