package io.github.dalong.hyperhome;

import com.dalong.pengpaitool.RouteCatalog;
import java.util.ArrayList;
import java.util.List;

/** Uses the tool router's own metadata; searching never prepares the Root backend. */
final class ToolSearchIndex {
    private ToolSearchIndex() {}
    static List<SubmoduleIndex.Entry> entries() {
        var entries = new ArrayList<>(RouteCatalog.entries().stream().map(entry -> new SubmoduleIndex.Entry(
                "tool:" + entry.getRoute(), entry.getTitle(),
                RouteCatalog.title(entry.getGroupId()), entry.getSubtitle(),
                entry.getRoute())).toList());
        entries.add(new SubmoduleIndex.Entry("app:logs", "日志与诊断", "澎湃工坊", "模块 运行状态 连接 日志 充电 调试", "logs"));
        return List.copyOf(entries);
    }
}
