package com.github.baek.footballobsbackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * BunnyCDN 프록시를 통해 API Football v3를 호출하는 HTTP 클라이언트.
 *
 * [왜 BunnyCDN을 거치는가?]
 * API Football의 API 키(x-apisports-key)를 프론트에 노출하지 않기 위해
 * BunnyCDN Edge Script에서 헤더를 주입함. 따라서 이 클래스에서는 API 키를 직접 다루지 않음.
 *
 * [역할 범위]
 * 이 클래스는 API 호출 + 응답의 "response" 배열 추출까지만 담당.
 * JSON 파싱 및 DTO 조립은 FixtureService에서 처리.
 */
@Slf4j
@Component
public class ApiFootballClient {

    private final RestClient restClient;

    /**
     * RestClient를 CDN baseUrl로 초기화.
     * application.yaml의 api.cdn-url을 주입받아 모든 요청의 base가 됨.
     */
    public ApiFootballClient(@Value("${api.cdn-url}") String cdnUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(cdnUrl)
                .build();
    }

    /**
     * 경기 상세 정보를 가져온다 (이벤트, 라인업, 스탯, 선수 스탯 포함).
     * API Football /fixtures 엔드포인트는 배열로 응답하지만 ID 단건 조회이므로 response[0]만 반환.
     *
     * @param fixtureId API Football 경기 ID
     * @return response[0] JsonNode. 경기 데이터 없으면 null.
     */
    public JsonNode getFixture(long fixtureId) {
        log.info("Fetching fixture id={}", fixtureId);

        // 1. CDN에 GET /fixtures?id={fixtureId} 요청
        JsonNode root = restClient.get()
                .uri("/fixtures?id=" + fixtureId)
                .retrieve()
                .body(JsonNode.class);

        // 2. 응답 자체가 null이면 중단
        if (root == null) return null;

        // 3. "response" 배열 확인 — 결과가 없으면 null 반환
        JsonNode response = root.path("response");
        if (!response.isArray() || response.isEmpty()) return null;

        // 4. 단건 조회이므로 첫 번째 원소만 꺼내서 반환
        return response.get(0);
    }

    /**
     * 해당 경기의 부상/결장 선수 목록을 가져온다.
     * API Football /injuries 엔드포인트는 fixtureId 기준으로 양팀 결장 선수를 배열로 반환.
     *
     * @param fixtureId API Football 경기 ID
     * @return injuries response 배열 JsonNode. 응답 없으면 null.
     */
    public JsonNode getInjuries(long fixtureId) {
        log.info("Fetching injuries fixtureId={}", fixtureId);

        // 1. CDN에 GET /injuries?ids={fixtureId} 요청
        JsonNode root = restClient.get()
                .uri("/injuries?ids=" + fixtureId)
                .retrieve()
                .body(JsonNode.class);

        // 2. 응답 null 체크 후 "response" 배열 그대로 반환 (여러 선수가 들어있음)
        if (root == null) return null;
        return root.path("response");
    }
}
