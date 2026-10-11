package com.github.baek.footballobsbackend.web;

import com.github.baek.footballobsbackend.dto.fixtures.TeamFixturesResponseDto;
import com.github.baek.footballobsbackend.error.GlobalExceptionAdvice;
import com.github.baek.footballobsbackend.service.TeamFixturesService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TeamFixturesControllerTest {
    private final TeamFixturesService service = mock(TeamFixturesService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TeamFixturesController(service))
            .setControllerAdvice(new GlobalExceptionAdvice()).build();

    @Test
    void routesBothQueriesAndReturnsEmptyListWithCacheHeader() throws Exception {
        for (boolean upcoming : new boolean[]{false, true}) {
            when(service.getTeamFixtures(85, upcoming)).thenReturn(new TeamFixturesResponseDto(85, List.of()));
            mvc.perform(get("/api/fixtures/" + (upcoming ? "next" : "last")).param("teamId", "85"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "max-age=300"))
                    .andExpect(jsonPath("$.teamId").value(85))
                    .andExpect(jsonPath("$.matches").isEmpty());
            verify(service).getTeamFixtures(85, upcoming);
        }
    }

    @Test
    void rejectsMissingNonpositiveNonNumericAndOverflowIds() throws Exception {
        mvc.perform(get("/api/fixtures/last"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TEAM_ID"));
        for (String teamId : new String[]{"0", "-1", "abc", "9223372036854775808"}) {
            mvc.perform(get("/api/fixtures/next").param("teamId", teamId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_TEAM_ID"));
        }
        verifyNoInteractions(service);
    }
}
