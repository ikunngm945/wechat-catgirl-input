#!/usr/bin/env python3.12
# -*- coding: utf-8 -*-
"""
从 config/ 生成 src/org/dsh/qqcatime/Defaults.java。

这样 config/ 里的词库、规则、标签会成为模块的「内置默认值」，
用户误删配置后可一键恢复，且不同设备首次运行也会自动释放。

build.py 会在每次构建前自动调用本脚本。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(ROOT, 'config')
OUT = os.path.join(ROOT, 'src', 'org', 'dsh', 'qqcatime', 'Defaults.java')


def read(path):
    if not os.path.isfile(path):
        return ''
    with io.open(path, encoding='utf-8') as f:
        return f.read()


def norm(text):
    """统一换行、去掉行尾空白，保持原顺序。"""
    lines = []
    for line in text.replace('\r\n', '\n').replace('\r', '\n').split('\n'):
        lines.append(line.rstrip())
    return '\n'.join(lines).strip('\n')


def jstr(s):
    """转成 Java 字符串字面量。"""
    out = []
    for ch in s:
        if ch == '\\':
            out.append('\\\\')
        elif ch == '"':
            out.append('\\"')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\t':
            out.append('\\t')
        elif ch == '\r':
            continue
        elif ord(ch) < 0x20:
            out.append('\\u%04x' % ord(ch))
        else:
            out.append(ch)
    return '"' + ''.join(out) + '"'


def emit_long(name, text):
    """长文本切成多段，运行时再拼起来。

    不能写成 `"a" + "b" + ...` —— javac 会在编译期把它们折叠成
    一个字符串常量，超过常量池 64KB 上限就报
    "UTF8 representation ... is too long for the constant pool"。
    因此这里生成的是 join(...) 调用，编译期不会折叠。
    """
    if not text:
        return '    public static final String %s = "";\n' % name
    chunks = []
    step = 3000
    for i in range(0, len(text), step):
        chunks.append(jstr(text[i:i + step]))
    body = ',\n            '.join(chunks)
    return ('    public static final String %s = join(\n            %s);\n'
            % (name, body))


def main():
    kao = norm(read(os.path.join(CFG, 'katxt')))
    rules = norm(read(os.path.join(CFG, 'rules.txt')))
    kaodesc = norm(read(os.path.join(CFG, 'kaodesc.txt')))
    mseed = norm(read(os.path.join(CFG, 'memory_seed.txt')))

    tags_dir = os.path.join(CFG, 'tags')
    names, bodies = [], []
    if os.path.isdir(tags_dir):
        for fn in sorted(os.listdir(tags_dir)):
            if not fn.endswith('.txt'):
                continue
            body = norm(read(os.path.join(tags_dir, fn)))
            names.append(fn[:-4])
            bodies.append(body)

    parts = []
    parts.append('package org.dsh.qqcatime;\n\n')
    parts.append('/**\n')
    parts.append(' * 内置默认配置。\n')
    parts.append(' *\n')
    parts.append(' * <b>本文件由 tools/gen_config.py 从 config/ 目录自动生成，请勿手工修改。</b>\n')
    parts.append(' * 要改默认值，请改 config/ 下的文件后重新构建。\n')
    parts.append(' */\n')
    parts.append('public final class Defaults {\n\n')
    parts.append(emit_long('KAOMOJI', kao))
    parts.append('\n')
    parts.append(emit_long('RULES', rules))
    parts.append('\n')
    parts.append('    /**\n')
    parts.append('     * 颜文字语义描述（颜文字 = 类别：…。表情特征：…。……）。\n')
    parts.append('     * 向量索引的输入源；只有写在这里的颜文字才参与向量匹配。\n')
    parts.append('     */\n')
    parts.append(emit_long('KAODESC', kaodesc))
    parts.append('\n')
    parts.append('    /**\n')
    parts.append('     * 内置记忆库种子（句子=颜文字）。\n')
    parts.append('     * 只在 memory.txt 不存在时释放一次，让常见话术一开口就走')
    parts.append('「完全相同命中」，不必等向量算。\n')
    parts.append('     */\n')
    parts.append(emit_long('MEMORY_SEED', mseed))
    parts.append('\n')
    parts.append('    /** 标签名（与 TAG_BODIES 一一对应）。 */\n')
    parts.append('    public static final String[] TAG_NAMES = {\n')
    for n in names:
        parts.append('        %s,\n' % jstr(n))
    parts.append('    };\n\n')
    parts.append('    /** 各标签的颜文字内容（与 TAG_NAMES 一一对应）。 */\n')
    parts.append('    public static final String[] TAG_BODIES = {\n')
    for b in bodies:
        parts.append('        %s,\n' % jstr(b))
    parts.append('    };\n\n')
    parts.append('    /** 把多段字面量在运行时拼起来，避开常量池 64KB 上限。 */\n')
    parts.append('    private static String join(String... parts) {\n')
    parts.append('        StringBuilder sb = new StringBuilder();\n')
    parts.append('        for (String p : parts) {\n')
    parts.append('            sb.append(p);\n')
    parts.append('        }\n')
    parts.append('        return sb.toString();\n')
    parts.append('    }\n\n')
    parts.append('    private Defaults() {\n    }\n')
    parts.append('}\n')

    content = ''.join(parts)
    with io.open(OUT, 'w', encoding='utf-8') as f:
        f.write(content)

    print('Defaults.java 已生成:')
    print('  词库 %d 条' % len([l for l in kao.split('\n') if l.strip()]))
    print('  规则 %d 条' % len([l for l in rules.split('\n')
                               if l.strip() and not l.startswith('#')]))
    print('  标签 %d 个' % len(names))
    print('  语义描述 %d 条' % len([l for l in kaodesc.split('\n') if l.strip()]))
    print('  记忆库种子 %d 条' % len([l for l in mseed.split('\n')
                                      if l.strip() and not l.startswith('#')]))
    print('  文件大小 %d 字节' % len(content.encode('utf-8')))


if __name__ == '__main__':
    sys.exit(main())
