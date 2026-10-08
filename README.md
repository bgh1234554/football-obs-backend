# football-obs-backend

[OBS Football Dashboard](https://github.com/bgh1234554/football-obs-frontend)의 백엔드입니다. API-Football v3에서 경기 데이터를 받아 대시보드가 쓰기 좋은 형태로 가공하고, 팀·선수·감독·주심·경기장·리그 이름을 한글로 바꿔 내려줍니다.

대시보드 사용법과 출시 소식은 [프런트엔드 저장소](https://github.com/bgh1234554/football-obs-frontend)에서 관리합니다. 이 저장소는 백엔드 코드와 한글화 데이터만 다룹니다.

## 목차

- [구성](#구성)
- [API](#api)
- [한글화 데이터](#한글화-데이터)
- [로컬에서 실행하기](#로컬에서-실행하기)
- [배포](#배포)
- [문서](#문서)
- [문의](#문의)
- [데이터 출처와 라이선스](#데이터-출처와-라이선스)

## 구성

```text
대시보드(Vercel) -> 이 백엔드(Render) -> BunnyCDN -> API-Football v3
                         |
                         +-> CSV 한글화 데이터 (src/main/resources/data)
```

- **BunnyCDN**: API-Football 요청에 API 키를 붙이고 응답을 캐싱합니다. 백엔드 코드에는 API 키가 없습니다.
- **가공**: 경기 상태 변환, 이벤트·라인업·스탯 정리, 팀 컬러 대비 보정, 깨진 문자열 복구를 합니다.
- **한글화**: 앱 시작 시 CSV를 메모리에 올려 ID 또는 이름으로 한글 표기를 찾습니다. 없으면 API 영문 값을 그대로 씁니다.
- **이미지**: 팀 로고는 `logos.csv`에 등록된 URL([football-obs-logo-cdn](https://github.com/bgh1234554/football-obs-logo-cdn) 등)을 먼저 쓰고, 없으면 API-Football 이미지 주소를 미디어 CDN 주소로 바꿔 내려줍니다.

기술 스택: Java 21, Spring Boot 3.5, Gradle, Bucket4j(요청 제한), Caffeine

## API

| 메서드 | 경로 | 내용 | 응답 캐시 |
| --- | --- | --- | --- |
| GET | `/api/fixtures/{fixtureId}` | 경기 정보, 이벤트, 라인업, 팀·선수 스탯, 부상·결장 선수 | 15초 |
| GET | `/api/hth?teamA={teamId}&teamB={teamId}` | 두 팀의 상대전적 | 1시간 |
| GET | `/api/playerStats/{playerId}` | 선수 프로필과 시즌·대회별 스탯 | 12시간 |
| GET | `/actuator/health` | 서버 상태 확인 | - |

- 응답 필드 설명: [docs/api-endpoints.md](https://github.com/bgh1234554/football-obs-frontend/blob/develop/docs/api-endpoints.md) (프런트엔드 저장소)
- `/api/**`에는 IP별 요청 제한이 있습니다. 연속 15회까지 허용하고 이후 4초마다 1회씩 다시 허용하며, 초과하면 `429`와 재시도 대기 시간을 돌려줍니다. 자세한 내용은 [docs/rate-limiting.md](docs/rate-limiting.md)를 참고하세요.

이 API는 OBS Football Dashboard 전용입니다. 응답 형식은 대시보드에 맞춰 예고 없이 바뀔 수 있습니다.

## 한글화 데이터

`src/main/resources/data/`의 CSV 파일이 한글 표기의 원본입니다.

| 파일 | 내용 |
| --- | --- |
| `teams.csv` | 팀 한글 이름·단축명, API에 팀 컬러가 없을 때 쓸 색상 |
| `players.csv` | 선수 영문 이름, 국적, 한글 이름·단축명 |
| `coaches.csv` | 감독 한글 이름 |
| `referees.csv` | 주심 한글 이름과 국가 |
| `venues.csv` | 경기장·도시 한글 이름 |
| `leagues.csv` | 리그 한글 이름, 커스텀 로고 URL |
| `logos.csv` | 팀 커스텀 로고 URL, 국가대표 협회 로고 URL |

### 빠진 한글 표기 채우기

한글 표기가 없으면 서버 로그에 다음 키워드가 남습니다.

| 키워드 | 채울 파일 |
| --- | --- |
| `[KO_TEAM_NAME_NEEDED]` | `teams.csv` |
| `[KO_NAME_NEEDED]` | `players.csv` |
| `[KO_COACH_NAME_NEEDED]` | `coaches.csv` |
| `[KO_REFEREE_NAME_NEEDED]` | `referees.csv` |
| `[KO_VENUE_NAME_NEEDED]` | `venues.csv` |
| `[KO_LEAGUE_NAME_NEEDED]` | `leagues.csv` |
| `[LOGO_NEEDED]`, `[LEAGUE_LOGO_NEEDED]` | `logos.csv`, `leagues.csv` |

운영 흐름은 Render 로그에서 키워드 검색 -> CSV 수정 -> 커밋 -> 재배포입니다.

### 새 리그·시즌 데이터 추가

`update/CsvUpdater.java`는 리그와 시즌을 고르면 팀, 경기장, 선수, 감독 행을 CSV에 추가하는 콘솔 도구입니다. IDE에서 `CsvUpdater.main()`을 실행합니다. 이미 있는 ID는 건너뛰며, 한글 이름 칸은 비워 두므로 직접 채워야 합니다.

## 로컬에서 실행하기

필요한 것:

- JDK 21
- API 키를 붙여 주는 API-Football 프록시 주소. 백엔드는 키 없이 이 주소로 요청하므로, 직접 실행하려면 같은 역할을 하는 프록시가 필요합니다.

1. `.env.example`을 복사해 프로젝트 루트에 `.env`를 만들고 값을 채웁니다. `.env`는 Git에 올라가지 않습니다.

   | 변수 | 내용 |
   | --- | --- |
   | `CDN_URL` | API-Football 프록시 주소 |
   | `MEDIA_CDN_URL` | `https://media.api-sports.io`를 대신할 이미지 CDN 주소 |
   | `CORS_ALLOWED_ORIGINS` | 허용할 프런트엔드 주소. 현재 코드는 모든 주소를 허용하므로 이 값은 쓰이지 않습니다. |

2. 서버를 실행합니다.

   ```bash
   ./gradlew bootRun
   ```

   Windows PowerShell에서는 `.\gradlew.bat bootRun`을 사용합니다. 기본 포트는 8080입니다.

3. 동작을 확인합니다.

   ```bash
   curl http://localhost:8080/actuator/health
   ```

테스트는 `./gradlew test`로 실행합니다.

## 배포

[Dockerfile](Dockerfile)로 이미지를 빌드해 Render에서 실행합니다. 환경변수(`CDN_URL`, `MEDIA_CDN_URL`)는 Render 대시보드에 등록합니다.

## 문서

- [docs/rate-limiting.md](docs/rate-limiting.md): 요청 제한 정책과 `429` 응답 헤더
- [docs/client-ip-resolution.md](docs/client-ip-resolution.md): 프록시 뒤에서 요청자 IP를 구하는 방법
- [docs/logging.md](docs/logging.md): 요청 ID를 포함한 로그 형식

## 문의

- 화면에 보이는 문제(잘못된 한글 표기, 로고, 데이터 누락 등)는 [프런트엔드 저장소 Issues](https://github.com/bgh1234554/football-obs-frontend/issues)에 남겨 주세요.
- 백엔드 코드에 관한 내용은 이 저장소의 [Issues](https://github.com/bgh1234554/football-obs-backend/issues)를 사용합니다.
- 이메일: [bgh1234554@gmail.com](mailto:bgh1234554@gmail.com)

## 데이터 출처와 라이선스

- 경기 데이터, 선수 사진, 기본 팀 로고: [API-Football](https://www.api-football.com/) v3 (유료 요금제)
- CSV의 한글 표기는 이 프로젝트에서 직접 작성했습니다.

데이터와 이미지의 권리는 각 제공자와 권리자에게 있으며, 이용 조건은 각 제공자의 정책을 따릅니다. 이 저장소에는 아직 오픈소스 라이선스가 지정되어 있지 않습니다. 코드나 데이터를 다른 곳에 사용하려면 먼저 문의해 주세요.
