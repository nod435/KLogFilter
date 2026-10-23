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
- adb 기록 파일이 정한 크기를 넘으면 다음 파일(`이름_2.txt`, `_3` …)에 이어서 기록합니다. 크기는 File > 기록 파일 크기(10·50·100·200·500 MB·1 GB 또는 직접 입력, 기본 100 MB)에서 고르며 설정에 저장됩니다.

화면 그림과 번호 설명으로 정리한 사용 가이드는 [docs/USER_GUIDE_20261023174410.html](docs/USER_GUIDE_20261023174410.html)입니다(브라우저로 열기, 그림은 `docs/img`). 버전별 변경 이력은 [CHANGELOG.txt](CHANGELOG.txt)에 있으며(빌드 때 `dist`에도 복사), 시작 화면에는 버전과 단축키 설명만 표시됩니다.

## 빌드와 실행

JDK 8 이상이 필요합니다. 외부 라이브러리는 없습니다.

```
build.bat                       → dist\KLogFilter.jar (+ KLogFilter.bat, LogFilterCmd.ini)
dist\KLogFilter.bat [로그파일]   → 실행 (또는 java -jar dist\KLogFilter.jar [로그파일])
```

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
