# KLogFilter

Android `logcat` 로그를 보면서 필터링하는 Java Swing 데스크톱 툴입니다.

- 저장된 로그 파일(logcat, DDMS, dmesg)을 열거나, `adb logcat` 출력을 실시간으로 받아 봅니다.
- 지원 형식: `adb logcat -v` threadtime · time · brief · process · tag (+ year · uid), 커널 로그(`<4>[시간] msg`), dmesg(`[시간] msg`). 형식을 알 수 없는 줄은 메시지로 그대로 표시합니다.
- 워드 / 태그 / PID / TID / 레벨 필터 (`|`로 OR 조건, 대소문자 무시)
- 하이라이트(여러 색), 북마크(메모 포함), 북마크·에러 위치 인디케이터
- 컬럼 숨기기, 선택 행·셀 복사, 드래그&드롭으로 파일 열기
- Full / Compact 화면 (View 메뉴, <kbd>F12</kbd>): 위쪽 필터 영역을 숨겨 로그를 넓게 봅니다.
- 안드로이드 장치 통계: 상태 표시줄에 장치 CPU·CPU 온도·메모리, 클릭하면 그래프·코어별·상위 프로세스(이미지로 복사·저장). 자세히 창의 "표시 :" 체크로 CPU·메모리·온도를 각각 켜고 끄고(그래프 선과 안내 줄·코어별 막대), "코어별" 체크로 cpu0, cpu1, … 선도 함께 표시. 그래프는 측정을 켠 뒤의 전체 기록을 시간 눈금과 함께 그리며, 아래 가로 스크롤 바(또는 마우스 휠)로 구간을 옮기고 "간격"으로 측정 하나의 폭(1·2·4·8px)을 고릅니다. 맨 오른쪽에 두면 새 측정을 따라갑니다. 통계 창은 View > 장치 통계 보기/숨기기(또는 상태 표시줄 클릭)로 열고 닫으며, 측정 주기와 측정할 시간(계속 / 몇 시간 뒤 멈춤)은 창 위쪽의 "주기"와 "측정 시간…"에서 정합니다. 기본은 측정 안 함이며, 창 아래의 "측정 시작"·"측정 종료" 버튼으로 측정을 켜고 끕니다(프로그램을 시작하거나 창을 열어도 자동으로 시작하지 않음). 측정 중에는 주기·측정 시간 설정이 흐리게 잠기고, 종료하거나 정한 시간이 끝나면 다시 바꿀 수 있습니다.
- 장치 통계 엑셀 저장: 측정을 켠 뒤의 전체 기록(시각·CPU·메모리·CPU 온도·코어별·센서별·재부팅/오류 비고, 상위 프로세스, 측정 정보)을 `.xlsx`로 저장. "그래프" 시트에는 통계 시트의 셀을 참조하는 엑셀 꺾은선 차트(CPU·메모리·온도, 코어별 선이 켜져 있으면 코어별 차트, 메모리 점유 상위 5개 프로세스의 MB 차트)가 들어가 셀 값을 고치면 차트도 따라 바뀝니다. 메모리 상위 프로세스는 측정마다 `top`을 메모리 기준으로 읽어 "메모리 상위 프로세스" 시트에 기록합니다. 통계 창의 "엑셀로 저장…" 버튼으로 저장하고, 정한 측정 시간이 끝나거나 "측정 종료"를 누르면 실행 폴더에 `DeviceStats_날짜_시각.xlsx`로 자동 저장("완료 시 자동 저장" 체크로 끄기). 외부 라이브러리 없이 씁니다.
- 수집 중 ADB 연결이 끊기면 기본으로 Stop 됩니다. Run/Stop 옆(또는 File 메뉴)의 "연결 대기"를 켜면 다시 연결될 때까지 기다렸다가 이어서 수집하고, 재부팅이면 그 시점에 북마크된 표시 줄을 넣습니다.
- `.gz` 로그: 열면 자동으로 압축을 풀어 같은 폴더(쓸 수 없으면 임시 폴더 `KLogFilter_gz`)에 `abc.log.gz → abc.log`로 두고 파싱합니다. `logcat.10`처럼 확장자 없이 gzip으로 압축된 파일은 풀어서 같은 이름 `logcat.10`으로 바꿉니다. 다 풀면 원본 `.gz`는 지우고 푼 파일을 원본으로 씁니다(수정 시각은 `.gz`와 같게, Recent도 푼 파일로). 임시 폴더에 푼 경우에는 `.gz`를 남깁니다.
- Reboot 버튼(프로세스 창·프로퍼티 창): 확인 후 `adb reboot`로 장치를 재부팅하고, 다시 연결되고 부팅이 끝날 때까지(`sys.boot_completed`, 최대 3분) 경과 시간을 보이며 기다린 뒤 목록을 새로 읽습니다. 실시간 수집 중이면 "연결 대기" 설정에 따라 이어서 수집하거나 멈춘다고 확인 창에서 알려 줍니다.
- Root 버튼(프로세스 창·프로퍼티 창): `adb root`로 장치의 adbd를 root 권한으로 다시 시작하고, 다시 연결되면 목록을 새로 읽습니다. 지금 권한(root/shell)을 옆에 보여 주며 이미 root면 버튼이 꺼집니다. userdebug·eng 빌드에서만 되고, 일반 판매용 빌드는 안 된다고 안내합니다. 실시간 수집 중이면 adbd 재시작으로 수집이 끊길 수 있어 먼저 확인합니다.
- 프로세스 상태(PS): <kbd>F7</kbd> 또는 View > 프로세스 상태. 장치의 `ps` 결과를 Process·PID·PPID·User·VSIZE·RSS·WCHAN 표로 보여 주며(열 머리 클릭으로 정렬), Thread 체크로 스레드까지, Name·PID·User로 거르며 일치하는 글자는 노란 바탕으로 표시합니다. "자동 갱신"(기본 10초, 주기는 입력해서 1~3600초)으로 주기마다 다시 읽습니다. 선택한 프로세스는 Kill(`kill`), Status(`/proc/PID/status`), Info(`dumpsys meminfo`), Stat, Maps, Stack(`debuggerd -b`), FD(`ls -l /proc/PID/fd`) 결과를 별도 창에서 보고 복사·저장할 수 있습니다. 새 ps(toybox)와 예전 ps 형식을 모두 읽습니다.
- 장치 프로퍼티: <kbd>F8</kbd> 또는 View > 장치 프로퍼티. 연결된 장치의 `getprop`을 Name/Value 표로 보여 주고 "자동 갱신"(기본 10초, 주기는 입력해서 1~3600초)으로 다시 읽으며 이전 값과 달라진 줄은 다음 갱신까지 연두색으로 칠하고 마지막 Changed 열에 바뀐 시각과 "이전 값 → 새 값"을 보여 주고 바뀐 줄을 표 맨 위에 모으며, 필터와 일치하는 글자는 노란 바탕으로 표시합니다. Reload, 저장/열기(파일), Edit·Add(`setprop`), Filter(이름·값, `|`로 여러 개)를 지원하며, 장치가 없으면 adb 오류를 표에 그대로 보여 줍니다. Home/End로 첫 줄/마지막 줄로 이동합니다. 연결된 장치가 바뀌면(케이블만 바꾼 경우 포함) 시리얼로 알아채 이전 장치 값과 비교하지 않고 새로 읽습니다. 긴 값·여러 줄 값은 줄 높이를 늘려 칸 안에 모두 보여 줍니다. 장치에 `/LGU_data` 폴더가 있으면 나타나는 **UP File** 탭에서는 장치의 `/LGU_data/UP` 폴더(경로 변경 가능, 설정에 저장) 안 파일들을 읽어 "파일 이름 = 값"(key=value 파일은 "파일 / key")으로 같은 표에 보여 주고, 장치에서 0.2초마다 변경을 감시해(안 되면 1초마다 수정 시간 확인) 바뀐 파일만 바로 다시 읽어 표시하며, **Edit**(더블클릭)로 값을 장치 파일에 고쳐 쓸 수 있습니다(key=value 파일은 그 줄의 값만, 다른 줄·권한은 그대로).
- 장치 화면 캡처: <kbd>F9</kbd>, View > 장치 화면 캡처, Device select의 "화면 캡처" 버튼. `adb exec-out screencap -p`로 PNG를 받아 실행 폴더에 `Screen_날짜_시각.png`로 저장하고 미리보기 창(다시 캡처·클립보드 복사·다른 이름으로 저장)을 띄웁니다. 수집 중이면 로그에 `-------- Screen captured : 파일 --------` 표시 줄을 넣어 캡처 시각을 로그와 맞춰 볼 수 있습니다.
- 장치 로그 열기: Device select의 "화면 캡처" 아래 "장치 로그 열기" 버튼(장치에 `/LGU_data/logs`가 있을 때만 보임, 5초마다 확인). 누르면 먼저 `adb root`를 실행합니다(이미 root면 생략). 장치의 `/LGU_data/logs` 폴더(경로 변경 가능, 설정에 저장) 목록을 이름·크기·수정 시각으로 보여 주고, 고른 파일을 `adb pull`로 실행 폴더의 `DeviceLogs\시리얼\`에 받아 바로 엽니다(`.gz`·확장자 없는 gzip은 풀어서). 폴더 들어가기/위로, 정렬, 이름 필터, 받는 중 진행 표시와 취소를 지원합니다.
- adb 기록 파일이 정한 크기를 넘으면 다음 파일(`이름_2.txt`, `_3` …)에 이어서 기록합니다. 크기는 File > 기록 파일 크기(10·50·100·200·500 MB·1 GB 또는 직접 입력, 기본 100 MB)에서 고르며 설정에 저장됩니다.

화면 그림과 번호 설명으로 정리한 사용 가이드는 [docs/USER_GUIDE_20261023174410.html](docs/USER_GUIDE_20261023174410.html)입니다(브라우저로 열기, 그림이 파일 안에 들어 있어 이 파일 하나만 배포하면 됨). 버전별 변경 이력은 [CHANGELOG.txt](CHANGELOG.txt)에 있으며(빌드 때 `dist`에도 복사), 시작 화면에는 버전과 단축키 설명만 표시됩니다.

## 빌드와 실행

JDK 8 이상이 필요합니다. 외부 라이브러리는 없습니다. 만든 jar는 Windows·Linux·macOS에서 같은 파일로 실행됩니다.

**Windows**

```
build.bat                       → dist\KLogFilter.jar (+ KLogFilter.bat, KLogFilter.sh, LogFilterCmd.ini, CHANGELOG.txt)
dist\KLogFilter.bat [로그파일]   → 실행 (콘솔 창 없이, 또는 java -jar dist\KLogFilter.jar [로그파일])
```

**Linux / macOS**

```
sh build.sh                     → dist/KLogFilter.jar (같은 내용)
chmod +x dist/KLogFilter.sh     → 처음 한 번
dist/KLogFilter.sh [로그파일]    → 실행 (또는 java -jar dist/KLogFilter.jar [로그파일])
```

Linux에서는 OpenJDK(예: `sudo apt install openjdk-17-jre`)와 adb(`sudo apt install adb` 또는 platform-tools를 PATH에)가 필요하고, GUI(X11/XWayland) 환경에서만 실행됩니다. USB 장치는 udev 규칙(`android-sdk-platform-tools-common`)이 필요할 수 있습니다.

개발 중에는 `javac -encoding UTF-8 -d bin src/*.java` 후 `java -cp bin LogFilterMain`으로 바로 실행할 수 있습니다(버전의 날짜 자리가 `dev`로 표시됨).

## 버전 규칙

`큰버전.중간버전.날짜` (예: `1.10.20261007090000`)

| 자리 | 의미 |
|---|---|
| 큰버전 | 큰 변경이 있을 때 올립니다. |
| 중간버전 | 신규 기능을 추가할 때마다 1 올립니다. |
| 날짜 | 버전을 만든(빌드한) 날짜시간 `yyyyMMddHHmmss`. `build.bat`이 자동으로 넣습니다. |

큰버전·중간버전은 `src/AppVersion.java`의 `MAJOR`·`MINOR`에서 바꿉니다. 버전은 창 제목, 시작 안내 문구, JAR 매니페스트(`Implementation-Version`)에 표시됩니다.

설정 파일을 실행한 폴더에서 읽고 쓰므로, `KLogFilter.bat`으로 실행하거나 JAR이 있는 폴더에서 실행하세요. Eclipse 프로젝트(`.project`, `.classpath`, JavaSE-1.8)로도 열 수 있습니다.

실시간 수집(Run)을 쓰려면 `adb`가 PATH에 있어야 합니다.

### 장치 연결이 안 될 때

- **Device OK**를 누르면 장치 목록이 나옵니다. 연결된 장치는 시리얼만, 그 외에는 `(offline)`, `(unauthorized)`처럼 상태가 함께 표시됩니다. 연결된 장치가 하나면 자동으로 선택됩니다.
- offline·unauthorized 장치나, 연결된 장치가 여러 개인데 선택하지 않은 상태로 **Run**을 누르면 실행하지 않고 하단 상태 표시줄에 이유를 보여줍니다.
- 네트워크 장치(`IP:5555`)는 adb 서버가 다시 시작되거나 장치가 재부팅되면 연결이 끊깁니다. 명령 창에서 `adb connect <IP>:5555`로 다시 연결한 뒤 **Device OK**를 누르세요.
- 실행 중 adb가 5초 동안 아무것도 출력하지 않거나 장치를 기다리면 상태 표시줄에 경고가 나옵니다. **Stop** 후 장치 상태를 확인하세요.
## 사용법

| 동작 | 방법 |
|---|---|
| 북마크 토글 | 더블클릭 또는 <kbd>Ctrl</kbd>+<kbd>F2</kbd> (Mark 컬럼에서 메모 입력) |
| 북마크 이동 | <kbd>F2</kbd> 아래(다음) / <kbd>F3</kbd> 위(이전) |
| 메시지 검색 | <kbd>F4</kbd>(또는 <kbd>Ctrl</kbd>+<kbd>F</kbd>) 검색창으로, 검색창에서 <kbd>Enter</kbd> 다음 / <kbd>Shift</kbd>+<kbd>Enter</kbd> 이전 |
| 로그 목록으로 | <kbd>Esc</kbd> — 진행 중인 검색을 멈추고 포커스를 로그 목록으로 |
| 로그 목록에서 나가기 | <kbd>Tab</kbd> / <kbd>Shift</kbd>+<kbd>Tab</kbd> — 다른 항목으로 포커스 이동 (셀 사이 이동 아님) |
| Full / Compact 화면 | <kbd>F12</kbd> |
| 장치 화면 캡처 | <kbd>F9</kbd> (View 메뉴, Device select의 버튼) |
| 프로세스 상태(PS) | <kbd>F7</kbd> (View 메뉴). 창 안에서 <kbd>F5</kbd> Refresh, 더블클릭 Status, <kbd>Delete</kbd> Kill |
| 장치 프로퍼티 | <kbd>F8</kbd> (View 메뉴). 창 안에서 <kbd>F5</kbd> 다시 읽기, <kbd>Ctrl</kbd>+<kbd>F</kbd> 필터, 더블클릭 Edit |
| 태그 필터에 추가 | Tag 셀에서 <kbd>Alt</kbd>+좌클릭(Show) / <kbd>Alt</kbd>+우클릭(Remove) |
| 복사 | <kbd>Ctrl</kbd>+<kbd>C</kbd>: 선택 행의 보이는 컬럼 / 우클릭: 셀 하나 |
| 필터 입력 되돌리기 | <kbd>Ctrl</kbd>+<kbd>Z</kbd> / <kbd>Ctrl</kbd>+<kbd>Y</kbd> |
| 최근 필터 입력 불러오기 | 필터 입력창에서 <kbd>↑</kbd> / <kbd>↓</kbd> (Enter나 포커스 이동 때 기록, 최근 10개) |
| 북마크만 / 에러만 보기 | 왼쪽 인디케이터 위의 체크박스 |

## 설정 파일

| 파일 | 내용 |
|---|---|
| `LogFilterCmd.ini` | Cmd 콤보의 adb 명령 목록 (`CMD_COUNT`, `CMD_0`…). 저장소에 포함 |
| `LogFilter.ini` | 필터 문자열, 폰트, 창 크기, 컬럼 폭. 종료할 때 생성/저장 |
| `LogFilterColor.ini` | 레벨별 색상과 하이라이트 색상(`INI_HIGILIGHT_COUNT`, `INI_HIGILIGHT_0`…). 종료할 때 생성/저장 |
| `RecentFile.ini` | 최근 파일 목록 (Open·Recent·드래그&드롭·실행 인자로 연 파일) |

아래 세 파일은 실행할 때마다 바뀌므로 저장소에서 제외했습니다(`.gitignore`). 파일이 없거나 키가 빠지면 기본값을 씁니다.

## 소스 구조

| 클래스 | 역할 |
|---|---|
| `LogFilterMain` | 메인 프레임: 화면 구성과 이벤트 연결 |
| `FilterEngine` | 로그 목록, 필터 조건, 재필터 스레드 |
| `LogSource` | 파일 파싱, adb 실시간 수집, 장치 목록 |
| `AppConfig` | 설정 파일 읽기/쓰기 |
| `LogTable` / `LogFilterTableModel` / `IndicatorPanel` | 로그 테이블, 모델, 북마크/에러 바 |
| `DeviceStats` / `StatsDialog` / `StatsDurationDialog` | 장치 통계 측정 / 자세히 창 / 측정 시간 설정 창 |
| `StatsExcel` / `XlsxWriter` | 장치 통계 기록 → 엑셀 시트 구성 / 외부 라이브러리 없는 .xlsx 기록기 |
| `LogCatParser` / `LogInfo` | 로그 한 줄 파싱 / 데이터 |

동작 흐름(시퀀스 다이어그램), 클래스 구조, 수정 이력은 [docs/SOURCE_STRUCTURE_20261012134216.html](docs/SOURCE_STRUCTURE_20261012134216.html)에 정리되어 있습니다(브라우저로 열기). Markdown 요약본은 [docs/SOURCE_STRUCTURE_20261012134216.md](docs/SOURCE_STRUCTURE_20261012134216.md)입니다. 문서 파일 이름 끝의 숫자는 생성 시각(`yyyyMMddHHmmss`)이며, 문서를 다시 만들 때마다 새 시각으로 바뀝니다.

## 라이선스 참고

- `src/RecentFileMenu.java`는 Hugues Johnson의 GPL v2 코드입니다.
- `src/T.java` 헤더에는 WiseStone Co. Ltd.의 저작권 고지가 있습니다.
