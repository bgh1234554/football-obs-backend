package com.github.baek.footballobsbackend.util;

import java.util.Set;

/**
 * API Football 인명 표기를 CSV 생성 규칙과 같은 방식으로 정규화한다.
 *
 * 이 클래스는 CSV 업데이트 시점과 런타임 응답 fallback 시점이
 * 서로 다른 이름 규칙을 쓰지 않도록 short/long 이름 포맷을 한 곳에 모아둔 유틸이다.
 */
public final class PersonNameFormatter {

    /**
     * long name을 만들 때 성-이름 순서를 유지해야 하는 국적 목록.
     * 예: 한국, 일본, 중국권, 헝가리, 베트남.
     */
    private static final Set<String> FAMILY_NAME_FIRST_NATIONALITIES = Set.of(
            "japan",
            "korea republic", "korea dpr", "south korea", "north korea",
            "hungary",
            "china", "china pr", "pr china", "taiwan", "chinese taipei", "hong kong", "macao",
            "vietnam", "viet nam"
    );

    private PersonNameFormatter() {
    }

    /**
     * API name을 short 표기로 변환한다.
     *
     * 핵심 규칙:
     * - 이미 "A. Gomes"처럼 점이 들어간 약식이면 그대로 반환
     * - "Neymar"처럼 단일 이름이면 그대로 반환
     * - "Ákos Markgráf"처럼 풀네임일 때만 국적별 축약 규칙 적용
     *
     * firstName/lastName이 비어 있으면 apiName을 공백 기준으로 나눠 보조적으로 사용한다.
     * 즉, 상세 프로필 정보가 부족해도 축약이 최대한 동작하도록 설계했다.
     */
    public static String buildNameShortAbbrev(String apiName, String firstName, String lastName, String nationality) {
        if (apiName == null || apiName.isBlank()) return apiName == null ? "" : apiName;

        String normalizedApiName = apiName.trim();

        // 이미 이니셜 형태면 다시 축약하지 않는다.
        if (normalizedApiName.contains(".")) return normalizedApiName;

        // 닉네임/단일 이름은 줄일 정보가 없으므로 그대로 쓴다.
        if (!normalizedApiName.contains(" ")) return normalizedApiName;

        String fn = firstName != null ? firstName.trim() : "";
        String ln = lastName != null ? lastName.trim() : "";
        String nat = nationality != null ? nationality.trim().toLowerCase() : "";

        // firstname/lastname이 비어 있으면 API name 자체를 나눠 fallback한다.
        if (fn.isEmpty() || ln.isEmpty()) {
            String[] parts = normalizedApiName.split("\\s+", 2);
            if (fn.isEmpty()) fn = parts[0];
            if (ln.isEmpty() && parts.length > 1) ln = parts[1];
        }

        // 성을 확정할 수 없으면 원문을 그대로 유지한다.
        if (ln.isEmpty()) return normalizedApiName;

        // 한국식: Son Heung-Min -> Son H.M.
        if (nat.equals("south korea") || nat.equals("korea republic") || nat.equals("korea dpr") || nat.equals("north korea")) {
            String initials = buildHyphenatedInitials(fn);
            return ln + (initials.isEmpty() ? "" : " " + initials);
        }

        // 중화권: Wu Lei -> Wu L.
        if (nat.equals("china") || nat.equals("china pr") || nat.equals("pr china")
                || nat.equals("taiwan") || nat.equals("chinese taipei")
                || nat.equals("hong kong") || nat.equals("macao")) {
            String initials = buildHyphenatedInitials(fn);
            return ln + (initials.isEmpty() ? "" : " " + initials);
        }

        // 베트남식(한국/중국과 동일): Nguyen Quang Hai -> Nguyen Q.H.
        if (nat.equals("vietnam") || nat.equals("viet nam")) {
            String initials = buildHyphenatedInitials(fn);
            return ln + (initials.isEmpty() ? "" : " " + initials);
        }

        // 기본 서구권 규칙: First Last -> F. Last
        if (fn.isEmpty()) return normalizedApiName;
        return Character.toUpperCase(fn.charAt(0)) + ". " + ln;
    }

    /**
     * API first/last name으로 long name을 만든다.
     *
     * short와 달리 long name은 축약하지 않고,
     * 국적에 따라 성-이름 / 이름-성 순서만 정리한다.
     */
    public static String buildLongName(String firstName, String lastName, String nationality) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();

        if (first.isEmpty()) return last;
        if (last.isEmpty()) return first;

        if (isFamilyNameFirstNationality(nationality)) {
            return last + " " + first;
        }
        return first + " " + last;
    }

    /**
     * "Heung-Min" -> "H.M.", "Ji Sung" -> "J.S."처럼
     * 하이픈/공백으로 나뉜 각 파트의 첫 글자를 이니셜로 만든다.
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
     * long name에서 성을 앞에 두는 국적인지 판별한다.
     */
    private static boolean isFamilyNameFirstNationality(String nationality) {
        if (nationality == null) return false;
        return FAMILY_NAME_FIRST_NATIONALITIES.contains(nationality.trim().toLowerCase());
    }
}
