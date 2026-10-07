# KLogFilter 소스 구조 및 동작 흐름

> 작성일: 2026-10-02 · 생성: 2026-10-07 19:01:59 (파일 이름의 `20261007190159`) · 대상 버전: LogFilter 1.10 (큰버전.중간버전.빌드날짜) · 기준 소스: [github.com/nod435/KLogFilter](https://github.com/nod435/KLogFilter) `fd5e19f` + 로컬 변경분(1.1절) (현재 23개 파일, 약 4,850줄 / 원본 18개, 약 4,400줄)
>
> 시퀀스 다이어그램(4장), 클래스 관계도와 클래스별 UML 구조(5.1·5.2절)는 HTML 버전에만 있다: [SOURCE_STRUCTURE_20261007190159.html](SOURCE_STRUCTURE_20261007190159.html)

---

## 1. 개요

KLogFilter는 Android `logcat` 로그를 보면서 필터링하는 Java Swing 데스크톱 툴이다.

- **입력:** 저장된 로그 파일(Open, Recent, 드래그&드롭, 실행 인자)을 읽거나, `adb logcat` 출력을 실시간으로 받는다.
- **기능:** 워드/태그/PID/TID/레벨 필터, 하이라이트, 북마크, 에러/북마크 인디케이터, 컬럼 숨김, 클립보드 복사
- **빌드 환경:** Eclipse Java 프로젝트, JavaSE-1.8(원본은 1.6). JDK 8로 빌드한다.
- **패키지:** default 패키지만 사용하고, 외부 라이브러리 의존성은 없다.
- **버전 규칙(2026-10-06~):** `큰버전.중간버전.날짜`. 중간버전은 신규 기능 추가 때 +1, 날짜는 빌드 날짜시간 `yyyyMMddHHmmss`. `src/AppVersion.java`(MAJOR·MINOR)와 `build.bat`(날짜)이 담당. 현재 1.10(2026-10-07 대용량 파일 지원으로 1.9 → 1.10).

```powershell
# 빌드 — 로컬 소스(UTF-8)
javac -encoding UTF-8 -d bin src\*.java
# 빌드 — GitHub 원본(MS949)
javac -encoding MS949 -d bin src\*.java
# 실행 (ini 파일을 현재 작업 디렉터리에서 읽으므로 반드시 KLogFilter 폴더에서 실행)
java -cp bin LogFilterMain [로그파일]
```

### 1.1 GitHub 원본 대비 로컬 변경 사항

이 문서는 [nod435/KLogFilter](https://github.com/nod435/KLogFilter)의 유일한 커밋 `fd5e19f`("파일 등록")를 기준으로 한다. 로컬 작업 폴더에만 있는 변경분도 함께 설명한다. 2026-10-06 구조 개선으로 대부분의 파일이 바뀌었고, 7개를 삭제하고 3개를 새로 만들었다.

| 구분 | 파일 | 내용 | 상태 |
|---|---|---|---|
| 대용량 파일 | `LogStore`·`FileLogStore`·`MemoryLogStore`·`FilteredList`·`LogList`·`IntList`·`LongList`(신규) · `FilterEngine` · `LogSource` · `LogTable` · `LogFilterTableModel` · `IndicatorPanel` | 2026-10-06 (1.10): 줄 내용 대신 파일 위치만 색인하고 보이는 줄만 읽어 해석(LRU 캐시), 읽는 즉시 화면 표시(부분 로딩), 분석·필터를 증분·병렬로 처리. 440MB 파일 55.6초·힙 2.4GB → 첫 화면 0.07초·Ready 2.3초·힙 77MB (9장 7) | 적용됨 |
| 장치 연결 | `LogSource` · `LogFilterMain` | 2026-10-06: 장치 상태 표시, 하나면 자동 선택, offline·unauthorized·여러 장치 미선택 시 Run 전에 이유 표시, adb 대기·무출력 경고, adb 오류 종료 메시지 | 적용됨 |
| 기능 보완 | `LogCatParser` · `LogFilterMain` · `LogInfo` | 2026-10-06: 파서 포맷 확장과 threadtime 빠른 경로(B13), 입력 히스토리 ↑/↓, 모든 열기 경로에서 Recent 추가 | 적용됨 |
| 구조 개선 | `AppConfig`·`FilterEngine`·`LogSource`(신규) 외 전체 | 2026-10-06: `LogFilterMain` 화면 전용 분리(S1), 필터 상태 이동(S5), 북마크 이동 TreeSet(S6), `T` 정리(S7), EDT 대기 제거(P10), 미사용 클래스 7개·주석 코드 삭제 | 적용됨 |
| Java 버전 | `.classpath`·JDT 설정 | 2026-10-06: JavaSE-1.6 → 1.8 | 적용됨 |
| 안정성 | `LogFilterMain` · `LogFilterTableModel` · `IndicatorPanel` · `RecentFileMenu` | 2026-10-06: EDT 반영(`refreshTable`), 목록 교체 방식 `clearData`, 모델 행 수 고정, `ConcurrentHashMap`, 재필터 요청 플래그, 파싱 세대 번호, adb devices 백그라운드, 설정 키별 기본값, try-with-resources | 적용됨 |
| 성능 개선 | `FilterToken`(신규) · `LogInfo` · `LogCatParser` · `LogTable` · `LogFilterMain` | 2026-10-06: 필터 토큰 캐시(P2), 레벨·줄 번호 int 필드(P3/P4), 렌더러 조기 반환과 Font/Color 재사용(P1), 입력 디바운스 250ms(P5), Color 재사용(P8), DocumentListener 통합(S4) | 적용됨 |
| 버그 수정 | `LogFilterMain` · `LogCatParser` · `LogTable` · `T` | 2026-10-04: B1(`==` → `equals`), B2(메시지의 `'` 보존), B3·B9(HTML 이스케이프, 대소문자 무시 하이라이트), B10(날짜 포맷), 안내 문구의 ini 키 이름(`INI_HIGILIGHT_n`) | 적용됨 |
| 기능 추가 | `LogFilterMain.java` | `installUndoRedo()`: 필터 입력창 6개와 Highlight 입력창에 Ctrl+Z / Ctrl+Y | 적용됨 |
| 기능 추가 | `LogFilterMain.java` | `installInputHistory()`: 입력창별 최근 입력 10개, ↑/↓로 불러오기 | **호출 안 됨** |
| UI 변경 | `LogFilterMain.java` | Highlight 입력창을 FlowLayout 패널에 넣고 폭 300px로 고정 | 적용됨 |
| import 추가 | `LogFilterMain.java` | 위 기능용 `InputEvent`, `AbstractAction`, `UndoManager` 등 8개 | — |
| 인코딩 | 전체 `.java` | GitHub는 **MS949**, 로컬은 **UTF-8**(+ CRLF → LF). 한글이 있는 `LogFilterMain`, `IndicatorPanel`, `ClassTaster`만 실제 내용이 다름 | — |
| 프로젝트 설정 | `.project` | VS Code Java 확장이 리소스 필터(`node_modules\|.git\|…`) 추가 | — |

> ✅ **인코딩 정리 완료(2026-10-04):** 소스와 Eclipse 설정(`encoding/<project>`)을 모두 UTF-8로 맞췄다. GitHub 원본 커밋(`fd5e19f`)만 MS949다.

**줄 번호:** 7장 문제점 표의 줄 번호는 2026-10-04 시점 로컬 소스 기준이다(구조 개선 뒤에는 코드가 다른 클래스로 옮겨져 위치가 다르다).
- `LogFilterMain.java`는 GitHub 2,084줄, 로컬 2,236줄이다. GitHub 줄 번호는 약 816행 이전은 8, 그 뒤는 약 154 작다.
- 7장 표에는 두 줄 번호를 함께 적었다. 다른 파일은 줄 번호가 같다.

---

## 2. 파일 구성

2026-10-06 대용량 파일 구조 적용 후 기준이다. 원본 18개 중 미사용 7개를 삭제하고 `AppConfig`, `FilterEngine`, `LogSource`를 새로 만들었다(`FilterToken`은 성능 개선, `LogStore` 외 6개는 대용량 파일 구조 때 추가).

| 파일 | 줄 수 | 역할 |
|---|---:|---|
| [LogFilterMain.java](../src/LogFilterMain.java) | 1431 (원본 2084) | 메인 프레임. 화면 구성과 이벤트 연결만 담당, 동작은 아래 세 클래스에 위임 |
| [FilterEngine.java](../src/FilterEngine.java) | 613 | **신규.** 현재 줄 목록(`LogStore`)과 필터 결과(`FilteredList`), 북마크/에러 맵, 필터 조건과 토큰, 분석·재필터 스레드(증분·병렬) |
| [LogSource.java](../src/LogSource.java) | 359 | **신규.** 파일 색인(LoadFile 스레드), adb 실행·기록 파일 이어 색인, 장치 목록 |
| [LogStore.java](../src/LogStore.java) | 138 | **신규(대용량).** 줄 목록 공통 부모. 지연 해석 + LRU 캐시(2만 줄), 북마크(BitSet)·메모, 최대 태그 길이, `m_bComplete` |
| [FileLogStore.java](../src/FileLogStore.java) | 224 | **신규(대용량).** 파일 기반 줄 목록. 줄마다 시작 위치·길이(12바이트)만 기억, 4MB 단위 색인, `FileChannel` 위치 읽기 |
| [MemoryLogStore.java](../src/MemoryLogStore.java) | 44 | **신규(대용량).** 메모리 줄 목록(시작 안내 문구, adb 시작 전 빈 목록) |
| [FilteredList.java](../src/FilteredList.java) | 32 | **신규(대용량).** 필터 결과. 통과한 줄 번호(`IntList`)만 갖고 내용은 `LogStore`에서 읽음 |
| [LogList.java](../src/LogList.java) | 14 | **신규(대용량).** 화면이 보는 목록 인터페이스(`size`, `get`, `lineIndexOf`) |
| [IntList.java](../src/IntList.java) · [LongList.java](../src/LongList.java) | 40 · 36 | **신규(대용량).** 뒤에만 추가하는 기본형 배열. 쓰는 스레드 하나, 읽는 스레드 여럿 |
| [AppConfig.java](../src/AppConfig.java) | 250 | **신규.** 설정 파일(*.ini) 읽기/쓰기, 키별 기본값 |
| [LogTable.java](../src/LogTable.java) | 661 | 로그 테이블. 셀 렌더링(하이라이트), 키/마우스, 복사, 북마크 이동 |
| [IndicatorPanel.java](../src/IndicatorPanel.java) | 221 | 북마크/에러 위치 바, 북마크만/에러만 보기 |
| [LogFilterTableModel.java](../src/LogFilterTableModel.java) | 76 | 테이블 모델(`LogList` 참조), 컬럼 정의/폭, EDT에서 알린 행 수 |
| [LogCatParser.java](../src/LogCatParser.java) | 230 | 로그 한 줄 → `LogInfo` (threadtime · time · year · uid · brief · process · tag · kernel/dmesg) |
| [ILogParser.java](../src/ILogParser.java) | 15 | 파서 인터페이스 |
| [LogInfo.java](../src/LogInfo.java) | 95 | 로그 한 줄(VO), 레벨 비트, 줄 번호·레벨 int |
| [FilterToken.java](../src/FilterToken.java) | 51 | 필터 토큰 분리, 대소문자 무시 부분 일치 |
| [RecentFileMenu.java](../src/RecentFileMenu.java) | 154 | 최근 파일 메뉴. 외부 GPL v2 코드 |
| [LogColor.java](../src/LogColor.java) | 21 | 색상 static 값 |
| [INotiEvent.java](../src/INotiEvent.java) | 26 | 테이블/인디케이터 → 메인 프레임 이벤트 |
| [AppVersion.java](../src/AppVersion.java) | 48 | 버전 규칙, 빌드 날짜 읽기 |
| [T.java](../src/T.java) | 67 | 디버그 로그 출력 |

**삭제한 파일(7개):** `ClassTaster`, `TagTable`, `TagFilterTableModel`, `TagInfo`, `DevicesPanel`, `MouseEventHandler`, `WindowEventHandler`. GitHub 이력에 남아 있다.

---

## 3. 아키텍처

### 3.1 구성

```
LogFilterMain (JFrame, 화면)  ── implements INotiEvent, FilterEngine.Listener, LogSource.Listener
 ├─ FilterEngine   현재 줄 목록(LogStore)·필터 결과(FilteredList) · 북마크·에러 맵 · 필터 조건/토큰 · 분석·재필터 스레드 + FilterWorker 풀 · LOCK
 │    └─ LogStore (추상) ── FileLogStore(파일 위치 색인) / MemoryLogStore(안내 문구·adb 시작)
 │         FilteredList ──▶ LogStore (통과한 줄 번호만)      LogStore, FilteredList ── implements LogList
 ├─ LogSource      파일 색인 · adb 프로세스/기록 파일 감시 · 장치 목록 · FILE_LOCK ──▶ FilterEngine.setStore() / notifyIndexed() / notifyAppended()
 ├─ AppConfig      LogFilter.ini / LogFilterColor.ini / LogFilterCmd.ini
 ├─ LogCatParser   (ILogParser) 한 줄 → LogInfo
 ├─ LogTable       (JTable) 하이라이트 · 렌더러 · 복사 · 북마크 이동 ──▶ FilterEngine (Find/Tag 토큰, 북마크 위치)
 ├─ LogFilterTableModel   FilterEngine 목록을 참조, EDT에서 알린 행 수
 ├─ IndicatorPanel        북마크/에러 바
 └─ RecentFileMenu        File > Recent
알림: FilterEngine/LogSource ──Listener──▶ LogFilterMain ──runOnEdt()──▶ 화면
```

### 3.2 구조적 특징

- **역할 분리(S1):** 화면은 `LogFilterMain`, 데이터·필터는 `FilterEngine`, 입력은 `LogSource`, 설정은 `AppConfig`. `FilterEngine`과 `LogSource`는 Swing을 직접 쓰지 않고 Listener로만 알린다.
- **스레드 경계:** Listener 알림은 어느 스레드에서든 올 수 있으므로 `LogFilterMain`이 `runOnEdt()`로 EDT에서 반영한다.
- **필터 상태(S5):** 필터 조건은 `FilterEngine`에 있다. 표시 설정인 하이라이트만 `LogTable`에 남았다.
- **대용량 파일 구조(2026-10-06):** 줄 내용을 메모리에 쌓지 않는다. `FileLogStore`는 줄마다 파일 안의 시작 위치(`LongList`)와 길이(`IntList`)만 기억하고(줄당 12바이트), 화면에 보이는 줄만 `FileChannel`로 읽어 해석한다. 해석 결과는 `LogStore`의 LRU 캐시(2만 줄)에 둔다. 분석·필터는 4MB 블록 단위로 순차로 읽는다(`forEach`).
- **부분 로딩:** LoadFile 스레드가 4MB씩 색인하면서 200ms마다 `notifyIndexed()`로 알린다. 색인된 줄은 바로 화면에 보이고, 에러 위치 계산과 필터 판정은 엔진 스레드가 뒤이어 증분으로 한다(`m_nAnalyzed`, `m_nFilteredUpTo`). 파일을 다 읽고(`m_bComplete`) 분석도 끝나면 "Ready"를 표시한다.
- **병렬 처리:** 처리할 줄이 13만 개(`CHUNK_LINES`×2) 이상이면 6만5천 줄 단위로 나눠 `FilterWorker` 풀(코어 수−1)에서 처리하고, 결과는 순서대로 합친다. 조건이 바뀌거나 목록이 교체되면 작업을 중단한다. 작업은 인터럽트하지 않는다(`FileChannel`은 인터럽트되면 닫힌다).
- **남은 결합:** `LogTable`·`IndicatorPanel`은 여전히 `LogFilterMain` 필드(`m_scrollVBar`, `m_tbLogTable`, `m_engine`)를 직접 참조한다. `LogColor`와 컬럼 폭은 public static이다.

### 3.3 핵심 데이터 구조 (`FilterEngine`)

| 필드 | 의미 |
|---|---|
| `m_store` | 현재 줄 목록(`LogStore`, volatile). 파일 열기·Clear·adb 시작 때 `setStore()`로 교체하고 이전 목록은 닫음 |
| `m_filtered` | 필터 결과(`FilteredList`, 필터를 안 쓰면 null). 재필터 때 새 객체를 만들어 채우는 동안에도 화면에 보임 |
| `m_nAnalyzed` / `m_nFilteredUpTo` | 에러 위치 계산을 마친 줄 수 / 필터 판정을 마친 줄 수. 새로 색인된 줄만 이어서 처리 |
| `m_hmBookmarkAll/Filtered`, `m_hmErrorAll/Filtered` | ConcurrentHashMap. key = 원본 위치, value = 표시 행 번호. 북마크 여부·메모 자체는 `LogStore`(BitSet)에 있음 |
| `m_bUserFilter` | 필터 조건이 하나라도 켜졌는지 |
| `m_nChangedFilter` | `STATUS_READY` / `STATUS_CHANGE`(중단 요청) / `STATUS_PARSING` |
| `m_bFilterRequested` / `m_bAnalyzeRequested` | 전체 재필터 / 증분 분석 요청 플래그(LOCK 안). 대기 직전 요청도 잃지 않음 |
| `m_str*` / `m_ar*Token` | 필터 조건 6종과 소문자 토큰 |
| `LOCK`, `LogSource.FILE_LOCK` | 목록 변경·재필터 대기 / 기록 파일 동기화 |

---

## 4. 동작 흐름

시퀀스 다이어그램은 [HTML 버전](SOURCE_STRUCTURE_20261007190159.html) 4장에 있다. 요약:

| 흐름 | 순서 |
|---|---|
| 시작 | `new LogFilterMain()` → `FilterEngine`·`LogCatParser`·`LogSource` 생성 → 화면 구성 → `m_engine.start()` → `addDesc()` → `AppConfig.load()` → `applyConfig()` → `loadColors()`·`loadCmds()` |
| 파일 열기 | Open/Recent/드래그&드롭/인자 → `LogFilterMain.parseFile()` → `LogSource.parseFile()`: `new FileLogStore` → `engine.setStore()` / LoadFile 스레드: `indexNext()`로 4MB씩 줄 위치 색인 → 200ms마다 `notifyIndexed()`("Loading x%") → 끝나면 `m_bComplete` = true("Loaded N lines · analyzing") / 엔진 스레드: 색인된 줄을 이어서 분석·필터 판정 → 끝나면 "Ready" |
| 화면 표시 | `LogFilterTableModel.getValueAt()` → `LogList.get(i)` → `LogStore` 캐시에 없으면 `FileLogStore.readLine()`(위치 읽기) → `parse()` → 캐시. 보이는 줄만 읽는다 |
| logcat | Run → `LogSource.startProcess()`: `setStore(MemoryLogStore)` / AdbProcess 스레드: adb 출력(stderr 포함) → 기록 파일 / `startFileParse()`: 기록 파일의 `FileLogStore` → WatchFile 스레드: 50ms마다 `indexNext()`(끝의 미완성 줄은 다음에) → `notifyAppended()` → 증분 분석 → `refreshTable(FOLLOW_END)` |
| 재필터 | 입력 변경 → `FilterEngine.setFind()` 등 + `markChanged()` → 250ms 디바운스 → `requestFilter()` → 엔진 스레드가 새 `FilteredList`를 채움(많으면 병렬, 채우는 동안에도 표시, "Filtering x%") → `onDataChanged(SELECT_LAST)` → "Complete" |
| 렌더링 | `LogCellRenderer`가 Find/Tag 토큰(`FilterEngine`)과 하이라이트 토큰(`LogTable`)으로 일치 여부 확인 → 없으면 원문, 있으면 이스케이프된 HTML |
| 북마크 | 더블클릭/Ctrl+F2 → `FilterEngine.bookmarkItem()` / F2·F3 → 표시 북마크 위치 `TreeSet`에서 이전/다음 |
| 종료 | `LogSource.stopProcess()` → `FilterEngine.stop()` → `saveConfig()` → `AppConfig.saveColors()` → `System.exit(0)` |

**스레드:** EDT, `FilterEngine`(분석·재필터), `FilterWorker-n`(병렬 처리 풀, 데몬), `LoadFile`, `AdbProcess`, `WatchFile`, `AdbDevices`. 백그라운드 스레드는 Swing을 직접 건드리지 않는다.

---

## 5. 클래스별 상세

클래스별 필드/메서드 UML 박스와 클래스 관계도는 [HTML 버전](SOURCE_STRUCTURE_20261007190159.html) 5장에 있다.

- **LogFilterMain:** 화면 구성(`get*Panel`), 이벤트 연결, Listener 구현(`onDataChanged`·`onStatus`·`onTitle`·`onProcessStopped`·`onDevices`), `refreshTable()`·`runOnEdt()`, 설정 반영(`applyConfig`/`saveConfig`), 동작 위임.
- **FilterEngine:** `setStore()`, `clearData()`(같은 파일의 이후 부분만 보는 새 목록), `bookmarkItem()`, `getView()`(LOCK 없음), `accept()`, `checkUseFilter()`, `markChanged()`/`requestFilter()`(전체), `requestAnalysis()`/`notifyIndexed()`/`notifyAppended()`(증분), `process()`/`processParallel()`, `start()`/`stop()`. 내부 `Listener` 인터페이스, `View` 클래스.
- **LogStore / FileLogStore / MemoryLogStore:** `get()`(캐시 + 북마크·메모 상태 반영), `forEach()`(블록 순차 읽기), `readLine()`, `parse()`, `isMarked()`/`setMarked()`/`setMemo()`, `cleared()`, `close()`. `FileLogStore.indexNext(bIncludeTail)`: 빈 줄 건너뜀, CRLF 제거, 쓰는 중인 파일은 '\n'으로 끝난 줄까지만. 닫힌 채널은 다시 열어 한 번 더 읽는다.
- **FilteredList / LogList / IntList / LongList:** 필터 결과(줄 번호 목록), 화면용 목록 인터페이스, 뒤에만 추가하는 기본형 배열(쓰기 하나·읽기 여럿).
- **LogSource:** `parseFile()`, `startProcess()`, `startFileParse()`, `stopProcess()`, `setPause()`, `listDevices()`. 내부 `Listener` 인터페이스, `Device` 클래스.
- **AppConfig:** `load()`/`save()`, `loadCmds()`, `loadColors()`/`saveColors()`, `intOf`/`hexOf`(키별 기본값).
- **LogTable:** 하이라이트 보관, `setFilterEngine()`, Alt+클릭 → `FilterEngine.setShowTag/RemoveTag`, `gotoBookmark()`(TreeSet), `LogCellRenderer`(조기 반환, 이스케이프, Font/Color 재사용).
- **LogFilterTableModel:** `setData(LogList)`/`syncRowCount()`(EDT 전용), `getData()`. **IndicatorPanel:** `Map` 값 순회로 그리기, 재필터 중 생략.
- **LogCatParser:** 첫 글자로 후보 형식을 좁히고, threadtime 기본형은 정규식 없이 직접 나눈다(빠른 경로). 나머지 형식(time, year, uid, brief, process, tag, kernel/dmesg)은 미리 컴파일한 정규식. 레벨 `A`(Assert)는 Fatal로 취급.
- **LogInfo / FilterToken / RecentFileMenu / LogColor / INotiEvent / T:** 역할은 이전과 같다. `T`는 `log()` 하나로 정리(S7).

---
## 6. 설정 파일 (`KLogFilter/` 작업 디렉터리 기준)

저장소에는 `LogFilterCmd.ini`만 들어 있다. 나머지 셋은 실행할 때마다 바뀌므로 `.gitignore`로 제외했다(없으면 기본값을 쓰고 종료할 때 만들어짐).

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
| B13 | 🟡 ✅ 수정됨 | LogCatParser | 고정 위치로 형식을 판별해서 `-v year`, `-v uid`, 최신 logcat 포맷, 공백이나 `/`가 들어간 태그를 제대로 파싱하지 못한다. threadtime 형식에서는 Tag에 `:`가 붙고 Message 앞에 공백이 남는다. |

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
| P10 ✅ | ★ | `runFilter()` | EDT에서 `Thread.sleep(100)` 바쁜 대기 | 대기 제거(상태 플래그와 notify만으로 충분) |
| P11 | ★ | `T` 로그 | 호출마다 `new Exception()`(스택 수집)과 `SimpleDateFormat` 생성, 항상 켜져 있음 | 릴리스에서는 비활성화하거나 `misEnabled`를 먼저 검사(이미 검사 중이므로 기본값을 false로) |

### 8.2 구조 / 코드 품질

| # | 대상 | 개선안 |
|---|---|---|
| S1 ✅ | `LogFilterMain`(2,236줄) | 역할별로 분리: `MainFrame`(UI), `LogSource`(파일/adb 입력), `FilterEngine`(필터 스레드와 판정), `AppConfig`(ini 로드/저장) |
| S2 | 중복된 필터 판정 | `addLogInfo()`와 필터 스레드에 같은 판정 로직이 복사돼 있다 → `boolean accept(LogInfo)` 하나로 통합 |
| S3 | `check*Filter()` 6개 | 공통 헬퍼 `matchAny(String field, String[] tokens)`로 통합 |
| S4 ✅ | DocumentListener | `changedUpdate`, `insertUpdate`, `removeUpdate`의 본문이 똑같다 → `onFilterTextChanged(DocumentEvent)` 하나로 |
| S5 ✅ | 필터 상태 위치 | 필터 문자열을 `LogTable`(뷰)에서 `FilterEngine`(모델)으로 이동 |
| S6 ✅ | `gotoNext/PreBookmark` | 표시 리스트를 선형 탐색 → 정렬된 북마크 index 목록에서 이진 탐색 |
| S7 ✅ | `T.java` | 거의 같은 메서드 8개 → `log(level, msg)` 하나로 |
| S8 ✅ | Swing 스레드 | 백그라운드 결과는 `SwingUtilities.invokeLater`나 `SwingWorker`로 EDT에 반영 (B4 해결) |
| S9 ✅ | 설정 로드 | `getProperty(key, default)`로 키마다 기본값 지정 (B7 해결), try-with-resources는 JDK 7 이상 |

### 8.3 정리 가능한 죽은 코드 ✅ (2026-10-06 삭제 완료)

아래 항목은 모두 삭제했다. `installInputHistory()`만 6단계(기능 보완)에서 연결하려고 남겼다.

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
4. ✅ **완료(2026-10-06) — 구조 개선:** 미사용 클래스 7개·주석 코드 삭제, S1(`LogFilterMain` 2,235 → 1,330줄, `FilterEngine`·`LogSource`·`AppConfig` 분리), S5, S6, S7, P10. 단위 테스트·실제 창 기능 테스트 8항목·스트레스 테스트 60초 예외 0건.
5. ✅ **완료(2026-10-06) — 저장소 정리:** UTF-8·JavaSE-1.8 통일, 실행 중 바뀌는 `LogFilter.ini`·`LogFilterColor.ini`·`RecentFile.ini`를 저장소에서 제외(`.gitignore`), `.gitattributes`·`README.md` 추가, 작업 브랜치를 `master`에 반영.
6. ✅ **완료(2026-10-06) — 기능 보완:** 필터·하이라이트 입력창 7개에 ↑/↓ 입력 히스토리 연결(↓가 입력을 지우던 문제 수정, 맨 앞을 지나면 탐색 전 값 복원), 드래그&드롭·실행 인자·Recent로 연 파일도 Recent 맨 위에 추가, 파서 포맷 확장(B13: year·uid·brief·process·tag·dmesg, 긴 PID, threadtime 태그 정리). threadtime 빠른 경로로 30만 줄 파싱 약 720 ms → 약 90 ms(필드 30만 건 일치 확인).
7. ✅ **완료(2026-10-06) — 대용량 파일 구조:** 줄 위치만 색인하는 `FileLogStore`, 지연 해석 + LRU 캐시, 읽는 즉시 표시(부분 로딩), 증분 분석, 병렬 필터(결과는 순차 처리와 같음). 실제 창에서 측정(JDK 8, 논리 코어 16개):

   | 항목 | 440MB (420만 줄) 이전 | 440MB 이후 | 1GB (1,000만 줄) 이전 | 1GB 이후 |
   |---|---:|---:|---:|---:|
   | 첫 화면 표시 | 55.6초(다 읽은 뒤) | 0.07초 | 20분 넘게 끝나지 않음 | 0.05~0.08초 |
   | 전체 색인 / Ready | 55.6초 | 2.1초 / 2.3초 | — | 5.8~7.9초 |
   | 끝·중간으로 이동 + 그리기 | — | 13~57 ms | — | 13~61 ms |
   | Find 필터 입력 → 완료 | — | 1.8초 | — | 2.7~4.7초 |
   | 힙 사용량 | 2,489 MB | 77 MB | — | 160 MB |

   테스트: 단위 테스트(경계 조건, 증분 판정 = 전체 재판정 30만 줄), 실제 창 기능 테스트, 실시간 adb 수집(6초에 20만 줄), 스트레스 테스트 60초 예외 0건.
