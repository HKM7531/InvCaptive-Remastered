# InvCaptive (불편한 동거) for Paper 26.3

모든 플레이어가 인벤토리 하나를 공유합니다. `/invcaptive`로 인벤토리를 장벽으로 잠그고,
시드로 정해진 블록을 파괴할 때마다 칸이 하나씩 해제됩니다.

원작: [noonmaru/inv-captive](https://github.com/noonmaru/inv-captive) 0.2.0 (GPL-3.0). 이 프로젝트도 GPL-3.0입니다.

## 빌드

JDK 25가 필요합니다. 인터넷 연결이 필요합니다(Maven Central, repo.papermc.io).

    gradlew.bat build        (Windows)
    ./gradlew build          (Linux/macOS)

결과물: `build/libs/InvCaptive.jar` → 서버의 `plugins/`에 넣습니다.

## 동작

- 슬롯 번호: 0~35 일반 칸, 36~39 방어구, 40 보조 손
- `/invcaptive` (권한 `invcaptive.command`, 기본 OP): 전체를 장벽으로 채우고 핫바 첫 칸만 비움. 해제 기록도 초기화
- `/invcaptive blocks [broken|unbroken|all] [페이지]` (권한 `invcaptive.blocks`, 기본 모두): 블록 종류를 한국어 가나다순으로 20개씩 표시. 범위는 캔 블록(`broken`, 기본) / 안 캔 블록(`unbroken`, 슬롯 대응 후보 중 아직 파괴하지 않은 블록) / 전체(`all`)이며, 대화상자의 선택 버튼으로도 바꿀 수 있음. 칸 해제에 성공한 블록은 금색으로, 해제한 플레이어와 칸 번호를 옆에 표시. 명령어를 쓴 사람에게만 보이는 대화상자(Dialog)로 열리며, 페이지 번호 버튼(현재 페이지 주변 4개)과 [처음] [이전] [다음] [끝]을 누르면 같은 창이 새 페이지로 교체되어 채팅이 쌓이지 않음 (콘솔은 채팅 출력). 기록은 `broken-blocks.yml`에 저장
- `/invcaptive list`: 슬롯별로 어떤 블록을 부수면 해제되는지, 현재 잠김/열림 상태를 표시 (정답이 보이므로 OP 전용). 블록 이름을 클릭하면 그 블록을 파괴한 것으로 처리해 해당 칸을 해제 (해제 공지·폭죽·기록은 실제 파괴와 동일, 이미 열린 칸이면 안내만)
- `/invcaptive excluded`: 슬롯 대응 후보에서 제외된 블록을 사유 없이 쉼표로 이어서 한 줄로 표시 (OP 전용)
- `plugins/InvCaptive/excluded-blocks.txt`: 플러그인이 처음 실행될 때 내장 제외 목록으로 생성됨. 한 줄에 Material 이름 하나(또는 쉼표 구분), `#` 뒤는 주석. 줄을 지우면 제외가 풀리고 이름을 추가하면 제외됨. 수정 후 서버 재시작 필요. 파일을 지우면 내장 기본 목록으로 다시 생성됨
- 칸 표기: 핫바는 `0-1`~`0-9`, 인벤토리는 `행-열` (1행 1열 = `1-1`, 3행 9열 = `3-9`). 방어구/보조 손은 이름으로 표시
- `/inv`: `/invcaptive`의 짧은 별칭 (예: `/inv blocks 2`)
- `/q [페이지]`: `/invcaptive blocks`와 같음 (파괴한 블록 목록)
- `/invcaptive exclude [list | add <블록> | remove <블록>]` (OP 전용): `excluded-blocks.txt`에 제외 블록을 추가/삭제 (파일을 직접 편집해도 됨, Material 이름 예: `SCULK_SENSOR`). 서버 재시작 후 적용되며, 적용되면 슬롯 대응이 바뀌므로 `/invcaptive`로 다시 시작해야 함
- `/invcaptive stop`: 장벽을 모두 치우고 인벤토리 공유를 종료 (OP 전용). 종료 시점의 공유 인벤토리 내용이 모든 플레이어에게 그대로 남고, 상태는 `inventory.yml`에 저장되어 재시작해도 유지. `/invcaptive`로 다시 시작
- 시드(`plugins/InvCaptive/config.yml`)로 블록 41종을 슬롯 0~40에 대응. 해당 블록을 부수면 그 칸이 장벽일 때만 "새로운 인벤토리" 황금 사과로 해제
- 사망 시 장벽을 제외한 공유 인벤토리 전체를 사망 위치에 드롭
- 상태는 `plugins/InvCaptive/inventory.yml`에 저장(종료/월드 저장/퇴장 시)

## 구현 방식

NMS를 쓰지 않습니다. 서버가 기준 인벤토리를 하나 들고, 매 틱마다 플레이어의 변경을 모아(pull)
전원에게 복제(push)합니다. 같은 틱(50ms)에 둘 이상이 같은 칸을 바꾸면 늦은 쪽의 변경은
병합하거나 드롭으로 처리해 아이템 소실·복제를 막습니다.

## 원작 대비 변경

- Paper 26.3 전용, NMS/ProtocolLib/외부 Kotlin 플러그인/tap/kommand 의존성 제거 (Kotlin stdlib는 jar에 포함)
- `inventory.yml` 형식이 달라 원작 데이터와 호환되지 않음
- 슬롯 대응 후보에서 서바이벌로 부술 수 없는 블록은 서버 값(경도)으로 계산하지 않고 플러그인에 내장한 목록(`BUILTIN_EXCLUDED`: 베드록·방벽·빛·명령 블록·구조 블록·직소·테스트 블록·차원문·엔드 관문·움직이는 피스톤, 물·용암·기포 기둥·구조 공백, 규화한 참나무 반 블록·플레이어 머리, 공기 종류)이 `excluded-blocks.txt`의 기본값. legacy Material 제외. 아이템으로 얻을 수 없어도 부술 수 있는 블록은 포함
- 해제 공지에 블록 이름을 번역 키로 표시
- 정렬용 한국어 블록 이름은 마인크래프트 `ko_kr.json`에서 추출한 `ko_kr_blocks.properties`를 사용 (표시는 클라이언트 언어)
