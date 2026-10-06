# KLogFilter 소스 구조 및 동작 흐름

> 작성일: 2026-10-02 · 대상 버전: LogFilter Version 1.8 · 기준 소스: [github.com/nod435/KLogFilter](https://github.com/nod435/KLogFilter) `fd5e19f` + 로컬 변경분(1.1절) (18개 파일, 약 4,400줄)
>
> 시퀀스 다이어그램(4장), 클래스 관계도와 클래스별 UML 구조(5.1·5.2절)는 HTML 버전에만 있다: [SOURCE_STRUCTURE.html](SOURCE_STRUCTURE.html)

---

## 1. 개요

KLogFilter는 Android `logcat` 로그를 보면서 필터링하는 Java Swing 데스크톱 툴이다.

- **입력:** 저장된 로그 파일(Open, Recent, 드래그&드롭, 실행 인자)을 읽거나, `adb logcat` 출력을 실시간으로 받는다.
- **기능:** 워드/태그/PID/TID/레벨 필터, 하이라이트, 북마크, 에러/북마크 인디케이터, 컬럼 숨김, 클립보드 복사
- **빌드 환경:** Eclipse Java 프로젝트이며 `.classpath`에는 JavaSE-1.6이 설정되어 있다. 현재는 JDK 8로도 빌드된다.
- **패키지:** default 패키지만 사용하고, 외부 라이브러리 의존성은 없다.

```powershell
# 빌드 — 로컬 소스(UTF-8)
javac -encoding UTF-8 -d bin src\*.java
# 빌드 — GitHub 원본(MS949)
javac -encoding MS949 -d bin src\*.java
# 실행 (ini 파일을 현재 작업 디렉터리에서 읽으므로 반드시 KLogFilter 폴더에서 실행)
java -cp bin LogFilterMain [로그파일]
```

### 1.1 GitHub 원본 대비 로컬 변경 사항

이 문서는 [nod435/KLogFilter](https://github.com/nod435/KLogFilter)의 유일한 커밋 `fd5e19f`("파일 등록")를 기준으로 한다. 로컬 작업 폴더에만 있는 변경분도 함께 설명한다. 코드가 바뀐 파일은 `LogFilterMain.java`, `LogCatParser.java`, `LogTable.java`, `T.java` 네 개다(뒤의 셋은 2026-10-04 버그 수정).

| 구분 | 파일 | 내용 | 상태 |
|---|---|---|---|
| 안정성 | `LogFilterMain` · `LogFilterTableModel` · `IndicatorPanel` · `RecentFileMenu` | 2026-10-06: EDT 반영(`refreshTable`), 목록 교체 방식 `clearData`, 모델 행 수 고정, `ConcurrentHashMap`, 재필터 요청 플래그, 파싱 세대 번호, adb devices 백그라운드, 설정 키별 기본값, try-with-resources | 적용됨 |
| 성능 개선 | `FilterToken`(신규) · `LogInfo` · `LogCatParser` · `LogTable` · `LogFilterMain` | 2026-10-06: 필터 토큰 캐시(P2), 레벨·줄 번호 int 필드(P3/P4), 렌더러 조기 반환과 Font/Color 재사용(P1), 입력 디바운스 250ms(P5), Color 재사용(P8), DocumentListener 통합(S4) | 적용됨 |
| 버그 수정 | `LogFilterMain` · `LogCatParser` · `LogTable` · `T` | 2026-10-04: B1(`==` → `equals`), B2(메시지의 `'` 보존), B3·B9(HTML 이스케이프, 대소문자 무시 하이라이트), B10(날짜 포맷), 안내 문구의 ini 키 이름(`INI_HIGILIGHT_n`) | 적용됨 |
| 기능 추가 | `LogFilterMain.java` | `installUndoRedo()`: 필터 입력창 6개와 Highlight 입력창에 Ctrl+Z / Ctrl+Y | 적용됨 |
| 기능 추가 | `LogFilterMain.java` | `installInputHistory()`: 입력창별 최근 입력 10개, ↑/↓로 불러오기 | **호출 안 됨** |
| UI 변경 | `LogFilterMain.java` | Highlight 입력창을 FlowLayout 패널에 넣고 폭 300px로 고정 | 적용됨 |
| import 추가 | `LogFilterMain.java` | 위 기능용 `InputEvent`, `AbstractAction`, `UndoManager` 등 8개 | — |
| 인코딩 | 전체 `.java` | GitHub는 **MS949**, 로컬은 **UTF-8**(+ CRLF → LF). 한글이 있는 `LogFilterMain`, `IndicatorPanel`, `ClassTaster`만 실제 내용이 다름 | — |
| 프로젝트 설정 | `.project` | VS Code Java 확장이 리소스 필터(`node_modules\|.git\|…`) 추가 | — |

> ⚠️ **인코딩 설정 불일치:** 로컬 `.settings/org.eclipse.core.resources.prefs`는 아직 `encoding/<project>=MS949`이다. 로컬 소스는 이미 UTF-8이라서 Eclipse에서 열면 한글 주석과 `addDesc()` 안내 문구가 깨진다. 설정을 UTF-8로 바꾸거나, 저장소에 올릴 때 인코딩을 하나로 통일해야 한다.

**줄 번호:** 이 문서의 줄 번호는 로컬 소스 기준이다.
- `LogFilterMain.java`는 GitHub 2,084줄, 로컬 2,236줄이다. GitHub 줄 번호는 약 816행 이전은 8, 그 뒤는 약 154 작다.
- 7장 표에는 두 줄 번호를 함께 적었다. 다른 파일은 줄 번호가 같다.

---

## 2. 파일 구성

| 파일 | 줄 수 | 역할 | 사용 여부 |
|---|---:|---|---|
| [LogFilterMain.java](../src/LogFilterMain.java) | 2236 | 메인 프레임. UI 구성, 파일/프로세스 읽기, 필터 엔진, 설정 저장 | **핵심** |
| [LogTable.java](../src/LogTable.java) | 712 | 로그 테이블(JTable). 셀 렌더링(하이라이트), 키/마우스 처리, 복사, 필터 문자열 보관 | **핵심** |
| [LogCatParser.java](../src/LogCatParser.java) | 212 | 로그 한 줄을 `LogInfo`로 파싱(time / threadtime / kernel 형식) | **핵심** |
| [IndicatorPanel.java](../src/IndicatorPanel.java) | 237 | 좌측 북마크/에러 위치 표시 바, "북마크만/에러만 보기" 체크박스 | **핵심** |
| [LogInfo.java](../src/LogInfo.java) | 67 | 로그 한 줄 데이터(VO), 레벨 비트 상수 | **핵심** |
| [LogFilterTableModel.java](../src/LogFilterTableModel.java) | 64 | 테이블 모델, 컬럼 정의/폭 | **핵심** |
| [RecentFileMenu.java](../src/RecentFileMenu.java) | 155 | 최근 파일 메뉴(`RecentFile.ini`). 외부 GPL v2 코드 | 사용 |
| [LogColor.java](../src/LogColor.java) | 22 | 레벨별/하이라이트 색상 전역 static 값 | 사용 |
| [ILogParser.java](../src/ILogParser.java) | 19 | 파서 인터페이스 | 사용 |
| [INotiEvent.java](../src/INotiEvent.java) | 31 | 컴포넌트 → 메인 프레임 이벤트 콜백 인터페이스 | 사용 |
| [T.java](../src/T.java) | 156 | 디버그 로그 출력 유틸(`System.out`) | 사용 |
| [TagTable.java](../src/TagTable.java) | 187 | 태그 목록 테이블 | **미사용**(생성 코드가 주석 처리됨) |
| [TagFilterTableModel.java](../src/TagFilterTableModel.java) | 45 | 태그 테이블 모델 | **미사용** |
| [TagInfo.java](../src/TagInfo.java) | 25 | 태그 데이터 | **미사용**(`m_arTagInfo`는 clear만 함) |
| [ClassTaster.java](../src/ClassTaster.java) | 261 | 리플렉션 기반 메서드 테스트 유틸. LogFilter 기능과 무관 | **미사용** |
| [DevicesPanel.java](../src/DevicesPanel.java) | 7 | 빈 JPanel | **미사용** |
| [MouseEventHandler.java](../src/MouseEventHandler.java) | 13 | 빈 WindowListener(이름과 내용 불일치) | **미사용** |
| [WindowEventHandler.java](../src/WindowEventHandler.java) | 19 | 종료용 WindowListener | **미사용**(익명 WindowAdapter로 대체됨) |

18개 중 7개(약 560줄)가 실제 동작에 쓰이지 않는다.

---

## 3. 아키텍처

### 3.1 계층 구성

```
┌────────────────────────────── LogFilterMain (JFrame) ──────────────────────────────┐
│ [UI 구성]  getOptionPanel / getFilterPanel / getCheckPanel / getTabPanel ...       │
│ [입력]     parseFile()  startProcess() → startFileParse()                          │
│ [필터엔진] startFilterParse() 스레드, check*Filter(), addLogInfo()                  │
│ [설정]     load/saveFilter, load/saveColor, loadCmd  (*.ini)                       │
│ [데이터]   m_arLogInfoAll, m_arLogInfoFiltered, m_hmBookmark*, m_hmError*          │
└───────┬───────────────────────┬───────────────────────────┬────────────────────────┘
        │ 소유/참조               │ 소유/참조                   │ 사용
        ▼                       ▼                           ▼
  ┌───────────┐  setData  ┌─────────────────────┐    ┌──────────────┐
  │ LogTable  │◀────────▶│ LogFilterTableModel │    │ ILogParser   │
  │ (JTable)  │           │  (ArrayList 참조)    │    │ └LogCatParser│
  │ ·필터문자열│           └─────────────────────┘    └──────┬───────┘
  │ ·Renderer │                                             │ 생성
  └─────┬─────┘                                             ▼
        │ notiEvent()        ┌────────────────┐        ┌─────────┐
        └──────────────────▶│ IndicatorPanel │        │ LogInfo │
                             └────────────────┘        └─────────┘
  공용: LogColor(static 색상), T(디버그 로그), RecentFileMenu(메뉴)
```

### 3.2 구조적 특징

- **God class:** `LogFilterMain`이 UI, I/O, 필터링, 설정을 모두 맡고 있다(전체 코드의 약 50%).
- **양방향 결합:** `LogTable`과 `IndicatorPanel`이 `LogFilterMain`의 필드(`m_scrollVBar`, `m_tbLogTable`, `m_nChangedFilter`)에 직접 접근한다.
- **필터 상태가 뷰에 있음:** 필터 문자열(`m_strFilterFind` 등)은 `LogTable` 필드로 보관되고, 실제 필터 판정은 `LogFilterMain.check*Filter()`가 `LogTable` getter를 통해 읽는다.
- **전역 static 상태:** `LogColor`의 색상과 `LogFilterTableModel.ColWidth`가 모두 public static이다.

### 3.3 핵심 데이터 구조

| 필드 | 타입 | 의미 |
|---|---|---|
| `m_arLogInfoAll` | `ArrayList<LogInfo>` | 읽은 모든 로그 |
| `m_arLogInfoFiltered` | `ArrayList<LogInfo>` | 필터를 통과한 로그 (`m_bUserFilter == true`일 때 화면에 표시) |
| `m_hmBookmarkAll / Filtered` | `HashMap<Integer,Integer>` | key = 원본 라인 index, value = 표시 리스트 내 index (인디케이터 위치 계산용) |
| `m_hmErrorAll / Filtered` | `HashMap<Integer,Integer>` | E/ERROR 레벨 라인. 구조는 위와 같음 |
| `m_bUserFilter` | boolean | 필터가 하나라도 활성화됐는지 여부 (`checkUseFilter()`로 결정) |
| `m_nChangedFilter` | volatile int | 필터 스레드 상태: `STATUS_READY(4)` / `STATUS_CHANGE(1)` / `STATUS_PARSING(2)` |
| `FILTER_LOCK` | Object | 리스트/맵 접근 동기화 + 필터 스레드 wait/notify |
| `FILE_LOCK` | Object | logcat 기록 파일 쓰기/읽기 동기화 |

`LogFilterTableModel`은 데이터를 복사하지 않는다. `setData()`로 두 리스트 중 하나를 **참조로 바꿔 끼우는** 방식이다.

---

## 4. 동작 흐름

### 4.1 시작 (main → 생성자)

```
main(args)
 └ new LogFilterMain()
    ├ initValue()            리스트/맵/락 생성, 로그 파일명 생성(LogFilter_yyyyMMdd_HHmmss.txt)
    ├ getOptionPanel()       상단: Device 선택 | Word/Tag 필터 | 레벨·컬럼 체크 | Highlight
    │                        하단: Font, Encode, Goto, Cmd 콤보, Clear/Run/Pause/Stop
    ├ getBookmarkPanel()     좌측 IndicatorPanel
    ├ getStatusPanel()       하단 상태 텍스트
    ├ getTabPanel()          LogFilterTableModel + LogTable + LogCatParser 생성, 중앙 스크롤
    ├ setDnDListener()       파일 드롭 → stopProcess() + parseFile()
    ├ addChangeListener()    텍스트필드 DocumentListener, 체크박스 ItemListener, 뷰포트 리스너
    ├ startFilterParse()     ★ 필터 스레드 시작 (FILTER_LOCK.wait()로 대기)
    ├ setVisible(true)
    ├ addDesc()              버전 이력/단축키 안내를 로그 행으로 추가
    ├ loadFilter()           LogFilter.ini → 필터 문자열, 폰트, 창 크기, 컬럼 폭
    ├ loadColor()            LogFilterColor.ini → LogColor static 값
    └ loadCmd()              LogFilterCmd.ini → Cmd 콤보 항목
 └ JMenuBar(File > Open, Recent) 설정
 └ args[0]이 있으면 invokeLater(parseFile)
```

`loadFilter()`에서 텍스트필드 값을 바꾸면 DocumentListener가 동작한다. 그 결과 시작할 때 저장돼 있던 필터가 바로 적용된다.

### 4.2 로그 파일 열기

Open(Alt+O), Recent, 드래그&드롭, 실행 인자는 모두 `parseFile(File)`로 들어온다.

```
parseFile(file)                                   [새 Thread]
 ├ setStatus("Parsing"), clearData()
 ├ while readLine():                              (Encode 콤보에 따라 UTF-8 / 시스템 기본)
 │   logInfo = LogCatParser.parseLog(line)
 │   logInfo.m_strLine = 줄 번호
 │   addLogInfo(logInfo)                          [FILTER_LOCK]
 │     ├ m_arLogInfoAll.add, E/ERROR면 m_hmErrorAll
 │     └ m_bUserFilter면 필터 검사 후 m_arLogInfoFiltered.add
 ├ runFilter()                                    → 필터 스레드 깨움(화면 갱신)
 └ setStatus("Parse complete")
```

### 4.3 logcat 실시간 수집 (Run 버튼)

이 경로는 스레드 2개가 **파일을 사이에 두고** 동작한다.

```
startProcess()
 ├ clearData()
 └ m_thProcess [Thread]
     ├ exec("adb [-s <serial>] " + Cmd콤보)     예: adb logcat -v threadtime
     ├ stdout → LogFilter_yyyyMMdd_HHmmss.txt   [FILE_LOCK, 줄마다 flush]
     ├ startFileParse() ──────────────────────┐
     └ 스트림 종료 시 stopProcess()             │
                                               ▼
                          m_thWatchFile [Thread] : 50ms 폴링 루프
                           ├ 필터 재계산 중(CHANGE/PARSING)이거나 Pause면 skip
                           ├ [FILE_LOCK] 새 줄 readLine → parseLog → addLogInfo
                           └ [FILTER_LOCK] model.setData(All 또는 Filtered)
                                           마지막 행 선택 중이면 자동 스크롤(updateTable)
```

- **Pause:** `m_bPauseADB = true`. 파일 기록은 계속되고 화면 반영만 멈춘다.
- **Stop:** `Process.destroy()`를 호출하고 스레드를 interrupt한다.
- **Clear:** `clearData()`를 호출한다. 이미 읽은 데이터만 지우며, 파일 읽기 위치는 그대로다.
- **Device OK 버튼:** `adb devices`를 실행해 결과를 목록에 표시한다. 장치를 선택하면 `-s <serial>`이 붙는다.

### 4.4 필터 재계산 (상태 머신)

필터 텍스트 입력, 체크박스, 레벨, 인디케이터 체크가 바뀌면 아래 순서로 처리된다.

```
[EDT] DocumentListener / ItemListener / notiEvent
   ├ LogTable.setFilterXxx(문자열)         (체크박스가 꺼져 있으면 "")
   ├ m_nChangedFilter = STATUS_CHANGE      → 진행 중인 필터 루프는 break
   └ runFilter()
        ├ checkUseFilter()                 → m_bUserFilter 결정
        ├ STATUS_PARSING이면 100ms sleep 반복 (EDT에서 대기)
        └ FILTER_LOCK.notify()

[필터 스레드] startFilterParse
   loop:
     m_nChangedFilter = READY; FILTER_LOCK.wait()
     m_nChangedFilter = PARSING
     Filtered 리스트/맵 clear
     if !m_bUserFilter → model = All, 끝
     for 전체 로그:
        STATUS_CHANGE면 break  (새 요청이 들어오면 중단)
        북마크만/에러만 체크 시 → 해당 라인만
        그 외 → LogLV && Pid && Tid && ShowTag && !RemoveTag && Find && !Remove
     완료 시 model = Filtered, 마지막 행으로 이동, "Complete"
```

**필터 판정 규칙:** `check*Filter()`가 담당한다.
- 필터 문자열은 `|`로 나눈 OR 조건이다.
- 대소문자를 무시하는 부분 일치(`contains`)로 판정한다.
- 대상 필드는 Find/Remove가 Message, Show/Remove Tag가 Tag, Pid가 Pid, Tid가 Thread이다.
- 레벨 필터는 `LogInfo.LOG_LV_*` 비트 마스크(`m_nFilterLogLV`)로 판정한다.

### 4.5 화면 렌더링

`LogTable.LogCellRenderer.getTableCellRendererComponent()`는 **셀을 그릴 때마다** 실행된다.

1. Message/Tag 컬럼이면 `remakeData()`가 실행된다.
   - 공백을 `&nbsp;`(U+00A0)로 바꾼다.
   - Highlight 토큰을 `<span style="background-color:#색">`으로 감싼다. 색은 `LogColor.COLOR_HIGHLIGHT` 배열을 순환한다.
   - Find(Message) 또는 ShowTag(Tag) 토큰을 `<font color=#FF0000>`으로 감싼다.
   - 하나라도 바뀌면 전체를 `<html><nobr>…</nobr></html>`로 감싼다.
2. 글꼴 크기를 적용하고, 글자색은 `LogInfo.m_TextColor`(파싱할 때 레벨별로 결정)를 쓴다.
3. 배경색은 북마크면 `COLOR_BOOKMARK`(선택 상태면 `COLOR_BOOKMARK2`), 아니면 흰색이다.

### 4.6 북마크 / 인디케이터

- **토글:** 더블클릭 또는 Ctrl+F2 → `LogFilterMain.bookmarkItem()`이 `m_bMarked`와 북마크 맵을 갱신하고 인디케이터를 repaint한다.
- **이동:** F2/F3 → `LogTable.gotoPreBookmark()/gotoNextBookmark()`가 표시 리스트를 선형 탐색한다. 끝에 닿으면 처음부터 다시 찾는다.
- **메모:** Mark 컬럼을 표시하면 셀을 편집할 수 있다. `setValueAt()` → `setBookmark()`.
- **IndicatorPanel:**
  - 북마크(파랑)와 에러(빨강) 위치를 패널 높이 비율로 그리고, 현재 화면 범위도 표시한다.
  - 클릭이나 드래그하면 그 비율 위치로 이동한다.
  - 상단 체크박스 2개는 "북마크만 보기 / 에러만 보기"이며, `notiEvent` → 필터 재계산으로 이어진다.

### 4.7 기타 입력 처리

| 입력 | 처리 위치 | 동작 |
|---|---|---|
| Ctrl+C | `LogTable.actionPerformed` | 선택 행의 보이는 컬럼(Line 제외)을 복사. Tag는 최대 길이, Pid/Tid는 8자 패딩 |
| 우클릭 | `LogTable` mouseClicked | 클릭한 셀 값 하나만 복사 |
| Alt+좌클릭(Tag) | 〃 | Show tag 필터에 추가/제거 → `EVENT_CHANGE_FILTER_SHOW_TAG` |
| Alt+우클릭(Tag) | 〃 | Remove tag 필터에 추가 |
| Ctrl+F | `processKeyBinding` | Find 입력창으로 포커스 이동 |
| Home / End | 〃 | 첫 행 / 마지막 행 |
| 헤더 더블클릭 | `ColumnHeaderListener` | 보이는 행 기준으로 컬럼 폭 자동 맞춤 |
| Goto 입력 | CaretListener | 해당 줄로 이동(화면 중앙) |
| Ctrl+Z / Ctrl+Y | `installUndoRedo` | 필터/하이라이트 입력창 실행 취소/다시 실행 |

### 4.8 종료

창 닫기 → `exit()` 순서로 처리된다.
1. 프로세스를 destroy하고 스레드 3개를 interrupt한다.
2. `saveFilter()`로 `LogFilter.ini`에 필터 문자열, 폰트, 창 크기/상태, 컬럼 폭을 저장한다.
3. `saveColor()`로 `LogFilterColor.ini`를 저장한다.
4. `System.exit(0)`

### 4.9 스레드 모델 요약

| 스레드 | 생성 위치 | 수명 | 하는 일 |
|---|---|---|---|
| EDT | Swing | 앱 전체 | UI 이벤트, 렌더링, `runFilter()` 호출(대기 포함) |
| 필터 스레드 `m_thFilterParse` | 생성자 | 앱 전체 | wait/notify로 필터 재계산 |
| 파일 파싱 스레드 | `parseFile()` | 파일 1개 | 파일 전체 읽기 |
| 프로세스 스레드 `m_thProcess` | Run | Stop까지 | adb stdout → 파일 기록 |
| 파일 감시 스레드 `m_thWatchFile` | Run | Stop까지 | 기록 파일 tail → 파싱 → 화면 반영 |

---

## 5. 클래스별 상세

### LogFilterMain
- **UI 빌더:** `getOptionPanel`, `getCmdPanel`, `getFilterPanel`, `getCheckPanel`, `getHighlightPanel`, `getOptionMenu`, `getBookmarkPanel`, `getStatusPanel`, `getTabPanel`
- **입력:** `parseFile`, `startProcess`, `startFileParse`, `stopProcess`, `pauseProcess`, `setDeviceList`, `getProcessCmd`
- **필터:** `startFilterParse`, `runFilter`, `checkUseFilter`, `addLogInfo`, `checkLogLVFilter`, `checkPidFilter`, `checkTidFilter`, `checkFindFilter`, `checkRemoveFilter`, `checkShowTagFilter`, `checkRemoveTagFilter`, `useFilter`, `setLogLV`
- **설정:** `loadFilter/saveFilter`, `loadColor/saveColor`, `loadCmd`
- **이벤트:** `notiEvent`(INotiEvent 구현), `m_alButtonListener`, `m_dlFilterListener`, `m_itemListener`
- **편집 보조:** `installUndoRedo`(적용됨), `installInputHistory`(**정의만 되어 있고 호출되지 않음**)

### LogTable
- `JTable`을 상속하고 `FocusListener`와 `ActionListener`(Ctrl+C 복사)를 구현한다.
- 필터 문자열 7종을 보관한다(Find, Remove, ShowTag, RemoveTag, Pid, Tid, Highlight).
- `LogCellRenderer`(내부 클래스)가 HTML로 하이라이트를 처리한다.
- 그 밖에 컬럼 표시/숨김(`showColumn`, `hideColumn`, `m_arbShow`), 북마크 이동, `showRow`(스크롤), `packColumn`(폭 자동 맞춤)을 담당한다.

### LogCatParser (implements ILogParser)
형식은 줄의 고정된 위치에 있는 문자로 판별한다.

| 형식 | 판별 조건 | 예 |
|---|---|---|
| time | 19~20번째 문자가 `D/`, `V/` 등 | `04-17 09:01:18.910 D/LightsService(  139): msg` |
| threadtime | 31~32번째 문자가 `D `, `V ` 등 | `04-20 12:06:02.125   146   179 D BatteryService: msg` |
| kernel | 1번째 문자가 `0`~`7` | `<4>[19553.494855] msg` |
| 기타 | 위에 해당 없음 | 줄 전체를 Message로 사용 |

- `getColor()`: 레벨에 따라 글자색을 정한다. `F/E/W/I/D`와 커널 레벨 `0`~`7`을 처리한다.
- `getLogLV()`: 비트 레벨을 반환하지만 **아무 데서도 호출되지 않는다**.

### IndicatorPanel
- `paintComponent`에서 북마크/에러 맵 전체를 순회하며 막대를 그리고, 화면 범위 표시(page indicator)도 그린다.
- 마우스 클릭/드래그로 이동하고, 휠 이벤트는 테이블 스크롤로 넘긴다.

### LogInfo / LogFilterTableModel
- **LogInfo:** 문자열 필드 9개와 `Color`, `m_bMarked`를 가진다. `getData(col)`로 컬럼 값을 돌려준다.
- **LogFilterTableModel:** 컬럼 9개(Line, Date, Time, LogLV, Pid, Thread, Tag, Bookmark, Message)를 정의한다. 폭은 static 배열이며, `setColumnWidth()`는 **기본값 이상인 폭만** 받아들인다.

### RecentFileMenu
- Hugues Johnson이 작성한 GPL v2 코드다.
- 최근 파일 10개를 `user.dir/RecentFile.ini`에 저장한다.
- 메뉴에 추가하는 곳은 `openFileBrowser()` 하나뿐이다. 드래그&드롭과 실행 인자로 연 파일은 Recent에 추가되지 않는다.

### LogColor / T / ILogParser / INotiEvent
- **LogColor:** 색상을 담는 static 필드만 있다.
- **T:** 호출 위치(파일:메서드:줄)를 붙여 `System.out`에 출력한다. `misEnabled = true`로 항상 켜져 있다.
- **INotiEvent:** 이벤트 0~3을 사용하고, 4~5(`FIND_WORD`, `REMOVE_WORD`)는 정의만 되어 있다.

---

## 6. 설정 파일 (`KLogFilter/` 작업 디렉터리 기준)

| 파일 | 읽기/쓰기 | 주요 키 |
|---|---|---|
| `LogFilter.ini` | 시작할 때 읽고 종료할 때 씀 | `WORD_FIND`, `WORD_REMOVE`, `TAG_SHOW`, `TAG_REMOVE`, `PID_SHOW`, `TID_SHOW`, `HIGHLIGHT`, `FONT_TYPE`, `INI_WIDTH/HEIGHT`, `INI_WINDOW_STATE`, `INI_COMUMN_0~8` |
| `LogFilterColor.ini` | 시작할 때 읽고 종료할 때 씀 | `INI_COLOR_0~8` (3=E, 4=W, 6=I, 7=D, 8=F), `INI_HIGILIGHT_COUNT`, `INI_HIGILIGHT_n` |
| `LogFilterCmd.ini` | 읽기 전용 | `CMD_COUNT`, `CMD_n` (adb 뒤에 붙는 명령) |
| `RecentFile.ini` | 읽기/쓰기 | 한 줄에 경로 하나 |

> ⚠️ **키 이름 주의:** 하이라이트 색상 키는 실제로 `INI_HIGILIGHT_n`이다.
> - 앱 안의 안내 문구([LogFilterMain.java:533](../src/LogFilterMain.java#L533))와 기능 설명은 `INI_COLOR_HIGILIGHT_n`으로 되어 있다.
> - 안내대로 `INI_COLOR_HIGILIGHT_n`으로 쓰면 해당 키를 찾지 못한다. `replace()`에서 NPE가 발생하고 catch되며, 하이라이트 색상 목록이 적용되지 않는다.

---

## 7. 발견된 문제점 (버그·위험)

중요도: 🔴 동작 오류 · 🟠 잠재적 오류/불안정 · 🟡 경미

| # | 중요도 | 위치 | 내용 |
|---|---|---|---|
| B1 | 🔴 ✅ 수정됨 | [LogFilterMain.java:758](../src/LogFilterMain.java#L758) (GitHub :750) | `m_strLogLV == "E"`로 **참조를 비교**한다. 파싱된 문자열과는 항상 false라서, 필터가 켜진 상태로 실시간 수집하면 에러 인디케이터가 갱신되지 않는다(다음 재필터 때만 반영). |
| B2 | 🔴 ✅ 수정됨 | [LogCatParser.java:134-137](../src/LogCatParser.java#L134-L137) | 메시지를 `'`를 구분자로 토큰화한 뒤 다시 이어 붙여서, **메시지 안의 작은따옴표(`'`)가 모두 사라진다**. |
| B3 | 🔴 ✅ 수정됨 | [LogTable.java:514-531](../src/LogTable.java#L514-L531) | HTML 이스케이프가 없다. 하이라이트나 Find가 일치하면 메시지의 `<init>`, `<tag>` 같은 부분이 HTML로 해석돼 화면에서 사라진다. 또 토큰이 `b`, `span`, `color`처럼 태그 이름과 같으면 이미 넣은 태그 내부까지 치환돼 마크업이 깨진다. |
| B4 | 🟠 ✅ 수정됨 | 전반 | **EDT 밖에서 Swing을 조작한다**. 백그라운드 스레드에서 `setStatus`, `model.setData`, `fireTableRowsUpdated`, `clearSelection`, `changeSelection`을 직접 호출해서, 간헐적 예외나 화면 깨짐이 생길 수 있다. |
| B5 | 🟠 ✅ 수정됨 | `clearData()`, `parseFile()`, `startFileParse()` | 락 없이 `m_arLogInfoAll.clear()`를 호출한다. 필터 스레드나 렌더러가 순회하는 중이면 `IndexOutOfBounds`/`ConcurrentModification`이 날 수 있다(Clear 버튼, 파일 재오픈). |
| B6 | 🟠 ✅ 수정됨 | [LogFilterMain.java:1402-1443](../src/LogFilterMain.java#L1402-L1443) (GitHub :1248-1289) | `setDeviceList()`가 EDT에서 `adb devices`를 동기 실행한다. adb가 느리면 UI가 멈춘다. `exitValue()`를 종료 대기 없이 호출해서 `IllegalThreadStateException`이 목록에 표시될 수 있다. stdout과 stderr를 순차로 읽어서 교착 가능성도 있다. |
| B7 | 🟠 ✅ 수정됨 | `loadFilter()`, `loadColor()` | 키 하나만 빠져도 예외가 나서 **그 뒤의 설정 전체**가 적용되지 않는다(키마다 개별 기본값이 없음). |
| B8 | 🟡 ✅ 수정됨 | [LogFilterMain.java:757](../src/LogFilterMain.java#L757), [1811-1815](../src/LogFilterMain.java#L1811-L1815) (GitHub :749, 1657-1661) | Filtered 맵에 index를 `add()` 후의 `size()`로 넣어서 1이 크다. 북마크/에러 전용 분기는 add 전에 넣으므로 서로 일관성이 없다(인디케이터가 1행 어긋남). |
| B9 | 🟡 ✅ 수정됨 | [LogTable.java:549-560](../src/LogTable.java#L549-L560) | 일치 여부는 대소문자 무시로 검사하는데 `replace`는 대소문자를 구분한다. `error`로 하이라이트하면 `Error`는 색이 안 바뀌고 HTML로만 감싸진다. |
| B10 | 🟡 ✅ 수정됨 | [T.java:151](../src/T.java#L151) | 날짜 포맷이 `yyyy-mm-dd hh`이다. `mm`(분)이 월 자리에 들어가고 `hh`는 12시간제라서, 콘솔 로그에 `2026-28-02` 같은 날짜가 찍힌다. → `yyyy-MM-dd HH` |
| B11 | 🟡 ✅ 수정됨 | `loadXxx`, `saveXxx`, `RecentFileMenu` | `FileInputStream`, `FileOutputStream`, `FileReader`를 close하지 않는다(리소스 누수). |
| B12 | 🟡 | `LogFilterTableModel.setColumnWidth` | 기본값보다 좁힌 컬럼 폭은 저장해도 다음 실행 때 무시된다. |
| B13 | 🟡 | LogCatParser | 고정 위치로 형식을 판별해서 `-v year`, `-v uid`, 최신 logcat 포맷, 공백이나 `/`가 들어간 태그를 제대로 파싱하지 못한다. threadtime 형식에서는 Tag에 `:`가 붙고 Message 앞에 공백이 남는다. |

---

## 8. 최적화 가능 요소

### 8.1 성능 (대용량 로그 기준)

| # | 효과 | 대상 | 현재 | 개선안 |
|---|---|---|---|---|
| P1 ✅ | ★★★ | 셀 렌더러 | 셀을 그릴 때마다 `replace`, `toLowerCase`, `StringTokenizer`, HTML 문자열 생성과 HTML 렌더링(무거움), `new Color()`, `deriveFont()`를 실행 | 하이라이트/Find가 일치할 때만 HTML 사용, 필터 토큰을 미리 소문자화·분리해 캐시, Font/Color는 필드로 재사용 |
| P2 ✅ | ★★★ | 필터 판정 | 행마다, 필터마다 `StringTokenizer`를 새로 만들고 필터 문자열과 필드 모두 `toLowerCase()` | 필터가 바뀔 때 토큰을 `String[]`(소문자)로 한 번만 준비. 필드의 소문자 값은 LogInfo에 지연 캐시 |
| P3 ✅ | ★★ | 레벨 비교 | `m_strLogLV.equals("E") \|\| equals("ERROR")`를 곳곳에서 반복 | 파싱할 때 `int m_nLogLV`를 저장(`getLogLV()`가 이미 있음)하고 비트 연산으로 판정 |
| P4 ✅ | ★★ | 줄 번호 | `Integer.parseInt(m_strLine)`을 반복 호출 | `int m_nLine` 필드를 추가하고 표시할 때만 문자열로 변환 |
| P5 ✅ | ★★ | 입력 즉시 재필터 | 키를 누를 때마다 전체 로그를 재필터 | `javax.swing.Timer`로 200~300ms 디바운스 |
| P6 | ★★ | 실시간 수집 | adb → 파일 기록(줄마다 flush) → 50ms 폴링으로 다시 읽기(디스크 I/O 2배) | stdout을 바로 파싱하고 파일 기록은 버퍼링(주기적 flush) |
| P7 ✅ | ★★ | 테이블 갱신 | 행이 추가될 때마다 `fireTableRowsUpdated(0, 전체)`와 `validate`, `repaint` | `fireTableRowsInserted(old, new-1)`로 증분 통지 |
| P8 ✅ | ★ | Color 객체 | 줄마다 `getColor()`가 `new Color()`를 생성 | 레벨별 Color 인스턴스 캐시(메모리와 GC 절감) |
| P9 | ★ | 인디케이터 | 스크롤할 때마다 북마크/에러 맵 전체를 순회해 다시 그림 | 픽셀 단위로 모은 결과나 BufferedImage를 캐시하고 데이터가 바뀔 때만 다시 그림 |
| P10 | ★ | `runFilter()` | EDT에서 `Thread.sleep(100)` 바쁜 대기 | 대기 제거(상태 플래그와 notify만으로 충분) |
| P11 | ★ | `T` 로그 | 호출마다 `new Exception()`(스택 수집)과 `SimpleDateFormat` 생성, 항상 켜져 있음 | 릴리스에서는 비활성화하거나 `misEnabled`를 먼저 검사(이미 검사 중이므로 기본값을 false로) |

### 8.2 구조 / 코드 품질

| # | 대상 | 개선안 |
|---|---|---|
| S1 | `LogFilterMain`(2,236줄) | 역할별로 분리: `MainFrame`(UI), `LogSource`(파일/adb 입력), `FilterEngine`(필터 스레드와 판정), `AppConfig`(ini 로드/저장) |
| S2 | 중복된 필터 판정 | `addLogInfo()`와 필터 스레드에 같은 판정 로직이 복사돼 있다 → `boolean accept(LogInfo)` 하나로 통합 |
| S3 | `check*Filter()` 6개 | 공통 헬퍼 `matchAny(String field, String[] tokens)`로 통합 |
| S4 ✅ | DocumentListener | `changedUpdate`, `insertUpdate`, `removeUpdate`의 본문이 똑같다 → `onFilterTextChanged(DocumentEvent)` 하나로 |
| S5 | 필터 상태 위치 | 필터 문자열을 `LogTable`(뷰)에서 `FilterEngine`(모델)으로 이동 |
| S6 | `gotoNext/PreBookmark` | 표시 리스트를 선형 탐색 → 정렬된 북마크 index 목록에서 이진 탐색 |
| S7 | `T.java` | 거의 같은 메서드 8개 → `log(level, msg)` 하나로 |
| S8 ✅ | Swing 스레드 | 백그라운드 결과는 `SwingUtilities.invokeLater`나 `SwingWorker`로 EDT에 반영 (B4 해결) |
| S9 ✅ | 설정 로드 | `getProperty(key, default)`로 키마다 기본값 지정 (B7 해결), try-with-resources는 JDK 7 이상 |

### 8.3 정리 가능한 죽은 코드

| 항목 | 위치 |
|---|---|
| 미사용 클래스 | `ClassTaster`, `DevicesPanel`, `MouseEventHandler`, `WindowEventHandler`, `TagTable`, `TagFilterTableModel`, `TagInfo` |
| 빈 메서드 / 주석 코드 | `createComponent()`, `addTagList()`(전체 주석), `setProcessCmd()`(실제로는 로그 파일명 재생성만 함), `TagTable.packColumn` 주석부 |
| 미사용 필드 / 상수 | `m_tpTab`(탭 생성 후 미표시), `m_arTagInfo`, `COMBO_IOS`, `COMBO_CUSTOM_COMMAND`, `IOS_*`, `DEVICES_IOS/CUSTOM`, `INI_LAST_DIR`, `ILogParser.TYPE_*`, `INotiEvent` 이벤트 4·5, `IndicatorPanel.testMsg`, `m_bDrawFull` |
| 미사용 메서드 | `LogCatParser.getLogLV()`(P3에서 활용 가능), `installInputHistory()`(연결하면 ↑/↓ 입력 히스토리 기능이 동작함) |
| 미사용 import | `LogFilterTableModel`의 `java.io.ObjectInputStream.GetField` |

> `setProcessCmd()`는 이름과 달리 `m_strLogFileName = makeFilename()`이라는 **부수 효과**만 남아 있다. 삭제하면 Run을 누를 때마다 새 로그 파일이 만들어지지 않으므로 이 한 줄은 `startProcess()`로 옮겨야 한다.

### 8.4 라이선스 참고
- `RecentFileMenu.java`는 **GPL v2**다. 배포 형태에 따라 라이선스 의무가 생길 수 있다.
- `T.java` 헤더에는 타사(WiseStone Co. Ltd.) 저작권/기밀 고지가 있다. 배포 전에 확인이 필요하다.

---

## 9. 권장 진행 순서

1. ✅ **완료(2026-10-04) — 바로 고칠 버그:** B1(`==` 비교), B2(따옴표 소실), B3(HTML 이스케이프), B10(날짜 포맷), 안내 문구의 `INI_HIGILIGHT` 키 이름. B3를 고치면서 B9도 함께 해결했다.
2. ✅ **완료(2026-10-06) — 성능 개선:** P1(렌더러), P2(필터 토큰 캐시), P3/P4(int 필드), P5(디바운스), P8, S4. 30만 줄 측정(JDK 8, 5회 중 최소값):

   | 항목 | 이전 | 이후 | 개선 |
   |---|---:|---:|---:|
   | 파싱 30만 줄 | 825 ms | 596 ms | −28% |
   | 필터 판정 30만 줄 | 391 ms | 112 ms | 3.5배 |
   | 셀 렌더링 5만 회, 필터 없음 | 214 ms | 6.5 ms | 33배 |
   | 셀 렌더링 5만 회, 하이라이트+Find | 234 ms | 154 ms | −34% |
3. ✅ **완료(2026-10-06) — 안정성:** B4/B5/B8 + S8(EDT 반영, 목록 교체 방식 clearData, 모델 행 수 고정, ConcurrentHashMap), B6(adb devices 백그라운드), B7/S9(설정 키별 기본값), B11(스트림 닫기), P7. 재필터 요청 유실 경합, 파일 연속 열기 시 이전 파싱 혼입, Stop 직후 Run 시 새 프로세스 중단 문제도 수정. 스트레스 테스트(파싱·실시간 추가·필터 변경·Clear·스크롤 동시 실행): 수정 전 20초 동안 예외 17건 → 수정 후 60초 동안 0건.
4. **구조 개선:** 죽은 코드 제거 → S2/S3/S4 중복 제거 → S1 클래스 분리
5. **저장소 정리:** 소스 인코딩을 UTF-8로 통일하고 Eclipse 설정(`encoding/<project>`)도 맞춘 뒤, 로컬 변경분(1.1)을 GitHub에 커밋
6. **기능 보완(선택):** `installInputHistory` 연결, 드래그&드롭과 실행 인자로 연 파일도 Recent에 추가, 파서 포맷 확장(B13)
