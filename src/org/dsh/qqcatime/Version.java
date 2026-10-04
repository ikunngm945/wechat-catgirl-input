package org.dsh.qqcatime;

/**
 * 模块版本号。
 *
 * 修改版本时请同步更新 AndroidManifest.xml 的 versionCode / versionName，
 * 以及构建脚本 build.py 里的 VERSION 常量，三者保持一致。
 */
public final class Version {

    /** 版本名，与 build.py 的 VERSION 对应。 */
    public static final String NAME = "7.7";

    /** 版本号（整数），与 AndroidManifest 的 versionCode 对应。 */
    public static final int CODE = 77;

    private Version() {
    }
}
