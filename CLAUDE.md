# CLAUDE_DEVLOG.md
> VS Code 안 Claude가 이 프로젝트의 맥락을 파악하기 위한 기술 devlog.
> claude.ai에서 진행된 대화 히스토리 기반으로 작성됨.

---

## 프로젝트 개요

**목적**: OBS 방송용 축구 점수판 오버레이 + 경기 정보 대시보드

**구성**:
- `overlay_dashboard.html` — 프론트엔드 단일 파일 (Vercel 배포)
- Spring Boot 백엔드 — 미완성 (Render 배포 예정)
- BunnyCDN — API Football 프록시 + 이미지 CDN
- API Football v3 — 유료 오픈 API

**협업**: 별도 프론트 개발자와 협업 예정 (점수판 디자인 이식 작업)

---

## 기술 스택

| 영역 | 기술 |
|---|---|
| 프론트 | HTML + CSS + Vanilla JS (단일 파일) |
| 백엔드 | Java Spring Boot |
| API | API Football v3 |
| CDN | BunnyCDN Pull Zone + Edge Scripting |
| 배포 | Vercel (프론트), Render (백엔드) |
| 위젯 | api-sports.io 공식 위젯 (일정 확인 탭) |

---

## 세션별 작업 기록

---

### 세션 1 — 2026-02-28 (UTC 13:11 ~ )
**파일**: `2026-03-01-17-00-38-football-scoreboard-ui-integration.txt`

**작업 내용**:
- 기존 수동 점수판(`FootballScorelineOBS`)을 API 대시보드로 이식 시작
- OBS 오버레이 HTML 구조 설계
- API Football 위젯 통합 (일정 확인 탭 4열 레이아웃)
- 스코어보드 포지셔닝 (상단 고정)
- 컨트롤 패널 구조 재설계
- Mock API 엔드포인트 stub 작성

---

### 세션 2 — 2026-02-28 ~ 2026-03-07 (UTC 13:11 ~ 09:19)
**파일**: `2026-03-07-18-42-51-football-scoreboard-ui-integration.txt`

**작업 내용**:
- 버튼 높이, 입력창 너비, 탭 정렬 UI 수정
- 메인 레이아웃 페이지 설계 (캠 큰/작은 버전)
- 테마 탭 컨트롤 패널 개선
- 단축키 체계 완성 (Space/R/T/[/]/q/a/w/s/z/x)
- velog 기술 블로그 포스트 초안 작성

---

### 세션 3 — 2026-03-07 (UTC 09:19) ~ 2026-03-08 (UTC 04:40)
**파일**: `2026-03-08-06-18-54-football-scoreboard-ui-backend-design.txt`

**주요 작업**:
- BunnyCDN Edge Script API 키 주입 방식 확인
- Spring Boot 백엔드 아키텍처 설계
- DTO 구조 확정 (아래 참고)
- CSV 선수 한글화 전략 수립

---

### 세션 4 — 2026-03-08 (UTC 04:40 ~ 06:43)
**파일**: `2026-03-08-07-08-42-football-scoreboard-ui-backend-design.txt`

**주요 작업**:

#### BunnyCDN 수정 (04:40 ~ 05:00, 약 20분)
- **문제**: API CDN이 Query String 무시 → 모든 경기 ID 응답이 같은 캐시 키
- **해결**: Pull Zone → Vary Cache → URL Query String 활성화, Query String Sort 비활성화, Purge Cache 실행

#### 일정 확인 탭 위젯 CSS 격리 (05:00 ~ 05:40, 약 40분)
- **문제**: 점수판 CSS 변수(`--home-bg`, `--away-bg` 등)가 위젯에 새어들어가 배경색 오염
- **해결**: `#game-content, #standings-content, #games-list { --home-bg: initial; ... }` 격리

