package io.github.dalong.hyperhome;
import com.dalong.pengpaitool.RouteCatalog;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.*;
public class ToolSearchIndexTest {
    @Test public void everyRetainedPageHasOneSearchDestination() {
        var entries=ToolSearchIndex.entries();
        assertEquals(RouteCatalog.entries().size()+1,entries.size());
        var ids=new HashSet<String>();
        for(var entry:entries) {
            assertTrue(ids.add(entry.id()));
            if(!entry.id().equals("app:logs")) assertNotNull(RouteCatalog.entry(entry.route()));
        }
    }
    @Test public void removedFeatureGroupsHaveNoSearchDestinations() {
        var index=new SubmoduleIndex(ToolSearchIndex.entries());
        for(String query:new String[]{"文件夹排列数量","桌面图标大小","双层信息布局","电源菜单大小"})
            assertTrue(query,index.search(query).isEmpty());
        assertNull(RouteCatalog.entry("navtools"));
        assertEquals("charge",index.search("充电控制").get(0).route());
        assertEquals("mydevice",index.search("我的设备美化").get(0).route());
        assertEquals("logs",index.search("日志与诊断").get(0).route());
    }
}
