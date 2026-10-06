/**
 * 화면에 보일 줄 목록(전체 줄 또는 필터 결과).
 * 크기는 늘어나기만 하고, get()은 그 줄이 필요할 때 해석한 LogInfo를 돌려준다.
 */
public interface LogList
{
    int size();

    // nRow번째 줄 (0부터)
    LogInfo get(int nRow);

    // nRow번째 줄의 원본 위치 (전체 목록이면 nRow 그대로)
    int lineIndexOf(int nRow);
}
