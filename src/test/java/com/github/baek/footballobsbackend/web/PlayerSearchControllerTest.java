package com.github.baek.footballobsbackend.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.baek.footballobsbackend.client.ApiFootballClient;
import com.github.baek.footballobsbackend.error.GlobalExceptionAdvice;
import com.github.baek.footballobsbackend.service.PlayerService;
import com.github.baek.footballobsbackend.util.CsvLoader;
import com.github.baek.footballobsbackend.util.KoResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlayerSearchControllerTest {
    @Test void exposesSharedDtoAndRejectsBadInputWith400() throws Exception {
        var client = mock(ApiFootballClient.class);
        when(client.searchPlayerProfiles("Smith", 2)).thenReturn(new ObjectMapper().readTree("{\"paging\":{\"current\":2,\"total\":3},\"response\":[]}"));
        var service = new PlayerService(client, mock(CsvLoader.class), mock(KoResolver.class));
        var mvc = MockMvcBuilders.standaloneSetup(new PlayerController(service)).setControllerAdvice(new GlobalExceptionAdvice()).build();
        mvc.perform(get("/api/players/search").param("query", "Smith").param("page", "2"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=3600"))
            .andExpect(jsonPath("$.players").isEmpty()).andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/players/search")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PLAYER_SEARCH"));
        mvc.perform(get("/api/players/search").param("query", "Smith").param("page", "abc")).andExpect(status().isBadRequest());
        verify(client).searchPlayerProfiles("Smith", 2);
        verifyNoMoreInteractions(client);
    }
}
