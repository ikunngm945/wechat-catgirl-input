package org.dsh.qqcatime;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 在微信输入法「关于」页注入的配置面板。
 *
 * 显示词库条数 + 白名单列表，并可从「输入法可见的应用」中勾选加入白名单。
 * 因为界面跑在输入法进程内，写白名单文件不需要 root。
 */
public final class ConfigUI {

    /** 面板 id，用于防止重复注入。 */
    public static final int PANEL_ID = 0x7f0f0001;

    private ConfigUI() {
    }

    /** 注入面板；延迟到布局挂载后执行，找不到容器时静默跳过。 */
    public static void inject(final Activity activity) {
        try {
            if (activity.findViewById(PANEL_ID) != null) {
                return;
            }
            // onCreate 时 content 尚未挂载子视图，必须 post 到消息队列末尾
            final View decor = activity.getWindow() == null
                    ? null : activity.getWindow().getDecorView();
            if (decor == null) {
                Cat.log("UI: 无 decorView，跳过");
                return;
            }
            decor.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (activity.isFinishing() || activity.findViewById(PANEL_ID) != null) {
                            return;
                        }
                        ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
                        if (content == null) {
                            Cat.log("UI: content 为 null");
                            return;
                        }
                        View panel = buildPanel(activity);
                        content.addView(panel, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                        Cat.log("UI: 面板已插入，content children=" + content.getChildCount());
                    } catch (Throwable t) {
                        Cat.log("UI: 注入失败 " + t.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable t) {
            Cat.log("UI: 异常 " + t.getClass().getSimpleName());
        }
    }

    private static View buildPanel(final Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(a);
        root.setId(PANEL_ID);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#F5F5F5"));

        // 标题
        TextView title = new TextView(a);
        title.setText("微信输入法猫娘模块");
        title.setTextSize(16);
        title.setTextColor(Color.parseColor("#111111"));
        title.setPadding(0, 0, 0, (int) (8 * d));
        root.addView(title);

        // ---- 转换设置（开关 / 后缀 / 替换规则）----
        TextView setTitle = new TextView(a);
        setTitle.setText("\n转换设置");
        setTitle.setTextSize(14);
        setTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(setTitle);

        final LinearLayout setBox = new LinearLayout(a);
        setBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(setBox);

        final Runnable refreshSettings = new Runnable() {
            @Override
            public void run() {
                setBox.removeAllViews();
                Config.load();

                // 三个总开关 + 所有应用
                setBox.addView(buildSwitchRow(a, "文字替换（我→本喵 等）",
                        Config.replaceEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Config.saveSettings(v, Config.suffix(),
                                        Config.suffixEnabled(),
                                        Config.kaomojiEnabled(),
                                        Config.allAppsEnabled());
                            }
                        }));

                // 后缀：文本框 + 开关
                LinearLayout sufRow = new LinearLayout(a);
                sufRow.setOrientation(LinearLayout.HORIZONTAL);
                sufRow.setGravity(Gravity.CENTER_VERTICAL);
                sufRow.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
                TextView sufLab = new TextView(a);
                sufLab.setText("句末后缀：");
                sufLab.setTextSize(13);
                sufRow.addView(sufLab);
                final EditText sufInput = new EditText(a);
                sufInput.setTextSize(13);
                sufInput.setText(Config.suffix());
                sufInput.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                sufRow.addView(sufInput);
                Button sufSave = new Button(a);
                sufSave.setText("保存后缀");
                sufSave.setTextSize(11);
                sufSave.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Config.saveSettings(Config.replaceEnabled(),
                                sufInput.getText().toString(),
                                Config.suffixEnabled(),
                                Config.kaomojiEnabled(),
                                Config.allAppsEnabled());
                        toast(a, "后缀已保存：" + Config.suffix());
                    }
                });
                sufRow.addView(sufSave);
                setBox.addView(sufRow);