#### game-item 높이 축소 (05:40 ~ 06:00, 약 20분)
- Games 컬럼 경기당 높이 줄이기
- `padding: 0`, `font-size: 12px`, `team-logo: 16px` 등 CSS override

#### auto-standings 트리거 (06:00 ~ 06:43, 약 43분)
- 경기 클릭 시 해당 리그 Standings 버튼 자동 클릭
- MutationObserver로 `game-item[data-id]` 감지 → `game-list-header .league-standings` 클릭

---

### 세션 5 — 2026-03-15 (KST)
**현재 대화 기반**

#### 위젯 CSS 격리 심화 (작업 시간 누적 약 2시간)

**문제 1**: `.name { font-size: 24px }` 등 점수판 전역 클래스가 위젯 선수명에 적용됨
- **원인**: `.name`, `.score`, `.digits`, `.meta`, `.half` 등이 전역 CSS로 선언됨
- **해결**: 전부 `.board .name`, `.board .score` 등 `.board` 하위로 스코프 제한
- **수정 파일**: `overlay_dashboard.html` CSS 섹션 (~213번째 줄)

**문제 2**: Shadow DOM vs iframe 파악
- `document.querySelector('api-sports-widget[data-type="game"]').shadowRoot` → `null` 반환
- → 일반 DOM임 확인, 전역 CSS가 직접 영향을 미치는 구조

**문제 3**: `box-sizing: content-box` 위젯 내부 오버라이드가 score 원형 뱃지 파괴
- **해결**: content-box 오버라이드 완전 제거

### 세션 6 — 2026-03-27 (KST)

#### 전술판 탭 추가

**신규 탭**: `page-tactics` — 포메이션 배치 + 드로잉 도구

**주요 구조**:
- 컨트롤 바: HOME/AWAY 포메이션 셀렉터 각각 독립, 초기화, 색상 적용, 이름 표시, 공 체크박스
- 피치: `height:100%; aspect-ratio:3/2` — 세로 꽉 채우면서 비율 유지
- 우측 드로잉 패널 (width:150px): 화살표/실선/점선/박스 + 10색 + 되돌리기/전체 지우기

**포메이션 좌표계**:
- `TACTICS_FM` — x,y 퍼센트 (GK→수비→미드→공격 순, 20개 포메이션)
- `TACTICS_LABELS` — 포메이션별 포지션 레이블 (포메이션 변경 시 실시간 반영)
- away 좌표 = home 좌표 미러 (`x: 100-x, y: 100-y`)

**점수판 연동**:
- `render()` 안에 `tacticsRenderTokens()` 호출 → 테마 탭 색 변경 시 자동 반영
- `homeBg/homeText/awayBg/awayText` → 토큰 원 색상/텍스트 색상
- `homeName/awayName` → 팀 레이블

**공 토큰**:
- `tactics-show-ball` 체크박스로 표시/숨김
- `tacticsState.ballPosition: { x: 50, y: 50 }` — 상태로 위치 관리
- `tacticsCreateBallToken()` — ⚽ 이모지, z-index:15, `data-kind="ball"`
- 드래그 가능, `tacticsDragMove`에서 `dataset.kind === 'ball'` 분기로 `ballPosition` 업데이트
- 초기화 시 중앙(50, 50)으로 리셋

**선택 도구 기본값 보장**:
- `tacticsInitDefaultSelect()` — 초기 로드 시 선택 모드 확정
    1. `tacticsApplyLineup(TACTICS_MOCK_LINEUP)`
    2. `tacticsDrawSetTool('select')`
    3. `draw-layer pointer-events: none` 명시
    4. `tacticsDragSetup()`
- `DOMContentLoaded` 타이밍 대응 (`readyState === 'loading'`이면 이벤트 등록, 아니면 즉시 실행)

**드로잉 버그 수정**:
- 화살표 색상 공유 버그: 단일 `<marker id="td-arrow">` → 색상별 고유 마커 동적 생성
- `tdEnsureMarker(layer, color)` — `#tdm{hex}` ID로 defs에 없으면 추가

