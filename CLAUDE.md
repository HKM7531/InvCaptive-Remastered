# InvCaptive (불편한 동거) — Paper 26.x 포팅

모든 플레이어가 인벤토리 하나(41칸)를 공유하고, 시드로 정해진 블록을 부술 때마다 잠긴 칸(방벽)이 하나씩 열리는 플러그인.
원본: noonmaru/inv-captive 0.2.0 (GPL-3.0, Minecraft 1.16.5 + NMS). 이 프로젝트는 같은 콘셉트를 **Bukkit/Paper API만으로** 새로 작성한 것이며 GPL-3.0.

## 빌드 / 환경
- 서버 Paper 26.2 (26.3에서도 로드되도록 `api-version: '26.2'`, `paper-api:26.2.build.+`로 컴파일)
- JDK 25, Kotlin 2.3.0 (jvmTarget 25), Gradle wrapper 9.1.0
- 빌드: `.\gradlew.bat build` (Windows PowerShell) → `build/libs/InvCaptive.jar` 를 서버 `plugins/`에 넣음
- Kotlin stdlib는 jar에 포함. NMS/ProtocolLib/외부 라이브러리 의존성 없음
- 클라우드 샌드박스는 Maven/Paper 저장소에 접속할 수 없어 **지금까지 한 번도 컴파일 검증을 못 했음**. 새 세션에서 먼저 빌드해서 오류부터 확인할 것

## 구조 (`src/main/kotlin/com/github/monun/invcaptive/plugin/`)
- `InvCaptivePlugin.kt`: 이벤트(클릭/드롭/상호작용/아이템 생성/손 바꾸기 방벽 보호, 블록 파괴, 사망), 명령어, 대화상자(Dialog) 목록, 제외 목록 파일 처리
- `SharedInventory.kt`: 기준 인벤토리 1개를 매 틱 pull(플레이어→기준) / push(기준→전원). 같은 틱 충돌은 드롭·병합·무시로 처리. `captive()`, `stop()`, `release(slot)`, `takeAllExceptBarriers()`, 저장/불러오기
- `BlockLog.kt`: 파괴한 블록 종류 + 칸 해제에 성공한 블록(칸, 플레이어) 기록
- `BlockNames.kt`: 한국어 이름 표(정렬·벽 블록 표시용). `displayName()`은 벽 블록(번역 키가 일반 블록과 같은 경우)에 고정 한국어 이름을 씀
- `resources/ko_kr_blocks.properties`: 한국어 블록 이름 1,276개 (`block.minecraft.<id>=이름`). 사용자가 준 `minecraft_blocks_ko_with_ids_26.txt`의 ID와 일치하도록 맞춤
- `resources/plugin.yml`: 명령어 `invcaptive`(별칭 `inv`), `q`. 권한 `invcaptive.command`(OP), `invcaptive.blocks`(모두)

## 규칙(콘셉트)
- 슬롯 0~35 일반, 36~39 방어구(신발→투구), 40 보조 손. `/invcaptive`로 전체를 방벽으로 채우고 핫바 0번(0-1)만 비움
- 시드로 후보 블록을 섞어 앞에서부터 41개를 슬롯 0..40에 대응. 해당 블록을 부수면 그 칸이 방벽일 때만 "새로운 인벤토리"(황금 사과)로 해제 + 폭죽 + 공지
- 사망 시 방벽 제외 전체를 사망 위치에 드롭 (keepInventory, drops 재구성)
- 해제 조건은 **파괴**이지 획득이 아님 → 서바이벌에서 파괴 가능한 블록은 제외하지 않음

## 명령어
| 명령어 | 권한 | 설명 |
|---|---|---|
| `/invcaptive` (`/inv`) | command | 잠금 시작 + 기록 초기화 |
| `/invcaptive blocks [broken\|unbroken\|all] [페이지]`, `/q ...` | blocks | 블록 목록 (Dialog, 20개/페이지, 캔 블록/안 캔 블록/전체 선택 버튼·번호(4개)·처음/이전/다음/끝, 콘솔은 채팅). 안 캔 블록 = 슬롯 후보 중 미파괴 |
| `/invcaptive list` | command | 칸별 해제 블록. 이름 클릭 = 그 블록을 파괴한 것으로 처리해 칸 해제 |
| `/invcaptive excluded` | command | 제외 블록(사유 없이 쉼표로 한 줄) |
| `/invcaptive exclude [list|add|remove <블록>]` | command | `excluded-blocks.txt` 편집(재시작 후 적용) |
| `/invcaptive stop` | command | 방벽 제거 + 공유 종료 (`/invcaptive`로 재시작) |

