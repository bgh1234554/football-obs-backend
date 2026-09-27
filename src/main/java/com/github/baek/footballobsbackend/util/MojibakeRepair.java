package com.github.baek.footballobsbackend.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * API Football 원본 데이터에 섞여 오는 인코딩 깨짐(mojibake)을 복구한다.
 *
 * [증상] players.csv에 없어 API 영문 이름이 그대로 내려가는 선수 중 일부가
 * "K. Urbański" 대신 "K. UrbaÅ„ski"처럼 표시된다. UTF-8 바이트(ń = C5 84)를 업스트림 어딘가에서
 * Windows-1252로 잘못 읽은 뒤 다시 UTF-8로 저장한 전형적인 형태다. 우리 쪽 RestClient/Jackson은
 * 응답 바이트를 UTF-8로 정상 해석하므로, 깨진 문자열 자체가 API 응답에 들어 있는 것으로 본다.
 *
 * [복구 방법] 문자열을 Windows-1252 바이트로 되돌린 뒤 UTF-8로 "엄격하게" 다시 읽는다.
 * 다음 조건을 모두 만족할 때만 바꾸므로 정상 문자열은 건드리지 않는다.
 *   1) ASCII가 아닌 문자가 있어야 한다.
 *   2) 모든 문자가 1바이트(Windows-1252/Latin-1)로 표현돼야 한다 — 한글, ł 같은 문자가 있으면 원본 유지.
 *   3) 그 바이트열이 올바른 UTF-8이어야 한다 — "Müller"(FC), "Åke"(C5 6B)처럼 정상 악센트 이름은
 *      UTF-8로 유효하지 않아 걸러진다.
 * 이중으로 깨진 경우를 위해 최대 2회 반복한다.
 */
public final class MojibakeRepair {

    // Windows-1252에서 0x80~0x9F 구간에 매핑된 문자 → 원래 바이트. 나머지 0x00~0xFF는 Latin-1과 같다.
    private static final Map<Character, Byte> CP1252_SPECIAL = new HashMap<>();

    static {
        char[] chars = {
                '€', 0, '‚', 'ƒ', '„', '…', '†', '‡',
                'ˆ', '‰', 'Š', '‹', 'Œ', 0, 'Ž', 0,
                0, '‘', '’', '“', '”', '•', '–', '—',
                '˜', '™', 'š', '›', 'œ', 0, 'ž', 'Ÿ',
        };
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] != 0) CP1252_SPECIAL.put(chars[i], (byte) (0x80 + i));
        }
    }

    private MojibakeRepair() {
    }

    /** 깨진 문자열이면 복구한 값, 아니면 원본을 그대로 반환한다. null은 null. */
    public static String repair(String value) {
        if (value == null) return null;
        String current = value;
        for (int i = 0; i < 2; i++) {
            String repaired = repairOnce(current);
            if (repaired == null) break;
            current = repaired;
        }
        return current;
    }

    /** JSON 트리의 모든 문자열 값을 제자리에서 복구한다(키는 그대로). */
    public static void repairTree(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Map<String, String> replacements = new HashMap<>();
            for (Map.Entry<String, JsonNode> field : obj.properties()) {
                JsonNode child = field.getValue();
                if (child.isTextual()) {
                    String repaired = repair(child.textValue());
                    if (!repaired.equals(child.textValue())) replacements.put(field.getKey(), repaired);
                } else {
                    repairTree(child);
                }
            }
            replacements.forEach((key, repaired) -> obj.set(key, TextNode.valueOf(repaired)));
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child.isTextual()) {
                    String repaired = repair(child.textValue());
                    if (!repaired.equals(child.textValue())) arr.set(i, TextNode.valueOf(repaired));
                } else {
                    repairTree(child);
                }
            }
        }
    }

    /** 한 번 복구를 시도한다. 조건을 만족하지 않으면 null. */
    private static String repairOnce(String value) {
        byte[] bytes = new byte[value.length()];
        boolean hasNonAscii = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                bytes[i] = (byte) c;
                continue;
            }
            hasNonAscii = true;
            Byte special = CP1252_SPECIAL.get(c);
            if (special != null) {
                bytes[i] = special;
            } else if (c <= 0xFF) {
                // 0xA0~0xFF, 그리고 Windows-1252에 없는 0x81/0x8D/0x8F/0x90/0x9D(Latin-1 제어문자로 남는 경우)
                bytes[i] = (byte) c;
            } else {
                return null; // 1바이트로 표현할 수 없는 문자 → 정상 문자열로 본다
            }
        }
        if (!hasNonAscii) return null;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