**Fallback / 백엔드 연동 구조**:
```javascript
// 현재: TACTICS_MOCK_LINEUP (포지션 레이블로 표시)
// 백엔드 연동 시: _isReal: true 플래그 붙이면 nameKo(한글 이름) 표시
// 엔드포인트 예정: GET /api/lineups/{fixtureId}
// 응답 shape: { home: { teamName, formation, players: [{nameKo, number, pos, _isReal}] }, away: {...} }
```

**주의사항**:
- `tacticsState`는 스크립트 하단 선언 → `render()`에서 호출 시 `typeof tacticsState !== 'undefined'` 가드 필수
- `#page-tactics.active { display: flex }` — 전술판만 flex, 나머지 탭은 block 유지
- 드로잉 레이어(`#tactics-draw-layer`)는 선택 모드일 때 `pointer-events:none` → 선수 드래그와 충돌 방지
- `tacticsDragSetup`에서 `removeEventListener` 먼저 호출 후 재등록 — 중복 등록 방지

---

#### 경기 ID 바 인라인화 (약 30분)

**변경 전**: 일정 확인 탭 진입 시 별도 줄(`fixtureBar`)이 생겨 공간 낭비
**변경 후**: BMC 버튼 옆 탭바 인라인에 표시, 경기 ID 없을 때 숨김

```html
<!-- 탭바 안에 추가 -->
<div class="fixture-inline" id="fixture-inline-wrap" style="display:none">
  <span class="fixture-inline-label">경기 ID</span>
  <span id="selected-fixture-id" class="fixture-inline-id" title="클릭하여 복사">-</span>
</div>
<!-- 하단 토스트 -->
<div id="copy-toast" class="copy-toast">복사되었습니다!</div>
```

**경기 ID 클릭 → 클립보드 복사 + 토스트 알림 (1.8초 자동 소멸)**
- 이전 `copy-fixture-btn` 버튼 방식 → 클릭 복사 방식으로 교체

---

#### 메인(캠 작은) 레이아웃 5열 개편 (약 40분)

**변경 전**: `[events+stat 묶음] [lineup] [bench+injury] [cam-chat]` 4열
**변경 후**: `[events] [stat] [lineup] [bench+injury] [cam-chat]` 5열

```css
.layout-small .lp-col-events { flex: 1; }
.layout-small .lp-col-stat   { flex: 1; }
.layout-small .lp-lineup-s   { aspect-ratio: 2/3; flex: none; } /* 축구장 비율 */
.layout-small .lp-col-bench  { flex: 0 0 18%; }
.layout-small .lp-cam-chat   { flex: 0 0 14.5%; }
```

**lineup 너비**: `aspect-ratio: 2/3` — 세로 높이 기준 가로 자동 계산 (축구장 2:3 비율)

---

#### 득점자 자동 레이아웃 (약 1시간)

**추가 함수**: `formatScorers()` + `autoLayoutNote()` + `autoLayoutNotes()`

**동작 방식**:
1. 기본 폰트 크기로 **1명씩 1줄** 렌더링
2. boardH 초과 시 → **2명씩 1줄** 전환 (`12' 홍길동  45' 김철수`)
3. 여전히 초과 시 → **폰트 1px씩 축소** (최소 8px)
4. 홈/원정 **완전 독립** 처리 (CSS 변수 `--note-font-size-home`, `--note-font-size-away` 분리)
5. 테마 탭 `글자크기(px)` 입력창에 실제 적용된 크기 반영

**note-side 너비 동적 조정**:
- `position: absolute`, `board-stage-inner`를 `flex + justify-content: center`로 변경
- board 위치 고정, note-side는 board `offsetLeft`/`offsetWidth` 기준으로 JS에서 배치
- `scrollWidth + 20px` 패딩으로 너비 자동 결정
- `window resize` + `ResizeObserver` 로 개발자도구 열고닫기 시에도 재계산

