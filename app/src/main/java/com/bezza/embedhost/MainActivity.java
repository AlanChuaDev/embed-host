package com.bezza.embedhost;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.window.embedding.ActivityEmbeddingController;
import androidx.window.embedding.EmbeddingRule;
import androidx.window.embedding.RuleController;
import androidx.window.embedding.SplitAttributes;
import androidx.window.embedding.SplitController;
import androidx.window.embedding.SplitPairFilter;
import androidx.window.embedding.SplitPairRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 最小测试宿主：点列表里的 app，把它放进左边的分栏，本 app 占右边。
 * 分栏规则全部在 applyRule() 里，之后整合进 Bezza Dash 时抄这一段即可。
 */
public class MainActivity extends Activity {
    private static final String TAG = "EmbedHost";

    /** 左边（被嵌入的 app）占屏幕宽度的比例，0.4 = 40%。 */
    private float leftShare = 0.4f;

    private TextView status;
    private final List<ResolveInfo> apps = new ArrayList<>();
    private String lastPkg = null;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(Color.parseColor("#101418"));

        TextView title = new TextView(this);
        title.setText("Embed Host（右侧就是宿主）");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9fb3c8"));
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final int pct : new int[]{30, 40, 50}) {
            Button btn = new Button(this);
            btn.setText("左 " + pct + "%");
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    leftShare = pct / 100f;
                    if (lastPkg != null) applyRule(lastPkg);
                    toast("已设为左 " + pct + "%。要关掉 YT Music 再点一次列表才生效");
                    refreshStatus();
                }
            });
            row.addView(btn);
        }
        root.addView(row);

        TextView hint = new TextView(this);
        hint.setText("点下面的 app 嵌入到左边（先点比例，再点 app）：");
        hint.setTextColor(Color.parseColor("#9fb3c8"));
        hint.setPadding(0, dp(8), 0, dp(4));
        root.addView(hint);

        ListView list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // 列出所有能启动的 app，名字或包名里带 music 的排最前面
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(q, 0);
        Collections.sort(all, (x, y) -> {
            int sx = score(pm, x), sy = score(pm, y);
            if (sx != sy) return sy - sx;
            return String.valueOf(x.loadLabel(pm)).compareToIgnoreCase(String.valueOf(y.loadLabel(pm)));
        });
        apps.addAll(all);
        List<String> names = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            names.add(ri.loadLabel(pm) + "\n" + ri.activityInfo.packageName + " / " + ri.activityInfo.name);
        }
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names));
        list.setOnItemClickListener((parent, view, pos, id) -> launchEmbedded(apps.get(pos)));

        refreshStatus();
    }

    private int score(PackageManager pm, ResolveInfo ri) {
        String s = (ri.loadLabel(pm) + " " + ri.activityInfo.packageName).toLowerCase();
        return s.contains("music") ? 1 : 0;
    }

    private void launchEmbedded(ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        String cls = ri.activityInfo.name;
        lastPkg = pkg;
        applyRule(pkg);
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(pkg, cls));
            // 关键：不加 FLAG_ACTIVITY_NEW_TASK，让它进入本 app 的 task，分栏才可能生效
            startActivity(i);
            Log.i(TAG, "launched " + pkg + "/" + cls);
        } catch (Exception e) {
            Log.e(TAG, "launch failed", e);
            toast("启动失败：" + e);
        }
    }

    /**
     * 分栏规则：本 Activity 为 primary，被嵌入的 app 为 secondary。
     * 想要 secondary 在左边 40%：方向 RIGHT_TO_LEFT（primary 在右），ratio 填 primary 的占比 = 1 - 0.4。
     */
    private void applyRule(String otherPkg) {
        Set<SplitPairFilter> filters = new HashSet<>();
        filters.add(new SplitPairFilter(
                new ComponentName(getPackageName(), MainActivity.class.getName()),
                new ComponentName(otherPkg, "*"),   // 该 app 的任意 Activity
                null));

        SplitAttributes attrs = new SplitAttributes.Builder()
                .setSplitType(SplitAttributes.SplitType.ratio(1f - leftShare))
                .setLayoutDirection(SplitAttributes.LayoutDirection.RIGHT_TO_LEFT)
                .build();

        SplitPairRule rule = new SplitPairRule.Builder(filters)
                .setDefaultSplitAttributes(attrs)
                .build();

        Set<EmbeddingRule> rules = new HashSet<>();
        rules.add(rule);
        RuleController.getInstance(this).setRules(rules);
    }

    private void refreshStatus() {
        String support;
        boolean embedded;
        try {
            support = String.valueOf(SplitController.getInstance(this).getSplitSupportStatus());
            embedded = ActivityEmbeddingController.getInstance(this).isActivityEmbedded(this);
        } catch (Throwable t) {
            support = "error: " + t;
            embedded = false;
        }
        status.setText("分栏支持: " + support
                + "\n本 Activity 处于嵌入容器: " + embedded
                + "\n窗口宽度: " + getResources().getConfiguration().screenWidthDp + "dp"
                + "\n左边占比: " + Math.round(leftShare * 100) + "%");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        refreshStatus();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
