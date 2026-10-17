package com.ciallo.live2d.cubism;

import java.nio.charset.StandardCharsets;

/**
 * MOC3 binary format parser (pure Java).
 *
 * This is a direct port of Sakura Motion Purism Core's moc3.c / moc3.h layout
 * reading (MIT licensed). The original Live2D Cubism Core is a native library;
 * this class replaces the JNA native binding with a pure Java parser so the
 * client no longer ships any .so/.dll.
 *
 * Layout recap (validated against all 8 official sample models, MOC3 v1..v6):
 *  - header: 4 byte magic "MOC3", 1 byte version, 1 byte endian flag
 *  - u32 section offset table starting at 0x40 (160 entries for v1-v5, 480 for v6)
 *  - count_info is an i32 table of 32 (v1-v4) or 64 (v5+) entries
 *  - ID blocks are stored inline as 64-byte null-terminated strings
 */
public final class Moc3 {

    public static final int VER_30 = 1;
    public static final int VER_33 = 2;
    public static final int VER_40 = 3;
    public static final int VER_42 = 4;
    public static final int VER_50 = 5;
    public static final int VER_53 = 6;

    // Section indices, matching the Purism Core PSM__SECTIONS_* macro order.
    public static final int S_COUNT_INFO = 0;
    public static final int S_CANVAS_INFO = 1;
    public static final int S_PART_ID = 3;
    public static final int S_PART_BINDING_IDX = 4;
    public static final int S_PART_KEYFORM_OFF = 5;
    public static final int S_PART_KEY_LEN = 6;
    public static final int S_PART_VISIBLE = 7;
    public static final int S_PART_ENABLE = 8;
    public static final int S_PART_PARENT_PART_IDX = 9;
    public static final int S_DEFORMER_BINDING_IDX = 12;
    public static final int S_DEFORMER_VISIBLE = 13;
    public static final int S_DEFORMER_ENABLE = 14;
    public static final int S_DEFORMER_PARENT_PART_IDX = 15;
    public static final int S_DEFORMER_PARENT_DEFORMER_IDX = 16;
    public static final int S_DEFORMER_TYPE = 17;
    public static final int S_DEFORMER_LOCAL_IDX = 18;
    public static final int S_WARP_BINDING_IDX = 19;
    public static final int S_WARP_KEYFORM_OFF = 20;
    public static final int S_WARP_KEY_LEN = 21;
    public static final int S_WARP_VERTEX_COUNT = 22;
    public static final int S_WARP_ROW = 23;
    public static final int S_WARP_COL = 24;
    public static final int S_ROT_BINDING_IDX = 25;
    public static final int S_ROT_KEYFORM_OFF = 26;
    public static final int S_ROT_KEY_LEN = 27;
    public static final int S_ROT_BASE_ANGLE = 28;
    public static final int S_AM_ID = 33;
    public static final int S_AM_BINDING_IDX = 34;
    public static final int S_AM_KEYFORM_OFF = 35;
    public static final int S_AM_KEY_LEN = 36;
    public static final int S_AM_VISIBLE = 37;
    public static final int S_AM_ENABLE = 38;
    public static final int S_AM_PARENT_PART_IDX = 39;
    public static final int S_AM_PARENT_DEFORMER_IDX = 40;
    public static final int S_AM_TEXTURE_NO = 41;
    public static final int S_AM_DRAWABLE_FLAG = 42;
    public static final int S_AM_VERTEX_COUNT = 43;
    public static final int S_AM_UV_OFF = 44;
    public static final int S_AM_IDX_OFF = 45;
    public static final int S_AM_IDX_LEN = 46;
    public static final int S_AM_MASK_OFF = 47;
    public static final int S_AM_MASK_LEN = 48;
    public static final int S_PARAM_ID = 50;
    public static final int S_PARAM_MAX = 51;
    public static final int S_PARAM_MIN = 52;
    public static final int S_PARAM_DEFAULT = 53;
    public static final int S_PARAM_REPEAT = 54;
    public static final int S_PARAM_DECIMAL_PLACES = 55;
    public static final int S_PARAM_KEY_TABLE_OFF = 56;
    public static final int S_PARAM_KEY_TABLE_LEN = 57;
    public static final int S_PART_KEY_DRAW_ORDER = 58;
    public static final int S_WARP_KEY_OPACITY = 59;
    public static final int S_WARP_KEY_POS_OFF = 60;
    public static final int S_ROT_KEY_OPACITY = 61;
    public static final int S_ROT_KEY_ANGLE = 62;
    public static final int S_ROT_KEY_ORIGIN_X = 63;
    public static final int S_ROT_KEY_ORIGIN_Y = 64;
    public static final int S_ROT_KEY_SCALE = 65;
    public static final int S_ROT_KEY_REFLECT_X = 66;
    public static final int S_ROT_KEY_REFLECT_Y = 67;
    public static final int S_AM_KEY_OPACITY = 68;
    public static final int S_AM_KEY_DRAW_ORDER = 69;
    public static final int S_AM_KEY_POS_OFF = 70;
    public static final int S_KEY_POS_XY = 71;
    public static final int S_KEY_TABLE_IDX = 72;
    public static final int S_BINDING_KEY_TABLE_IDX_OFF = 73;
    public static final int S_BINDING_KEY_TABLE_IDX_LEN = 74;
    public static final int S_KEY_TABLE_KEYS_OFF = 75;
    public static final int S_KEY_TABLE_KEYS_LEN = 76;
    public static final int S_KEYS = 77;
    public static final int S_UVS = 78;
    public static final int S_IDX = 79;
    public static final int S_MASKS = 80;
    public static final int S_DG_OBJ_OFF = 81;
    public static final int S_DG_OBJ_LEN = 82;
    public static final int S_DG_OBJ_TOTAL_COUNT = 83;
    public static final int S_DG_MAX_ORDER = 84;
    public static final int S_DG_MIN_ORDER = 85;
    public static final int S_DGO_TYPE = 86;
    public static final int S_DGO_IDX = 87;
    public static final int S_DGO_SELF_GROUP_IDX = 88;
    public static final int S_GLUE_BINDING_IDX = 91;
    public static final int S_GLUE_KEYFORM_OFF = 92;
    public static final int S_GLUE_KEY_LEN = 93;
    public static final int S_GLUE_MESH_A = 94;
    public static final int S_GLUE_MESH_B = 95;
    public static final int S_GLUE_INFO_OFF = 96;
    public static final int S_GLUE_INFO_LEN = 97;
    public static final int S_GLUE_INFO_WEIGHT = 98;
    public static final int S_GLUE_INFO_POS_IDX = 99;
    public static final int S_GLUE_KEY_INTENSITY = 100;
    public static final int S_WARP_QUAD_TRANSFORM = 101;
    public static final int S_PARAM_KEY_RUNTIME = 102;
    public static final int S_PARAM_KEY_OFF = 103;
    public static final int S_PARAM_KEY_LEN = 104;
    public static final int S_WARP_KEY_COLOR_OFF = 105;
    public static final int S_ROT_KEY_COLOR_OFF = 106;
    public static final int S_AM_KEY_COLOR_OFF = 107;
    public static final int S_KEYFORM_MUL_R = 108;
    public static final int S_KEYFORM_MUL_G = 109;
    public static final int S_KEYFORM_MUL_B = 110;
    public static final int S_KEYFORM_SCR_R = 111;
    public static final int S_KEYFORM_SCR_G = 112;
    public static final int S_KEYFORM_SCR_B = 113;
    public static final int S_PARAM_TYPE = 114;
    public static final int S_PARAM_BLEND_KEY_TABLE_OFF = 115;
    public static final int S_PARAM_BLEND_KEY_TABLE_LEN = 116;
    public static final int S_BLEND_KEY_TABLE_KEYS_OFF = 117;
    public static final int S_BLEND_KEY_TABLE_KEYS_LEN = 118;
    public static final int S_BLEND_KEY_TABLE_BASE_KEY_IDX = 119;
    public static final int S_BLEND_BINDING_KEY_TABLE_IDX = 120;
    public static final int S_BLEND_BINDING_KEY_BS_OFF = 121;
    public static final int S_BLEND_BINDING_KEY_BS_LEN = 122;
    public static final int S_BLEND_BINDING_CONSTR_IDX_OFF = 123;
    public static final int S_BLEND_BINDING_CONSTR_IDX_LEN = 124;
    public static final int S_BS_WARP_TARGET_IDX = 125;
    public static final int S_BS_WARP_BINDING_OFF = 126;
    public static final int S_BS_WARP_BINDING_LEN = 127;
    public static final int S_BS_AM_TARGET_IDX = 128;
    public static final int S_BS_AM_BINDING_OFF = 129;
    public static final int S_BS_AM_BINDING_LEN = 130;
    public static final int S_BS_CONSTR_IDX = 131;
    public static final int S_BS_CONSTR_PARAM_IDX = 132;
    public static final int S_BS_CONSTR_VALUE_OFF = 133;
    public static final int S_BS_CONSTR_VALUE_LEN = 134;
    public static final int S_BS_CONSTR_VAL_KEY = 135;
    public static final int S_BS_CONSTR_VAL_WEIGHT = 136;
    public static final int S_WARP_KEY_MUL_OFF = 137;
    public static final int S_WARP_KEY_SCR_OFF = 138;
    public static final int S_ROT_KEY_MUL_OFF = 139;
    public static final int S_ROT_KEY_SCR_OFF = 140;
    public static final int S_AM_KEY_MUL_OFF = 141;
    public static final int S_AM_KEY_SCR_OFF = 142;
    public static final int S_BS_PART_TARGET_IDX = 143;
    public static final int S_BS_PART_BINDING_OFF = 144;
    public static final int S_BS_PART_BINDING_LEN = 145;
    public static final int S_BS_ROT_TARGET_IDX = 146;
    public static final int S_BS_ROT_BINDING_OFF = 147;
    public static final int S_BS_ROT_BINDING_LEN = 148;
    public static final int S_BS_GLUE_TARGET_IDX = 149;
    public static final int S_BS_GLUE_BINDING_OFF = 150;
    public static final int S_BS_GLUE_BINDING_LEN = 151;
    public static final int S_PART_OFFSCREEN_IDX = 152;
    public static final int S_AM_BLEND_MODE = 153;
    public static final int S_OS_OWNER_IDX = 155;
    public static final int S_OS_DRAWABLE_FLAG = 156;
    public static final int S_OS_BLEND_MODE = 157;
    public static final int S_OS_MASK_OFF = 158;
    public static final int S_OS_MASK_LEN = 159;
    public static final int S_PART_KEY_KEY_IDX = 160;
    public static final int S_OS_KEY_OPACITY = 161;
    public static final int S_OS_KEY_MUL_OFF = 162;
    public static final int S_OS_KEY_SCR_OFF = 163;
    public static final int S_BS_OS_TARGET_IDX = 164;
    public static final int S_BS_OS_BINDING_OFF = 165;
    public static final int S_BS_OS_BINDING_LEN = 166;

