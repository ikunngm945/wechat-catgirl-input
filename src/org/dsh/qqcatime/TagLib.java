package org.dsh.qqcatime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 颜文字标签库。
 *
 * <p>目录结构（都在 {@link Cat#DIR} 下）：
 * <pre>
 *   katxt            全部颜文字（兜底库，缺失时释放内置的 54 条）
 *   tags/            标签分类目录
 *     开心.txt        该标签下的颜文字，一行一个
 *     难过.txt
 *     ...
 *   rules.txt        关键词 → 标签 映射，一行一条，格式「关键词=标签」
 * </pre>
 *
 * <p>匹配规则：
 * <ul>
 *   <li>按句末标点把文本切成若干句，每句独立处理。</li>
 *   <li>句中出现某个关键词，即命中该关键词对应的标签。</li>
 *   <li>一句命中多个标签时，取<code>rules.txt</code>里<b>最后</b>出现的那个。</li>
 *   <li>没有命中任何标签时，从兜底库 katxt 中随机取。</li>
 * </ul>
 */
public final class TagLib {

    /** 标签目录。 */
    public static final String TAGS_DIR = Cat.DIR + "/tags";

    /** 关键词 → 标签 映射文件。 */
    public static final String RULES_FILE = Cat.DIR + "/rules.txt";

    /** 默认规则（首次运行释放，用户可自行修改）。 */
    /** 内置默认规则（来自 config/rules.txt）。 */
    public static final String[] DEFAULT_RULES = Cat.splitLines(Defaults.RULES);

    /** 示例标签文件（首次运行释放，内容取自内置词库的前若干条）。 */
    /** 内置默认标签名（来自 config/tags/）。 */
    public static final String[] DEFAULT_TAGS = Defaults.TAG_NAMES;

    private static final Random RND = new Random();

    /** 关键词 → 标签，保持文件顺序（用于「取最后命中」）。 */
    private static final Map<String, String> rules = new LinkedHashMap<String, String>();

    /** 标签 → 该标签下的颜文字。 */
    private static final Map<String, String[]> tagKaomoji =
            new HashMap<String, String[]>();

    private static long rulesStamp = -2;
    private static long tagsStamp = -2;

    private TagLib() {
    }

    // ------------------------------------------------------------ 加载

    /** 加载规则与标签库（有变化才重新读盘）。 */
    public static synchronized void load() {
        loadRules();
        loadTags();
    }

    private static void loadRules() {
        File f = new File(RULES_FILE);
        long stamp = stamp(f);
        if (stamp == rulesStamp) {
            return;
        }
        rulesStamp = stamp;

        if (stamp == -1L) {
            StringBuilder sb = new StringBuilder();
            for (String r : DEFAULT_RULES) {
                sb.append(r).append('\n');
            }
            Cat.writeFile(RULES_FILE, sb.toString());
        }

        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        List<String> lines = readLines(f);
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String kw = line.substring(0, eq).trim();
            String tag = line.substring(eq + 1).trim();
            if (kw.length() > 0 && tag.length() > 0) {
                out.put(kw, tag);
            }
        }
        if (out.isEmpty() && stamp != -1L) {
            for (String r : DEFAULT_RULES) {
                int eq = r.indexOf('=');
                out.put(r.substring(0, eq), r.substring(eq + 1));
            }
        }
        rules.clear();
        rules.putAll(out);
        Cat.log("rules: 已加载 " + rules.size() + " 条");
    }

    private static void loadTags() {
        File dir = new File(TAGS_DIR);
        long stamp = -1L;
        if (dir.isDirectory()) {
            long acc = 0;
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) {
                        acc = acc * 31 + f.getName().hashCode()
                                + f.lastModified() + f.length();
                    }
                }
            }
            stamp = acc;
        }
        if (stamp == tagsStamp) {
            return;
        }
        tagsStamp = stamp;

        if (!dir.isDirectory()) {
            releaseDefaultTags();
        }

        HashMap<String, String[]> out = new HashMap<String, String[]>();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.isFile()) {
                    continue;
                }
                String name = f.getName();
                if (!name.endsWith(".txt")) {
                    continue;
                }
                String tag = name.substring(0, name.length() - 4);
                List<String> lines = readLines(f);
                if (lines.isEmpty()) {
                    continue;
                }
                out.put(tag, lines.toArray(new String[0]));
            }
        }
        tagKaomoji.clear();
        tagKaomoji.putAll(out);
        Cat.log("tags: 已加载 " + tagKaomoji.size() + " 个标签 " + tagKaomoji.keySet());
    }

    /** 首次运行：按内置标签内容释放 tags/ 目录。 */
    private static void releaseDefaultTags() {
        try {
            File dir = new File(TAGS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
                dir.setReadable(true, false);
                dir.setExecutable(true, false);
            }
        } catch (Throwable t) {
            // 忽略
        }
        for (int i = 0; i < Defaults.TAG_NAMES.length
                && i < Defaults.TAG_BODIES.length; i++) {
            Cat.writeFile(TAGS_DIR + "/" + Defaults.TAG_NAMES[i] + ".txt",
                    Defaults.TAG_BODIES[i]);
        }
        Cat.log("tags: 已释放内置 " + Defaults.TAG_NAMES.length + " 个标签");
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<String>();
        if (f == null || !f.isFile()) {
            return out;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0 && !line.startsWith("#")) {
                    out.add(line);
                }
            }
        } catch (Throwable t) {
            // 忽略
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable t) { }
            }
        }
        return out;
    }

    private static long stamp(File f) {
        return f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
    }

    // ------------------------------------------------------------ 查询

    /**
     * 为一句话挑选颜文字。
     *
     * @param sentence 单句文本（不含句末标点）
     * @return 颜文字
     */
    public static String pick(String sentence) {
        String tag = matchTag(sentence);
        if (tag != null) {
            String[] arr = tagKaomoji.get(tag);
            if (arr != null && arr.length > 0) {
                return arr[RND.nextInt(arr.length)];
            }
        }
        // 未命中，或标签文件为空 -> 兜底全库随机
        return Cat.randomKaomoji();
    }

    /**
     * 匹配标签。一句命中多个时，取 rules.txt 里最后出现的那个。
     *
     * @return 标签名；无命中返回 null
     */
    public static String matchTag(String sentence) {
        if (sentence == null || sentence.length() == 0) {
            return null;
        }
        String hit = null;
        // 关键词要同时兼容「替换前 / 替换后」两种形态：
        // 用户输入「你好」，替换后变成「主人好」，若只用替换后的文本匹配，
        // 规则「你好=开心」就永远命中不了。因此两种形态都试。
        String alt = sentence
                .replace("本喵", "我")
                .replace("主人", "你");
        // rules 是 LinkedHashMap，按文件顺序遍历，后面的覆盖前面的
        for (Map.Entry<String, String> e : rules.entrySet()) {
            if (sentence.contains(e.getKey()) || alt.contains(e.getKey())) {
                hit = e.getValue();
            }
        }
        return hit;
    }

    /** 已加载的标签数量。 */
    public static int tagCount() {
        return tagKaomoji.size();
    }

    /** 规则条数。 */
    public static int ruleCount() {
        return rules.size();
    }

    /** 强制下次 load() 重新读盘（恢复默认后调用）。 */
    public static void forceReload() {
        rulesStamp = -2;
        tagsStamp = -2;
        load();
    }

    // ------------------------------------------------------------ 编辑 API

    /** 当前所有标签名（已排序）。 */
    public static java.util.List<String> tagNames() {
        java.util.List<String> out = new ArrayList<String>(tagKaomoji.keySet());
        java.util.Collections.sort(out);
        return out;
    }

    /** 读取某标签的颜文字行。 */
    public static java.util.List<String> readTagKaomoji(String tag) {
        return readLines(new File(TAGS_DIR + "/" + tag + ".txt"));
    }

    /** 读取某标签对应的关键词（rules.txt 里 tag 等于该标签的）。 */
    public static java.util.List<String> readTagKeywords(String tag) {
        java.util.List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : rules.entrySet()) {
            if (e.getValue().equals(tag)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 保存某标签的颜文字（整文件覆盖）。 */
    public static synchronized boolean saveTagKaomoji(String tag, String content) {
        if (tag == null || tag.trim().length() == 0) {
            return false;
        }
        tag = tag.trim();
        try {
            File dir = new File(TAGS_DIR);
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            Cat.writeFile(TAGS_DIR + "/" + tag + ".txt", content);
            tagsStamp = -2;   // 强制重载
            load();
            rebuildMasterLibrary();
            Cat.log("保存标签颜文字 " + tag + "，共 " + readTagKaomoji(tag).size() + " 条");
            return true;
        } catch (Throwable t) {
            Cat.log("保存标签失败 " + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 保存某标签的关键词：把 rules.txt 里属于该标签的旧规则替换成新的一组，
     * 其它标签的规则保持原样。
     */
    public static synchronized boolean saveTagKeywords(String tag, String content) {
        if (tag == null || tag.trim().length() == 0) {
            return false;
        }
        tag = tag.trim();
        try {
            LinkedHashMap<String, String> next = new LinkedHashMap<String, String>();
            // 保留其它标签的规则
            for (Map.Entry<String, String> e : rules.entrySet()) {
                if (!e.getValue().equals(tag)) {
                    next.put(e.getKey(), e.getValue());
                }
            }
            // 追加本标签的新关键词（按行）
            for (String line : content.split("\n")) {
                String k = line.trim();
                if (k.length() > 0 && !k.startsWith("#")) {
                    // 关键词若含 = ，只取前半段，避免格式错乱
                    int eq = k.indexOf('=');
                    if (eq > 0) {
                        k = k.substring(0, eq).trim();
                    }
                    if (k.length() > 0) {
                        next.put(k, tag);
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : next.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Cat.writeFile(RULES_FILE, sb.toString());
            rulesStamp = -2;
            load();
            Cat.log("保存标签规则 " + tag + "，共 " + readTagKeywords(tag).size() + " 条关键词");
            return true;
        } catch (Throwable t) {
            Cat.log("保存规则失败 " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 新建标签。 */
    public static synchronized boolean createTag(String tag) {
        if (tag == null) {
            return false;
        }
        tag = tag.trim();
        if (tag.length() == 0 || tagKaomoji.containsKey(tag)) {
            return false;
        }
        return saveTagKaomoji(tag, "");
    }

    /** 删除标签（连同它的颜文字文件与关键词规则）。 */
    public static synchronized boolean deleteTag(String tag) {
        if (tag == null) {
            return false;
        }
        tag = tag.trim();
        try {
            File f = new File(TAGS_DIR + "/" + tag + ".txt");
            if (f.isFile()) {
                f.delete();
            }
            LinkedHashMap<String, String> next = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> e : rules.entrySet()) {
                if (!e.getValue().equals(tag)) {
                    next.put(e.getKey(), e.getValue());
                }
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : next.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Cat.writeFile(RULES_FILE, sb.toString());
            rulesStamp = -2;
            tagsStamp = -2;
            load();
            rebuildMasterLibrary();
            Cat.log("删除标签 " + tag);
            return true;
        } catch (Throwable t) {
            Cat.log("删除标签失败 " + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 按所有标签的颜文字重建总库 katxt（自动去重）。
     * 这样「总的颜文字库会随着其他的变化而变化，但不会汇入重复的」。
     */
    public static synchronized int rebuildMasterLibrary() {
        LinkedHashMap<String, Boolean> uniq = new LinkedHashMap<String, Boolean>();
        for (String[] arr : tagKaomoji.values()) {
            for (String k : arr) {
                String t = k == null ? "" : k.trim();
                if (t.length() > 0) {
                    uniq.put(t, Boolean.TRUE);
                }
            }
        }
        if (uniq.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (String k : uniq.keySet()) {
            sb.append(k).append('\n');
        }
        Cat.writeFile(Cat.KAO_FILE, sb.toString());
        Cat.reloadKaomoji();
        Cat.log("重建总库 katxt：" + uniq.size() + " 条（去重后）");
        return uniq.size();
    }
}
