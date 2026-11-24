package kr.hetagram.stock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.Map;

final class Text {
    private static final LegacyComponentSerializer LEGACY=LegacyComponentSerializer.legacyAmpersand();
    // YAML의 작은따옴표 문자열에 적은 \n도 실제 홀로그램 줄바꿈으로 표시합니다.
    static Component component(String value) { return LEGACY.deserialize(value.replace("\\n","\n")); }
    static String fill(String template,Map<String,String> values) {
        for(var e:values.entrySet())template=template.replace("{"+e.getKey()+"}",e.getValue());
        return template;
    }
    /** 표시만 반올림합니다. 실제 거래·평단·저장 금액은 정수 전 단위를 그대로 유지합니다. */
    static String money(GameConfig config,long cents) { return money(cents,config.ui().getInt("ui.money-decimals",0)); }
    static String money(long cents) { return money(cents,0); }
    private static String money(long cents,int decimals) {
        var format=new DecimalFormat("#,##0"+(decimals==0?"":"."+"0".repeat(decimals)),java.text.DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return format.format(java.math.BigDecimal.valueOf(cents,2));
    }
    static String percent(GameConfig config,double value) { return percent(value,config.ui().getInt("ui.percent-decimals",0)); }
    static String percent(double value) { return percent(value,0); }
    private static String percent(double value,int decimals) { return String.format(Locale.US,"%+."+decimals+"f",value); }
    static String color(GameConfig c,double change) { return c.ui().getString(change>0?"ui.rise-color":change<0?"ui.fall-color":"ui.flat-color"); }
    private Text() {}
}