**테스트 케이스** (콘솔에서 사용):
```javascript
// 케이스 1: 한글 이름
state.notes.home = "12' 홍길동\n34' 김철수\n67' 이민준";
state.notes.away = "23' 박지성\n89' 손흥민";
render();

// 케이스 2: 긴 영어 이름 10명 (2줄+폰트축소 테스트)
state.notes.home = "12' Max Alleyne\n24' Rodri\n42' OG Jake Doyle-Hayes\n45+2' OG Jack Fitzwater\n49' Rico Lewis\n54' Antoine Semenyo\n71' Tijjani Reijnders\n79' Nico O'Reilly\n86' Ryan McAidoo\n90+1' Rico Lewis";
state.notes.away = "";
render();

// 케이스 8: 초기화
state.notes.home = '';
state.notes.away = '';
render();
```

---

## 현재 파일 구조 (overlay_dashboard.html)

```
HTML 단일 파일 (~2400줄)
├── <style> CSS
│   ├── CSS 변수 (--home-bg, --board-a, --note-font-size-home 등)
│   ├── 점수판 스타일 (.board .name, .board .score 등 — .board 하위로 스코프 제한)
│   ├── 득점자 (.note-side, .note.home, .note.away)
│   ├── 탭/페이지 레이아웃
│   ├── 일정 확인 탭 위젯 CSS (.widgetGrid, #games-list 오버라이드)
│   ├── 테마 탭 컨트롤
│   └── 토스트 (.copy-toast)
├── <body>
│   ├── board-stage-wrap (OBS 캡처 영역)
│   │   └── board-stage-inner (flex center)
│   │       ├── homeNoteSide (absolute, board 왼쪽)
│   │       ├── board (grid 3열)
│   │       └── awayNoteSide (absolute, board 오른쪽)
│   ├── tabs (tabsBar)
│   │   ├── tab buttons
│   │   ├── BMC 버튼
│   │   ├── fixture-inline-wrap (경기 ID 인라인)
│   │   └── 경기ID 입력 버튼
│   ├── copy-toast
│   └── pages
│       ├── page-main-big
│       ├── page-main-small (5열 레이아웃)
│       ├── page-theme (테마 컨트롤)
│       ├── page-schedule (위젯 4열 그리드)
│       └── page-about
└── <script>
    ├── state 초기화
    ├── render()
    ├── autoLayoutNotes() + autoLayoutNote() + formatScorers()
    ├── applyBoardScale()
    ├── 타이머 로직
    ├── fetchAndApplyFixtureData() (현재 stub)
    ├── 일정 확인 탭 위젯 MutationObserver
    └── 이벤트 핸들러
```

---

## 중요한 CSS 규칙

### 점수판 변수가 위젯에 새어들어가지 않도록
```css
#game-content, #standings-content, #team-content, #games-list {
  --home-bg: initial;
  --away-bg: initial;
  --home-text: initial;
  /* ... 전부 initial */
}
```

### 점수판 클래스는 반드시 .board 하위로만
```css
/* 이렇게 하면 안됨 */
.name { font-size: 24px; }
/* 이렇게 해야 함 */
.board .name { font-size: 24px; }
```

### 득점자 폰트는 홈/원정 분리
```css
.note.home { font-size: var(--note-font-size-home); }
.note.away { font-size: var(--note-font-size-away); }
```

---

## 백엔드 설계 (미완성)

### 엔드포인트
```
GET /fixtures/{fixtureId}  → FixtureResponseDTO
GET /injuries/{fixtureId}  → List<InjuryDTO>
```

