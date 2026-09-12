package net.ccbluex.liquidbounce.features.module.modules.render.ttf;

import java.io.File;

/**
 * 模块与 Mixin 之间的桥：启用时写入 TTF 路径与参数，
 * MixinFontManagerForceTtf 在字体加载完成后从磁盘构造 TrueTypeGlyphProvider 并注入。
 */
public final class ForcedTtfHolder {
    private ForcedTtfHolder() {}

    public static volatile boolean enabled = false;
    public static volatile File ttfFile = null;
    public static volatile float size = 11f;
    public static volatile float oversample = 4f;
    public static volatile float shiftX = 0f;
    public static volatile float shiftY = 0.5f;
    public static volatile String skip = "";
    /** 是否覆盖 uniform 字体 */
    public static volatile boolean alsoUniform = true;
}
