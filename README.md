# KLogFilter

Android `logcat` 로그를 보면서 필터링하는 Java Swing 데스크톱 툴입니다.

- 저장된 로그 파일(logcat, DDMS, dmesg)을 열거나, `adb logcat` 출력을 실시간으로 받아 봅니다.
- 지원 형식: `adb logcat -v` threadtime · time · brief · process · tag (+ year · uid), 커널 로그(`<4>[시간] msg`), dmesg(`[시간] msg`). 형식을 알 수 없는 줄은 메시지로 그대로 표시합니다.
- 워드 / 태그 / PID / TID / 레벨 필터 (`|`로 OR 조건, 대소문자 무시)
- 하이라이트(여러 색), 북마크(메모 포함), 북마크·에러 위치 인디케이터
- 컬럼 숨기기, 선택 행·셀 복사, 드래그&드롭으로 파일 열기

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
| 북마크 이동 | <kbd>F2</kbd> 이전 / <kbd>F3</kbd> 다음 |
| 태그 필터에 추가 | Tag 셀에서 <kbd>Alt</kbd>+좌클릭(Show) / <kbd>Alt</kbd>+우클릭(Remove) |
| 복사 | <kbd>Ctrl</kbd>+<kbd>C</kbd>: 선택 행의 보이는 컬럼 / 우클릭: 셀 하나 |
| Find 입력창으로 | <kbd>Ctrl</kbd>+<kbd>F</kbd> |
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
| `LogCatParser` / `LogInfo` | 로그 한 줄 파싱 / 데이터 |

동작 흐름(시퀀스 다이어그램), 클래스 구조, 수정 이력은 [docs/SOURCE_STRUCTURE_20261007212214.html](docs/SOURCE_STRUCTURE_20261007212214.html)에 정리되어 있습니다(브라우저로 열기). Markdown 요약본은 [docs/SOURCE_STRUCTURE_20261007212214.md](docs/SOURCE_STRUCTURE_20261007212214.md)입니다. 문서 파일 이름 끝의 숫자는 생성 시각(`yyyyMMddHHmmss`)이며, 문서를 다시 만들 때마다 새 시각으로 바뀝니다.

## 라이선스 참고

- `src/RecentFileMenu.java`는 Hugues Johnson의 GPL v2 코드입니다.
- `src/T.java` 헤더에는 WiseStone Co. Ltd.의 저작권 고지가 있습니다.
