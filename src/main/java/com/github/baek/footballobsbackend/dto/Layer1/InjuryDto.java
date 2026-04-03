package com.github.baek.footballobsbackend.dto.Layer1;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InjuryDto {
    private long playerId;
    private String playerName;      // 한글 우선, 없으면 영문
    private String playerPhotoUrl;  // media CDN URL

    private String type;            // "Missing Fixture"
    private String reason;          // "Injury" | "Knee Injury" | "Muscle Injury" | ...

    private long teamId;
    private String teamName;
    private String teamLogo;        // media CDN URL
}