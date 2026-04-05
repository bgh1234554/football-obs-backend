package com.github.baek.footballobsbackend.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CsvUpdater에서 사용하는 CSV 파일 I/O 유틸.
 * 모든 메서드는 static이며 상태를 갖지 않는다.
 */
class CsvUpdaterCsvHelper {

    record CsvTable(String[] header, List<String[]> rows) {}

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
     * CSV 전체를 읽어 헤더 + 행 목록으로 반환한다.
     * players.csv처럼 콤마가 이름 컬럼에 없다는 전제 하에 split(limit)을 사용한다.
     */
    static CsvTable loadCsvTable(String filePath, int columnCount) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            return new CsvTable(new String[columnCount], new ArrayList<>());
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String headerLine = br.readLine();
            String[] header = headerLine == null
                    ? new String[columnCount]
                    : padColumns(headerLine.split(",", columnCount), columnCount);

            List<String[]> rows = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                rows.add(padColumns(line.split(",", columnCount), columnCount));
            }
            return new CsvTable(header, rows);
        }
    }

    /**
     * CSV 전체를 덮어쓴다.
     */
    static void overwriteCsv(String filePath, String[] header, List<String[]> rows) throws IOException {
        File file = new File(filePath);
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, false))) {
            bw.write(joinRow(header));
            bw.newLine();
            for (String[] row : rows) {
                bw.write(joinRow(row));
                bw.newLine();
            }
        }
        System.out.printf("[CsvUpdater] %s 전체 %d행 재작성됨%n", filePath, rows.size());
    }

    /**
     * CSV 첫 번째 컬럼(Long id) → 지정 컬럼 값 Map 로드. 헤더 건너뜀.
     * teams.csv(team_name), coaches.csv(name_short) 등에서 diff 비교용으로 사용.
     */
    static Map<Long, String> loadIdToColumn(String filePath, int valueColumnIdx, int splitLimit) throws IOException {
        Map<Long, String> map = new HashMap<>();
        File file = new File(filePath);
        if (!file.exists()) return map;
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                if (line.isBlank()) continue;
                String[] parts = line.split(",", splitLimit);
                if (parts.length > valueColumnIdx) {
                    try { map.put(Long.parseLong(parts[0].trim()), parts[valueColumnIdx].trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return map;
    }

    /**
     * CSV 지정 컬럼(소문자 key) → 다른 컬럼 값 Map 로드. 헤더 건너뜀.
     * venues.csv에서 venue_name(소문자) → venue_city 비교용으로 사용.
     */
    static Map<String, String> loadKeyToColumn(
            String filePath, int keyColumnIdx, int valueColumnIdx, int splitLimit) throws IOException {
        Map<String, String> map = new HashMap<>();
        File file = new File(filePath);
        if (!file.exists()) return map;
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                if (line.isBlank()) continue;
                String[] parts = line.split(",", splitLimit);
                if (parts.length > Math.max(keyColumnIdx, valueColumnIdx)) {
                    map.put(parts[keyColumnIdx].trim().toLowerCase(), parts[valueColumnIdx].trim());
                }
            }
        }
        return map;
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

    private static String[] padColumns(String[] columns, int columnCount) {
        String[] padded = new String[columnCount];
        Arrays.fill(padded, "");
        for (int i = 0; i < Math.min(columns.length, columnCount); i++) {
            padded[i] = columns[i];
        }
        return padded;
    }

    private static String joinRow(String[] row) {
        StringJoiner joiner = new StringJoiner(",");
        for (String value : row) {
            joiner.add(esc(value));
        }
        return joiner.toString();
    }
}