### DTO 구조
```java
FixtureResponseDTO {
  MatchInfoDTO matchInfo        // 기본 정보 + 팀 색상 + 로고
  List<EventDTO> events         // 골/카드/교체
  List<TeamStatsDTO> teamStats  // 경기 스탯 홈/원정
  LineupDTO homeLineup          // 포메이션 + 선발 + 벤치 + 감독
  LineupDTO awayLineup
  List<PlayerStatsDTO> players  // 개인 스탯 (팝업용)
}
```

### API status → half 변환
```
1H              → 1H
HT              → HT
2H              → 2H
ET (elapsed≤105)→ ET1
ET (elapsed>105)→ ET2
P               → PK
FT              → FT
```

### CSV 구조 (계획)

**players.csv**
```
player_id, name_short, position, nationality, name_ko_long, name_ko_short
```
- Spring Boot `/players/squads`로 `player_id, name_short, position` 자동 수집
- 스프레드시트(라틴문자 풀네임, 포지션, 국적, 한국어 이름)와 성+포지션 매칭으로 합치기

**team_logos.csv**
```
team_id, team_name, logo_url
```
- API Football PNG URL 기본값, 커스텀은 `indvel.github.io` 로고 repo URL 사용
- 협업 프론트 개발자 로고 repo: `https://indvel.github.io/utils/fsm/logos/`

**coaches.csv**
```
coach_id, coach_name_api, coach_name_ko
```

**referees.csv**
```
referee_name_api, referee_name_ko
```

### 선수 CSV 수집 방법
```
API: GET /players/squads?team={team_id}
특징: 페이지네이션 없음, 스쿼드 전체 한 번에 반환
필드: id, name(약식), position, age, number, photo
```

---

## state 플래그 (중요)

```javascript
state.fixtureLinked       // 경기 ID 연동 여부
state.halfManualOverride  // 전/후반 수동 조작 시 true → 자동 업데이트 건너뜀
state.extraManualOverride // 추가시간 수동 입력 시 true
```

경기 ID 새로 입력 시 전부 리셋됨.

---

## 배포 구조

```
obs-overlay (프론트) → Vercel
obs-backend (Spring Boot) → Render
obs-logos (커스텀 이미지) → indvel.github.io (협업자 repo)
```

- GitHub repo: 현재 **private** (API 키 commit 히스토리 있어서)
- 백엔드 완성 후 public repo 별도 생성해서 릴리즈 예정
- 협업자 초대: GitHub Collaborator로 private 상태에서도 협업 가능

---

## BunnyCDN 설정 현황

**API CDN** (`obs-scoreline-overlay.b-cdn.net`):
- Vary Cache → URL Query String 활성화 ✅ (경기 ID별 별도 캐시)
- Query String Sort 비활성화 ✅
- TTL: 1분
- Edge Script: 요청 헤더에 `x-apisports-key` 주입

**미디어 CDN** (`media-handle-obsoverlay.b-cdn.net`):
- Smart Cache 활성화
- Edge Rules TTL: League Logo 14일, Flag 365일, Team Logo 7일, Player 7일

---

## 위젯 설정 (일정 확인 탭)

```html
<api-sports-widget
  data-type="config" data-sport="football" data-key=""
  data-url-football="https://obs-scoreline-overlay.b-cdn.net/"
  data-logo-url="https://media-handle-obsoverlay.b-cdn.net/"
  data-lang="en" data-theme="grey"
  data-refresh="20" data-favorite="true" data-standings="true"
  data-team-squad="true" data-team-statistics="true" data-player-statistics="true"
  data-tab="games" data-game-tab="statistics"
  data-target-league="#games-list" data-target-game="#game-content"
  data-target-standings="#standings-content" data-target-team="#team-content"
  data-target-player="modal"
></api-sports-widget>
```

---

### 세션 7 — 2026-03-29 (KST)

**작업 파일**: `overlay_dashboard_five_cols_20260328.html` (이전 파일 복사 후 수정)

#### 전술판 드로잉 도구 완성 (세션 6 미완성분)

