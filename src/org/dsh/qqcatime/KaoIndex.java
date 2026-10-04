package org.dsh.qqcatime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 颜文字语义向量索引。
 *
 * <p>把内置的「颜文字语义描述」({@link Defaults#KAODESC}) 逐条交给向量模型，
 * 算出<b>颜文字级</b>的向量并落盘。检索时把用户的句子算成向量，
 * 在这个库里找最贴切的一条颜文字。
 *
 * <p>文件位置：{@code /data/user/0/com.tencent.wetype/qqime/kaovec.txt}
 * <pre>
 *   # src=1234567           颜文字描述的指纹（变了就说明颜文字改过）
 *   # model=Qwen/Qwen3-Embedding-0.6B
 *   # dim=1024
 *   # count=190
 *   (＝^･ω･^＝)	0.12,-0.03,...
 *   (￣o￣) zzZ	0.04,0.11,...
 * </pre>
 *
 * <p><b>绝不自动重建。</b>颜文字改过之后 {@link #rebuildReason(String, List)}
 * 会返回非 null 的原因，界面据此提示用户「需要重建」，
 * 但真正重建必须由用户手动点按钮触发（见 {@link Vector#rebuildIndex(boolean)}）。
 */
public final class KaoIndex {

    /** 索引文件。 */
    public static final String FILE = Cat.DIR + "/kaovec.txt";

    /** 一条索引。 */
    public static final class Entry {
        public final String kaomoji;
        public final float[] vec;

        Entry(String kaomoji, float[] vec) {
            this.kaomoji = kaomoji;
            this.vec = vec;
        }
    }

    private static final List<Entry> entries = new ArrayList<Entry>();
    private static volatile int dim = 0;
    private static volatile String model = "";
    private static volatile long src = 0L;
    private static long stamp = -2;

    private KaoIndex() {
    }

    /** 内置颜文字语义描述：颜文字 -> 描述。 */
    private static Map<String, String> descMap() {
        Map<String, String> m = new HashMap<String, String>();
        String raw = Defaults.KAODESC == null ? "" : Defaults.KAODESC;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.length() == 0 || s.startsWith("#")) {
                continue;
            }
            int i = s.lastIndexOf(" = ");
            if (i <= 0) {
                continue;
            }
            String k = s.substring(0, i).trim();
            String d = s.substring(i + 3).trim();
            if (k.length() > 0 && d.length() > 0) {
                m.put(k, d);
            }
        }
        return m;
    }

    /**
     * 当前词库对应的指纹。
     *
     * <p>只有「有语义描述的颜文字」参与索引，因此指纹也只算这些：
     * 用户增删改词库后指纹会变，{@link #rebuildReason} 就会提示需要重建。
     */
    public static long sourceStamp(List<String> kaomoji) {
        Map<String, String> dm = descMap();
        long h = 1125899906842597L;
        int n = 0;
        if (kaomoji != null) {
            for (String k : kaomoji) {
                String d = dm.get(k);
                if (d == null) {
                    continue;
                }
                for (int i = 0; i < k.length(); i++) {
                    h = 31 * h + k.charAt(i);
                }
                h = 31 * h + 1;
                for (int i = 0; i < d.length(); i++) {
                    h = 31 * h + d.charAt(i);
                }
                h = 31 * h + 2;
                n++;
            }
        }
        return h * 31 + n;
    }

    /**
     * 取当前词库里「有语义描述」的颜文字，返回 {颜文字, 描述} 列表。
     * 没描述的颜文字不进索引（但仍在关键词 / 随机兜底里能用）。
     */
    public static List<String[]> pairs(List<String> kaomoji) {
        Map<String, String> dm = descMap();
        List<String[]> out = new ArrayList<String[]>();
        if (kaomoji == null) {
            return out;
        }
        for (String k : kaomoji) {
            String d = dm.get(k);
            if (d != null) {
                out.add(new String[] { k, d });
            }
        }
        return out;
    }

    /** 当前词库里「有语义描述」的颜文字条数。 */
    public static int indexable(List<String> kaomoji) {
        Map<String, String> dm = descMap();
        int n = 0;
        if (kaomoji != null) {
            for (String k : kaomoji) {
                if (dm.containsKey(k)) {
                    n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------ 加载

    public static synchronized void load() {
        File f = new File(FILE);
        long s = f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
        if (s == stamp) {
            return;
        }
        stamp = s;
        entries.clear();
        dim = 0;
        model = "";
        src = 0L;
        if (s == -1L || f.length() == 0) {
            return;   // 还没建过，正常
        }
        List<String> lines;
        try {
            lines = readLines(f);
        } catch (Throwable t) {
            Cat.log("kaovec: 读取失败 " + t.getClass().getSimpleName());
            return;
        }
        int bad = 0;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#")) {
                String body = line.substring(1).trim();
                int i = body.indexOf('=');
                if (i <= 0) {
                    continue;
                }
                String k = body.substring(0, i).trim().toLowerCase();
                String v = body.substring(i + 1).trim();
                if ("src".equals(k)) {
                    src = parseLong(v);
                } else if ("dim".equals(k)) {
                    dim = (int) parseLong(v);
                } else if ("model".equals(k)) {
                    model = v;
                }
                continue;
            }
            int t = line.lastIndexOf('\t');
            if (t <= 0) {
                bad++;
                continue;
            }
            String kao = line.substring(0, t).trim();
            float[] v = parseVec(line.substring(t + 1));
            if (kao.length() == 0 || v == null || v.length == 0) {
                bad++;
                continue;
            }
            entries.add(new Entry(kao, v));
            if (dim == 0) {
                dim = v.length;
            }
        }
        Cat.log("kaovec: 索引已加载 " + entries.size() + " 条 维度=" + dim
                + (bad > 0 ? " 跳过 " + bad + " 行" : ""));
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------ 状态

    public static synchronized int size() {
        return entries.size();
    }

    public static int dim() {
        return dim;
    }

    public static String model() {
        return model;
    }

    public static long src() {
        return src;
    }

    /** 文件存在且读进来至少一条。 */
    public static synchronized boolean usable() {
        return entries.size() > 0;
    }

    /**
     * 是否需要重建。
     *
     * @return 不需要返回 null；需要则返回原因（给界面显示）
     */
    public static synchronized String rebuildReason(String curModel, List<String> kaomoji) {
        if (entries.size() == 0) {
            return "还没有索引";
        }
        if (src != sourceStamp(kaomoji)) {
            return "颜文字改过了";
        }
        String m = curModel == null ? "" : curModel.trim();
        if (m.length() > 0 && !m.equals(model)) {
            return "模型换过了";
        }
        return null;
    }

    // ------------------------------------------------------------ 检索

    /**
     * 找最贴切的一条颜文字。
     *
     * @return 最相似的一条；库为空或维度不匹配返回 null
     */
    public static synchronized Entry nearest(float[] q) {
        if (q == null || q.length == 0 || entries.isEmpty()) {
            return null;
        }
        Entry best = null;
        Entry second = null;
        double bestScore = -2d;
        double secondScore = -2d;
        for (Entry e : entries) {
            if (e.vec.length != q.length) {
                continue;
            }
            double s = cosine(q, e.vec);
            if (s > bestScore) {
                secondScore = bestScore;
                second = best;
                bestScore = s;
                best = e;
            } else if (s > secondScore) {
                secondScore = s;
                second = e;
            }
        }
        if (best == null) {
            return null;
        }
        lastScore = bestScore;
        lastSecond = second == null ? "" : second.kaomoji;
        // 第一名与第二名的差距。190 条描述彼此很像，光看绝对分
        // 分不出「真的贴切」还是「一群差不多的里随便挑了个」，
        // 实测用间距做补充门槛可以把精度从 79% 提到 93%。
        lastMargin = (secondScore <= -2d) ? 1d : (bestScore - secondScore);
        return best;
    }

    /** 上一次 {@link #nearest} 的分数。 */
    private static volatile double lastScore = 0d;

    /** 上一次 {@link #nearest} 的第一名与第二名分差。 */
    private static volatile double lastMargin = 0d;

    /** 上一次 {@link #nearest} 的第二名颜文字（用于「同标签免间距」判定）。 */
    private static volatile String lastSecond = "";

    public static double lastScore() {
        return lastScore;
    }

    public static double lastMargin() {
        return lastMargin;
    }

    public static String lastSecond() {
        return lastSecond;
    }

    public static synchronized String describe() {
        if (entries.isEmpty()) {
            return "未建索引";
        }
        return entries.size() + " 条 维度=" + dim;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0d, na = 0d, nb = 0d;
        for (int i = 0; i < a.length; i++) {
            double x = a[i], y = b[i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na <= 0d || nb <= 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ------------------------------------------------------------ 写盘

    /**
     * 用算好的向量重建整个索引文件。
     *
     * @param kaomoji 颜文字
     * @param vectors 与 kaomoji 一一对应的向量
     */
    public static synchronized void save(List<String> kaomoji,
                                         List<float[]> vectors, String mdl) {
        long stampSrc = sourceStamp(kaomoji);
        if (kaomoji == null || vectors == null || kaomoji.isEmpty()
                || kaomoji.size() != vectors.size()) {
            return;
        }
        int d = 0;
        StringBuilder sb = new StringBuilder(kaomoji.size() * 4000);
        sb.append("# 颜文字语义向量索引 —— 由模块生成，也可手动编辑。\n");
        sb.append("# 颜文字改过之后需要在这里点「重建向量」重新生成。\n");
        for (int i = 0; i < vectors.size(); i++) {
            float[] v = vectors.get(i);
            if (v == null || v.length == 0) {
                continue;
            }
            if (d == 0) {
                d = v.length;
            }
        }
        sb.append("# src=").append(stampSrc).append('\n');
        sb.append("# model=").append(mdl == null ? "" : mdl).append('\n');
        sb.append("# dim=").append(d).append('\n');
        sb.append("# count=").append(kaomoji.size()).append('\n');
        int n = 0;
        for (int i = 0; i < kaomoji.size(); i++) {
            float[] v = vectors.get(i);
            String k = kaomoji.get(i);
            if (v == null || v.length == 0 || k == null || k.length() == 0) {
                continue;
            }
            sb.append(oneLine(k)).append('\t').append(formatVec(v)).append('\n');
            n++;
        }
        Cat.writeFile(FILE, sb.toString());
        stamp = -2;
        load();
        Cat.log("kaovec: 索引已重建 " + n + " 条 维度=" + d);
    }

    /** 删掉索引文件（界面上的「清除索引」）。 */
    public static synchronized boolean clear() {
        File f = new File(FILE);
        boolean ok = !f.exists() || f.delete();
        entries.clear();
        dim = 0;
        model = "";
        src = 0L;
        stamp = -2;
        Cat.log("kaovec: 索引已清除 " + (ok ? "成功" : "失败"));
        return ok;
    }

    // ------------------------------------------------------------ 工具

    private static List<String> readLines(File f) throws Exception {
        List<String> out = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable t) { }
            }
        }
        return out;
    }

    private static float[] parseVec(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() == 0) {
            return null;
        }
        String[] parts = t.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                v[i] = Float.parseFloat(parts[i].trim());
            } catch (Throwable e) {
                return null;
            }
        }
        return v;
    }

    private static String formatVec(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8);
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(java.util.Locale.US, "%.6g", v[i]));
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
