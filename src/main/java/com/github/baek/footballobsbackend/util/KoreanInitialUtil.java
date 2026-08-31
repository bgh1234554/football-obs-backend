package com.github.baek.footballobsbackend.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Ju. 벨링엄"처럼 라틴 이니셜 + 마침표 + 한글 성으로 구성된 name_ko_short를
 * 이니셜/성으로 분리하고, 동명이인 판별용 축약 키를 만드는 순수 유틸리티.
 *
 * CsvLoader(CSV 전체 기준 "이 이니셜이 애초에 동명이인 대비용으로 확장됐는지" 전역 판정)와
 * KoResolver(경기 하나에 실제로 등장한 선수들 기준 "지금 이 경기에선 줄여도 되는지" 판정)
 * 양쪽에서 같은 파싱/키 규칙을 공유해야 하므로 별도 유틸로 분리했다.
 */
public final class KoreanInitialUtil {

    private KoreanInitialUtil() {}

    // 앞쪽 라틴 문자(1글자 이상) + "." + 나머지(성). "Kh. 크바라츠헬리아" / "J. 벨링엄" 둘 다 매칭.
    private static final Pattern INITIAL_PATTERN = Pattern.compile("^(\\p{L}+)\\.\\s*(.+)$");

    /**
     * "Ju. 벨링엄" → {"Ju", "벨링엄"}.
     * 이니셜+마침표 형식이 아니거나(단일 이름 등) 성 부분이 비어있으면 null.
     */
    public static String[] splitLeadingInitial(String koShort) {
        if (koShort == null) return null;
        String text = koShort.trim();
        if (text.isEmpty()) return null;
        Matcher m = INITIAL_PATTERN.matcher(text);
        if (!m.matches()) return null;
        String surname = m.group(2).trim();
        if (surname.isEmpty()) return null;
        return new String[]{ m.group(1), surname };
    }

    /**
     * 이니셜의 첫 글자 + 성으로 만드는 동명이인 판별 키.
     * "Ju."와 "J."는 둘 다 "j|벨링엄"으로 수렴한다 — 2글자 이니셜을 1글자로 줄였을 때
     * 실제로 다른 누군가와 똑같아 보이게 되는지 확인하는 용도.
     */
    public static String reducedKey(String initial, String surname) {
        return initial.substring(0, 1).toLowerCase() + "|" + surname.toLowerCase();
    }
}