**곡선 화살표 자동 방향 수정** — `tdCalcCurveCtrl()`
- 문제: 모든 곡선이 항상 같은 방향으로 휘어짐
- 해결: 화면 픽셀 기준 수직 벡터 두 후보 계산 → `|cy - 50|` 더 큰 쪽(피치 중앙 기준 바깥쪽) 선택
- 3:2 비율 왜곡 보정: viewBox % 아닌 실제 픽셀로 계산 후 다시 % 변환

**다각형 팀 필터** — 드래그 시작점 기준
- 문제: DOM 순서상 첫 토큰 = 항상 홈팀 → 원정팀만 다각형 불가
- 해결: `data-team="home"/"away"` 속성 + 드래그 시작점에서 가장 가까운 토큰의 팀으로 필터

**색상 선택 시각 피드백** — `tacticsDrawSetColor()`
- 선택된 색상 버튼에 `✓` 텍스트 + 흰 테두리 ring + 검정 외곽선

**점선 화살표 추가** — `dashed-arrow` 타입
- `stroke-dasharray: 1.5 1` + 마커 적용

**곡선 방향 반전 (클릭)** — `tdFlipCurve()` + `tdHitTestCurve()`
- 선택 모드에서 피치 클릭 → 베지어 샘플링(t: 0→1, step 0.05)으로 14px 이내 hit 감지
- 컨트롤 포인트를 중점 기준 대칭 이동: `cx = 2*mx - cx`
- draw-layer는 select 모드에서 `pointer-events:none`이므로 pitch div에 click 핸들러 부착

---

#### 지우개 + Undo/Redo 시스템

**지우개 도구** — `eraser` 타입 추가
- 클릭 또는 드래그 시 `tdHitTestAny()`로 닿는 도형 즉시 삭제
- 지우개 모드 진입 시: OS 커서 숨김(`cursor: none`) + 반투명 빨간 원 div(`#td-eraser-cursor`)가 마우스 따라다님
- 피치 밖으로 나가면(`mouseleave`) 커서 div 숨김

**`tdHitTestAny(px, py)`** — 모든 도형 타입 hit test (픽셀 기준)
| 타입 | 판정 방법 |
|---|---|
| line / dashed / arrow / dashed-arrow | 선분 최근접점 거리 ≤ 14px |
| curve-arrow / curve-dashed-arrow | 베지어 샘플링 (step 0.04) ≤ 14px |
| box | 4변 각각 최근접점 거리 ≤ 14px |
| circle | 타원 경계까지 거리 근사 ≤ 14px |
| polygon | 각 edge 최근접점 거리 ≤ 14px |

**Undo/Redo 스택** — `tdHistory[]` / `tdFuture[]`

```javascript
// 액션 타입
{ type: 'draw',  drawing: <ref> }           // 도형 추가
{ type: 'erase', index: <int>, drawing: <ref> } // 도형 삭제
{ type: 'move',  kind, idx, fromX, fromY, toX, toY } // 선수 이동
{ type: 'flip',  index, prevCx, prevCy }    // 곡선 반전
```

- `tdHistoryPush()` 호출 시 `tdFuture` 초기화 (새 작업 → redo 불가)
- `tdUndo()`: `tdHistory` pop → `tdFuture` push → 역적용
- `tdRedo()`: `tdFuture` pop → `tdHistory` push(직접, push 시 future 초기화 안 함) → 재적용
- 선수 이동: `tacticsDragStart`에서 시작 좌표 기록, `tacticsDragEnd`에서 실제 이동된 경우만 push
- Ctrl+Z / Ctrl+Y: 기존 `z` 키(PK undo)와 충돌 없도록 `e.ctrlKey` 조건 먼저 체크
- 전체 지우기 / 초기화 시 history + future 모두 초기화

---

#### 레이저 포인터 (GoodNotes 스타일)

**캔버스**: `#td-laser-canvas` — 피치 위에 z-index:25, pointer-events:none, 피치와 동일 크기