    public final byte[] data;
    public final int version;
    public final int endianFlag;
    public final int[] offsets;

    // count_info fields (see PSM__SECTIONS_V30..V53 order)
    public final int cntParts;
    public final int cntDeformers;
    public final int cntWarps;
    public final int cntRotations;
    public final int cntArtMeshes;
    public final int cntParameters;
    public final int cntPartKeyforms;
    public final int cntWarpKeyforms;
    public final int cntRotationKeyforms;
    public final int cntArtMeshKeyforms;
    public final int cntKeyformPos;
    public final int cntKeyTableIdx;
    public final int cntBindings;
    public final int cntKeyTables;
    public final int cntKeys;
    public final int cntUvs;
    public final int cntIdx;
    public final int cntMasks;
    public final int cntDrawGroups;
    public final int cntDrawItems;
    public final int cntGlues;
    public final int cntGlueInfo;
    public final int cntGlueKeyforms;
    public final int cntKeyformMulColors;
    public final int cntKeyformScrColors;
    public final int cntBlendKeyTables;
    public final int cntBlendBindings;
    public final int cntBsWarps;
    public final int cntBsArtMeshes;
    public final int cntBsConstraintIdx;
    public final int cntBsConstraints;
    public final int cntBsConstraintVals;
    public final int cntBsParts;
    public final int cntBsRotations;
    public final int cntBsGlues;
    public final int cntOffscreens;
    public final int cntOffscreenKeyforms;
    public final int cntBsOffscreens;