칸 표기: 핫바 `0-1`~`0-9`, 인벤토리 `행-열`(1행 1열=`1-1`), 방어구는 이름(신발/레깅스/흉갑/투구/보조 손).

## 데이터 파일 (`plugins/InvCaptive/`)
- `config.yml`(시드), `inventory.yml`, `broken-blocks.yml`
- `excluded-blocks.txt`: 처음 실행 시 내장 제외 목록(`BUILTIN_EXCLUDED`)으로 생성. 한 줄에 Material 이름 하나(또는 쉼표), `#` 주석. 이후 이 파일이 기준. 파일을 지우면 내장 기본값으로 재생성. **내장 목록을 코드에서 바꿔도 이미 만들어진 파일에는 반영되지 않음**

## 제외 블록 (내장 25개)
- 경도 -1: BEDROCK, BARRIER, LIGHT, MOVING_PISTON, COMMAND_BLOCK, REPEATING_COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, STRUCTURE_BLOCK, JIGSAW, TEST_BLOCK, TEST_INSTANCE_BLOCK, END_PORTAL, END_PORTAL_FRAME, END_GATEWAY, NETHER_PORTAL (minecraft-data 26.1의 경도 -1 목록 15개와 일치)
- 선택해서 부술 수 없음: WATER, LAVA, BUBBLE_COLUMN, STRUCTURE_VOID (근거 약함, 데이터상 경도는 0~100)
- 서바이벌에서 마주치지 않음: PETRIFIED_OAK_SLAB, PLAYER_HEAD, PLAYER_WALL_HEAD
- 공기 종류: AIR, CAVE_AIR, VOID_AIR
- REINFORCED_DEEPSLATE는 경도 55로 부술 수 있어 제외하지 않음 (서버에 예전 `excluded-blocks.txt`가 있으면 그 줄을 지울 것)
- 제외 목록이 바뀌면 시드→슬롯 대응이 바뀜 → `/invcaptive list`로 확인하고 `/invcaptive`로 다시 시작

## 알려진 사항 / 겪은 문제
- Paper Dialog: `DialogBase.pause(false)` 필수 (기본이 일시정지인데 afterAction NONE과 충돌해 `IllegalArgumentException`)
- Adventure `ClickEvent`는 제네릭 → 반환 타입은 `ClickEvent<*>`
- 벽 블록(플레이어 벽 머리 등)의 번역 키는 일반 블록과 같음 → `BlockNames.displayName` 사용
- 채팅 줄은 서버가 지우거나 수정할 수 없어 "페이지 넘기면 교체"는 Dialog로 구현. Dialog 본문은 가운데 정렬(옵션 없음)
- 채팅/대화상자의 클릭은 왼쪽 클릭만 구분됨 (휠 클릭 판별은 인벤토리 GUI에서만 가능)
- 사용자 취향: 컴파일 실패 가능성/빌드 안내 같은 안내문은 출력하지 말 것 (오류가 나면 사용자가 `e:` 줄을 보내 줌)

## 남은 결정 / 할 일
1. 빌드해서 컴파일 오류 확인 (최근 변경: 번호 버튼 Dialog, `/q`, `/inv`, `BlockNames.displayName`)
2. 물·용암·기포 기둥·구조 공백을 제외 목록에 계속 둘지
3. "명령어로만 얻는 블록"(싹 틔우는 자수정, 몬스터 생성기, 시련 생성기, 금고, 벌레 먹은 돌류, 경작지, 개구리알, 후렴초, 스컬크 비명체 등)을 제외할지 — 현재는 후보에 포함
4. `stop` 의미: 지금은 방벽만 제거하고 공유 종료. 대안(공유 유지/아이템 정리/한 명에게 전달) 미정
5. 인게임 확인: 번호 버튼 레이아웃(페이지가 5개 미만일 때 버튼 줄), `/q` 탭 완성, `list` 클릭 해제
6. GitHub 저장소 만들기: `.gitignore`(build/, .gradle/, .kotlin/), `LICENSE.md`(GPL-3) 포함, `gradlew.bat`와 `gradle/wrapper/`는 커밋