**스트로크 구조**:
```javascript
tdLaserStrokes = [
  { pts: [{x,y}, ...], fadeStart: null }
]
```
- 클릭+드래그 중에만 획이 쌓임 (이동만 해도 점만 표시)
- 마우스를 떼면 `TD_LASER_IDLE_MS`(300ms) 후 페이드 시작
- 각 획은 `TD_LASER_GAP_MS`(150ms) 간격으로 순서대로 페이드
- 페이드 시간 `TD_LASER_FADE_MS`(350ms), opacity 1→0 선형

**RAF 루프**:
- `tdLaserAnimId` — requestAnimationFrame ID
- 모든 스트로크 렌더 → `fadeStart`가 지난 스트로크는 opacity 계산 후 그림
- 전체 사라지면 루프 자동 종료

**커서 dot**: `tdLaserLastPt` — 마우스 오버 시 항상 작은 빨간 점 표시 (클릭 여부 무관)

**주의사항**:
- 드로잉 레이어 SVG와 별개 캔버스 — `tdRenderAll()`과 독립 작동
- 레이저 모드 해제 시 `tdLaserStop()` 호출 → 스트로크 초기화 + 캔버스 clear

---

#### 탭 숨기기 버튼 + 1~8 단축키

**탭 숨기기 버튼**:
```html
<div style="display:flex;gap:4px;">
  <button id="open-fixture-overlay" class="right-btn">경기ID 입력</button>
  <button onclick="toggleTabsAndPages()" class="right-btn" id="btn-toggle-tabs">탭 숨기기 (H)</button>
</div>
```
- 경기ID 입력 버튼 바로 오른쪽에 붙여서 배치
- 클릭 시 H 키와 동일한 `toggleTabsAndPages()` 호출
- 버튼 텍스트가 `탭 숨기기 (H)` ↔ `탭 보이기 (H)` 토글

**1~8 단축키** (기존 Space/R/T/[/]/q/a/w/s/z/x 체계에 추가):
```javascript
const tabPages = ['main-big','main-small','theme','schedule','tactics','about'];
if (e.key >= '1' && e.key <= '6') activatePage(tabPages[+e.key - 1]);
if (e.key === '7') document.getElementById('open-fixture-overlay')?.click();
if (e.key === '8') window.open('https://www.buymeacoffee.com/bgh1234554', '_blank');
```

---

#### 다중 선택 그룹 이동

**선택 상태**:
```javascript
let tdSelectedTokens = new Set(); // 선택된 .tactics-token DOM 요소들
let tdGroupDrag = null;           // { tokens:[{tok,offX,offY,kind,idx,fromX,fromY}], pitchRect }
```

**선택 rect** (`#td-select-rect`):
- select 모드에서 빈 피치 pointerdown → 선택 시작
- pointermove 중 파란 점선 rect 표시 + 내부 토큰 실시간 glow 미리보기
- pointerup → rect 내부 토큰을 `tdSelectedTokens`에 수집
- 드래그 거리가 1% 미만이면 단순 클릭으로 판정 → 선택 해제

**그룹 이동**:
- 선택된 토큰 중 하나를 드래그하면 그룹 이동 진입
- 각 토큰이 독립적으로 `Math.max(1, Math.min(99, ...))` 경계 clamping
- 이동 완료 시 모든 토큰 각각 undo 기록 (`type: 'move'`)

**glow 우선순위**:
- `tdApplyGlows()` 실행 시 선택된 토큰은 초기화 건너뜀 (`tdSelectedTokens.has(tok)` 가드)
- glow 재계산 후 `tdSelectApplyGlow()` 추가 호출 → polygon glow 위에 selection glow 덮어씌움
- 선택 해제(`tdClearSelection()`) 시 `tdApplyGlows()` 호출 → polygon glow 복원

**도구 전환 시 선택 해제**:
```javascript
if (t !== 'select') tdClearSelection();
```