                setBox.addView(buildSwitchRow(a, "启用句末后缀",
                        Config.suffixEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Config.saveSettings(Config.replaceEnabled(),
                                        Config.suffix(), v,
                                        Config.kaomojiEnabled(),
                                        Config.allAppsEnabled());
                            }
                        }));

                setBox.addView(buildSwitchRow(a, "启用颜文字",
                        Config.kaomojiEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Config.saveSettings(Config.replaceEnabled(),
                                        Config.suffix(), Config.suffixEnabled(),
                                        v, Config.allAppsEnabled());
                            }
                        }));

                setBox.addView(buildSwitchRow(a, "对所有应用生效（关闭则按白名单）",
                        Config.allAppsEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Config.saveSettings(Config.replaceEnabled(),
                                        Config.suffix(), Config.suffixEnabled(),
                                        Config.kaomojiEnabled(), v);
                            }
                        }));

                // 替换规则编辑
                TextView rLab = new TextView(a);
                rLab.setTextSize(13);
                rLab.setTextColor(Color.parseColor("#111111"));
                rLab.setText("\n文字替换规则（每行一条：原词=替换词）\n"
                        + "行首加 ! 可单独关闭该条，例如 !你=主人");
                setBox.addView(rLab);

                final EditText rInput = new EditText(a);
                rInput.setTextSize(12);
                rInput.setGravity(Gravity.TOP | Gravity.START);
                StringBuilder rb = new StringBuilder();
                for (Config.Rule r : Config.rules()) {
                    rb.append(r.line()).append('\n');
                }
                rInput.setText(rb.toString());
                rInput.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (110 * d)));
                setBox.addView(rInput);

                Button rSave = new Button(a);
                rSave.setText("保存替换规则");
                rSave.setTextSize(11);
                rSave.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Config.saveRules(rInput.getText().toString());
                        toast(a, "已保存 " + Config.enabledRuleCount() + " 条启用规则");
                    }
                });
                setBox.addView(rSave);

                TextView rHint = new TextView(a);
                rHint.setTextSize(10);
                rHint.setTextColor(Color.parseColor("#888888"));
                rHint.setText("当前设置：替换 " + (Config.replaceEnabled() ? "开" : "关")
                        + " / 后缀 " + (Config.suffixEnabled()
                                ? "[" + Config.suffix() + "]" : "关")
                        + " / 颜文字 " + (Config.kaomojiEnabled() ? "开" : "关")
                        + " / 全部应用 " + (Config.allAppsEnabled() ? "开" : "关"));
                setBox.addView(rHint);
            }
        };
        refreshSettings.run();

        // ---- 使用说明 ----
        TextView helpTitle = new TextView(a);
        helpTitle.setText("\n使用说明");
        helpTitle.setTextSize(14);
        helpTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(helpTitle);

        TextView help = new TextView(a);
        help.setTextSize(12);
        help.setTextColor(Color.parseColor("#444444"));
        help.setText(
            "本模块让微信 / QQ 聊天时自动喵化：\n"
          + "  我 → 本喵，你 → 主人，句末加「喵」，末尾随机颜文字。\n"
          + "\n"
          + "【三种触发方式，满足任一即可】\n"
          + "  1. 语音转文字：说完自动发送前改写，直接触发。\n"
          + "  2. 打标点：输入到 。！？ 等标点后停顿约 2 秒，自动改写。\n"
          + "  3. 输入法发送键：点微信输入法的「发送」触发。\n"
          + "     微信 / QQ 需先开启：设置 → 辅助输入 → 回车键发送。\n"
          + "\n"
          + "【使用前检查】\n"
          + "  · 先在下面「白名单」里勾选要生效的应用（如微信）。\n"
          + "  · 改完词库或规则立即生效，无需重启输入法。\n"
          + "  · 不生效时，点最下方「复制全部日志」发给开发者排查。"
        );
        help.setTextIsSelectable(true);
        root.addView(help);

        // ---- 向量模型 ----
        root.addView(buildVectorPanel(a, d));

        // ---- LLM 改写 ----
        root.addView(buildLlmPanel(a, d));

        // 词库信息
        Cat.loadKaomoji();
        Cat.loadWhitelist();
        TagLib.load();
        TextView kao = new TextView(a);
        kao.setText("颜文字词库：" + Cat.kaomojiCount() + " 条\n"
                + "标签分类：" + TagLib.tagCount() + " 个\n"
                + "关键词规则：" + TagLib.ruleCount() + " 条\n"
                + "词库文件：" + Cat.KAO_FILE + "\n"
                + "标签目录：" + TagLib.TAGS_DIR + "\n"
                + "规则文件：" + TagLib.RULES_FILE);
        kao.setTextSize(12);
        kao.setTextColor(Color.parseColor("#666666"));
        root.addView(kao);

        // 白名单标题
        TextView wlTitle = new TextView(a);
        wlTitle.setText("\n白名单（勾选后可自动喵化）");
        wlTitle.setTextSize(14);
        wlTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(wlTitle);

        // 当前白名单
        final TextView wlView = new TextView(a);
        wlView.setTextSize(12);
        wlView.setTextColor(Color.parseColor("#0A7D32"));
        wlView.setText(formatWhitelist());
        root.addView(wlView);

        // 可见应用列表（可勾选）
        List<AppEntry> apps = visibleApps(a);
        TextView appsTitle = new TextView(a);
        appsTitle.setText("\n可添加的应用（输入法可见 " + apps.size() + " 个）");
        appsTitle.setTextSize(14);
        appsTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(appsTitle);

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (AppEntry e : apps) {
            list.addView(buildRow(a, e, wlView));
        }
        root.addView(list);

        // ---- 标签与词库编辑 ----
        TextView tagTitle = new TextView(a);
        tagTitle.setText("\n标签与词库（点标签名编辑颜文字和关键词）");
        tagTitle.setTextSize(14);
        tagTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(tagTitle);

        final LinearLayout tagList = new LinearLayout(a);
        tagList.setOrientation(LinearLayout.VERTICAL);
        root.addView(tagList);

        final LinearLayout masterInfo = new LinearLayout(a);
        masterInfo.setOrientation(LinearLayout.VERTICAL);
        root.addView(masterInfo);

        // 刷新标签列表（编辑后调用）
        final Runnable refreshTags = new Runnable() {
            @Override
            public void run() {
                tagList.removeAllViews();
                masterInfo.removeAllViews();
                for (final String tg : TagLib.tagNames()) {
                    tagList.addView(buildTagRow(a, tg, wlView, null));
                }
                TextView mi = new TextView(a);
                mi.setTextSize(11);
                mi.setTextColor(Color.parseColor("#666666"));
                mi.setText("总库 katxt：" + Cat.kaomojiCount()
                        + " 条（由所有标签自动汇总去重）");
                masterInfo.addView(mi);
            }
        };
        // 用可复用的 holder 让行内能触发刷新
        for (final String tg : TagLib.tagNames()) {
            tagList.addView(buildTagRow(a, tg, wlView, refreshTags));
        }

        // 新建标签
        LinearLayout newTagRow = new LinearLayout(a);
        newTagRow.setOrientation(LinearLayout.HORIZONTAL);
        newTagRow.setPadding(0, (int) (8 * d), 0, 0);
        final EditText newTagInput = new EditText(a);
        newTagInput.setHint("新标签名，如 得意");
        newTagInput.setTextSize(12);
        newTagInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        newTagRow.addView(newTagInput);
        Button newTagBtn = new Button(a);
        newTagBtn.setText("新建");
        newTagBtn.setTextSize(12);
        newTagBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = newTagInput.getText().toString().trim();
                if (name.length() == 0) {
                    toast(a, "请先输入标签名");
                    return;
                }
                if (TagLib.createTag(name)) {
                    newTagInput.setText("");
                    refreshTags.run();
                    toast(a, "已新建标签：" + name);
                } else {
                    toast(a, "标签已存在或名称无效");
                }
            }
        });
        newTagRow.addView(newTagBtn);
        root.addView(newTagRow);

        // ---- 恢复默认设置 ----
        Button restoreBtn = new Button(a);
        restoreBtn.setText("恢复默认设置");
        restoreBtn.setTextSize(12);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) (10 * d);
        restoreBtn.setLayoutParams(rlp);
        restoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(a)
                        .setTitle("恢复默认设置")
                        .setMessage("将把内置的词库、标签分类、关键词规则全部还原，"
                                + "你自行添加或修改的内容会被覆盖。\n\n"
                                + "白名单不受影响（仍保留你的勾选）。\n\n确定继续吗？")
                        .setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dlg, int w) {
                                if (Cat.restoreDefaults()) {
                                    toast(a, "已恢复默认：词库 " + Cat.kaomojiCount()
                                            + " 条，标签 " + TagLib.tagCount()
                                            + " 个，规则 " + TagLib.ruleCount() + " 条");
                                    // 标签列表需要按新配置重建
                                    tagList.removeAllViews();
                                    for (String tg2 : TagLib.tagNames()) {
                                        tagList.addView(buildTagRow(a, tg2, wlView, null));
                                    }
                                    masterInfo.removeAllViews();
                                    TextView mi2 = new TextView(a);
                                    mi2.setTextSize(11);
                                    mi2.setTextColor(Color.parseColor("#666666"));
                                    mi2.setText("总库 katxt：" + Cat.kaomojiCount()
                                            + " 条（由所有标签自动汇总去重）");
                                    masterInfo.addView(mi2);
                                    wlView.setText(formatWhitelist());
                                } else {
                                    toast(a, "恢复失败，请查看日志");
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        root.addView(restoreBtn);

        TextView restoreHint = new TextView(a);
        restoreHint.setTextSize(10);
        restoreHint.setTextColor(Color.parseColor("#888888"));
        restoreHint.setText("误删配置后可用。内置默认来自打包时的 config/ 目录。");
        root.addView(restoreHint);

        TextView mi = new TextView(a);
        mi.setTextSize(11);
        mi.setTextColor(Color.parseColor("#666666"));
        mi.setText("总库 katxt：" + Cat.kaomojiCount()
                + " 条（由所有标签自动汇总去重）");
        masterInfo.addView(mi);

        // ---- 自动发现的应用（输入法遇到过、且不在可见列表里）----
        Cat.loadSeen();
        java.util.Set<String> visible = new java.util.HashSet<String>();
        for (AppEntry e : apps) {
            visible.add(e.pkg);
        }
        List<String> seen = new ArrayList<String>();
        for (String p : Cat.seenList()) {
            if (!visible.contains(p)) {
                seen.add(p);
            }
        }
        Cat.log("UI: 发现列表 " + seen.size() + " 个 " + seen
                + "（原始 " + Cat.seenCount() + "，可见 " + visible.size() + "）");
        if (!seen.isEmpty()) {
            TextView seenTitle = new TextView(a);
            seenTitle.setText("\n自动发现的应用（" + seen.size()
                    + " 个，来自实际输入记录）");
            seenTitle.setTextSize(14);
            seenTitle.setTextColor(Color.parseColor("#111111"));
            root.addView(seenTitle);

            TextView seenHint = new TextView(a);
            seenHint.setText("这些应用没有可枚举的入口，但输入法在它们里面出现过；"
                    + "勾选即加入白名单。");
            seenHint.setTextSize(11);
            seenHint.setTextColor(Color.parseColor("#888888"));
            root.addView(seenHint);

            LinearLayout seenList = new LinearLayout(a);
            seenList.setOrientation(LinearLayout.VERTICAL);
            for (String pkg : seen) {
                seenList.addView(buildSeenRow(a, pkg, wlView));
            }
            root.addView(seenList);

            TextView clear = new TextView(a);
            clear.setText("清空发现记录");
            clear.setTextSize(12);
            clear.setTextColor(Color.parseColor("#C0392B"));
            clear.setPadding(0, (int) (8 * d), 0, 0);
            clear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Cat.clearSeen();
                    wlView.setText(formatWhitelist());
                    try {
                        android.widget.Toast.makeText(a, "已清空，重新打开本页生效",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        // 忽略
                    }
                }
            });
            root.addView(clear);
        }

        // ---- 日志 ----
        TextView logTitle = new TextView(a);
        logTitle.setText("\n日志（可长按复制后发给开发者）");
        logTitle.setTextSize(14);
        logTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(logTitle);

        final TextView logInfo = new TextView(a);
        logInfo.setTextSize(11);
        logInfo.setTextColor(Color.parseColor("#666666"));
        String shown = Cat.readLogForDisplay();
        logInfo.setText(shown);
        logInfo.setTextIsSelectable(true);   // 允许长按选中复制

        // 日志正文放进一个限高可滚动的框里，避免撑爆面板
        android.widget.ScrollView logBox = new android.widget.ScrollView(a);
        int boxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.22f);
        logBox.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, boxH));
        logBox.setBackgroundColor(Color.parseColor("#FFFFFF"));
        int p8 = (int) (8 * d);
        logBox.setPadding(p8, p8, p8, p8);
        logBox.addView(logInfo);
        root.addView(logBox);

        LinearLayout logBtns = new LinearLayout(a);
        logBtns.setOrientation(LinearLayout.HORIZONTAL);
        logBtns.setPadding(0, (int) (8 * d), 0, 0);

        Button copyBtn = new Button(a);
        copyBtn.setText("复制全部日志");
        copyBtn.setTextSize(12);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    String full = Cat.buildLogReport("用户复制");
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "QQ猫娘日志", full));
                        toast(a, "日志已复制，去聊天框长按粘贴即可发送");
                    }
                } catch (Throwable t) {
                    toast(a, "复制失败：" + t.getClass().getSimpleName());
                }
                logInfo.setText(Cat.readLogForDisplay());
            }
        });
        logBtns.addView(copyBtn);

        Button clearBtn = new Button(a);
        clearBtn.setText("清空");
        clearBtn.setTextSize(12);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Cat.clearLog();
                logInfo.setText(Cat.readLogForDisplay());
                toast(a, "日志已清空");
            }
        });
        logBtns.addView(clearBtn);
        root.addView(logBtns);

        TextView logHint = new TextView(a);
        logHint.setText("点「复制全部日志」，粘贴到任意聊天框发送给开发者即可。"
                + "日志只记录运行状态，不含聊天内容。");
        logHint.setTextSize(10);
        logHint.setTextColor(Color.parseColor("#888888"));
        root.addView(logHint);

        // 提示
        TextView tip = new TextView(a);
        tip.setText("\n改动立即生效，无需重启输入法。");
        tip.setTextSize(11);
        tip.setTextColor(Color.parseColor("#888888"));
        root.addView(tip);

        ScrollView sv = new ScrollView(a);
        sv.addView(root);
        // 限高，避免把输入法布局撑坏；内容可在面板内滚动
        int h = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55f);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return sv;
    }

    /** 已发现应用的勾选行（可能拿不到图标，用包名兜底）。 */
    private static View buildSeenRow(final Activity a, final String pkg,
                                     final TextView wlView) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));

        String label = pkg;
        Drawable icon = null;
        try {
            android.content.pm.PackageManager pm = a.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = String.valueOf(pm.getApplicationLabel(ai));
            icon = pm.getApplicationIcon(ai);
        } catch (Throwable t) {
            // 拿不到就只显示包名
        }
        if (icon != null) {
            ImageView iv = new ImageView(a);
            int sz = (int) (32 * d);
            iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            iv.setImageDrawable(icon);
            row.addView(iv);
        }

        final CheckBox cb = new CheckBox(a);
        cb.setText(label + "\n" + pkg);
        cb.setTextSize(12);
        cb.setChecked(Cat.allowed(pkg));
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Cat.setWhitelisted(pkg, checked);
                Cat.log("UI: 发现项 " + (checked ? "添加 " : "移除 ") + pkg);
                wlView.setText(formatWhitelist());
            }
        });
        row.addView(cb);
        return row;
    }

    private static View buildRow(final Activity a, final AppEntry e, final TextView wlView) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));

        if (e.icon != null) {
            ImageView iv = new ImageView(a);
            int sz = (int) (32 * d);
            iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            iv.setImageDrawable(e.icon);
            row.addView(iv);
        }

        final CheckBox cb = new CheckBox(a);
        cb.setText(e.label + "\n" + e.pkg);
        cb.setTextSize(12);
        cb.setChecked(Cat.allowed(e.pkg));
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                boolean ok = Cat.setWhitelisted(e.pkg, checked);
                Cat.log("UI: " + (checked ? "添加" : "移除") + " " + e.pkg + " ok=" + ok);
                wlView.setText(formatWhitelist());
            }
        });
        row.addView(cb);
        return row;
    }

    /** 一行标签：显示名称、条数，点击进入编辑，长按删除。 */
    private static View buildTagRow(final Activity a, final String tag,
                                    final TextView wlView, final Runnable onChanged) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (8 * d), 0, (int) (8 * d));

        TextView tv = new TextView(a);
        int kaoN = TagLib.readTagKaomoji(tag).size();
        int kwN = TagLib.readTagKeywords(tag).size();
        tv.setText("● " + tag + "    颜文字 " + kaoN + " 条 / 关键词 " + kwN + " 条");
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#0A7D32"));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTagEditor(a, tag, onChanged);
            }
        });
        row.addView(tv);

        Button del = new Button(a);
        del.setText("删除");
        del.setTextSize(11);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(a)
                        .setTitle("删除标签")
                        .setMessage("确定删除标签「" + tag + "」？\n"
                                + "它的颜文字与关键词规则都会一并移除。")
                        .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dlg, int w) {
                                if (TagLib.deleteTag(tag)) {
                                    toast(a, "已删除：" + tag);
                                    if (onChanged != null) {
                                        onChanged.run();
                                    }
                                } else {
                                    toast(a, "删除失败");
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        row.addView(del);
        return row;
    }

    /**
     * 标签编辑对话框：上下两个文本框。
     * 上：该标签的颜文字（每行一条）
     * 下：该标签的关键词（每行一个）
     * 保存后自动重建总库 katxt（去重）。
     */
    private static void showTagEditor(final Activity a, final String tag,
                                      final Runnable onChanged) {
        try {
            float d = a.getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            int pd = (int) (16 * d);
            box.setPadding(pd, pd, pd, pd);

            TextView l1 = new TextView(a);
            l1.setText("颜文字（每行一条）");
            l1.setTextSize(13);
            box.addView(l1);

            final EditText kao = new EditText(a);
            kao.setTextSize(12);
            kao.setGravity(Gravity.TOP | Gravity.START);
            kao.setMinLines(4);
            StringBuilder kb = new StringBuilder();
            for (String k : TagLib.readTagKaomoji(tag)) {
                kb.append(k).append('\n');
            }
            kao.setText(kb.toString());
            kao.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (150 * d)));
            box.addView(kao);

            TextView l2 = new TextView(a);
            l2.setText("\n关键词（每行一个，句中出现即命中本标签）");
            l2.setTextSize(13);
            box.addView(l2);

            final EditText kw = new EditText(a);
            kw.setTextSize(12);
            kw.setGravity(Gravity.TOP | Gravity.START);
            StringBuilder wb = new StringBuilder();
            for (String k : TagLib.readTagKeywords(tag)) {
                wb.append(k).append('\n');
            }
            kw.setText(wb.toString());
            kw.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (110 * d)));
            box.addView(kw);

            TextView hint = new TextView(a);
            hint.setTextSize(10);
            hint.setTextColor(Color.parseColor("#888888"));
            hint.setText("保存后立即生效，总库 katxt 会自动按所有标签汇总去重。");
            box.addView(hint);

            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(box);

            new android.app.AlertDialog.Builder(a)
                    .setTitle("编辑标签：" + tag)
                    .setView(sv)
                    .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dlg, int w) {
                            boolean ok1 = TagLib.saveTagKaomoji(tag, kao.getText().toString());
                            boolean ok2 = TagLib.saveTagKeywords(tag, kw.getText().toString());
                            // 汇总去重，重建总库
                            int total = TagLib.rebuildMasterLibrary();
                            toast(a, (ok1 && ok2 ? "已保存" : "部分保存失败")
                                    + "，总库 " + total + " 条");
                            if (onChanged != null) {
                                onChanged.run();
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Cat.log("标签编辑失败 " + t.getClass().getSimpleName());
            toast(a, "打开编辑器失败：" + t.getClass().getSimpleName());
        }
    }

    /** 开关变更回调。 */
    private interface SwitchHandler {
        void onSet(boolean v);
    }

    /** 构造一行「标题 + 开关」。 */
    private static View buildSwitchRow(Activity a, String label,
                                       boolean checked,
                                       final SwitchHandler h) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        android.widget.Switch sw = new android.widget.Switch(a);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                        h.onSet(v);
                    }
                });
        row.addView(sw);
        return row;
    }

    /**
     * LLM 改写设置区块。
     *
     * <p>开启后由大模型完成人称替换与句末后缀：「文字替换」「句末后缀」
     * 两个开关不再机械套用，而是展开进提示词里交给模型。
     * 改写完的正文再逐句匹配颜文字（开了向量就走向量，没开就直接按规则）。
     */
    static View buildLlmPanel(final Activity a, final float d) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(a);
        title.setText("\nLLM 改写（提示词 + 用户请求）");
        title.setTextSize(14);
        title.setTextColor(Color.parseColor("#111111"));
        box.addView(title);

        final LinearLayout inner = new LinearLayout(a);
        inner.setOrientation(LinearLayout.VERTICAL);
        box.addView(inner);

        final Runnable[] holder = new Runnable[1];
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                inner.removeAllViews();
                Llm.load();

                inner.addView(buildSwitchRow(a, "启用 LLM 改写", Llm.enabled(),
                        new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Llm.save(v, Llm.preEnabled(), Llm.url(), Llm.KEEP_KEY,
                                        Llm.model(), Llm.temp());
                                run();
                            }
                        }));

                inner.addView(buildSwitchRow(a, "事前改写（打字时就用）",
                        Llm.preEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Llm.save(Llm.enabled(), v, Llm.url(), Llm.KEEP_KEY,
                                        Llm.model(), Llm.temp());
                                run();
                            }
                        }));

                inner.addView(buildSwitchRow(a, "结果缓存（llm_cache.txt）",
                        Llm.cacheEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Llm.save(Llm.enabled(), Llm.preEnabled(), v,
                                        Llm.url(), Llm.KEEP_KEY, Llm.model(),
                                        Llm.temp());
                                run();
                            }
                        }));

                TextView tip = new TextView(a);
                tip.setTextSize(11);
                tip.setTextColor(Color.parseColor("#888888"));
                tip.setText(Llm.enabled()
                        ? "已开启：上面的「文字替换」「句末后缀」转为提示词交给模型，"
                          + "本地不再机械套用。改写完的正文再逐句匹配颜文字。"
                        : "开启后由模型改写整段话；未开启时用本地规则（当前行为不变）。");
                inner.addView(tip);

                inner.addView(field(a, d, "接口地址", Llm.url(),
                        "https://api.siliconflow.cn/v1", new Saver() {
                            @Override
                            public void save(String v) {
                                Llm.save(Llm.enabled(), Llm.preEnabled(), v,
                                        Llm.KEEP_KEY, Llm.model(), Llm.temp());
                            }
                        }));

                inner.addView(field(a, d, "API Key", "",
                        Llm.hasKey() ? "已配置（留空保持不变）"
                                     : "sk-... 填入后不再回显", new Saver() {
                            @Override
                            public void save(String v) {
                                Llm.save(Llm.enabled(), Llm.preEnabled(), Llm.url(),
                                        v.length() == 0 ? Llm.KEEP_KEY : v,
                                        Llm.model(), Llm.temp());
                            }
                        }));

                inner.addView(field(a, d, "模型名", Llm.model(),
                        "Qwen/Qwen2.5-7B-Instruct", new Saver() {
                            @Override
                            public void save(String v) {
                                Llm.save(Llm.enabled(), Llm.preEnabled(), Llm.url(),
                                        Llm.KEEP_KEY, v, Llm.temp());
                            }
                        }));

                inner.addView(field(a, d, "温度", String.valueOf(Llm.temp()),
                        "0.8（越小越稳定）", new Saver() {
                            @Override
                            public void save(String v) {
                                float tp = Llm.temp();
                                try {
                                    tp = Float.parseFloat(v.trim());
                                } catch (Throwable t) {
                                    // 保持原值
                                }
                                Llm.save(Llm.enabled(), Llm.preEnabled(), Llm.url(),
                                        Llm.KEEP_KEY, Llm.model(), tp);
                            }
                        }));

                inner.addView(field(a, d, "最大输出",
                        String.valueOf(Llm.maxTokens()), "512（token，1 汉字≈1）",
                        new Saver() {
                            @Override
                            public void save(String v) {
                                int mx = Llm.maxTokens();
                                try {
                                    mx = Integer.parseInt(v.trim());
                                } catch (Throwable t) {
                                    // 保持原值
                                }
                                Llm.save(Llm.enabled(), Llm.preEnabled(),
                                        Llm.cacheEnabled(), Llm.url(), Llm.KEEP_KEY,
                                        Llm.model(), Llm.temp(), mx,
                                        Llm.maxInput(), Llm.level());
                            }
                        }));

                inner.addView(field(a, d, "最大输入",
                        String.valueOf(Llm.maxInput()), "200（字符，0 = 不限制）",
                        new Saver() {
                            @Override
                            public void save(String v) {
                                int mi = Llm.maxInput();
                                try {
                                    mi = Integer.parseInt(v.trim());
                                } catch (Throwable t) {
                                    // 保持原值
                                }
                                Llm.save(Llm.enabled(), Llm.preEnabled(),
                                        Llm.cacheEnabled(), Llm.url(), Llm.KEEP_KEY,
                                        Llm.model(), Llm.temp(), Llm.maxTokens(),
                                        mi, Llm.level());
                            }
                        }));

                inner.addView(choiceRow(a, d, "思考等级", Llm.LEVEL_NAMES,
                        Llm.level(), new Chooser() {
                            @Override
                            public void onPick(int index) {
                                Llm.save(Llm.enabled(), Llm.preEnabled(),
                                        Llm.cacheEnabled(), Llm.url(), Llm.KEEP_KEY,
                                        Llm.model(), Llm.temp(), Llm.maxTokens(),
                                        Llm.maxInput(), index);
                            }
                        }));
                inner.addView(note(a, d, Llm.LEVEL_HINT));

                inner.addView(field(a, d, "等待",
                        String.valueOf(Llm.waitMs()), "2500（毫秒，200 ~ 15000）",
                        new Saver() {
                            @Override
                            public void save(String v) {
                                long wt = Llm.waitMs();
                                try {
                                    wt = Long.parseLong(v.trim());
                                } catch (Throwable t) {
                                    // 保持原值
                                }
                                Llm.save(Llm.enabled(), Llm.preEnabled(),
                                        Llm.cacheEnabled(), Llm.url(), Llm.KEEP_KEY,
                                        Llm.model(), Llm.temp(), Llm.maxTokens(),
                                        Llm.maxInput(), Llm.level(), wt);
                            }
                        }));
                inner.addView(note(a, d, "打字时最多等模型多久；超时先用本地规则。"
                        + "发送前兜底最多等 " + (Llm.SEND_CAP_MS / 1000) + " 秒。"));

                // 提示词编辑
                TextView pLab = new TextView(a);
                pLab.setTextSize(12);
                pLab.setTextColor(Color.parseColor("#111111"));
                pLab.setText("\n提示词（改完点「保存提示词」立刻生效）\n"
                        + "占位符：{替换规则} 会自动展开成文字替换清单，"
                        + "{句末后缀} 展开成后缀文字。");
                inner.addView(pLab);

                final EditText pInput = new EditText(a);
                pInput.setTextSize(11);
                pInput.setGravity(Gravity.TOP | Gravity.START);
                pInput.setMinLines(6);
                pInput.setMaxLines(14);
                pInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                pInput.setText(Llm.prompt());
                pInput.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                inner.addView(pInput);

                LinearLayout pBtn = new LinearLayout(a);
                pBtn.setOrientation(LinearLayout.HORIZONTAL);
                Button pSave = new Button(a);
                pSave.setText("保存提示词");
                pSave.setTextSize(11);
                pSave.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean ok = Llm.savePrompt(pInput.getText().toString());
                        toast(a, ok ? "提示词已保存" : "提示词不能为空");
                        run();
                    }
                });
                pBtn.addView(pSave);

                Button pReset = new Button(a);
                pReset.setText("恢复默认提示词");
                pReset.setTextSize(11);
                pReset.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new android.app.AlertDialog.Builder(a)
                                .setTitle("恢复默认提示词")
                                .setMessage("会覆盖当前编辑框里的内容，确定吗？")
                                .setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface di, int w) {
                                        Llm.savePrompt(Llm.DEFAULT_PROMPT);
                                        toast(a, "已恢复默认提示词");
                                        run();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
                pBtn.addView(pReset);
                inner.addView(pBtn);

                // 操作按钮
                LinearLayout btns = new LinearLayout(a);
                btns.setOrientation(LinearLayout.HORIZONTAL);
                btns.setPadding(0, (int) (4 * d), 0, (int) (4 * d));

                Button test = new Button(a);
                test.setText("测试连接");
                test.setTextSize(11);
                test.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toast(a, "正在测试…");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String r = Llm.test("我喜欢你。");
                                a.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        toast(a, r);
                                        if (holder[0] != null) {
                                            holder[0].run();
                                        }
                                    }
                                });
                            }
                        }, "qqcat-llm-test").start();
                    }
                });
                btns.addView(test);

                Button clrKey = new Button(a);
                clrKey.setText("清除 Key");
                clrKey.setTextSize(11);
                clrKey.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new android.app.AlertDialog.Builder(a)
                                .setTitle("清除 LLM API Key")
                                .setMessage("确定要删除已保存的 API Key 吗？"
                                        + "删除后需要重新填写才能使用 LLM 改写。")
                                .setPositiveButton("清除", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface di, int w) {
                                        Llm.clearKey();
                                        toast(a, "Key 已清除");
                                        run();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
                btns.addView(clrKey);

                Button clrCache = new Button(a);
                clrCache.setText("清空结果缓存");
                clrCache.setTextSize(11);
                clrCache.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new android.app.AlertDialog.Builder(a)
                                .setTitle("清空结果缓存")
                                .setMessage("确定要删除 llm_cache.txt 里记录的全部"
                                        + "「输入→输出」吗？删除后同样的句子"
                                        + "会重新请求模型。")
                                .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface di, int w) {
                                        Llm.clearCache();
                                        toast(a, "结果缓存已清空");
                                        run();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
                btns.addView(clrCache);
                inner.addView(btns);

                TextView stat = new TextView(a);
                stat.setTextSize(11);
                stat.setTextColor(Color.parseColor("#666666"));
                stat.setText("状态：" + Llm.lastStatus()
                        + "\n接口：" + Llm.endpoint()
                        + "\nKey：" + (Llm.hasKey() ? "已配置（不回显）" : "未配置")
                        + "\n结果缓存：" + (Llm.cacheEnabled() ? "开" : "关")
                        + "，已缓存 " + Llm.cacheCount() + " 条"
                        + "\n配置文件：" + Llm.CFG_FILE
                        + "\nKey 文件：" + Llm.KEY_FILE
                        + "\n提示词文件：" + Llm.PROMPT_FILE
                        + "\n缓存文件：" + Llm.CACHE_FILE);
                stat.setTextIsSelectable(true);
                inner.addView(stat);

                TextView hint = new TextView(a);
                hint.setTextSize(11);
                hint.setTextColor(Color.parseColor("#666666"));
                hint.setText(
                    "\n工作方式：\n"
                  + "  1. 用户打完一句带句末标点的话 → 先查结果缓存（llm_cache.txt）。\n"
                  + "     命中就直接用，不再请求模型；没命中才发给 LLM。\n"
                  + "  2. 提示词里带着「{替换规则}」与「{句末后缀}」展开后的要求，\n"
                  + "     模型据此完成人称替换与句末后缀，输出改写后的整段正文。\n"
                  + "  3. 改写后的正文再逐句匹配颜文字：\n"
                  + "     开了向量 → 走向量检索（记忆库 → 向量 → 关键词 → 随机）；\n"
                  + "     没开向量 → 直接按记忆库与关键词规则匹配。\n"
                  + "  · 「结果缓存」开关关掉后：既不读 llm_cache.txt，也不再往里写。\n"
                  + "  · 模型没回来 / 请求失败 → 自动回退本地规则改写，消息不会白发。\n"
                  + "  · 本地规则回退时「句末后缀」照常生效，不会重复加喵。\n"
                  + "  · 模型输出明显离谱（比原文长三倍以上）会被丢弃，走本地规则。\n"
                );
                inner.addView(hint);
            }
        };
        holder[0] = refresh;
        refresh.run();
        return box;
    }

    /**
     * 向量模型设置区块。
     *
     * 填 接口地址 / API Key / 模型名，维度可留 auto 自动识别；
     * 开启后：检测到句末标点时先拿句子去 memory.txt 里找完全相同记录，
     * 没有就请求向量模型找最相似的一条；都不中则回退关键词规则。
     */
    static View buildVectorPanel(final Activity a, final float d) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(a);
        title.setText("\n向量模型（句子匹配颜文字）");
        title.setTextSize(14);
        title.setTextColor(Color.parseColor("#111111"));
        box.addView(title);

        final LinearLayout inner = new LinearLayout(a);
        inner.setOrientation(LinearLayout.VERTICAL);
        box.addView(inner);

        // 匿名类里要回调自身刷新，用数组持有引用绕开“未初始化”限制
        final Runnable[] holder = new Runnable[1];
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                inner.removeAllViews();
                Vector.load();

                inner.addView(buildSwitchRow(a, "启用向量模型", Vector.enabled(),
                        new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Vector.save(v, Vector.preEnabled(), Vector.url(), Vector.KEEP_KEY,
                                        Vector.model(), Vector.dimText(),
                                        Vector.minScore());
                                run();
                            }
                        }));

                inner.addView(buildSwitchRow(a, "事前分析（打字时就用）",
                        Vector.preEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Vector.save(Vector.enabled(), v, Vector.url(),
                                        Vector.KEEP_KEY, Vector.model(),
                                        Vector.dimText(), Vector.minScore());
                                run();
                            }
                        }));

                inner.addView(buildSwitchRow(a, "记忆库（memory.txt）读写",
                        VecStore.enabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                VecStore.saveEnabled(v);
                                Vector.resetCache();
                                run();
                            }
                        }));

                inner.addView(field(a, d, "接口地址", Vector.url(),
                        "https://api.siliconflow.cn/v1", new Saver() {
                            @Override
                            public void save(String v) {
                                Vector.save(Vector.enabled(), Vector.preEnabled(), v, Vector.KEEP_KEY,
                                        Vector.model(), Vector.dimText(),
                                        Vector.minScore());
                            }
                        }));

                inner.addView(field(a, d, "API Key", "",
                        Vector.hasKey() ? "已配置（留空保持不变）"
                                        : "sk-... 填入后不再回显", new Saver() {
                            @Override
                            public void save(String v) {
                                // Key 只写不可读：留空 = 保持原值，
                                // 想清空用旁边的「清除 Key」。
                                Vector.save(Vector.enabled(), Vector.preEnabled(), Vector.url(),
                                        v.length() == 0 ? Vector.KEEP_KEY : v,
                                        Vector.model(), Vector.dimText(),
                                        Vector.minScore());
                            }
                        }));

                inner.addView(field(a, d, "模型名", Vector.model(),
                        "Qwen/Qwen3-Embedding-0.6B", new Saver() {
                            @Override
                            public void save(String v) {
                                Vector.save(Vector.enabled(), Vector.preEnabled(), Vector.url(),
                                        Vector.KEEP_KEY, v, Vector.dimText(),
                                        Vector.minScore());
                            }
                        }));

                inner.addView(field(a, d, "维度", "auto".equals(Vector.dimText())
                                ? "auto" : Vector.dimText(),
                        "auto = 自动识别", new Saver() {
                            @Override
                            public void save(String v) {
                                Vector.save(Vector.enabled(), Vector.preEnabled(), Vector.url(),
                                        Vector.KEEP_KEY, Vector.model(), v,
                                        Vector.minScore());
                            }
                        }));

                inner.addView(field(a, d, "相似度阈值", String.valueOf(Vector.minScore()),
                        "0.42", new Saver() {
                            @Override
                            public void save(String v) {
                                float ms = Vector.minScore();
                                try {
                                    ms = Float.parseFloat(v.trim());
                                } catch (Throwable t) {
                                    // 保持原值
                                }
                                Vector.save(Vector.enabled(), Vector.preEnabled(), Vector.url(),
                                        Vector.KEEP_KEY, Vector.model(),
                                        Vector.dimText(), ms);
                            }
                        }));

                inner.addView(field(a, d, "领先间距", String.valueOf(Vector.minMargin()),
                        "0.015", new Saver() {
                            @Override
                            public void save(String v) {
                                float mg = Vector.minMargin();
                                try {
                                    mg = Float.parseFloat(v.trim());
                                } catch (Throwable t) {
                                    // 保持原值
                                }
                                Vector.save(Vector.enabled(), Vector.preEnabled(), Vector.url(),
                                        Vector.KEEP_KEY, Vector.model(),
                                        Vector.dimText(), Vector.minScore(), mg);
                            }
                        }));

                // 操作按钮
                LinearLayout btns = new LinearLayout(a);
                btns.setOrientation(LinearLayout.HORIZONTAL);
                btns.setPadding(0, (int) (4 * d), 0, (int) (4 * d));

                Button test = new Button(a);
                test.setText("测试连接");
                test.setTextSize(11);
                test.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 联网必须在后台线程，主线程会抛 NetworkOnMainThreadException
                        toast(a, "正在测试连接…");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String r = Vector.test("你好");
                                a.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        toast(a, r);
                                        if (holder[0] != null) {
                                            holder[0].run();
                                        }
                                    }
                                });
                            }
                        }, "qqcat-vec-test").start();
                    }
                });
                btns.addView(test);

                Button clrKey = new Button(a);
                clrKey.setText("清除 Key");
                clrKey.setTextSize(11);
                clrKey.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new android.app.AlertDialog.Builder(a)
                                .setTitle("清除 API Key")
                                .setMessage("确定要删除已保存的 API Key 吗？"
                                        + "删除后需要重新填写才能使用向量模型。")
                                .setPositiveButton("清除", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface di, int w) {
                                        Vector.clearKey();
                                        toast(a, "Key 已清除");
                                        run();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
                btns.addView(clrKey);

                Button build = new Button(a);
                build.setText("重建向量");
                build.setTextSize(11);
                build.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final String why = Vector.indexStale();
                        String msg = "要用向量模型给词库里的每一条颜文字算一次语义向量，"
                                + "大约需要 1~2 秒并消耗一次接口额度。\n\n"
                                + "当前词库里"
                                + KaoIndex.indexable(Cat.kaomojiList())
                                + " 条颜文字带语义描述，会进入索引。";
                        if (why != null) {
                            msg = "检测到：" + why + "，建议重建。\n\n" + msg;
                        }
                        new android.app.AlertDialog.Builder(a)
                                .setTitle("重建颜文字向量索引")
                                .setMessage(msg)
                                .setPositiveButton("开始重建", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface di, int w) {
                                        String r = Vector.rebuildIndex(true);
                                        toast(a, r);
                                        run();
                                        // 后台在跑，每 1.2 秒刷新一次状态直到结束
                                        final android.os.Handler h = new android.os.Handler(
                                                android.os.Looper.getMainLooper());
                                        h.postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (holder[0] != null) {
                                                    holder[0].run();
                                                }
                                                if (Vector.rebuilding()) {
                                                    h.postDelayed(this, 1200);
                                                } else {
                                                    toast(a, Vector.rebuildStatus());
                                                }
                                            }
                                        }, 1200);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
                btns.addView(build);

                Button clear = new Button(a);
                clear.setText("清空记忆库");
                clear.setTextSize(11);
                clear.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new android.app.AlertDialog.Builder(a)
                                .setTitle("清空记忆库")
                                .setMessage("确定要删除 memory.txt 里记录的全部"
                                        + "「句子=颜文字」吗？删除后需要重新积累。")
                                .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface di, int w) {
                                        VecStore.clear();
                                        Vector.resetCache();
                                        toast(a, "记忆库已清空");
                                        run();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
                btns.addView(clear);
                inner.addView(btns);

                TextView stat = new TextView(a);
                stat.setTextSize(11);
                stat.setTextColor(Color.parseColor("#666666"));
                String stale = Vector.indexStale();
                stat.setText("状态：" + Vector.lastStatus()
                        + "\n记忆库：" + VecStore.count() + " 条"
                        + "（" + (VecStore.enabled() ? "读写开" : "读写关") + "）"
                        + "\n颜文字索引：" + KaoIndex.describe()
                        + (Vector.rebuilding() ? "（正在重建…）"
                           : (stale != null ? "　⚠ " + stale + "，需重建" : "　✓ 最新"))
                        + "\n事前分析：" + (Vector.preEnabled() ? "开" : "关（只做事后分析）")
                        + "\n接口地址：" + Vector.endpoint()
                        + "\n模型：" + (Vector.model().length() > 0 ? Vector.model() : "未填写")
                        + "\n相似度阈值：" + Vector.minScore()
                        + "　领先间距：" + Vector.minMargin()
                        + "\nKey：" + (Vector.hasKey() ? "已配置（不回显）" : "未配置")
                        + "\n记忆库文件：" + VecStore.FILE
                        + "\n索引文件：" + KaoIndex.FILE
                        + "\n配置文件：" + Vector.CFG_FILE
                        + "\nKey 文件：" + Vector.KEY_FILE);
                stat.setTextIsSelectable(true);
                inner.addView(stat);

                TextView hint = new TextView(a);
                hint.setTextSize(11);
                hint.setTextColor(Color.parseColor("#666666"));
                hint.setText(
                    "\n检索顺序（每句最多一个颜文字）：\n"
                  + "  1. 记忆库完全相同 → 直接用（不联网）\n"
                  + "  2. 向量：把句子交给模型，在颜文字语义索引里找最贴近的一条。\n"
                  + "     要同时满足两个条件才用：\n"
                  + "       ① 相似度 >= 相似度阈值\n"
                  + "       ② 第一名比第二名高出的分 >= 领先间距\n"
                  + "     两条一起卡，是为了避免在一堆「差不多的颜文字」里瞎挑。\n"
                  + "     例外：前两名属于同一个标签（同类心情）时免看领先间距。\n"
                  + "  3. 关键词规则（「标签与词库」里配的）。\n"
                  + "  4. 都没命中 → 全库随机。\n"
                  + "  · 命中之后，这个句子会自动记进记忆库，越用越准。\n"
                  + "  · 向量问过的句子，即使没达上面两个门槛，也会把「最接近的\n"
                  + "    那条」记进记忆库（值来自向量，不是随机）——所以同一句话\n"
                  + "    第一次可能随机、第二次起就按记忆库稳定输出，也不再重复请求。\n"
                  + "  · 关键词规则与全库随机的结果不写记忆库。\n"
                  + "  · 记忆库存的时候会去掉句末的 。．；;… —— 所以\n"
                  + "    「我喜欢你。」与「我喜欢你」是同一条；但 ？与！ 各存各的。\n"
                  + "\n事前分析 / 事后分析：\n"
                  + "  · 事前分析 = 打字打出标点时先请求一次，当场选出颜文字。\n"
                  + "  · 事后分析 = 把用过的句子记进记忆库，供以后检索。\n"
                  + "  · 事前分析开着时，事后分析照常进行（事前包含事后）。\n"
                  + "  · 事前分析关掉 = 只做事后分析，打字过程中不联网，\n"
                  + "    已经记下的句子仍然能靠「完全相同」命中。\n"
                  + "  · 关掉总开关则完全不请求网络，只用关键词规则。\n"
                  + "\n颜文字向量索引：\n"
                  + "  · 索引是把每条颜文字的「语义描述」向量化后存进 kaovec.txt。\n"
                  + "  · 改过词库或换过模型后，这里会提示「需重建」，\n"
                  + "    点上面的「重建向量」重新生成（约 1~2 秒）。\n"
                  + "  · 模块不会自动重建，免得白白消耗额度。\n"
                  + "\n"
                  + "关于 API Key：保存后不再回显，只显示「已配置」。\n"
                  + "想换就填新的再保存；想删用「清除 Key」。\n"
                  + "配置存于 vector.yaml（Key 只写不可读）。\n"
                  + "接口地址填到 /v1 即可，会自动补 /embeddings。"
                );
                inner.addView(hint);
            }
        };
        holder[0] = refresh;
        refresh.run();
        return box;
    }

    /** 一行「标签 + 输入框 + 保存按钮」。 */
    private interface Saver {
        void save(String value);
    }

    /** 下拉选择回调。 */
    private interface Chooser {
        void onPick(int index);
    }

    /** 构造一行「标题 + 下拉框」。 */
    private static View choiceRow(Activity a, float d, String label,
                                  String[] items, int sel,
                                  final Chooser h) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (3 * d), 0, (int) (3 * d));

        TextView lab = new TextView(a);
        lab.setText(label + "：");
        lab.setTextSize(12);
        lab.setMinWidth((int) (72 * d));
        row.addView(lab);

        android.widget.Spinner sp = new android.widget.Spinner(a);
        android.widget.ArrayAdapter<String> ad =
                new android.widget.ArrayAdapter<String>(a,
                        android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        if (sel >= 0 && sel < items.length) {
            sp.setSelection(sel);
        }
        sp.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> p,
                                               View v, int pos, long id) {
                        h.onPick(pos);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> p) {
                        // 忽略
                    }
                });
        sp.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sp);
        return row;
    }

    /** 小号灰色说明文字。 */
    private static TextView note(Activity a, float d, CharSequence text) {
        TextView tv = new TextView(a);
        tv.setTextSize(11);
        tv.setTextColor(Color.parseColor("#888888"));
        tv.setPadding(0, 0, 0, (int) (4 * d));
        tv.setText(text);
        return tv;
    }

    private static View field(final Activity a, float d, String label,
                              String value, String hintText, final Saver saver) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (3 * d), 0, (int) (3 * d));

        TextView lab = new TextView(a);
        lab.setText(label + "：");
        lab.setTextSize(12);
        lab.setMinWidth((int) (72 * d));
        row.addView(lab);

        final EditText in = new EditText(a);
        in.setTextSize(12);
        in.setText(value == null ? "" : value);
        in.setHint(hintText);
        in.setSingleLine(true);
        in.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(in);

        Button save = new Button(a);
        save.setText("保存");
        save.setTextSize(11);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saver.save(in.getText().toString().trim());
                toast(a, "已保存");
            }
        });
        row.addView(save);
        return row;
    }

    /** 日志状态描述。 */
    private static String logStat() {
        try {
            java.io.File f = new java.io.File(Cat.LOG_FILE);
            long len = f.isFile() ? f.length() : 0;
            return "当前日志 " + (len / 1024) + " KB\n路径：" + Cat.LOG_FILE;
        } catch (Throwable t) {
            return "日志不可读";
        }
    }

    /** 弹一个 Toast，失败时静默。 */
    private static void toast(Activity a, String msg) {
        try {
            android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            // 忽略
        }
    }

    private static String formatWhitelist() {
        List<String> wl = Cat.whitelist();
        StringBuilder sb = new StringBuilder();
        for (String s : wl) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("  • ").append(s);
        }
        return sb.toString();
    }

    /** 取输入法可见、且有启动入口的应用。 */
    private static List<AppEntry> visibleApps(Activity a) {
        List<AppEntry> out = new ArrayList<AppEntry>();
        try {
            PackageManager pm = a.getPackageManager();
            // 只查 ACTION_MAIN、不加 CATEGORY_LAUNCHER：
            // 实测可见应用从 31 个提升到 72 个（含无桌面图标但有入口的应用）。
            android.content.Intent i =
                    new android.content.Intent(android.content.Intent.ACTION_MAIN);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || !seen.add(pkg)) {
                    continue;
                }
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    e.label = String.valueOf(pm.getApplicationLabel(ai));
                    e.icon = pm.getApplicationIcon(ai);
                } catch (Throwable t) {
                    e.label = pkg;
                }
                out.add(e);
            }
        } catch (Throwable t) {
            Cat.log("UI: 应用列表失败 " + t.getClass().getSimpleName());
        }
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry x, AppEntry y) {
                return x.label.compareToIgnoreCase(y.label);
            }
        });
        return out;
    }

    private static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
    }
}
