package io.github.dalong.hyperhome;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public class SubmoduleIndexTest {
    private final SubmoduleIndex index=new SubmoduleIndex(List.of(
        new SubmoduleIndex.Entry("one","充电控制","电池与性能","电池 温控 充电","charge"),
        new SubmoduleIndex.Entry("two","充电记录","日志与诊断","充电 曲线","chargelog"),
        new SubmoduleIndex.Entry("three","WLAN 密码","系统工具","Wi-Fi","wifi")));
    @Test public void exactTitleRanksBeforeAliases() {assertEquals("charge",index.search("充电控制").get(0).route());}
    @Test public void allTermsMustMatch() {
        assertEquals(1,index.search("充电 温控").size());
        assertTrue(index.search("充电 密码").isEmpty());
    }
    @Test public void normalizationAcceptsWifiAndIgnoresPunctuation() {
        assertEquals("wifi",index.search("wi-fi").get(0).route());
        assertTrue(index.search("  ").isEmpty());
    }
}
