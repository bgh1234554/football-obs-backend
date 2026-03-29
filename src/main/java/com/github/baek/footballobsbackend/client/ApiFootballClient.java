package com.github.baek.footballobsbackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
RAW 형태의 API를 Api Football에서 그대로 호출하는 클래스
 */
@Component
public class ApiFootballClient {

    @Value("${api.cdn-url}")
    private String cdnUrl;

    public JsonNode getFixture(long fixtureId) {
        // RestClient로 cdnUrl 호출
        return null;
    }

    public JsonNode getInjuries(long fixtureId){
        // RestClient로 cdnUrl 호출
        return null;
    }
}
