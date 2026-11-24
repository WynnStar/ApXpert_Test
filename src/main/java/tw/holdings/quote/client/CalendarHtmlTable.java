package tw.holdings.quote.client;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** 將官方 HTML 表格展開 rowspan，避免以固定列號讀取假日。 */
final class CalendarHtmlTable extends HTMLEditorKit.ParserCallback {
    private record Span(String text,int remaining) {}
    private final List<List<String>> rows=new ArrayList<>();
    private final Map<Integer,Span> carried=new HashMap<>();
    private List<String> row;
    private StringBuilder cell;
    private int span=1;
    static List<List<String>> parse(String html) throws IOException {
        var parser=new CalendarHtmlTable();
        new ParserDelegator().parse(new StringReader(html),parser,true);
        return parser.rows;
    }
    @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attributes,int position) {
        if(tag==HTML.Tag.TABLE) carried.clear();
        if(tag==HTML.Tag.TR) { row=new ArrayList<>();fillCarried(); }
        if(tag==HTML.Tag.TD || tag==HTML.Tag.TH) {
            cell=new StringBuilder();
            var raw=attributes.getAttribute(HTML.Attribute.ROWSPAN);
            span=raw==null?1:Integer.parseInt(raw.toString());
            if(span<1 || span>40) throw new IllegalArgumentException("Invalid calendar rowspan");
        }
    }
    private void fillCarried() {
        while(carried.containsKey(row.size())) {
            int col=row.size();var old=carried.remove(col);row.add(old.text());
            if(old.remaining()>1) carried.put(col,new Span(old.text(),old.remaining()-1));
        }
    }
    @Override public void handleText(char[] text,int position) { if(cell!=null)cell.append(text); }
    @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attributes,int position) {
        if(tag==HTML.Tag.BR && cell!=null)cell.append(' ');
    }
    @Override public void handleEndTag(HTML.Tag tag,int position) {
        if((tag==HTML.Tag.TD || tag==HTML.Tag.TH) && cell!=null && row!=null) {
            String text=cell.toString().replace('\u00a0',' ').strip();
            if(span>1)carried.put(row.size(),new Span(text,span-1));
            row.add(text);cell=null;fillCarried();
        }
        if(tag==HTML.Tag.TR && row!=null) { rows.add(List.copyOf(row));row=null; }
    }
}
