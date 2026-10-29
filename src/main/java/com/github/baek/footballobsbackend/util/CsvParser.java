package com.github.baek.footballobsbackend.util;

import java.util.ArrayList;
import java.util.List;

/** 큰따옴표와 이스케이프된 따옴표를 처리하는 공통 CSV 파서. */
public final class CsvParser {
    private CsvParser() {}

    /**
     * CSV 한 줄을 RFC 4180 스타일로 파싱.
     * - 큰따옴표로 감싼 필드 내부의 콤마는 구분자로 취급하지 않음
     *   (예: `"Washington, District of Columbia"` → 한 필드)
     * - 필드 내부의 큰따옴표는 `""`로 이스케이프
     * - limit > 0이면 String.split(",", limit)와 동일하게 최대 limit 컬럼까지 분리하고
     *   limit-1번째 콤마 이후의 모든 문자는 마지막 필드에 그대로 보존
     */
    public static String[] parseCsvLine(String line, int limit) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"' && cur.length() == 0) {
                inQuotes = true;
            } else if (c == ',' && (limit <= 0 || result.size() < limit - 1)) {
                result.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        result.add(cur.toString());
        return result.toArray(new String[0]);
    }
}
