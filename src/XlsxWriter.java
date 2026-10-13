import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 외부 라이브러리 없이 엑셀 파일(.xlsx, Office Open XML)을 쓴다. (1.28)
 * - 시트 여러 개, 셀은 문자열·숫자·날짜(null이면 빈 칸)
 * - 머리줄은 굵게, 첫 줄 고정, 열 너비 지정
 * 문자열은 inlineStr로 넣어 sharedStrings 없이 한 번에 흘려 쓴다.
 */
public class XlsxWriter
{
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final String  NS   = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final int STYLE_NORMAL = 0, STYLE_HEADER = 1, STYLE_DATE = 2;

    /** 시트 하나: 이름, 머리줄, 자료 줄들, 열 너비(글자 수) */
    public static class Sheet
    {
        final String         m_strName;
        final Object[]       m_arHeader;
        final List<Object[]> m_arRow = new ArrayList<Object[]>();
        double[]             m_arWidth;

        Sheet(String strName, Object[] arHeader)
        {
            m_strName = strName;
            m_arHeader = arHeader;
        }

        public void addRow(Object... arCell) { m_arRow.add(arCell); }
        public void setWidths(double... arWidth) { m_arWidth = arWidth; }
        public int rowCount() { return m_arRow.size(); }
    }

    final List<Sheet> m_arSheet = new ArrayList<Sheet>();

    public Sheet addSheet(String strName, Object... arHeader)
    {
        Sheet sheet = new Sheet(sheetName(strName), arHeader);
        m_arSheet.add(sheet);
        return sheet;
    }

    // 시트 이름 규칙: 31자 이하, [ ] : * ? / \ 금지, 같은 이름 금지
    String sheetName(String s)
    {
        String strName = s.replaceAll("[\\[\\]:*?/\\\\]", "_").trim();
        if(strName.length() == 0) strName = "Sheet";
        if(strName.length() > 31) strName = strName.substring(0, 31);
        String strBase = strName;
        for(int n = 2; hasSheet(strName); n++)
        {
            String strSuffix = " (" + n + ")";
            strName = (strBase.length() + strSuffix.length() > 31 ? strBase.substring(0, 31 - strSuffix.length()) : strBase) + strSuffix;
        }
        return strName;
    }

    boolean hasSheet(String strName)
    {
        for(Sheet sheet : m_arSheet)
            if(sheet.m_strName.equalsIgnoreCase(strName)) return true;
        return false;
    }

    public void write(File file) throws IOException
    {
        if(m_arSheet.isEmpty()) addSheet("Sheet1");
        try(ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file)))
        {
            Writer w = new OutputStreamWriter(zip, UTF8);
            put(zip, w, "[Content_Types].xml", contentTypes());
            put(zip, w, "_rels/.rels", rels());
            put(zip, w, "xl/workbook.xml", workbook());
            put(zip, w, "xl/_rels/workbook.xml.rels", workbookRels());
            put(zip, w, "xl/styles.xml", styles());
            for(int i = 0; i < m_arSheet.size(); i++)
            {
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + (i + 1) + ".xml"));
                writeSheet(w, m_arSheet.get(i));
                w.flush();
                zip.closeEntry();
            }
        }
    }

    static void put(ZipOutputStream zip, Writer w, String strPath, String strXml) throws IOException
    {
        zip.putNextEntry(new ZipEntry(strPath));
        w.write(strXml);
        w.flush();
        zip.closeEntry();
    }

    String contentTypes()
    {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for(int i = 1; i <= m_arSheet.size(); i++)
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return sb.append("</Types>").toString();
    }

    static String rels()
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";
    }

    String workbook()
    {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<workbook xmlns=\"" + NS + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for(int i = 0; i < m_arSheet.size(); i++)
            sb.append("<sheet name=\"").append(esc(m_arSheet.get(i).m_strName)).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        return sb.append("</sheets></workbook>").toString();
    }

    String workbookRels()
    {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for(int i = 1; i <= m_arSheet.size(); i++)
            sb.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        sb.append("<Relationship Id=\"rId").append(m_arSheet.size() + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        return sb.append("</Relationships>").toString();
    }

    // 0: 기본, 1: 굵게(머리줄), 2: 날짜시간
    static String styles()
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<styleSheet xmlns=\"" + NS + "\">"
            + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\\ hh:mm:ss\"/></numFmts>"
            + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"3\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";
    }

    static void writeSheet(Writer w, Sheet sheet) throws IOException
    {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"" + NS + "\">");
        boolean bHeader = sheet.m_arHeader != null && sheet.m_arHeader.length > 0;
        if(bHeader)
            w.write("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        if(sheet.m_arWidth != null && sheet.m_arWidth.length > 0)
        {
            w.write("<cols>");
            for(int i = 0; i < sheet.m_arWidth.length; i++)
                if(sheet.m_arWidth[i] > 0)
                    w.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + sheet.m_arWidth[i] + "\" customWidth=\"1\"/>");
            w.write("</cols>");
        }
        w.write("<sheetData>");
        int nRow = 1;
        if(bHeader)
            writeRow(w, nRow++, sheet.m_arHeader, STYLE_HEADER);
        for(Object[] arCell : sheet.m_arRow)
            writeRow(w, nRow++, arCell, STYLE_NORMAL);
        w.write("</sheetData></worksheet>");
    }

    static void writeRow(Writer w, int nRow, Object[] arCell, int nStyle) throws IOException
    {
        w.write("<row r=\"" + nRow + "\">");
        for(int i = 0; i < arCell.length; i++)
        {
            Object v = arCell[i];
            if(v == null) continue;
            String strRef = col(i) + nRow;
            if(v instanceof Date)
                w.write("<c r=\"" + strRef + "\" s=\"" + STYLE_DATE + "\"><v>" + serial((Date)v) + "</v></c>");
            else if(v instanceof Number)
            {
                double d = ((Number)v).doubleValue();
                if(Double.isNaN(d) || Double.isInfinite(d)) continue;
                String strNum = v instanceof Integer || v instanceof Long ? v.toString() : Double.toString(d);
                w.write("<c r=\"" + strRef + "\"" + (nStyle == STYLE_NORMAL ? "" : " s=\"" + nStyle + "\"") + "><v>" + strNum + "</v></c>");
            }
            else
                w.write("<c r=\"" + strRef + "\" t=\"inlineStr\"" + (nStyle == STYLE_NORMAL ? "" : " s=\"" + nStyle + "\"") + "><is><t xml:space=\"preserve\">" + esc(v.toString()) + "</t></is></c>");
        }
        w.write("</row>");
    }

    /** 0 → A, 25 → Z, 26 → AA */
    static String col(int n)
    {
        StringBuilder sb = new StringBuilder();
        for(int v = n; v >= 0; v = v / 26 - 1)
            sb.insert(0, (char)('A' + v % 26));
        return sb.toString();
    }

    /** 엑셀 날짜 일련번호 (1900-01-01 = 1, 현지 시각 기준) */
    static double serial(Date date)
    {
        long nMs = date.getTime() + TimeZone.getDefault().getOffset(date.getTime());
        return nMs / 86400000.0 + 25569;
    }

    // XML 특수 문자 바꾸기, XML에 넣을 수 없는 제어 문자는 뺀다
    static String esc(String s)
    {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch(c)
            {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                default:
                    if(c >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c);
            }
        }
        return sb.toString();
    }
}
