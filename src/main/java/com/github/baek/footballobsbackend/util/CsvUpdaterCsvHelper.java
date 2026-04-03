package com.github.baek.footballobsbackend.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CsvUpdater에서 사용하는 CSV 파일 I/O 유틸.
 * 모든 메서드는 static이며 상태를 갖지 않는다.
 */
class CsvUpdaterCsvHelper {

    private CsvUpdaterCsvHelper() {}

    /**
     * CSV 첫 번째 컬럼에서 Long ID 집합을 로드한다. 헤더 행은 건너뜀.
     * 파일이 없으면 빈 Set 반환.
     */
    static Set<Long> loadLongIds(String filePath) throws IOException {
        Set<Long> ids = new HashSet<>();
        File file = new File(filePath);
        if (!file.exists()) return ids;
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                if (parts.length > 0) {
                    try { ids.add(Long.parseLong(parts[0].trim())); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return ids;
    }

    /**
     * venues.csv의 venue_name 컬럼(index 1)을 소문자로 읽어 Set 반환.
     * 이름 기준 중복 체크에 사용. 파일이 없으면 빈 Set 반환.
     */
    static Set<String> loadVenueNames(String filePath) throws IOException {
        Set<String> names = new HashSet<>();
        File file = new File(filePath);
        if (!file.exists()) return names;
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                if (line.isBlank()) continue;
                String[] parts = line.split(",", 5);
                if (parts.length > 1) names.add(parts[1].trim().toLowerCase());
            }
        }
        return names;
    }

    /**
     * 파일 끝에 행 목록을 추가한다.
     * 파일이 개행으로 끝나지 않으면 먼저 개행을 삽입한다.
     * rows가 비어있으면 아무것도 하지 않는다.
     */
    static void appendRows(String filePath, List<String> rows) throws IOException {
        if (rows.isEmpty()) return;
        File file = new File(filePath);

        boolean needsNewline = false;
        if (file.exists() && file.length() > 0) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(file.length() - 1);
                needsNewline = (raf.read() != '\n');
            }
        }

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, true))) {
            if (needsNewline) bw.newLine();
            for (String row : rows) {
                bw.write(row);
                bw.newLine();
            }
        }
        System.out.printf("[CsvUpdater] %s 에 %d행 추가됨%n", filePath, rows.size());
    }

    /**
     * CSV 값 이스케이프. 콤마, 쌍따옴표, 개행이 포함된 경우 RFC 4180 기준으로 감쌈.
     */
    static String esc(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
