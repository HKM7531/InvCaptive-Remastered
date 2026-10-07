# InvCaptive (불편한 동거) for Paper 26.3

모든 플레이어가 인벤토리 하나를 공유합니다. `/invcaptive`로 인벤토리를 장벽으로 잠그고, 시드로 정해진 블록을 파괴할 때마다 칸이 하나씩 해제됩니다.

원작: [noonmaru/inv-captive](https://github.com/noonmaru/inv-captive) 0.2.0 (GPL-3.0)

## 설치

Paper 26.2 이상(26.3 호환) 서버에 `InvCaptive.jar`를 `plugins/`에 넣고 서버를 재시작합니다. 최신 jar는 [Releases](https://github.com/HKM7531/InvCaptive-Remastered/releases)에서 받을 수 있습니다.

업데이트할 때는 서버를 끄고 기존 jar를 새 파일로 교체합니다. 저장 데이터(`plugins/InvCaptive/`)는 그대로 유지됩니다.

## 빌드

JDK 25가 필요합니다. 인터넷 연결이 필요합니다(Maven Central, repo.papermc.io).

    gradlew.bat build        (Windows)
    ./gradlew build          (Linux/macOS)

결과물: `build/libs/InvCaptive.jar` → 서버의 `plugins/`에 넣습니다.

## 게임 규칙

- 인벤토리 41칸(일반 36 + 방어구 4 + 보조 손 1)을 모든 플레이어가 공유
- `/invcaptive`로 시작하면 전체가 장벽으로 잠기고 핫바 첫 칸만 열림
- 시드로 정해진 블록을 부수면 대응하는 잠긴 칸이 해제되고 공지와 폭죽이 나옴. 이미 열린 칸은 해제되지 않음
- 사망하면 공유 인벤토리의 아이템이 모두 사망 위치에 떨어짐. 장벽은 떨어지지 않음
- 사망 페널티를 켜면 사망할 때마다 열린 칸이 하나씩 다시 봉인됨 ([사망 페널티](#사망-페널티))
- 칸 표기: 핫바 `0-1`~`0-9`, 인벤토리 `행-열`(1행 1열 = `1-1`), 방어구/보조 손은 이름

## 명령어

| 명령어 | 권한 | 설명 |
|---|---|---|
| `/invcaptive` (`/inv`) | `invcaptive.command` (OP) | 잠금 시작. 해제 기록 초기화 |
| `/invcaptive blocks [broken\|unbroken\|all] [페이지] [검색어]`<br>`/q [broken\|unbroken\|all] [페이지] [검색어]` | `invcaptive.blocks` (모두) | 블록 목록 보기. 예: `/q 다이아`, `/q all diamond` |
| `/invcaptive list` | OP | 칸별 해제 블록과 잠김/열림 상태, 봉인된 칸의 해제 조건. 블록 이름을 클릭하면 그 블록을 파괴한 것으로 처리하고, 봉인 조건을 클릭하면 봉인을 해제 |
| `/invcaptive deathpenalty [on\|off]` | OP | 사망 페널티 켜기/끄기. 인자를 생략하면 반전 |
| `/invcaptive deathpenalty difficulty [normal\|hard\|extreme]` | OP | 사망 페널티 난이도 설정. 인자를 생략하면 현재 난이도 표시 |
| `/invcaptive excluded` | `invcaptive.blocks` (모두) | 슬롯 대응에서 제외된 블록 보기 |
| `/invcaptive exclude [list\|add <블록>\|remove <블록>]` | OP | 제외 블록 편집. 재시작 후 적용 |
| `/invcaptive stop` | OP | 장벽을 모두 치우고 공유 종료. `/invcaptive`로 재시작 |

### 블록 목록

- 범위: 캔 블록(`broken`, 기본) / 안 캔 블록(`unbroken`) / 전체(`all`)
- 칸을 해제한 블록은 금색으로, 해제한 플레이어와 칸 번호와 함께 표시
- 대화상자의 버튼으로 범위를 바꾸고 페이지를 넘길 수 있음
- 검색: 대화상자 입력칸에 적고 `검색`을 누르거나 명령어 뒤에 검색어를 붙임. 한국어 이름과 영문 이름(`diamond_ore`)을 띄어쓰기와 대소문자 없이 부분 일치로 찾음. 입력칸을 비우고 누르거나 `검색 해제`를 누르면 해제. 현재 범위 안에서만 검색

## 사망 페널티

기본은 꺼져 있고 `/invcaptive deathpenalty on`으로 켭니다.

- 플레이어가 사망하면 핫바 1번 칸(`0-1`)을 제외한 열린 칸 하나가 무작위로 봉인됨. 공지와 소리가 재생됨
- 봉인된 칸은 "봉인된 인벤토리"(구조물 공허)로 채워지고 Lore에 해제 조건이 표시됨
- 해제 조건은 봉인할 때 무작위로 정해짐
  - 아이템 획득: 스컬크 촉매, 삼지창, 바다의 심장, 네더의 별 등
  - 보스 처치: 위더, 엔더 드래곤, 엘더 가디언, 워든 등
  - 블록 파괴: `??? 파괴`로 가려져 표시되며 가려진 글자 수는 블록 한국어 이름의 공백 제외 글자 수와 같음. 이미 캔 블록이면 빨간색
- 조건을 달성하면 봉인이 풀리고 일반 칸 해제처럼 황금 사과와 폭죽이 나옴

| 난이도 | 내용 |
|---|---|
| Normal (기본) | 봉인된 칸이 열린 칸의 절반 미만으로 제한 |
| Hard | 핫바 1번 칸을 제외한 모든 열린 칸 봉인 가능 |
| Extreme | Hard + 칸 해제에 쓰이지 않은 캔 블록도 파괴 조건에 포함 |

## 설정 (`plugins/InvCaptive/`)

- `config.yml`: `seed`(블록 대응 시드), `death-penalty`(사망 페널티 켜짐 여부), `death-penalty-difficulty`(`normal`/`hard`/`extreme`), `update-check`(`false`면 새 버전 확인과 알림 끔), `auto-update`(`false`면 새 jar 자동 다운로드 끔)
- `excluded-blocks.txt`: 슬롯 대응에서 제외할 블록. 한 줄에 Material 이름 하나(또는 쉼표 구분), `#` 뒤는 주석. 파일을 지우면 기본 목록으로 다시 생성. 수정하면 슬롯 대응이 바뀌므로 재시작 후 `/invcaptive`로 다시 시작

새 버전이 나오면 서버 시작 시와 1시간마다 확인해 콘솔과 모든 플레이어에게 알려 줍니다. 서버에 있는 플레이어에게는 발견 즉시, 이후 접속하는 플레이어에게는 접속할 때 알립니다.

기본값으로 새 jar를 `plugins/update/`에 자동으로 받아 두며, 서버를 재시작하면 교체됩니다. 실행 중에는 교체하지 않습니다. 알파 버전도 대상이며, 받은 파일은 GitHub가 알려 주는 SHA-256과 일치할 때만 저장합니다. 끄려면 `auto-update: false`로 설정합니다.
