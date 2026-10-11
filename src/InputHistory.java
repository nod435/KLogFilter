import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

/**
 * 입력창 하나의 최근 입력 기록 (최대 10개, 맨 앞이 가장 최근).
 * - Enter 또는 포커스를 옮길 때 기록하고, ↑/↓로 차례로 불러온다.
 * - showPopup()으로 목록을 띄워 골라 쓸 수 있다.
 * - 목록은 LogFilter.ini에 저장되어 다음 실행에도 남는다 (AppConfig의 HISTORY_<이름>_n).
 */
public class InputHistory
{
    static final int MAX = 10;

    final String         m_strKey;          // 설정 파일에 쓰는 이름 (예: FIND, HIGHLIGHT_0)
    final JTextComponent m_comp;
    final List<String>   m_arItem = new ArrayList<String>();
    private int          m_nCursor = -1;    // ↑/↓ 탐색 위치. -1이면 탐색 중이 아님
    private String       m_strDraft = "";   // 탐색을 시작하기 전에 입력창에 있던 값

    InputHistory(String strKey, JTextComponent comp)
    {
        m_strKey = strKey;
        m_comp   = comp;
        install();
    }

    List<String> items()
    {
        return new ArrayList<String>(m_arItem);
    }

    // 저장된 목록으로 바꾼다 (설정 읽기)
    void setItems(List<String> arItem)
    {
        m_arItem.clear();
        if(arItem != null)
            for(String s : arItem)
                if(s != null && s.trim().length() > 0 && !m_arItem.contains(s) && m_arItem.size() < MAX)
                    m_arItem.add(s);
        m_nCursor = -1;
    }

    // 지금 입력창 값을 맨 앞에 기록 (빈 값은 기록하지 않음, 같은 값은 앞으로 옮김)
    void commit()
    {
        add(m_comp.getText());
    }

    void add(String strText)
    {
        if(strText == null || strText.trim().length() == 0)
            return;
        m_arItem.remove(strText);
        m_arItem.add(0, strText);
        while(m_arItem.size() > MAX)
            m_arItem.remove(m_arItem.size() - 1);
        m_nCursor = -1;
    }

    void clear()
    {
        m_arItem.clear();
        m_nCursor = -1;
    }

    private void install()
    {
        // Enter : 현재 값을 기록
        m_comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "HistoryCommit");
        m_comp.getActionMap().put("HistoryCommit", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e) { commit(); }
        });
        // ↑ : 더 예전 입력값
        m_comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "HistoryPrev");
        m_comp.getActionMap().put("HistoryPrev", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                if(m_arItem.isEmpty())
                    return;
                if(m_nCursor == -1)
                {
                    m_strDraft = m_comp.getText();
                    // 지금 값이 가장 최근 기록과 같으면 그 다음 것부터 보여준다.
                    m_nCursor = m_arItem.get(0).equals(m_strDraft) && m_arItem.size() > 1 ? 1 : 0;
                }
                else if(m_nCursor < m_arItem.size() - 1)
                    m_nCursor++;
                setText(m_arItem.get(m_nCursor));
            }
        });
        // ↓ : 더 최근 입력값. 맨 앞을 지나면 탐색 전 값으로 돌아간다. (탐색 중이 아니면 아무 동작 안 함)
        m_comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "HistoryNext");
        m_comp.getActionMap().put("HistoryNext", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                if(m_nCursor == -1)
                    return;
                m_nCursor--;
                setText(m_nCursor == -1 ? m_strDraft : m_arItem.get(m_nCursor));
            }
        });
        // Alt+↓ : 목록 띄우기
        m_comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK), "HistoryList");
        m_comp.getActionMap().put("HistoryList", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e) { showPopup(m_comp); }
        });
        // 포커스가 벗어날 때도 기록 (Enter를 누르지 않아도)
        m_comp.addFocusListener(new java.awt.event.FocusAdapter()
        {
            public void focusLost(java.awt.event.FocusEvent e) { commit(); }
        });
    }

    private void setText(String strText)
    {
        m_comp.setText(strText);
        m_comp.setCaretPosition(m_comp.getDocument().getLength());
    }

    JPopupMenu m_popup;     // 마지막으로 띄운 목록 (테스트용)

    // 최근 입력 목록을 anchor 아래에 띄운다. 고르면 입력창에 넣고 기록 맨 앞으로 옮긴다.
    void showPopup(Component anchor)
    {
        commit();       // 지금 입력 중인 값도 목록에 넣는다
        JPopupMenu popup = new JPopupMenu();
        if(m_arItem.isEmpty())
        {
            JMenuItem miEmpty = new JMenuItem("(기록 없음)");
            miEmpty.setEnabled(false);
            popup.add(miEmpty);
        }
        for(final String strItem : m_arItem)
        {
            JMenuItem mi = new JMenuItem(strItem.length() <= 60 ? strItem : strItem.substring(0, 60) + "…");
            mi.setToolTipText(strItem);
            mi.addActionListener(new ActionListener()
            {
                public void actionPerformed(ActionEvent e)
                {
                    setText(strItem);
                    add(strItem);
                    m_comp.requestFocusInWindow();
                }
            });
            popup.add(mi);
        }
        if(!m_arItem.isEmpty())
        {
            popup.addSeparator();
            JMenuItem miClear = new JMenuItem("목록 지우기");
            miClear.addActionListener(new ActionListener()
            {
                public void actionPerformed(ActionEvent e) { clear(); }
            });
            popup.add(miClear);
        }
        m_popup = popup;
        popup.show(anchor, 0, anchor.getHeight());
    }
}
