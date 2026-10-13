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

    /** 시트 하나: 이름, 머리줄, 자료 줄들, 열 너비(글자 수), 차트들 */
    public static class Sheet
    {
        final String         m_strName;
        final Object[]       m_arHeader;
        final List<Object[]> m_arRow = new ArrayList<Object[]>();
        final List<Chart>    m_arChart = new ArrayList<Chart>();
        double[]             m_arWidth;

        Sheet(String strName, Object[] arHeader)
        {
            m_strName = strName;
            m_arHeader = arHeader;
        }

        public void addRow(Object... arCell) { m_arRow.add(arCell); }
        public void setWidths(double... arWidth) { m_arWidth = arWidth; }
        public int rowCount() { return m_arRow.size(); }

        /** 이 시트에 꺾은선 차트를 놓는다 (셀 nFromRow 줄부터 nRows 줄, 0열부터 nCols 열 크기) */
        public Chart addChart(String strTitle, int nFromRow, int nRows, int nCols)
        {
            Chart chart = new Chart(strTitle, nFromRow, nRows, nCols);
            m_arChart.add(chart);
            return chart;
        }
    }

    /**
     * 꺾은선 차트: 다른 시트의 셀 범위를 참조하므로 저장한 뒤 셀 값을 고쳐도 차트가 따라 바뀐다.
     * 가로축은 범주(시각 셀), 세로축은 0~nMax.
     */
    public static class Chart
    {
        final String m_strTitle;
        final int    m_nFromRow, m_nRows, m_nCols;
        String       m_strCategoryRef;          // 예: '통계'!$A$2:$A$61
        String       m_strCategoryFormat = "hh:mm:ss";
        double       m_dMax = 100, m_dMin = 0;
        final List<String[]> m_arSeries = new ArrayList<String[]>();   // {이름, 값 범위, 색 RRGGBB}

        Chart(String strTitle, int nFromRow, int nRows, int nCols)
        {
            m_strTitle = strTitle; m_nFromRow = nFromRow; m_nRows = nRows; m_nCols = nCols;
        }

        public Chart setCategories(String strRef) { m_strCategoryRef = strRef; return this; }
        public Chart setRange(double dMin, double dMax) { m_dMin = dMin; m_dMax = dMax; return this; }
        public Chart addSeries(String strName, String strValuesRef, String strColorHex) { m_arSeries.add(new String[]{ strName, strValuesRef, strColorHex }); return this; }
    }

    /** 셀 범위 참조 문자열: 시트 이름, 0부터 세는 열, 1부터 세는 줄 범위 → '시트'!$C$2:$C$61 */
    public static String ref(String strSheet, int nCol, int nRow1, int nRow2)
    {
        return "'" + strSheet.replace("'", "''") + "'!$" + col(nCol) + "$" + nRow1 + ":$" + col(nCol) + "$" + nRow2;
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
            int nDrawing = 0, nChart = 0;
            for(int i = 0; i < m_arSheet.size(); i++)
            {
                Sheet sheet = m_arSheet.get(i);
                boolean bDrawing = !sheet.m_arChart.isEmpty();
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + (i + 1) + ".xml"));
                writeSheet(w, sheet, bDrawing);
                w.flush();
                zip.closeEntry();
                if(!bDrawing) continue;
                // 차트가 있는 시트: 시트 → 그림(drawing) → 차트 순으로 연결한다
                nDrawing++;
                put(zip, w, "xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing" + nDrawing + ".xml\"/>"
                    + "</Relationships>");
                StringBuilder sbDrawing = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">");
                StringBuilder sbDrawingRels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
                for(int k = 0; k < sheet.m_arChart.size(); k++)
                {
                    Chart chart = sheet.m_arChart.get(k);
                    nChart++;
                    sbDrawing.append("<xdr:twoCellAnchor>")
                        .append("<xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(chart.m_nFromRow).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>")
                        .append("<xdr:to><xdr:col>").append(chart.m_nCols).append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(chart.m_nFromRow + chart.m_nRows).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>")
                        .append("<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"").append(k + 2).append("\" name=\"Chart ").append(k + 1).append("\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>")
                        .append("<xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm>")
                        .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">")
                        .append("<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:id=\"rId").append(k + 1).append("\"/>")
                        .append("</a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>");
                    sbDrawingRels.append("<Relationship Id=\"rId").append(k + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chart").append(nChart).append(".xml\"/>");
                    put(zip, w, "xl/charts/chart" + nChart + ".xml", chartXml(chart));
                }
                put(zip, w, "xl/drawings/drawing" + nDrawing + ".xml", sbDrawing.append("</xdr:wsDr>").toString());
                put(zip, w, "xl/drawings/_rels/drawing" + nDrawing + ".xml.rels", sbDrawingRels.append("</Relationships>").toString());
            }
        }
    }

    static final String NS_C = "http://schemas.openxmlformats.org/drawingml/2006/chart";
    static final String NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    // 꺾은선 차트 파트. 표식 없이 선만, 빈 셀은 끊어서 그린다.
    static String chartXml(Chart chart)
    {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<c:chartSpace xmlns:c=\"" + NS_C + "\" xmlns:a=\"" + NS_A + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<c:roundedCorners val=\"0\"/><c:chart>"
            + "<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"1200\" b=\"1\"/></a:pPr><a:r><a:rPr lang=\"ko-KR\" sz=\"1200\" b=\"1\"/><a:t>" + esc(chart.m_strTitle) + "</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title>"
            + "<c:autoTitleDeleted val=\"0\"/><c:plotArea><c:layout/><c:lineChart><c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>");
        for(int i = 0; i < chart.m_arSeries.size(); i++)
        {
            String[] s = chart.m_arSeries.get(i);
            sb.append("<c:ser><c:idx val=\"").append(i).append("\"/><c:order val=\"").append(i).append("\"/>")
              .append("<c:tx><c:v>").append(esc(s[0])).append("</c:v></c:tx>")
              .append("<c:spPr><a:ln w=\"19050\" cap=\"rnd\"><a:solidFill><a:srgbClr val=\"").append(s[2]).append("\"/></a:solidFill><a:round/></a:ln></c:spPr>")
              .append("<c:marker><c:symbol val=\"none\"/></c:marker>");
            if(chart.m_strCategoryRef != null)
                sb.append("<c:cat><c:numRef><c:f>").append(esc(chart.m_strCategoryRef)).append("</c:f></c:numRef></c:cat>");
            sb.append("<c:val><c:numRef><c:f>").append(esc(s[1])).append("</c:f></c:numRef></c:val><c:smooth val=\"0\"/></c:ser>");
        }
        sb.append("<c:marker val=\"1\"/><c:axId val=\"500100\"/><c:axId val=\"500200\"/></c:lineChart>")
          .append("<c:catAx><c:axId val=\"500100\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"b\"/>")
          .append("<c:numFmt formatCode=\"").append(esc(chart.m_strCategoryFormat)).append("\" sourceLinked=\"0\"/><c:majorTickMark val=\"out\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"low\"/>")
          // auto=0: 시각 셀을 날짜 축(하루 단위)으로 해석하지 않고 측정마다 한 칸씩 쓰는 문자 축으로 둔다
          .append("<c:crossAx val=\"500200\"/><c:crosses val=\"autoZero\"/><c:auto val=\"0\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/><c:noMultiLvlLbl val=\"1\"/></c:catAx>")
          .append("<c:valAx><c:axId val=\"500200\"/><c:scaling><c:orientation val=\"minMax\"/><c:max val=\"").append(chart.m_dMax).append("\"/><c:min val=\"").append(chart.m_dMin).append("\"/></c:scaling>")
          .append("<c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/><c:numFmt formatCode=\"General\" sourceLinked=\"1\"/><c:majorTickMark val=\"out\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/>")
          .append("<c:crossAx val=\"500100\"/><c:crosses val=\"autoZero\"/><c:crossBetween val=\"between\"/></c:valAx></c:plotArea>")
          .append("<c:legend><c:legendPos val=\"b\"/><c:overlay val=\"0\"/></c:legend><c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart></c:chartSpace>");
        return sb.toString();
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
        int nDrawing = 0, nChart = 0;
        for(Sheet sheet : m_arSheet)
        {
            if(sheet.m_arChart.isEmpty()) continue;
            sb.append("<Override PartName=\"/xl/drawings/drawing").append(++nDrawing).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
            for(int k = 0; k < sheet.m_arChart.size(); k++)
                sb.append("<Override PartName=\"/xl/charts/chart").append(++nChart).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>");
        }
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

    static void writeSheet(Writer w, Sheet sheet, boolean bDrawing) throws IOException
    {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"" + NS + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
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
        w.write("</sheetData>");
        if(bDrawing) w.write("<drawing r:id=\"rId1\"/>");
        w.write("</worksheet>");
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
