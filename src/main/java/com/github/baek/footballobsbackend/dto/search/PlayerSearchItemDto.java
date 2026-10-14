package com.github.baek.footballobsbackend.dto.search;

/** ID 검색과 영문 이름 검색에서 공통으로 사용하는 후보 프로필. */
public record PlayerSearchItemDto(long id, String name, String fullName, String englishName,
                                  String photoUrl, String nationality, Integer age,
                                  Integer number, String position) {}