---

#### polygon/line-connect 이동 시 도형 처리

**`tdRemovePolygonsForMovedTokens(movedIndices)`**:
- `type: 'polygon'` 또는 `type: 'line-connect'`이고 `playerIndices`가 있는 드로잉에 대해:
    - **전체 구성원이 이동** → 새 토큰 위치로 `points` 재계산 (polygon은 convex hull, line-connect는 y 정렬)
    - **일부만 이동** → `tdDrawings`에서 제거 + 나머지 구성원 glow도 사라짐
- 단일 토큰 드래그, 그룹 드래그 양쪽 모두 dragEnd에서 호출

---

#### 라인 연결 도구 (line-connect)

**동작**: polygon 도구와 동일하게 드래그 선택 → 선택된 토큰을 **y 좌표 오름차순(위→아래)**으로 정렬 → `<polyline>` 렌더

```javascript
// pointerup 시
points = selected.slice().sort((a, b) => a.y - b.y).map(p => ({x: p.x, y: p.y}));
// tdRemovePolygonsForMovedTokens 내 재계산 시
d.points = newPts.slice().sort((a, b) => a.y - b.y);
```

**polygon과의 차이점**:
| | polygon | line-connect |
|---|---|---|
| 렌더 | `<polygon>` + fill 음영 | `<polyline>` fill:none |
| 최소 인원 | 3명 | 2명 |
| 점 정렬 | convex hull | y 오름차순 |
| hit test | 닫힌 루프 (len = pts.length) | 열린 선 (len = pts.length - 1) |

**glow**: polygon과 동일하게 `tdApplyGlows()`에서 `glowMap`에 반영
```javascript
if ((d.type === 'polygon' || d.type === 'line-connect') && d.playerIndices) {
  d.playerIndices.forEach(i => { glowMap[i] = d.color; });
}
```

**주의사항**:
- 버튼 tooltip에 "x 순서로 직선 연결"이라고 남아 있어 추후 수정 필요 (실제 동작은 y 순서)
- 도구 ID: `td-tool-line-connect`, 타입 문자열: `'line-connect'`

---

## 미완성 / TODO

### 백엔드 (우선순위 높음)
- [ ] Spring Boot 백엔드 구현 및 Render 배포
- [ ] `/injuries/{fixtureId}` DTO 설계 (response 예시 아직 미수신)
- [ ] 실제 API 데이터로 메인 화면 패널 채우기 (백엔드 완성 후)
- [ ] 선수 한글 이름 CSV 구축 (`/players/squads` + 스프레드시트 매칭)

### 프론트 (백엔드 연동 시)
- [ ] 선수 클릭 → 개인 스탯 팝업 UI
- [ ] 포메이션 그리드 렌더링 (null이면 줄글 폴백)
- [ ] 전술판 — 백엔드 라인업 연동 (`GET /api/lineups/{fixtureId}`, `_isReal: true` 플래그 시 한글 이름 표시)

### 기타
- [ ] 협업 프론트 개발자 점수판 디자인 이식
- [ ] JS 파일 역할별 분리 (state.js, render.js, notes.js, timer.js, fixture.js 등)
- [ ] public repo 생성 및 소스 공개

---

## 주의사항

1. `.name`, `.score`, `.digits`, `.meta`, `.half` 등 점수판 클래스는 **반드시 `.board` 하위**에만 적용할 것 (위젯 오염 방지)
2. 위젯 CSS 격리를 위해 `--home-bg: initial` 등 CSS 변수 리셋 블록 절대 제거하지 말 것
3. `box-sizing: content-box` 위젯 오버라이드 금지 (score 원형 뱃지 파괴됨)
4. `note-side`는 `position: absolute` + board 기준 배치 — flex/grid 흐름에서 분리돼 있음
5. `board-stage-inner`는 `flex + justify-content: center` — board 위치 항상 가운데 고정
