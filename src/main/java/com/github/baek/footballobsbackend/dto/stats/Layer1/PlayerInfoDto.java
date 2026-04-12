package com.github.baek.footballobsbackend.dto.stats.Layer1;

import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.PlayerBirthDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerInfoDto {
    private long id;
    private String name;        // 한글 우선, 없으면 API 영문 (name_ko_short → name_short → API name 순)
    private String fullName;    // 한글 풀네임 우선, 없으면 API firstname + " " + lastname
    private int age;
    private PlayerBirthDto birth;
    private String nationality;
    private String height;      // "175 cm" 형태의 문자열
    private String weight;      // "71 kg" 형태의 문자열
    private String photoUrl;    // media CDN URL
}
