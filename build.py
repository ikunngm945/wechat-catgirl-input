#!/usr/bin/env python3.12
"""构建 QQ猫娘输入法桥（LSPosed 模块）。

关键点（踩过的坑，勿删）：
  1. 必须打包 assets/xposed_init，内容是入口类全名。
     缺它 LSPosed 管理器不会把本模块列出来。
  2. AndroidManifest 需 4 个 meta-data：xposedmodule / xposeddescription
     / xposedminversion / xposedscope。
  3. 装好后需用户在 LSPosed 管理器手动启用 + 勾选作用域，
     并重启输入法进程才生效。
"""
from pathlib import Path
import hashlib
import secrets
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
SDK = Path('/root/dsh/apk-analysis/tools/android-33/android.jar')
R8 = Path('/www/wwwroot/192.168.1.116_10086/android-music/tools/r8.jar')
API = ROOT / 'deps/xposed-api-82.jar'
ENTRY = 'org.dsh.qqcatime.Probe'
VERSION = '5.9'


def run(*args):
    print('执行:', ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), check=True)


def gen_defaults():
    """从 config/ 生成 Defaults.java（词库/规则/标签的内置默认值）。"""
    gen = ROOT / 'tools/gen_config.py'
    if not gen.exists():
        sys.exit(f'缺少 {gen}')
    run('python3.12', gen)


def main():
    gen_defaults()

    if not SDK.exists():
        sys.exit(f'缺少 android.jar: {SDK}')
    if not API.exists():
        sys.exit(f'缺少 xposed api: {API}')

    # 入口文件必须存在且内容正确
    entry_file = ROOT / 'assets/xposed_init'
    entry_file.parent.mkdir(exist_ok=True)
    entry_file.write_text(ENTRY, encoding='utf-8')

    build = ROOT / 'build'
    classes = build / 'classes'
    dex = build / 'dex'
    for d in (classes, dex):
        d.mkdir(parents=True, exist_ok=True)
    for old in classes.rglob('*.class'):
        old.unlink()
    for old in dex.glob('*.dex'):
        old.unlink()

    src = ROOT / 'src/org/dsh/qqcatime'
    run('javac', '-encoding', 'UTF-8', '--release', '8',
        '-cp', f'{SDK}:{API}', '-d', classes,
        src / 'Version.java', src / 'Defaults.java', src / 'Cat.java',
        src / 'TagLib.java',
        src / 'ConfigUI.java', src / 'Probe.java')

    jar = build / 'module.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as out:
        for item in sorted(classes.rglob('*.class')):
            out.write(item, item.relative_to(classes).as_posix())

    run('java', '-Dfile.encoding=UTF-8', '-cp', R8, 'com.android.tools.r8.D8',
        '--min-api', '26', '--lib', SDK, '--classpath', API, '--output', dex, jar)

    resources = build / 'resources.apk'
    run('aapt', 'package', '-f', '-M', ROOT / 'AndroidManifest.xml',
        '-S', ROOT / 'res', '-A', ROOT / 'assets', '-I', SDK, '-F', resources)

    unsigned = build / 'unsigned.apk'
    with zipfile.ZipFile(resources) as r, zipfile.ZipFile(unsigned, 'w') as out:
        for e in r.infolist():
            if e.filename == 'resources.arsc':
                e.compress_type = zipfile.ZIP_STORED
            out.writestr(e, r.read(e.filename))
        out.write(dex / 'classes.dex', 'classes.dex',
                  compress_type=zipfile.ZIP_DEFLATED)

    aligned = build / 'aligned.apk'
    run('zipalign', '-f', '4', unsigned, aligned)

    signing = ROOT / '.signing'
    signing.mkdir(mode=0o700, exist_ok=True)
    signing.chmod(0o700)
    pass_file = signing / 'storepass'
    if not pass_file.exists():
        pass_file.write_text(secrets.token_urlsafe(32), encoding='utf-8')
    pass_file.chmod(0o600)
    key = signing / 'module.p12'
    if not key.exists():
        run('keytool', '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
            '-alias', 'qqcatime', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '3650', '-dname', 'CN=QQCat IME Bridge',
            '-storepass:file', pass_file, '-keypass:file', pass_file)
        key.chmod(0o600)

    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    apk = dist / f'QQCatIME-{VERSION}.apk'
    run('apksigner', 'sign', '--ks', key, '--ks-key-alias', 'qqcatime',
        '--ks-pass', f'file:{pass_file}', '--v1-signing-enabled', 'true',
        '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
        '--out', apk, aligned)
    run('apksigner', 'verify', apk)

    data = apk.read_bytes()
    print(f'\nAPK: {apk}')
    print(f'大小: {len(data)} 字节')
    print(f'SHA-256: {hashlib.sha256(data).hexdigest()}')
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        assert 'assets/xposed_init' in names, '缺少 assets/xposed_init！'
        assert 'classes.dex' in names, '缺少 classes.dex！'
        print('校验: assets/xposed_init 与 classes.dex 均在 ✅')


if __name__ == '__main__':
    main()