    /** Parses a moc3 byte array. Throws IllegalArgumentException on bad data. */
    public Moc3(byte[] data) {
        this.data = data;
        if (data.length < 64) throw new IllegalArgumentException("MOC3 buffer too small");
        if (data[0] != 'M' || data[1] != 'O' || data[2] != 'C' || data[3] != '3') {
            throw new IllegalArgumentException("not a MOC3 file");
        }
        this.version = data[4] & 0xFF;
        this.endianFlag = data[5] & 0xFF;
        if (version < VER_30 || version > VER_53) {
            throw new IllegalArgumentException("unsupported MOC3 version: " + version);
        }
        if (endianFlag != 0 && endianFlag != 1) {
            throw new IllegalArgumentException("invalid MOC3 endian flag");
        }
        // Purism Core: needs_bswap = isLittleEndian != (endian_flag == 0).
        // On little-endian hosts endian_flag == 0 means native order.
        // All Cubism-toolchain produced files are little-endian (flag 0);
        // big-endian files would need byte swapping on every read.
        if (endianFlag != 0) {
            throw new IllegalArgumentException("big-endian MOC3 is not supported");
        }
        int nOffsets = version >= VER_53 ? 480 : 160;
        if (data.length < 64 + nOffsets * 4) throw new IllegalArgumentException("MOC3 offset table oob");
        this.offsets = new int[nOffsets];
        for (int i = 0; i < nOffsets; i++) {
            offsets[i] = readI32(64 + i * 4);
        }
        // count_info
        int ci = offsets[S_COUNT_INFO];
        int n = version >= VER_50 ? 64 : 32;
        checkRange(ci, n * 4, "count_info");
        int[] c = new int[n];
        for (int i = 0; i < n; i++) c[i] = readI32(ci + i * 4);
        this.cntParts = c[0];
        this.cntDeformers = c[1];
        this.cntWarps = c[2];
        this.cntRotations = c[3];
        this.cntArtMeshes = c[4];
        this.cntParameters = c[5];
        this.cntPartKeyforms = c[6];
        this.cntWarpKeyforms = c[7];
        this.cntRotationKeyforms = c[8];
        this.cntArtMeshKeyforms = c[9];
        this.cntKeyformPos = c[10];
        this.cntKeyTableIdx = c[11];
        this.cntBindings = c[12];
        this.cntKeyTables = c[13];
        this.cntKeys = c[14];
        this.cntUvs = c[15];
        this.cntIdx = c[16];
        this.cntMasks = c[17];
        this.cntDrawGroups = c[18];
        this.cntDrawItems = c[19];
        this.cntGlues = c[20];
        this.cntGlueInfo = c[21];
        this.cntGlueKeyforms = c[22];
        this.cntKeyformMulColors = c[23];
        this.cntKeyformScrColors = c[24];
        this.cntBlendKeyTables = c[25];
        this.cntBlendBindings = c[26];
        this.cntBsWarps = c[27];
        this.cntBsArtMeshes = c[28];
        this.cntBsConstraintIdx = c[29];
        this.cntBsConstraints = c[30];
        this.cntBsConstraintVals = c[31];
        this.cntBsParts = version >= VER_50 ? c[32] : 0;
        this.cntBsRotations = version >= VER_50 ? c[33] : 0;
        this.cntBsGlues = version >= VER_50 ? c[34] : 0;
        this.cntOffscreens = version >= VER_50 ? c[35] : 0;
        this.cntOffscreenKeyforms = version >= VER_50 ? c[36] : 0;
        this.cntBsOffscreens = version >= VER_50 ? c[37] : 0;
    }

