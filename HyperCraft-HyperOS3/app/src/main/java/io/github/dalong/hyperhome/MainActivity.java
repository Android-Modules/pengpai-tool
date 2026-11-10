package io.github.dalong.hyperhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;

/** HyperCraft's original presentation with the five retained tool groups. */
public final class MainActivity extends Activity {
    private static final int TEXT=SettingsUi.TEXT, MUTED=SettingsUi.MUTED;
    private HyperHomeApp app;
    private LinearLayout body;
    private TextView status;
    private SettingsUi ui;
    private boolean destroyed;
    private int page;
    private String searchTerm="";
    private final Runnable serviceListener=()->runOnUiThread(()->{if(!destroyed) updateStatus();});
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app=(HyperHomeApp)getApplication();
        page=state==null?getIntent().getIntExtra("page",0):state.getInt("page");
        searchTerm=state==null?"":state.getString("search","");
        showPage(page);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        if(intent.hasExtra("page")) showPage(intent.getIntExtra("page",0));
    }
    @Override protected void onStart() { super.onStart();app.addServiceListener(serviceListener); }
    @Override protected void onStop() { app.removeServiceListener(serviceListener);super.onStop(); }
    @Override protected void onResume() { super.onResume();updateStatus(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);out.putInt("page",page);out.putString("search",searchTerm);
    }
    @Override protected void onDestroy() { destroyed=true;super.onDestroy(); }
    @Override public void onBackPressed() { if(page==0) super.onBackPressed();else showPage(0); }
    private void showPage(int next) {
        page=next==5?5:0;status=null;
        ui=new SettingsUi(this);
        body=ui.page(page==0?"":"日志与诊断",page!=0,this::onBackPressed,this::diagnostics);
        if(page==0) home();else logs();
        updateStatus();
    }
    private void home() {
        ui.hero(body,"澎湃工坊","HyperCraft · 让系统更合心意");
        status=ui.chip(body,app.status());
        EditText search=new EditText(this);search.setSingleLine();search.setTextSize(14);search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);search.setHint("搜索子模块与功能");search.setContentDescription("搜索子模块与功能");
        search.setPadding(dp(16),dp(12),dp(16),dp(12));search.setBackground(ui.background(Color.WHITE,14));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        LinearLayout.LayoutParams inputParams=new LinearLayout.LayoutParams(-1,-2);inputParams.bottomMargin=dp(16);
        body.addView(search,inputParams);
        LinearLayout results=vertical();body.addView(results);
        LinearLayout normal=vertical();body.addView(normal);
        LinearLayout modules=ui.card(normal);
        for(var group:com.dalong.pengpaitool.RouteCatalog.groups()) {
            if(group.getRoute().equals("records")) continue;
            int icon=switch(group.getRoute()) {case "slim_group"->5;case "display"->6;case "performance"->7;default->8;};
            ui.menu(modules,group.getTitle(),group.getSubtitle(),icon,()->openTool(group.getRoute()));
        }
        ui.menu(modules,"日志与诊断","模块状态、操作记录、充电与调试日志",9,()->showPage(5));
        ui.section(normal,"应用");LinearLayout info=ui.card(normal);
        ui.menu(info,"关于澎湃工坊","项目介绍、版本与开源仓库",10,()->openTool("about"));
        ui.menu(info,"备份与模块设置","备份目录、开机保持与工具恢复",9,()->openTool("backup"));
        SubmoduleIndex index=new SubmoduleIndex(ToolSearchIndex.entries());
        Runnable update=()-> {
            searchTerm=search.getText().toString();results.removeAllViews();
            if(searchTerm.isBlank()) {normal.setVisibility(View.VISIBLE);return;}
            normal.setVisibility(View.GONE);
            var matches=index.search(searchTerm);
            if(matches.isEmpty()) {note(results,"没有找到相关功能，试试“充电”“高刷”或“设备美化”。");return;}
            int shown=Math.min(24,matches.size());
            note(results,matches.size()>shown?"共 "+matches.size()+" 项，显示前 "+shown+" 项；可增加关键词缩小范围。":"找到 "+shown+" 项功能");
            LinearLayout list=ui.card(results);
            for(SubmoduleIndex.Entry entry:matches.subList(0,shown)) ui.menu(list,entry.title(),entry.path(),-1,()-> {
                hideKeyboard();
                if(entry.id().equals("app:logs")) showPage(5);else openTool(entry.route());
            });
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {update.run();}
            @Override public void afterTextChanged(Editable value) {}
        });
        search.setOnEditorActionListener((view,action,event)-> {
            if(action!=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();return true;
        });
        search.setText(searchTerm);search.setSelection(search.length());
    }
    private void logs() {
        note(body,"集中查看各功能的运行状态与记录。");
        LinearLayout list=ui.card(body);
        ui.menu(list,"模块运行状态","LSPosed 连接与设备美化作用域",8,this::diagnostics);
        ui.menu(list,"系统工具运行日志","精简操作、工具运行与开机检查",9,()->openTool("modlog"));
        ui.menu(list,"充电记录","充电曲线与会话详情",7,()->openTool("chargelog"));
        ui.menu(list,"无线调试诊断","配对、连接与调试记录",8,()->openTool("adbdiag"));
        note(body,"工具日志需要 Root 授权。充电记录需在充电控制中主动开启，关闭时不采集。");
    }
    private void updateStatus() {if(status!=null) status.setText(app.status());}
    private void diagnostics() {
        updateStatus();
        new AlertDialog.Builder(this).setTitle("运行状态").setMessage(app.status()
                +"\nAndroid "+android.os.Build.VERSION.RELEASE+" · API "+android.os.Build.VERSION.SDK_INT)
                .setPositiveButton("关闭",null).show();
    }
    private void openTool(String route) {
        startActivity(new Intent(this,com.dalong.pengpaitool.StreamlineActivity.class).putExtra("route",route));
    }
    private void hideKeyboard() {
        View target=getCurrentFocus();
        if(target!=null) getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(target.getWindowToken(),0);
    }
    private LinearLayout vertical() {LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private void note(LinearLayout parent,String value) {
        TextView label=ui.text(value,12,MUTED);label.setPadding(0,dp(8),0,dp(12));parent.addView(label);
    }
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
}
