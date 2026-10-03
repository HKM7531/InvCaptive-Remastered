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
- 칸 표기: 핫바 `0-1`~`0-9`, 인벤토리 `행-열`(1행 1열 = `1-1`), 방어구/보조 손은 이름

## 명령어

| 명령어 | 권한 | 설명 |
|---|---|---|
| `/invcaptive` (`/inv`) | `invcaptive.command` (OP) | 잠금 시작. 해제 기록 초기화 |
| `/invcaptive blocks [broken\|unbroken\|all] [페이지]`<br>`/q [broken\|unbroken\|all] [페이지]` | `invcaptive.blocks` (모두) | 블록 목록 보기 |
| `/invcaptive list` | OP | 칸별 해제 블록과 잠김/열림 상태. 블록 이름을 클릭하면 그 블록을 파괴한 것으로 처리 |
| `/invcaptive excluded` | OP | 슬롯 대응에서 제외된 블록 보기 |
| `/invcaptive exclude [list\|add <블록>\|remove <블록>]` | OP | 제외 블록 편집. 재시작 후 적용 |
| `/invcaptive stop` | OP | 장벽을 모두 치우고 공유 종료. `/invcaptive`로 재시작 |

### 블록 목록

- 범위: 캔 블록(`broken`, 기본) / 안 캔 블록(`unbroken`) / 전체(`all`)
- 칸을 해제한 블록은 금색으로, 해제한 플레이어와 칸 번호와 함께 표시
- 대화상자의 버튼으로 범위를 바꾸고 페이지를 넘길 수 있음

## 설정 (`plugins/InvCaptive/`)

- `config.yml`: `seed`(블록 대응 시드), `update-check`(`false`면 새 버전 알림 끔)
- `excluded-blocks.txt`: 슬롯 대응에서 제외할 블록. 한 줄에 Material 이름 하나(또는 쉼표 구분), `#` 뒤는 주석. 파일을 지우면 기본 목록으로 다시 생성. 수정하면 슬롯 대응이 바뀌므로 재시작 후 `/invcaptive`로 다시 시작

새 버전이 나오면 서버 시작 시와 1시간마다 확인해 콘솔과 접속 중인 OP에게 알려 줍니다.