    // ------------------------------------------------------------------ reads

    private void checkRange(int off, int len, String what) {
        if (off < 0 || len < 0 || off > data.length || data.length - off < len) {
            throw new IllegalArgumentException("MOC3 section oob: " + what + " off=" + off + " len=" + len);
        }
    }

    public int readI32(int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16) | ((data[off + 3] & 0xFF) << 24);
    }

    public int readU16(int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }

    public float readF32(int off) {
        return Float.intBitsToFloat(readI32(off));
    }

    /** Reads a whole i32 section. */
    public int[] i32Section(int section, int count) {
        int off = offsets[section];
        checkRange(off, count * 4, "section " + section);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = readI32(off + i * 4);
        return out;
    }

    public float[] f32Section(int section, int count) {
        int off = offsets[section];
        checkRange(off, count * 4, "section " + section);
        float[] out = new float[count];
        for (int i = 0; i < count; i++) out[i] = readF32(off + i * 4);
        return out;
    }

    public byte[] u8Section(int section, int count) {
        int off = offsets[section];
        checkRange(off, count, "section " + section);
        byte[] out = new byte[count];
        System.arraycopy(data, off, out, 0, count);
        return out;
    }

    public int[] u16Section(int section, int count) {
        int off = offsets[section];
        checkRange(off, count * 2, "section " + section);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = readU16(off + i * 2);
        return out;
    }

    /** Reads the ID block at {@code section}[index]; IDs are inline 64-byte strings. */
    public String idAt(int section, int index) {
        int off = offsets[section] + index * 64;
        checkRange(off, 64, "id");
        int end = off;
        while (end < off + 64 && data[end] != 0) end++;
        return new String(data, off, end - off, StandardCharsets.UTF_8);
    }

    /** Reads a u32 pointer at {@code section}[index]. */
    public int ptrAt(int section, int index) {
        return readI32(offsets[section] + index * 4);
    }

    public boolean yReversed() {
        int off = offsets[S_CANVAS_INFO];
        checkRange(off, 24, "canvas");
        return (data[off + 20] & 1) != 0;
    }

    public float canvasPixelsPerUnit() {
        return readF32(offsets[S_CANVAS_INFO]);
    }

    public float canvasOriginX() {
        return readF32(offsets[S_CANVAS_INFO] + 4);
    }

    public float canvasOriginY() {
        return readF32(offsets[S_CANVAS_INFO] + 8);
    }

    public float canvasWidth() {
        return readF32(offsets[S_CANVAS_INFO] + 12);
    }

    public float canvasHeight() {
        return readF32(offsets[S_CANVAS_INFO] + 16);
    }
}