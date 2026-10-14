package com.github.baek.footballobsbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.baek.footballobsbackend.client.ApiFootballClient;
import com.github.baek.footballobsbackend.error.ApiException;
import com.github.baek.footballobsbackend.error.ErrorCode;
import com.github.baek.footballobsbackend.util.CsvLoader;
import com.github.baek.footballobsbackend.util.KoResolver;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerSearchServiceTest {
    private final ApiFootballClient client = mock(ApiFootballClient.class);
    private final CsvLoader csv = mock(CsvLoader.class);
    private final KoResolver ko = mock(KoResolver.class);
    private final PlayerService service = new PlayerService(client, csv, ko);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void preservesNameSpacesAndUpstreamPagingAndLocalizesProfiles() throws Exception {
        when(client.searchPlayerProfiles(" German Iva", 2)).thenReturn(mapper.readTree("""
            {"paging":{"current":2,"total":3},"response":[{"player":{"id":5075,"name":"G. Boga",
             "firstname":"Germán Canal","lastname":"Boga","age":42,"number":null,"nationality":"Andorra",
             "position":"Goalkeeper","photo":"https://media.api-sports.io/football/players/5075.png"}}]}
            """));
        when(ko.resolvePlayerDisplayName(5075, "G. Boga")).thenReturn("게르만 보가");
        when(csv.getPlayerNameKoLong(5075)).thenReturn("게르만 카날 보가");
        when(csv.getTeamNameKoByName("Andorra")).thenReturn("안도라");
        when(ko.toMediaCdnUrl(anyString())).thenReturn("https://media.example/5075.png");
        var result = service.searchPlayers(" German Iva", "2");
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(3);
        var player = result.players().getFirst();
        assertThat(player.name()).isEqualTo("게르만 보가");
        assertThat(player.fullName()).isEqualTo("게르만 카날 보가");
        assertThat(player.englishName()).isEqualTo("Germán Canal Boga");
        assertThat(player.nationality()).isEqualTo("안도라");
        assertThat(player.number()).isNull();
        assertThat(player.photoUrl()).isEqualTo("https://media.example/5075.png");
        verify(client).searchPlayerProfiles(" German Iva", 2);
    }
    @Test void normalizesNumericIdAndAlwaysUsesFirstPageWithoutStatsCalls() throws Exception {
        when(client.searchPlayerProfiles("5075", 1)).thenReturn(mapper.readTree("{\"paging\":{\"current\":1,\"total\":1},\"response\":[]}"));
        var result = service.searchPlayers(" 005075 ", "9");
        assertThat(result.players()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        verify(client).searchPlayerProfiles("5075", 1);
        verifyNoMoreInteractions(client);
    }
    @Test void keepsLeadingSpaceThatCountsTowardsSearchLength() throws Exception {
        when(client.searchPlayerProfiles(" Iva", 1)).thenReturn(mapper.readTree("{\"paging\":{\"current\":1,\"total\":2},\"response\":[]}"));
        assertThat(service.searchPlayers(" Iva", "1").totalPages()).isEqualTo(2);
        verify(client).searchPlayerProfiles(" Iva", 1);
    }
    @Test void rejectsInvalidQueriesAndPagesBeforeCallingUpstream() {
        for (String query : new String[]{"", "    ", "abc", "0", "9223372036854775808", "a".repeat(129)}) {
            assertThatThrownBy(() -> service.searchPlayers(query, "1")).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PLAYER_SEARCH));
        }
        for (String page : new String[]{"0", "-1", "text", "2147483648"}) {
            assertThatThrownBy(() -> service.searchPlayers("Smith", page)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(client);
    }
}
