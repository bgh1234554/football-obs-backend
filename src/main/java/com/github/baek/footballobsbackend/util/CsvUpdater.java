package com.github.baek.footballobsbackend.util;

/**
 * players.csv, coaches.csv, teams.csv 초안을 자동 생성하는 독립 실행 유틸.
 *
 * [역할]
 * Render 파일시스템은 휘발성이라 런타임에 CSV를 쓸 수 없음.
 * 이 클래스는 Spring 빈이 아니라 main()을 가진 독립 실행 클래스로,
 * 로컬에서만 실행해 src/main/resources/data/ 에 CSV를 직접 기록하고
 * GitHub 커밋 → Render 자동 재배포 흐름으로 반영.
 *
 * [실행 흐름 (예정)]
 * 1. 대상 리그 ID 목록을 정의
 * 2. 각 리그의 팀 목록 조회 (/leagues?id=... → teams)
 * 3. 팀별 선수 스쿼드 조회 (/players/squads?team=...) → players.csv 업데이트
 * 4. 팀별 감독 조회 (/coachs?team=...) → coaches.csv 업데이트
 * 5. teams.csv에 팀 기본 정보 기록 (ko_name은 수동 입력)
 *
 * [주의]
 * - name_ko_long, name_ko_short (players/coaches), ko_name (teams) 컬럼은 수동 입력
 * - logos.csv는 이 유틸에서 다루지 않음 (수동 관리)
 * - referees.csv는 /fixtures lineups에서 수집 (수동 관리)
 */
public class CsvUpdater {

    public static void main(String[] args) {
        // TODO: 리그 ID 목록 정의 및 API 호출 구현 예정
    }
}