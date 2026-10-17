package com.ciallo.live2d.cubism;

/**
 * Pure Java re-implementation of the Live2D Cubism Core model runtime.
 *
 * Ported 1:1 from Sakura Motion Purism Core (MIT), specifically
 * model.c / update.c / param.c / part.c / interpolate.c / deformer.c /
 * artmesh.c / glue.c / blendshape.c / offscreen.c / render.c.
 *
 * All native pointers are replaced by flat Java arrays plus integer offsets.
 * Floating point math keeps the exact operation order of the C code so results
 * match the reference implementation.
 */
public final class Live2dModel {

    // ------------------------------------------------------------------ flags
    public static final int FLAG_IS_VISIBLE = 0x01;
    public static final int FLAG_VISIBILITY_CHANGED = 0x02;
    public static final int FLAG_OPACITY_CHANGED = 0x04;
    public static final int FLAG_DRAW_ORDER_CHANGED = 0x08;
    public static final int FLAG_RENDER_ORDER_CHANGED = 0x10;
    public static final int FLAG_VERTEX_CHANGED = 0x20;
    public static final int FLAG_BLEND_COLOR_CHANGED = 0x40;
    public static final int FLAG_ALL_CHANGED = 0x7E;
    public static final int FLAG_ALL = 0x7F;

    public static final int DEFORMER_TYPE_WARP = 0;
    public static final int DEFORMER_TYPE_ROTATION = 1;

    public static final int PARAM_TYPE_NORMAL = 0;
    public static final int PARAM_TYPE_BLEND_SHAPE = 1;

    public static final int MAX_KEY_TABLES = 20;

    private static final float PI = 3.14159265358979323846f;
    private static final float TWO_PI = 6.28318530717958647692f;

    public final Moc3 moc;
    public final int version;

    // Source arrays are immutable; cache them so update() allocates nothing per frame.
    private final java.util.HashMap<Integer, int[]> i32Cache = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, float[]> f32Cache = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, int[]> u16Cache = new java.util.HashMap<>();

    private int[] srcI32(int section, int count) {
        return i32Cache.computeIfAbsent(section, s -> moc.i32Section(section, count));
    }

    private float[] srcF32(int section, int count) {
        return f32Cache.computeIfAbsent(section, s -> moc.f32Section(section, count));
    }

    private int[] srcU16(int section, int count) {
        return u16Cache.computeIfAbsent(section, s -> moc.u16Section(section, count));
    }

    // ================================================================ parameters
    public int paramCount;
    public String[] paramIds;
    public int[] paramType;
    public float[] paramMin, paramMax, paramDefault, paramRange, paramValue, paramInput;
    public float[] paramSnapEps, paramInterpEps;
    public boolean[] paramRepeat, paramDirty;
    public int[] paramKeyTableOff, paramKeyTableLen;        // resolved key-table slice
    public int[] paramBlendKeyTableOff, paramBlendKeyTableLen;

    // ================================================================ key tables
    public int keyTableCount;
    public KeyTable[] keyTables;
    public float[] keys; // flat key pool

    // ================================================================ bindings
    public int bindingCount;
    public Binding[] bindings;
    public int[] bindingKeyformIdx;  // flat
    public float[] bindingWeights;   // flat

    // ================================================================ parts
    public int partCount;
    public Part[] parts;
    public boolean[] partEnable;
    public float[] partOpacity, partInputOpacity;
    public int[] partDrawOrder;
    public int[] partOffscreenSrcIdx;
    public Interp partInterp;
    public float[] partKeyDrawOrder;

    // ================================================================ deformers
    public int deformerCount;
    public DeformerNode[] deformerNodes;
    public boolean[] deformerEnable;
    public float[] deformerOpacity, deformerScale;
    public float[] deformerMulColor, deformerScrColor; // 4 per deformer

    public int warpCount;
    public Warp[] warps;
    public boolean[] warpEnable;
    public float[] warpOpacity;
    public float[][] warpPos;
    public float[] warpMulColor, warpScrColor;
    public Interp warpInterp;
    public float[] warpKeyOpacity;
    public int[] warpKeyPosOff;
    public float[] warpKeyMulR, warpKeyMulG, warpKeyMulB, warpKeyScrR, warpKeyScrG, warpKeyScrB;

    public int rotationCount;
    public Rotation[] rotations;
    public boolean[] rotationEnable;
    public float[] rotationOpacity, rotationScale, rotationOriginX, rotationOriginY, rotationAngle;
    public int[] rotationReflectX, rotationReflectY;
    public float[] rotationMulColor, rotationScrColor;
    public Interp rotationInterp;
    public float[] rotationKeyOpacity, rotationKeyAngle, rotationKeyOriginX, rotationKeyOriginY, rotationKeyScale;
    public float[] rotationKeyMulR, rotationKeyMulG, rotationKeyMulB, rotationKeyScrR, rotationKeyScrG, rotationKeyScrB;

    // ================================================================ art meshes
    public int artMeshCount;
    public ArtMesh[] artMeshes;
    public boolean[] artMeshEnable;
    public byte[] artMeshConstFlags, artMeshChangeFlags;
    public int[] artMeshBlendMode, artMeshDrawOrder;
    public float[][] artMeshPos;
    public float[] artMeshOpacity;
    public float[] artMeshMulColor, artMeshScrColor;
    public int[] artMeshLastRenderOrder, artMeshLastDrawOrder;
    public float[] artMeshLastOpacity, artMeshLastMulColor, artMeshLastScrColor;
    public Interp artMeshInterp;
    public float[] artMeshKeyOpacity, artMeshKeyDrawOrder;
    public int[] artMeshKeyPosOff;
    public float[] artMeshKeyMulR, artMeshKeyMulG, artMeshKeyMulB, artMeshKeyScrR, artMeshKeyScrG, artMeshKeyScrB;
    public boolean artMeshStateChanged;

    // source arrays (read-only)
    public int[] amUvOff, amIdxOff, amIdxLen, amMaskOff, amMaskLen, amTextureNo, amParentPartIdx, amParentDeformerIdx;
    public float[] uvs;
    public int[] indices; // u16 promoted

    // ================================================================ draw groups
    public int drawGroupCount;
    public DrawGroup[] drawGroups;
    public int[] renderOrder;

    // ================================================================ glues
    public int glueCount;
    public Glue[] glues;
    public Interp glueInterp;
    public float[] glueKeyIntensity, glueIntensity;

    // ================================================================ offscreen
    public int offscreenCount;
    public Offscreen[] offscreens;
    public boolean[] offscreenEnable;
    public float[] offscreenOpacity, offscreenMulColor, offscreenScrColor;
    public Interp offscreenInterp;
    public float[] offscreenKeyOpacity;
    public float[] offscreenKeyMulR, offscreenKeyMulG, offscreenKeyMulB, offscreenKeyScrR, offscreenKeyScrG, offscreenKeyScrB;

    // ================================================================ blendshapes
    public BlendKeyTable[] blendKeyTables;
    public BlendBinding[] blendBindings;
    public BlendConstraint[] blendConstraints;
    public BlendShape[] bsParts, bsWarps, bsRotations, bsArtMeshes, bsGlues, bsOffscreens;
    public int[] bsConstraintIdx;

    public boolean forceUpdate = true;
    public boolean yReversed;
    public int lastError = 0;

    // canvas
    public float canvasWidth, canvasHeight, canvasOriginX, canvasOriginY, pixelsPerUnit;

    // =================================================================== types

    public static final class KeyTable {
        public int keyOff;      // into keys[]
        public int keyCount;
        public int idx;
        public float weight;
        public boolean outOfRange = true;
        public boolean idxDirty = true;
        public boolean weightDirty = true;
    }

    public static final class Binding {
        public int[] keyTableIdx;   // indices into model.keyTables
        public int keyTableLen;
        public int maxBlend;
        public int blendCount;
        public int keyformIdxOff;   // into bindingKeyformIdx
        public int weightsOff;      // into bindingWeights
        public boolean idxDirty = true;
        public boolean weightDirty = true;
        public boolean outOfRange = true;
    }

    public static final class Interp {
        public int objectCount;
        public int[] maxBlend;      // per object
        public int[] blendCount;    // per object
        public int tmpLen;
        public float[] tmp;
        public float[] weights;
    }

    public static final class Part {
        public int bindingIdx;
        public int parentPartIdx;
        public boolean localEnable;
    }

    public static final class DeformerNode {
        public int bindingIdx;
        public int parentPartIdx;
        public int parentDeformerIdx;
        public int type;
        public int localIdx;
        public boolean localEnable;
    }

    public static final class Warp {
        public int bindingIdx;
        public int row, col;
        public boolean quadTransform;
        public int vertexCount;
    }

    public static final class Rotation {
        public int bindingIdx;
        public float baseAngle;
    }

    public static final class ArtMesh {
        public int bindingIdx;
        public int parentPartIdx;
        public int parentDeformerIdx;
        public boolean localEnable;
        public int vertexCount;
    }

    public static final class DrawItem {
        public int objectType;
        public int objectIdx;
        public int groupIdx;
        public int drawOrder;
    }

    public static final class DrawGroup {
        public int totalCount, count, cursor, maxOrder, minOrder, orderLevel;
        public DrawItem[] items;
        // sort scratch
        public int[] first, last, next;
    }

    public static final class Glue {
        public int bindingIdx;
        public int meshIdx0, meshIdx1;
        public int glueInfoCount;
        public boolean localEnable;
        public int weightsOff;  // into glueInfoWeight
        public int posIdxOff;   // into glueInfoPosIdx
    }

    public static final class Offscreen {
        public int bindingIdx;
        public int ownerPartIdx;
        public int keyformIdx;   // part_key_key_idx value
    }

    public static final class BlendKeyTable {
        public int keyOff;
        public int keyCount;
        public int baseKeyIdx;
        public int idx;
        public float weight;
        public boolean idxDirty = true;
        public boolean weightDirty = true;
    }

    public static final class BlendConstraint {
        public int paramIdx;
        public int keysOff;    // into bsConstraintValKey
        public int weightsOff; // into bsConstraintValWeight
        public int count;
        public float weight = 1.0f;
    }

    public static final class BlendBinding {
        public int keyTableIdx;   // into blendKeyTables
        public int keySrcOff;
        public int blendCount;
        public final int[] keyformIdx = new int[2];
        public final float[] weights = new float[2];
        public boolean idxDirty = true;
        public boolean weightDirty = true;
        public int constraintCount;
        public int[] constraints; // indices into blendConstraints
        public float weight = 1.0f;
    }

    public static final class BlendShape {
        public int targetIdx;
        public int bindingCount;
        public int bindingOff; // into blendBindings
    }

    // glue source pools
    public float[] glueInfoWeight;
    public int[] glueInfoPosIdx;

    // bs constraint value pools
    public float[] bsConstraintValKey, bsConstraintValWeight;

    // =================================================================== load

    public Live2dModel(Moc3 moc) {
        this.moc = moc;
        this.version = moc.version;
        init();
        update();
    }

    private void init() {
        this.canvasWidth = moc.canvasWidth() / Math.max(1.0f, moc.canvasPixelsPerUnit());
        this.canvasHeight = moc.canvasHeight() / Math.max(1.0f, moc.canvasPixelsPerUnit());
        this.canvasOriginX = moc.canvasOriginX() / Math.max(1.0f, moc.canvasPixelsPerUnit());
        this.canvasOriginY = moc.canvasOriginY() / Math.max(1.0f, moc.canvasPixelsPerUnit());
        this.pixelsPerUnit = moc.canvasPixelsPerUnit();
        this.yReversed = moc.yReversed();

        initParameters();
        initKeyTables();
        initBindings();
        initParts();
        initDeformers();
        initArtMeshes();
        initDrawGroups();
        initGlues();
        initOffscreens();
        initBlendShapes();
        initColors();
        initRenderOrders();
        this.forceUpdate = true;
    }

    private void initParameters() {
        int n = moc.cntParameters;
        paramCount = n;
        paramIds = new String[n];
        paramType = new int[n];
        paramMin = srcF32(Moc3.S_PARAM_MIN, n);
        paramMax = srcF32(Moc3.S_PARAM_MAX, n);
        paramDefault = srcF32(Moc3.S_PARAM_DEFAULT, n);
        paramValue = new float[n];
        paramInput = new float[n];
        paramRange = new float[n];
        paramSnapEps = new float[n];
        paramInterpEps = new float[n];
        paramRepeat = new boolean[n];
        paramDirty = new boolean[n];
        paramKeyTableOff = new int[n];
        paramKeyTableLen = new int[n];
        paramBlendKeyTableOff = new int[n];
        paramBlendKeyTableLen = new int[n];
        int[] repeat = srcI32(Moc3.S_PARAM_REPEAT, n);
        int[] dec = srcI32(Moc3.S_PARAM_DECIMAL_PLACES, n);
        int[] ktOff = srcI32(Moc3.S_PARAM_KEY_TABLE_OFF, n);
        int[] ktLen = srcI32(Moc3.S_PARAM_KEY_TABLE_LEN, n);
        int[] types = version >= Moc3.VER_42 ? srcI32(Moc3.S_PARAM_TYPE, n) : null;
        for (int i = 0; i < n; i++) {
            paramIds[i] = moc.idAt(Moc3.S_PARAM_ID, i);
            paramType[i] = types != null ? types[i] : PARAM_TYPE_NORMAL;
            paramRange[i] = paramMax[i] - paramMin[i];
            paramRepeat[i] = repeat[i] != 0;
            float snap = (float) Math.pow(0.1f, dec[i]);
            paramSnapEps[i] = snap;
            paramInterpEps[i] = snap * 1.5f;
            // key table binding, validated
            if (ktOff[i] >= 0 && ktLen[i] >= 0 && ktOff[i] <= moc.cntKeyTables
                    && moc.cntKeyTables - ktOff[i] >= ktLen[i]) {
                paramKeyTableOff[i] = ktOff[i];
                paramKeyTableLen[i] = ktLen[i];
            } else {
                paramKeyTableOff[i] = 0;
                paramKeyTableLen[i] = 0;
            }
            paramValue[i] = paramDefault[i];
            paramInput[i] = paramValue[i];
            paramDirty[i] = true;
        }
        // v4.2+ blend key tables per parameter
        if (version >= Moc3.VER_42) {
            int[] bOff = srcI32(Moc3.S_PARAM_BLEND_KEY_TABLE_OFF, n);
            int[] bLen = srcI32(Moc3.S_PARAM_BLEND_KEY_TABLE_LEN, n);
            for (int i = 0; i < n; i++) {
                if (bOff[i] >= 0 && bLen[i] >= 0 && bOff[i] <= moc.cntBlendKeyTables
                        && moc.cntBlendKeyTables - bOff[i] >= bLen[i]) {
                    paramBlendKeyTableOff[i] = bOff[i];
                    paramBlendKeyTableLen[i] = bLen[i];
                }
            }
        }
    }

    private void initKeyTables() {
        int n = moc.cntKeyTables;
        keyTableCount = n;
        keyTables = new KeyTable[n];
        keys = srcF32(Moc3.S_KEYS, moc.cntKeys);
        int[] kOff = srcI32(Moc3.S_KEY_TABLE_KEYS_OFF, n);
        int[] kLen = srcI32(Moc3.S_KEY_TABLE_KEYS_LEN, n);
        for (int i = 0; i < n; i++) {
            KeyTable kt = new KeyTable();
            kt.keyOff = kOff[i];
            kt.keyCount = kLen[i];
            kt.outOfRange = true;
            keyTables[i] = kt;
        }
    }

    private void initBindings() {
        int n = moc.cntBindings;
        bindingCount = n;
        bindings = new Binding[n];
        int[] idxOff = srcI32(Moc3.S_BINDING_KEY_TABLE_IDX_OFF, n);
        int[] idxLen = srcI32(Moc3.S_BINDING_KEY_TABLE_IDX_LEN, n);
        int[] idxPool = srcI32(Moc3.S_KEY_TABLE_IDX, moc.cntKeyTableIdx);
        int totalIdx = 0;
        for (int i = 0; i < n; i++) {
            int bc = Math.max(0, Math.min(idxLen[i], MAX_KEY_TABLES));
            totalIdx += 1 << bc;
        }
        bindingKeyformIdx = new int[totalIdx];
        bindingWeights = new float[totalIdx];
        int cursor = 0;
        for (int i = 0; i < n; i++) {
            Binding b = new Binding();
            int bc = Math.max(0, Math.min(idxLen[i], MAX_KEY_TABLES));
            b.keyTableLen = bc;
            b.maxBlend = 1 << bc;
            b.keyformIdxOff = cursor;
            b.weightsOff = cursor;
            cursor += b.maxBlend;
            b.keyTableIdx = new int[bc];
            if (idxOff[i] >= 0 && idxLen[i] >= 0 && idxOff[i] <= moc.cntKeyTableIdx
                    && moc.cntKeyTableIdx - idxOff[i] >= idxLen[i]) {
                for (int j = 0; j < bc; j++) {
                    int idx = idxPool[idxOff[i] + j];
                    b.keyTableIdx[j] = (idx >= 0 && idx < keyTableCount) ? idx : -1;
                }
            }
            b.idxDirty = true;
            b.weightDirty = true;
            b.outOfRange = true;
            bindings[i] = b;
        }
    }

    private void initParts() {
        int n = moc.cntParts;
        partCount = n;
        parts = new Part[n];
        int[] bind = srcI32(Moc3.S_PART_BINDING_IDX, n);
        int[] parent = srcI32(Moc3.S_PART_PARENT_PART_IDX, n);
        int[] enable = srcI32(Moc3.S_PART_ENABLE, n);
        int[] visible = srcI32(Moc3.S_PART_VISIBLE, n);
        partEnable = new boolean[n];
        partOpacity = new float[n];
        partInputOpacity = new float[n];
        partDrawOrder = new int[n];
        partOffscreenSrcIdx = new int[n];
        for (int i = 0; i < n; i++) {
            Part p = new Part();
            p.bindingIdx = (bind[i] >= 0 && bind[i] < bindingCount) ? bind[i] : -1;
            p.parentPartIdx = parent[i];
            p.localEnable = enable[i] != 0;
            parts[i] = p;
            partInputOpacity[i] = visible[i] != 0 ? 1.0f : 0.0f;
            partOffscreenSrcIdx[i] = -1;
        }
        if (version >= Moc3.VER_53) {
            int[] osIdx = srcI32(Moc3.S_PART_OFFSCREEN_IDX, n);
            System.arraycopy(osIdx, 0, partOffscreenSrcIdx, 0, n);
        }
        partInterp = new Interp();
        partInterp.objectCount = n;
        partInterp.maxBlend = new int[n];
        partInterp.blendCount = new int[n];
        int tmp = 0;
        for (int i = 0; i < n; i++) {
            int mc = parts[i].bindingIdx >= 0 ? bindings[parts[i].bindingIdx].maxBlend : 0;
            partInterp.maxBlend[i] = mc;
            tmp += mc;
        }
        partInterp.tmpLen = tmp;
        partInterp.tmp = new float[tmp];
        partInterp.weights = new float[tmp];
        partKeyDrawOrder = new float[tmp];
    }

    private void initDeformers() {
        int n = moc.cntDeformers;
        deformerCount = n;
        deformerNodes = new DeformerNode[n];
        deformerEnable = new boolean[n];
        deformerOpacity = new float[n];
        deformerScale = new float[n];
        deformerMulColor = new float[4 * n];
        deformerScrColor = new float[4 * n];
        int[] bind = srcI32(Moc3.S_DEFORMER_BINDING_IDX, n);
        int[] pp = srcI32(Moc3.S_DEFORMER_PARENT_PART_IDX, n);
        int[] pd = srcI32(Moc3.S_DEFORMER_PARENT_DEFORMER_IDX, n);
        int[] type = srcI32(Moc3.S_DEFORMER_TYPE, n);
        int[] local = srcI32(Moc3.S_DEFORMER_LOCAL_IDX, n);
        int[] en = srcI32(Moc3.S_DEFORMER_ENABLE, n);
        for (int i = 0; i < n; i++) {
            DeformerNode d = new DeformerNode();
            d.bindingIdx = (bind[i] >= 0 && bind[i] < bindingCount) ? bind[i] : -1;
            d.parentPartIdx = pp[i];
            d.parentDeformerIdx = pd[i];
            d.type = type[i];
            d.localIdx = local[i];
            d.localEnable = en[i] != 0;
            deformerNodes[i] = d;
        }

        // ---- warps
        int w = moc.cntWarps;
        warpCount = w;
        warps = new Warp[w];
        warpEnable = new boolean[w];
        warpOpacity = new float[w];
        warpPos = new float[w][];
        warpMulColor = new float[4 * w];
        warpScrColor = new float[4 * w];
        int[] wbind = srcI32(Moc3.S_WARP_BINDING_IDX, w);
        int[] wrow = srcI32(Moc3.S_WARP_ROW, w);
        int[] wcol = srcI32(Moc3.S_WARP_COL, w);
        int[] wvc = srcI32(Moc3.S_WARP_VERTEX_COUNT, w);
        int[] wquad = version >= Moc3.VER_33 ? srcI32(Moc3.S_WARP_QUAD_TRANSFORM, w) : null;
        warpInterp = new Interp();
        warpInterp.objectCount = w;
        warpInterp.maxBlend = new int[w];
        warpInterp.blendCount = new int[w];
        int tmp = 0;
        for (int i = 0; i < w; i++) {
            Warp o = new Warp();
            o.bindingIdx = (wbind[i] >= 0 && wbind[i] < bindingCount) ? wbind[i] : -1;
            o.row = wrow[i];
            o.col = wcol[i];
            o.vertexCount = wvc[i];
            o.quadTransform = wquad != null && wquad[i] != 0;
            warps[i] = o;
            warpPos[i] = new float[2 * Math.max(0, wvc[i])];
            int mc = o.bindingIdx >= 0 ? bindings[o.bindingIdx].maxBlend : 0;
            warpInterp.maxBlend[i] = mc;
            tmp += mc;
        }
        warpInterp.tmpLen = tmp;
        warpInterp.tmp = new float[tmp];
        warpInterp.weights = new float[tmp];
        warpKeyOpacity = new float[tmp];
        warpKeyPosOff = new int[tmp];
        warpKeyMulR = new float[tmp];
        warpKeyMulG = new float[tmp];
        warpKeyMulB = new float[tmp];
        warpKeyScrR = new float[tmp];
        warpKeyScrG = new float[tmp];
        warpKeyScrB = new float[tmp];

        // ---- rotations
        int r = moc.cntRotations;
        rotationCount = r;
        rotations = new Rotation[r];
        rotationEnable = new boolean[r];
        rotationOpacity = new float[r];
        rotationScale = new float[r];
        rotationOriginX = new float[r];
        rotationOriginY = new float[r];
        rotationAngle = new float[r];
        rotationReflectX = new int[r];
        rotationReflectY = new int[r];
        rotationMulColor = new float[4 * r];
        rotationScrColor = new float[4 * r];
        int[] rbind = srcI32(Moc3.S_ROT_BINDING_IDX, r);
        float[] rbase = srcF32(Moc3.S_ROT_BASE_ANGLE, r);
        rotationInterp = new Interp();
        rotationInterp.objectCount = r;
        rotationInterp.maxBlend = new int[r];
        rotationInterp.blendCount = new int[r];
        tmp = 0;
        for (int i = 0; i < r; i++) {
            Rotation o = new Rotation();
            o.bindingIdx = (rbind[i] >= 0 && rbind[i] < bindingCount) ? rbind[i] : -1;
            o.baseAngle = rbase[i];
            rotations[i] = o;
            int mc = o.bindingIdx >= 0 ? bindings[o.bindingIdx].maxBlend : 0;
            rotationInterp.maxBlend[i] = mc;
            tmp += mc;
        }
        rotationInterp.tmpLen = tmp;
        rotationInterp.tmp = new float[tmp];
        rotationInterp.weights = new float[tmp];
        rotationKeyOpacity = new float[tmp];
        rotationKeyAngle = new float[tmp];
        rotationKeyOriginX = new float[tmp];
        rotationKeyOriginY = new float[tmp];
        rotationKeyScale = new float[tmp];
        rotationKeyMulR = new float[tmp];
        rotationKeyMulG = new float[tmp];
        rotationKeyMulB = new float[tmp];
        rotationKeyScrR = new float[tmp];
        rotationKeyScrG = new float[tmp];
        rotationKeyScrB = new float[tmp];
    }

    private void initArtMeshes() {
        int n = moc.cntArtMeshes;
        artMeshCount = n;
        artMeshes = new ArtMesh[n];
        artMeshEnable = new boolean[n];
        artMeshConstFlags = moc.u8Section(Moc3.S_AM_DRAWABLE_FLAG, n);
        artMeshChangeFlags = new byte[n];
        artMeshBlendMode = new int[n];
        artMeshDrawOrder = new int[n];
        artMeshPos = new float[n][];
        artMeshOpacity = new float[n];
        artMeshMulColor = new float[4 * n];
        artMeshScrColor = new float[4 * n];
        artMeshLastRenderOrder = new int[n];
        artMeshLastDrawOrder = new int[n];
        artMeshLastOpacity = new float[n];
        artMeshLastMulColor = new float[4 * n];
        artMeshLastScrColor = new float[4 * n];

        int[] bind = srcI32(Moc3.S_AM_BINDING_IDX, n);
        int[] pp = srcI32(Moc3.S_AM_PARENT_PART_IDX, n);
        int[] pd = srcI32(Moc3.S_AM_PARENT_DEFORMER_IDX, n);
        int[] vc = srcI32(Moc3.S_AM_VERTEX_COUNT, n);
        int[] en = srcI32(Moc3.S_AM_ENABLE, n);
        amTextureNo = srcI32(Moc3.S_AM_TEXTURE_NO, n);
        amUvOff = srcI32(Moc3.S_AM_UV_OFF, n);
        amIdxOff = srcI32(Moc3.S_AM_IDX_OFF, n);
        amIdxLen = srcI32(Moc3.S_AM_IDX_LEN, n);
        amMaskOff = srcI32(Moc3.S_AM_MASK_OFF, n);
        amMaskLen = srcI32(Moc3.S_AM_MASK_LEN, n);
        amParentPartIdx = pp;
        amParentDeformerIdx = pd;
        uvs = srcF32(Moc3.S_UVS, moc.cntUvs);
        indices = srcU16(Moc3.S_IDX, moc.cntIdx);

        // Purism Core parity (moc3.c revive): when the canvas is not stored
        // y-reversed the loader flips triangle winding and texture V so the
        // per-frame flipped vertex Y stays consistent for renderers.
        if (!yReversed) {
            for (int i = 0; i < n; i++) {
                int uvOff = amUvOff[i];
                // artMeshes is still null here — use vertex-count section directly
                int vCount = vc[i];
                if (uvOff < 0) continue;
                for (int j = 0; j < vCount; j++) {
                    int idx = uvOff + j * 2 + 1;
                    if (idx >= 0 && idx < uvs.length) uvs[idx] = 1.0f - uvs[idx];
                }
            }
            for (int i = 0; i < n; i++) {
                int io = amIdxOff[i];
                int il = amIdxLen[i];
                if (io < 0 || il <= 0) continue;
                for (int j = 0; j + 2 < il; j += 3) {
                    int a = io + j, b = io + j + 2;
                    if (a >= 0 && b < indices.length) {
                        int tmp = indices[a];
                        indices[a] = indices[b];
                        indices[b] = tmp;
                    }
                }
            }
        }

        int[] blendModes = version >= Moc3.VER_53 ? srcI32(Moc3.S_AM_BLEND_MODE, n) : null;

        artMeshInterp = new Interp();
        artMeshInterp.objectCount = n;
        artMeshInterp.maxBlend = new int[n];
        artMeshInterp.blendCount = new int[n];
        int tmp = 0;
        for (int i = 0; i < n; i++) {
            ArtMesh a = new ArtMesh();
            a.bindingIdx = (bind[i] >= 0 && bind[i] < bindingCount) ? bind[i] : -1;
            a.parentPartIdx = pp[i];
            a.parentDeformerIdx = pd[i];
            a.vertexCount = vc[i];
            a.localEnable = en[i] != 0;
            artMeshes[i] = a;
            artMeshPos[i] = new float[2 * Math.max(0, vc[i])];
            int mc = a.bindingIdx >= 0 ? bindings[a.bindingIdx].maxBlend : 0;
            artMeshInterp.maxBlend[i] = mc;
            tmp += mc;
            if (version < Moc3.VER_53) {
                int flag = artMeshConstFlags[i] & 0xFF;
                int mode = (flag & 2) != 0 ? 2 : 0; // MultiplyCompatible : Normal
                if ((flag & 1) != 0) mode = 1;      // AddCompatible
                artMeshBlendMode[i] = mode;
            } else {
                artMeshBlendMode[i] = blendModes[i];
            }
        }
        artMeshInterp.tmpLen = tmp;
        artMeshInterp.tmp = new float[tmp];
        artMeshInterp.weights = new float[tmp];
        artMeshKeyOpacity = new float[tmp];
        artMeshKeyDrawOrder = new float[tmp];
        artMeshKeyPosOff = new int[tmp];
        artMeshKeyMulR = new float[tmp];
        artMeshKeyMulG = new float[tmp];
        artMeshKeyMulB = new float[tmp];
        artMeshKeyScrR = new float[tmp];
        artMeshKeyScrG = new float[tmp];
        artMeshKeyScrB = new float[tmp];
    }

    private void initDrawGroups() {
        int n = moc.cntDrawGroups;
        drawGroupCount = n;
        drawGroups = new DrawGroup[n];
        if (n == 0) {
            return;
        }
        int[] objOff = srcI32(Moc3.S_DG_OBJ_OFF, n);
        int[] objLen = srcI32(Moc3.S_DG_OBJ_LEN, n);
        int[] objTotal = srcI32(Moc3.S_DG_OBJ_TOTAL_COUNT, n);
        int[] maxOrder = srcI32(Moc3.S_DG_MAX_ORDER, n);
        int[] minOrder = srcI32(Moc3.S_DG_MIN_ORDER, n);
        int[] types = srcI32(Moc3.S_DGO_TYPE, moc.cntDrawItems);
        int[] idx = srcI32(Moc3.S_DGO_IDX, moc.cntDrawItems);
        int[] self = srcI32(Moc3.S_DGO_SELF_GROUP_IDX, moc.cntDrawItems);
        for (int i = 0; i < n; i++) {
            DrawGroup g = new DrawGroup();
            g.totalCount = objTotal[i];
            g.maxOrder = maxOrder[i];
            g.minOrder = minOrder[i];
            g.orderLevel = safeOrderLevel(maxOrder[i], minOrder[i]);
            g.cursor = 0;
            int count = objLen[i];
            int off = objOff[i];
            if (count > 0 && off >= 0 && off <= moc.cntDrawItems && moc.cntDrawItems - off >= count) {
                g.count = count;
                g.items = new DrawItem[count];
                for (int j = 0; j < count; j++) {
                    DrawItem it = new DrawItem();
                    it.objectType = types[off + j];
                    it.objectIdx = idx[off + j];
                    it.groupIdx = self[off + j];
                    it.drawOrder = 0;
                    g.items[j] = it;
                }
            } else {
                g.count = 0;
                g.items = null;
            }
            drawGroups[i] = g;
        }
        // sort scratch buffers (max sizes)
        int maxLevel = 0, maxItems = 0;
        for (int i = 0; i < n; i++) {
            if (drawGroups[i].orderLevel > maxLevel) maxLevel = drawGroups[i].orderLevel;
            if (drawGroups[i].count > maxItems) maxItems = drawGroups[i].count;
        }
        for (int i = 0; i < n; i++) {
            drawGroups[i].first = new int[Math.max(1, maxLevel)];
            drawGroups[i].last = new int[Math.max(1, maxLevel)];
            drawGroups[i].next = new int[Math.max(1, maxItems)];
        }
    }

    private void initGlues() {
        int n = moc.cntGlues;
        glueCount = n;
        glues = new Glue[n];
        if (n == 0) {
            glueInterp = new Interp();
            glueInterp.objectCount = 0;
            glueInterp.maxBlend = new int[0];
            glueInterp.blendCount = new int[0];
            glueInterp.tmp = new float[0];
            glueInterp.weights = new float[0];
            glueKeyIntensity = new float[0];
            glueIntensity = new float[0];
            glueInfoWeight = new float[0];
            glueInfoPosIdx = new int[0];
            return;
        }
        int[] bind = srcI32(Moc3.S_GLUE_BINDING_IDX, n);
        int[] mA = srcI32(Moc3.S_GLUE_MESH_A, n);
        int[] mB = srcI32(Moc3.S_GLUE_MESH_B, n);
        int[] iOff = srcI32(Moc3.S_GLUE_INFO_OFF, n);
        int[] iLen = srcI32(Moc3.S_GLUE_INFO_LEN, n);
        glueInfoWeight = srcF32(Moc3.S_GLUE_INFO_WEIGHT, moc.cntGlueInfo);
        int[] posIdx16 = srcU16(Moc3.S_GLUE_INFO_POS_IDX, moc.cntGlueInfo);
        glueInfoPosIdx = posIdx16;
        glueInterp = new Interp();
        glueInterp.objectCount = n;
        glueInterp.maxBlend = new int[n];
        glueInterp.blendCount = new int[n];
        int tmp = 0;
        for (int i = 0; i < n; i++) {
            Glue g = new Glue();
            g.bindingIdx = (bind[i] >= 0 && bind[i] < bindingCount) ? bind[i] : -1;
            g.meshIdx0 = mA[i];
            g.meshIdx1 = mB[i];
            g.glueInfoCount = iLen[i];
            g.weightsOff = iOff[i];
            g.posIdxOff = iOff[i];
            g.localEnable = true;
            glues[i] = g;
            int mc = g.bindingIdx >= 0 ? bindings[g.bindingIdx].maxBlend : 0;
            glueInterp.maxBlend[i] = mc;
            tmp += mc;
        }
        glueInterp.tmpLen = tmp;
        glueInterp.tmp = new float[tmp];
        glueInterp.weights = new float[tmp];
        glueKeyIntensity = new float[tmp];
        glueIntensity = new float[tmp];
    }

    private void initOffscreens() {
        int n = moc.cntOffscreens;
        offscreenCount = n;
        if (n == 0) {
            offscreens = new Offscreen[0];
            offscreenEnable = new boolean[0];
            offscreenOpacity = new float[0];
            offscreenMulColor = new float[0];
            offscreenScrColor = new float[0];
            offscreenInterp = new Interp();
            offscreenInterp.objectCount = 0;
            offscreenInterp.maxBlend = new int[0];
            offscreenInterp.blendCount = new int[0];
            offscreenInterp.tmp = new float[0];
            offscreenInterp.weights = new float[0];
            offscreenKeyOpacity = new float[0];
            offscreenKeyMulR = new float[0];
            offscreenKeyMulG = new float[0];
            offscreenKeyMulB = new float[0];
            offscreenKeyScrR = new float[0];
            offscreenKeyScrG = new float[0];
            offscreenKeyScrB = new float[0];
            return;
        }
        offscreens = new Offscreen[n];
        offscreenEnable = new boolean[n];
        offscreenOpacity = new float[n];
        offscreenMulColor = new float[4 * n];
        offscreenScrColor = new float[4 * n];
        int[] owner = srcI32(Moc3.S_OS_OWNER_IDX, n);
        int[] partKeyKeyIdx = srcI32(Moc3.S_PART_KEY_KEY_IDX, moc.cntPartKeyforms);
        int[] partBindingIdx = srcI32(Moc3.S_PART_BINDING_IDX, moc.cntParts);
        int[] partKeyformOff = srcI32(Moc3.S_PART_KEYFORM_OFF, moc.cntParts);
        offscreenInterp = new Interp();
        offscreenInterp.objectCount = n;
        offscreenInterp.maxBlend = new int[n];
        offscreenInterp.blendCount = new int[n];
        int tmp = 0;
        for (int i = 0; i < n; i++) {
            Offscreen o = new Offscreen();
            int oi = owner[i];
            if (oi >= 0 && oi < partCount) {
                o.ownerPartIdx = oi;
                o.bindingIdx = (partBindingIdx[oi] >= 0 && partBindingIdx[oi] < bindingCount)
                        ? partBindingIdx[oi] : -1;
                int kbi = partKeyformOff[oi];
                if (kbi >= 0 && kbi < moc.cntPartKeyforms) {
                    o.keyformIdx = partKeyKeyIdx[kbi];
                } else {
                    o.keyformIdx = -1;
                }
            } else {
                o.ownerPartIdx = -1;
                o.bindingIdx = -1;
                o.keyformIdx = -1;
            }
            offscreens[i] = o;
            int mc = o.bindingIdx >= 0 ? bindings[o.bindingIdx].maxBlend : 0;
            offscreenInterp.maxBlend[i] = mc;
            tmp += mc;
        }
        offscreenInterp.tmpLen = tmp;
        offscreenInterp.tmp = new float[tmp];
        offscreenInterp.weights = new float[tmp];
        offscreenKeyOpacity = new float[tmp];
        offscreenKeyMulR = new float[tmp];
        offscreenKeyMulG = new float[tmp];
        offscreenKeyMulB = new float[tmp];
        offscreenKeyScrR = new float[tmp];
        offscreenKeyScrG = new float[tmp];
        offscreenKeyScrB = new float[tmp];
        for (int i = 0; i < 4 * n; i++) {
            offscreenMulColor[i] = 1.0f;
            offscreenScrColor[i] = 1.0f;
        }
    }

    private void initBlendShapes() {
        if (version < Moc3.VER_42) {
            blendKeyTables = new BlendKeyTable[0];
            blendBindings = new BlendBinding[0];
            blendConstraints = new BlendConstraint[0];
            bsParts = new BlendShape[0];
            bsWarps = new BlendShape[0];
            bsRotations = new BlendShape[0];
            bsArtMeshes = new BlendShape[0];
            bsGlues = new BlendShape[0];
            bsOffscreens = new BlendShape[0];
            bsConstraintIdx = new int[0];
            bsConstraintValKey = new float[0];
            bsConstraintValWeight = new float[0];
            return;
        }
        int nkt = moc.cntBlendKeyTables;
        blendKeyTables = new BlendKeyTable[nkt];
        int[] kOff = srcI32(Moc3.S_BLEND_KEY_TABLE_KEYS_OFF, nkt);
        int[] kLen = srcI32(Moc3.S_BLEND_KEY_TABLE_KEYS_LEN, nkt);
        int[] base = srcI32(Moc3.S_BLEND_KEY_TABLE_BASE_KEY_IDX, nkt);
        for (int i = 0; i < nkt; i++) {
            BlendKeyTable t = new BlendKeyTable();
            if (kOff[i] >= 0 && kLen[i] >= 0 && kOff[i] <= moc.cntKeys && moc.cntKeys - kOff[i] >= kLen[i]) {
                t.keyOff = kOff[i];
                t.keyCount = kLen[i];
            } else {
                t.keyOff = 0;
                t.keyCount = 0;
            }
            t.baseKeyIdx = base[i];
            blendKeyTables[i] = t;
        }

        int nbc = moc.cntBsConstraints;
        blendConstraints = new BlendConstraint[nbc];
        int[] cParam = srcI32(Moc3.S_BS_CONSTR_PARAM_IDX, nbc);
        int[] cOff = srcI32(Moc3.S_BS_CONSTR_VALUE_OFF, nbc);
        int[] cLen = srcI32(Moc3.S_BS_CONSTR_VALUE_LEN, nbc);
        bsConstraintValKey = srcF32(Moc3.S_BS_CONSTR_VAL_KEY, moc.cntBsConstraintVals);
        bsConstraintValWeight = srcF32(Moc3.S_BS_CONSTR_VAL_WEIGHT, moc.cntBsConstraintVals);
        for (int i = 0; i < nbc; i++) {
            BlendConstraint c = new BlendConstraint();
            c.paramIdx = (cParam[i] >= 0 && cParam[i] < paramCount) ? cParam[i] : -1;
            if (cOff[i] >= 0 && cLen[i] >= 0 && cOff[i] <= moc.cntBsConstraintVals
                    && moc.cntBsConstraintVals - cOff[i] >= cLen[i]) {
                c.keysOff = cOff[i];
                c.weightsOff = cOff[i];
                c.count = cLen[i];
            }
            blendConstraints[i] = c;
        }

        int nbb = moc.cntBlendBindings;
        blendBindings = new BlendBinding[nbb];
        int[] bbKt = srcI32(Moc3.S_BLEND_BINDING_KEY_TABLE_IDX, nbb);
        int[] bbOff = srcI32(Moc3.S_BLEND_BINDING_KEY_BS_OFF, nbb);
        int[] bbConstrOff = srcI32(Moc3.S_BLEND_BINDING_CONSTR_IDX_OFF, nbb);
        int[] bbConstrLen = srcI32(Moc3.S_BLEND_BINDING_CONSTR_IDX_LEN, nbb);
        bsConstraintIdx = srcI32(Moc3.S_BS_CONSTR_IDX, moc.cntBsConstraintIdx);
        for (int i = 0; i < nbb; i++) {
            BlendBinding b = new BlendBinding();
            b.keyTableIdx = (bbKt[i] >= 0 && bbKt[i] < nkt) ? bbKt[i] : -1;
            b.keySrcOff = bbOff[i];
            b.blendCount = 0;
            b.idxDirty = true;
            b.weightDirty = true;
            b.weight = 1.0f;
            if (bbConstrLen[i] > 0 && bbConstrOff[i] >= 0
                    && bbConstrOff[i] <= moc.cntBsConstraintIdx
                    && moc.cntBsConstraintIdx - bbConstrOff[i] >= bbConstrLen[i]) {
                b.constraintCount = bbConstrLen[i];
                b.constraints = new int[b.constraintCount];
                for (int j = 0; j < b.constraintCount; j++) {
                    int ci = bsConstraintIdx[bbConstrOff[i] + j];
                    b.constraints[j] = (ci >= 0 && ci < nbc) ? ci : -1;
                }
            } else {
                b.constraintCount = 0;
                b.constraints = new int[0];
            }
            blendBindings[i] = b;
        }

        bsWarps = loadShapes(Moc3.S_BS_WARP_TARGET_IDX, Moc3.S_BS_WARP_BINDING_OFF, Moc3.S_BS_WARP_BINDING_LEN, moc.cntBsWarps);
        bsArtMeshes = loadShapes(Moc3.S_BS_AM_TARGET_IDX, Moc3.S_BS_AM_BINDING_OFF, Moc3.S_BS_AM_BINDING_LEN, moc.cntBsArtMeshes);
        if (version >= Moc3.VER_50) {
            bsParts = loadShapes(Moc3.S_BS_PART_TARGET_IDX, Moc3.S_BS_PART_BINDING_OFF, Moc3.S_BS_PART_BINDING_LEN, moc.cntBsParts);
            bsRotations = loadShapes(Moc3.S_BS_ROT_TARGET_IDX, Moc3.S_BS_ROT_BINDING_OFF, Moc3.S_BS_ROT_BINDING_LEN, moc.cntBsRotations);
            bsGlues = loadShapes(Moc3.S_BS_GLUE_TARGET_IDX, Moc3.S_BS_GLUE_BINDING_OFF, Moc3.S_BS_GLUE_BINDING_LEN, moc.cntBsGlues);
        } else {
            bsParts = new BlendShape[0];
            bsRotations = new BlendShape[0];
            bsGlues = new BlendShape[0];
        }
        if (version >= Moc3.VER_53) {
            bsOffscreens = loadShapes(Moc3.S_BS_OS_TARGET_IDX, Moc3.S_BS_OS_BINDING_OFF, Moc3.S_BS_OS_BINDING_LEN, moc.cntBsOffscreens);
        } else {
            bsOffscreens = new BlendShape[0];
        }
    }

    private BlendShape[] loadShapes(int sTarget, int sOff, int sLen, int count) {
        BlendShape[] out = new BlendShape[count];
        if (count == 0) return out;
        int[] target = srcI32(sTarget, count);
        int[] off = srcI32(sOff, count);
        int[] len = srcI32(sLen, count);
        for (int i = 0; i < count; i++) {
            BlendShape s = new BlendShape();
            s.targetIdx = target[i];
            s.bindingCount = len[i];
            if (len[i] >= 0 && off[i] >= 0 && off[i] <= blendBindings.length
                    && blendBindings.length - off[i] >= len[i]) {
                s.bindingOff = off[i];
            } else {
                s.bindingOff = -1;
                s.bindingCount = 0;
            }
            out[i] = s;
        }
        return out;
    }

    private void initColors() {
        for (int i = 0; i < 4 * deformerCount; i++) {
            deformerMulColor[i] = 1.0f;
        }
        for (int i = 0; i < 4 * warpCount; i++) {
            warpMulColor[i] = 1.0f;
        }
        for (int i = 0; i < 4 * rotationCount; i++) {
            rotationMulColor[i] = 1.0f;
        }
        for (int i = 0; i < 4 * artMeshCount; i++) {
            artMeshMulColor[i] = 1.0f;
            artMeshLastMulColor[i] = 1.0f;
        }
        // v4.2+ keyform colors are pre-linked in C; here they are gathered lazily
        // in gatherWarp/gatherRotation/gatherArtMesh directly from source pools.
    }

    private void initRenderOrders() {
        renderOrder = new int[artMeshCount + offscreenCount];
        for (int i = 0; i < artMeshCount; i++) {
            renderOrder[i] = i;
        }
    }

    // =================================================================== update

    public void update() {
        lastError = 0;
        saveFlags();
        resolveParams();
        resolveKeyTables();
        resolveBlendKeyTables();
        resolveBindings();
        resolveBlendBindings();

        for (int i = 0; i < partCount; i++) {
            partInputOpacity[i] = clamp01(partInputOpacity[i]);
        }

        enableParts();
        gatherParts();
        interpParts();

        enableDeformers();
        gatherWarps();
        gatherRotations();
        interpWarps();
        interpRotations();

        enableArtMeshes();
        gatherArtMeshes();
        interpArtMeshes();

        gatherGlues();
        interpGlues();

        enableOffscreens();
        gatherOffscreens();
        interpOffscreens();

        blendParts();
        blendWarps();
        blendRotations();
        blendArtMeshes();
        blendGlues();
        blendOffscreens();

        applyTransforms();
        applyTransformsToMeshes();
        applyPartOpacity();
        applyPartsToMeshes();

        applyGlues();

        reverseY();
        sortRenderOrder();
        updateFlags();

        if (version >= Moc3.VER_53) {
            for (int i = 0; i < offscreenCount; i++) {
                if (!offscreenEnable[i]) offscreenOpacity[i] = 0.0f;
            }
        }
        forceUpdate = false;
    }

    // ------------------------------------------------------------- parameters

    private void resolveParams() {
        for (int i = 0; i < paramCount; i++) {
            float userValue = paramInput[i];
            float newValue;
            if (paramRepeat[i]) {
                float rangeMin = paramMin[i], rangeLength = paramRange[i];
                float normalized = (userValue - rangeMin) / rangeLength;
                float wrapped = normalized - (float) Math.floor(normalized);
                newValue = wrapped * rangeLength + rangeMin;
                if (paramValue[i] != newValue) {
                    paramValue[i] = newValue;
                    paramDirty[i] = true;
                } else {
                    paramDirty[i] = false;
                }
            } else {
                float rangeMin = paramMin[i], rangeMax = paramMax[i];
                if (userValue < rangeMin || userValue > rangeMax) {
                    lastError = 2; // parameter range
                }
                newValue = clamp(userValue, rangeMin, rangeMax);
                if (paramValue[i] != newValue) {
                    paramValue[i] = newValue;
                    paramDirty[i] = true;
                } else {
                    paramDirty[i] = false;
                }
                paramInput[i] = newValue;
            }
        }
    }

    private void resolveKeyTables() {
        for (int i = 0; i < paramCount; i++) {
            if (paramType[i] != PARAM_TYPE_NORMAL) continue;
            if (!paramDirty[i] && !forceUpdate) {
                int bc = paramKeyTableLen[i];
                int base = paramKeyTableOff[i];
                for (int j = 0; j < bc; j++) {
                    KeyTable kt = keyTables[base + j];
                    kt.idxDirty = false;
                    kt.weightDirty = false;
                }
                continue;
            }
            int bindingCountLocal = paramKeyTableLen[i];
            if (bindingCountLocal <= 0) continue;
            float value = paramValue[i];
            float snapEps = paramSnapEps[i], interpEps = paramInterpEps[i];
            int base = paramKeyTableOff[i];
            for (int j = 0; j < bindingCountLocal; j++) {
                KeyTable binding = keyTables[base + j];
                if (binding.keyCount <= 0) continue;
                KeySearchResult r = findKeySegment(value, binding.keyOff, binding.keyCount, snapEps, interpEps);
                boolean idxDirty, weightDirty;
                if (!r.isOutside && binding.outOfRange) {
                    r.isOutside = false;
                    idxDirty = true;
                    weightDirty = true;
                } else {
                    idxDirty = binding.idx != r.index;
                    weightDirty = binding.weight != r.weight;
                    if (weightDirty) {
                        idxDirty = r.weight == 0.0f || binding.weight == 0.0f || idxDirty;
                    }
                }
                binding.idxDirty = idxDirty;
                binding.weightDirty = weightDirty;
                binding.idx = r.index;
                binding.weight = r.weight;
                binding.outOfRange = r.isOutside;
            }
        }
    }

    private static final class KeySearchResult {
        int index;
        float weight;
        boolean isOutside;
    }

    private KeySearchResult findKeySegment(float value, int keyOff, int keyCount, float snapEps, float interpEps) {
        KeySearchResult r = new KeySearchResult();
        if (keyCount <= 0) {
            return r;
        }
        if (keyCount == 1) {
            float key0 = keys[keyOff];
            r.isOutside = (value <= key0 - snapEps) || (value >= key0 + snapEps);
            return r;
        }
        float key0 = keys[keyOff], key1;
        if (value < key0 - snapEps) {
            r.isOutside = true;
            return r;
        }
        if (value < key0 + snapEps) {
            return r;
        }
        key1 = keys[keyOff + 1];
        if (value < key1 - snapEps) {
            float keyDiff = key1 - key0;
            if (keyDiff >= interpEps) r.weight = (value - key0) / keyDiff;
            return r;
        }
        if (value < key1 + snapEps) {
            r.index = 1;
            return r;
        }
        for (int k = 2; k < keyCount; k++) {
            key0 = key1;
            key1 = keys[keyOff + k];
            if (value < key1 - snapEps) {
                r.index = k - 1;
                float keyDiff = key1 - key0;
                if (keyDiff >= interpEps) r.weight = (value - key0) / keyDiff;
                return r;
            }
            if (value < key1 + snapEps) {
                r.index = k;
                return r;
            }
        }
        r.index = keyCount - 1;
        r.isOutside = true;
        return r;
    }

    private void resolveBlendKeyTables() {
        if (version < Moc3.VER_42) return;
        for (int i = 0; i < paramCount; i++) {
            if (paramType[i] != PARAM_TYPE_BLEND_SHAPE) continue;
            int bsCount = paramBlendKeyTableLen[i];
            if (bsCount <= 0) continue;
            float value = paramValue[i];
            if (forceUpdate || paramDirty[i]) {
                for (int j = 0; j < bsCount; j++) {
                    BlendKeyTable t = blendKeyTables[paramBlendKeyTableOff[i] + j];
                    int keyCount = t.keyCount;
                    int index = 0;
                    float weight = 0.0f;
                    if (keyCount >= 2) {
                        if (value > keys[t.keyOff]) {
                            for (index = 1; index < keyCount && value >= keys[t.keyOff + index]; index++) {
                            }
                            index--;
                            if (index < keyCount - 1) {
                                weight = (value - keys[t.keyOff + index])
                                        / (keys[t.keyOff + index + 1] - keys[t.keyOff + index]);
                            }
                        }
                    }
                    int oldIndex = t.idx;
                    float oldWeight = t.weight;
                    boolean idxDirty = oldIndex != index;
                    boolean weightDirty = oldWeight != weight;
                    if (weightDirty) {
                        idxDirty = weight == 0.0f || oldWeight == 0.0f || oldIndex != index;
                    }
                    t.idxDirty = idxDirty;
                    t.weightDirty = weightDirty;
                    t.weight = weight;
                    t.idx = index;
                }
            } else {
                for (int j = 0; j < bsCount; j++) {
                    BlendKeyTable t = blendKeyTables[paramBlendKeyTableOff[i] + j];
                    t.idxDirty = false;
                    t.weightDirty = false;
                }
            }
        }
    }

    private void resolveBindings() {
        for (int bi = 0; bi < bindingCount; bi++) {
            Binding b = bindings[bi];
            int bindingCountLocal = b.keyTableLen;
            boolean idxDirty = false;
            boolean weightDirty = false;
            boolean outOfRange = false;
            int activeBindingCount = 0;

            for (int i = 0; i < bindingCountLocal; i++) {
                int ktIdx = b.keyTableIdx[i];
                if (ktIdx < 0 || ktIdx >= keyTableCount) {
                    outOfRange = true;
                    break;
                }
                KeyTable binding = keyTables[ktIdx];
                if (binding.outOfRange) {
                    outOfRange = true;
                    idxDirty = false;
                    weightDirty = false;
                    break;
                }
                weightDirty |= binding.weightDirty;
                idxDirty |= binding.idxDirty;
                if (binding.weight != 0.0f) {
                    activeBindingCount++;
                }
            }

            if (outOfRange) {
                b.idxDirty = false;
                b.weightDirty = false;
                b.outOfRange = true;
                continue;
            }

            if (forceUpdate) {
                idxDirty = true;
                weightDirty = true;
            }

            if (!(idxDirty || weightDirty)) {
                b.idxDirty = false;
                b.weightDirty = false;
                b.outOfRange = false;
                continue;
            }

            int blendCount = 1 << activeBindingCount;
            b.blendCount = blendCount;

            int off = b.keyformIdxOff;
            for (int j = 0; j < blendCount; j++) {
                bindingKeyformIdx[off + j] = 0;
            }
            for (int j = 0; j < blendCount; j++) {
                bindingWeights[b.weightsOff + j] = 1.0f;
            }

            int indexStride = 1, comboStride = 1;
            for (int i = 0; i < bindingCountLocal; i++) {
                int ktIdx = b.keyTableIdx[i];
                KeyTable binding = keyTables[ktIdx];
                int index = binding.idx;
                int keyCount = binding.keyCount;
                float weight = binding.weight;
                int indexOffset = index * indexStride;

                if (weight != 0.0f) {
                    int nextIndexOffset = (index + 1) * indexStride;
                    float invWeight = 1.0f - weight;
                    for (int j = 0; j < blendCount; j++) {
                        if ((j & comboStride) == 0) {
                            bindingKeyformIdx[off + j] += indexOffset;
                            bindingWeights[b.weightsOff + j] *= invWeight;
                        } else {
                            bindingKeyformIdx[off + j] += nextIndexOffset;
                            bindingWeights[b.weightsOff + j] *= weight;
                        }
                    }
                    comboStride *= 2;
                } else {
                    for (int j = 0; j < blendCount; j++) {
                        bindingKeyformIdx[off + j] += indexOffset;
                    }
                }
                indexStride *= Math.max(1, keyCount);
            }

            b.idxDirty = idxDirty;
            b.weightDirty = weightDirty;
            b.outOfRange = false;
        }
    }

    private void resolveBlendBindings() {
        if (version < Moc3.VER_42) return;
        for (int bi = 0; bi < blendBindings.length; bi++) {
            BlendBinding b = blendBindings[bi];
            if (b.keyTableIdx < 0) continue;
            BlendKeyTable binding = blendKeyTables[b.keyTableIdx];

            boolean weightDirty = forceUpdate || binding.weightDirty;
            boolean idxDirty = forceUpdate || binding.idxDirty;

            if (weightDirty || idxDirty) {
                float weight = binding.weight;
                int baseKeyIdx = binding.baseKeyIdx;
                int nextKeyIndex = binding.idx;

                if (weight != 0.0f && nextKeyIndex == baseKeyIdx) {
                    idxDirty = true;
                    weightDirty = true;
                    b.blendCount = 1;
                    b.weights[0] = weight;
                    b.weights[1] = 1.0f - weight;
                    nextKeyIndex++;
                    b.keyformIdx[0] = nextKeyIndex;
                    b.keyformIdx[1] = nextKeyIndex + 1;
                } else {
                    if (weight == 0.0f) {
                        b.blendCount = nextKeyIndex != baseKeyIdx ? 1 : 0;
                    } else {
                        b.blendCount = (nextKeyIndex + 1 == baseKeyIdx) ? 1 : 2;
                    }
                    if (weightDirty) {
                        b.weights[0] = 1.0f - weight;
                        b.weights[1] = weight;
                    }
                    if (idxDirty) {
                        b.keyformIdx[0] = nextKeyIndex;
                        b.keyformIdx[1] = nextKeyIndex + 1;
                    }
                }
            }

            int constraintCount = b.constraintCount;
            float weight = 1.0f;
            for (int i = 0; i < constraintCount; i++) {
                int ci = b.constraints[i];
                if (ci < 0 || ci >= blendConstraints.length) continue;
                BlendConstraint constraint = blendConstraints[ci];
                int paramIdx = constraint.paramIdx;
                float constraintWeight = 1.0f;
                if (paramIdx >= 0 && (forceUpdate || paramDirty[paramIdx])) {
                    int keyCount = constraint.count;
                    float[] cKeys = bsConstraintValKey;
                    float[] cWeights = bsConstraintValWeight;
                    int ko = constraint.keysOff;
                    int wo = constraint.weightsOff;
                    if (keyCount >= 2) {
                        float value = paramValue[paramIdx];
                        if (value > cKeys[ko]) {
                            int idx;
                            for (idx = 1; idx < keyCount && value >= cKeys[ko + idx]; idx++) {
                            }
                            idx--;
                            if (idx < keyCount - 1) {
                                float t = (value - cKeys[ko + idx]) / (cKeys[ko + idx + 1] - cKeys[ko + idx]);
                                constraintWeight = cWeights[wo + idx] * (1.0f - t) + cWeights[wo + idx + 1] * t;
                            } else {
                                constraintWeight = cWeights[wo + keyCount - 1];
                            }
                        } else {
                            constraintWeight = cWeights[wo];
                        }
                    } else if (keyCount == 1) {
                        constraintWeight = cWeights[wo];
                    }
                    constraint.weight = constraintWeight;
                } else if (paramIdx >= 0) {
                    constraintWeight = constraint.weight;
                } else {
                    constraint.weight = constraintWeight;
                }
                weight = Math.min(weight, constraintWeight);
            }

            b.weight = weight;
            b.idxDirty = idxDirty;
            b.weightDirty = weightDirty;
        }
    }

    // ------------------------------------------------------------- parts

    private void enableParts() {
        for (int i = 0; i < partCount; i++) {
            Part part = parts[i];
            boolean en = part.localEnable;
            if (en && part.parentPartIdx != -1 && part.parentPartIdx >= 0 && part.parentPartIdx < partCount) {
                en = partEnable[part.parentPartIdx];
            }
            if (en && part.bindingIdx >= 0) {
                en = !bindings[part.bindingIdx].outOfRange;
            } else if (en) {
                en = false;
            }
            partEnable[i] = en;
        }
    }

    private void gatherParts() {
        if (partCount <= 0) return;
        int[] keyformOff = srcI32(Moc3.S_PART_KEYFORM_OFF, partCount);
        float[] drawOrderSrc = srcF32(Moc3.S_PART_KEY_DRAW_ORDER, moc.cntPartKeyforms);
        int offset = 0;
        for (int i = 0; i < partCount; i++) {
            int bi = parts[i].bindingIdx;
            if (bi < 0) continue;
            Binding b = bindings[bi];
            int cc = b.blendCount;
            if (b.idxDirty || b.weightDirty) {
                partInterp.blendCount[i] = cc;
            }
            if (b.idxDirty && cc > 0) {
                for (int j = 0; j < cc; j++) {
                    int kfi = bindingKeyformIdx[b.keyformIdxOff + j] + keyformOff[i];
                    partKeyDrawOrder[offset + j] = drawOrderSrc[kfi];
                }
            }
            if (b.weightDirty && cc > 0) {
                System.arraycopy(bindingWeights, b.weightsOff, partInterp.weights, offset, cc);
            }
            offset += b.maxBlend;
        }
    }

    private void interpParts() {
        interpI32(partInterp, partKeyDrawOrder, partDrawOrder, partEnable);
    }

    private void applyPartOpacity() {
        for (int i = 0; i < partCount; i++) {
            if (!partEnable[i]) continue;
            float opacity = partInputOpacity[i];
            partOpacity[i] = opacity;
            int parentIndex = parts[i].parentPartIdx;
            if (parentIndex != -1 && parentIndex >= 0 && parentIndex < partCount
                    && partOffscreenSrcIdx[parentIndex] == -1) {
                opacity *= partOpacity[parentIndex];
                partOpacity[i] = opacity;
            }
            if (partOffscreenSrcIdx[i] != -1 && partOffscreenSrcIdx[i] < offscreenCount) {
                int offscreenIdx = partOffscreenSrcIdx[i];
                offscreenOpacity[offscreenIdx] *= opacity;
            }
        }
    }

    // ------------------------------------------------------------- deformers

    private void enableDeformers() {
        for (int i = 0; i < deformerCount; i++) {
            DeformerNode node = deformerNodes[i];
            boolean e = node.localEnable;
            int ppi = node.parentPartIdx;
            int pdi = node.parentDeformerIdx;
            if (e && ppi != -1 && ppi >= 0 && ppi < partCount) e = partEnable[ppi];
            if (e && pdi != -1 && pdi >= 0 && pdi < deformerCount) e = deformerEnable[pdi];
            if (e && node.bindingIdx >= 0) {
                e = !bindings[node.bindingIdx].outOfRange;
            } else if (e) {
                e = false;
            }
            deformerEnable[i] = e;
            int dt = node.type;
            int si = node.localIdx;
            if (dt == DEFORMER_TYPE_WARP) {
                if (si >= 0 && si < warpCount) warpEnable[si] = e;
            } else if (dt == DEFORMER_TYPE_ROTATION) {
                if (si >= 0 && si < rotationCount) rotationEnable[si] = e;
            }
        }
    }

    private void gatherWarps() {
        if (warpCount <= 0) return;
        int[] keyformOff = srcI32(Moc3.S_WARP_KEYFORM_OFF, warpCount);
        float[] opSrc = srcF32(Moc3.S_WARP_KEY_OPACITY, moc.cntWarpKeyforms);
        int[] posSrc = srcI32(Moc3.S_WARP_KEY_POS_OFF, moc.cntWarpKeyforms);
        float[] keyPos = srcF32(Moc3.S_KEY_POS_XY, moc.cntKeyformPos);
        boolean v42 = version >= Moc3.VER_42;
        float[] mulR = null, mulG = null, mulB = null, scrR = null, scrG = null, scrB = null;
        int[] colorOff = null;
        if (v42) {
            colorOff = srcI32(Moc3.S_WARP_KEY_COLOR_OFF, moc.cntWarpKeyforms);
            mulR = srcF32(Moc3.S_KEYFORM_MUL_R, moc.cntKeyformMulColors);
            mulG = srcF32(Moc3.S_KEYFORM_MUL_G, moc.cntKeyformMulColors);
            mulB = srcF32(Moc3.S_KEYFORM_MUL_B, moc.cntKeyformMulColors);
            scrR = srcF32(Moc3.S_KEYFORM_SCR_R, moc.cntKeyformScrColors);
            scrG = srcF32(Moc3.S_KEYFORM_SCR_G, moc.cntKeyformScrColors);
            scrB = srcF32(Moc3.S_KEYFORM_SCR_B, moc.cntKeyformScrColors);
        }
        int offset = 0;
        for (int i = 0; i < warpCount; i++) {
            int bi = warps[i].bindingIdx;
            if (bi < 0) continue;
            Binding b = bindings[bi];
            int cc = b.blendCount;
            if (b.idxDirty || b.weightDirty) {
                warpInterp.blendCount[i] = cc;
            }
            if (b.idxDirty && cc > 0) {
                for (int j = 0; j < cc; j++) {
                    int kfi = bindingKeyformIdx[b.keyformIdxOff + j] + keyformOff[i];
                    warpKeyOpacity[offset + j] = opSrc[kfi];
                    warpKeyPosOff[offset + j] = posSrc[kfi];
                }
            }
            if (b.weightDirty && cc > 0) {
                System.arraycopy(bindingWeights, b.weightsOff, warpInterp.weights, offset, cc);
            }
            if (v42 && colorOff != null && b.idxDirty && cc > 0) {
                for (int j = 0; j < cc; j++) {
                    int kfi = bindingKeyformIdx[b.keyformIdxOff + j] + keyformOff[i];
                    int ci = colorOff[kfi];
                    if (ci >= 0 && ci < mulR.length) {
                        warpKeyMulR[offset + j] = mulR[ci];
                        warpKeyMulG[offset + j] = mulG[ci];
                        warpKeyMulB[offset + j] = mulB[ci];
                    }
                    if (ci >= 0 && ci < scrR.length) {
                        warpKeyScrR[offset + j] = scrR[ci];
                        warpKeyScrG[offset + j] = scrG[ci];
                        warpKeyScrB[offset + j] = scrB[ci];
                    }
                }
            }
            offset += b.maxBlend;
        }
    }

    private void gatherRotations() {
        if (rotationCount <= 0) return;
        int[] keyformOff = srcI32(Moc3.S_ROT_KEYFORM_OFF, rotationCount);
        float[] opSrc = srcF32(Moc3.S_ROT_KEY_OPACITY, moc.cntRotationKeyforms);
        float[] angSrc = srcF32(Moc3.S_ROT_KEY_ANGLE, moc.cntRotationKeyforms);
        float[] oxSrc = srcF32(Moc3.S_ROT_KEY_ORIGIN_X, moc.cntRotationKeyforms);
        float[] oySrc = srcF32(Moc3.S_ROT_KEY_ORIGIN_Y, moc.cntRotationKeyforms);
        float[] scSrc = srcF32(Moc3.S_ROT_KEY_SCALE, moc.cntRotationKeyforms);
        int[] rfxSrc = srcI32(Moc3.S_ROT_KEY_REFLECT_X, moc.cntRotationKeyforms);
        int[] rfySrc = srcI32(Moc3.S_ROT_KEY_REFLECT_Y, moc.cntRotationKeyforms);
        boolean v42 = version >= Moc3.VER_42;
        float[] mulR = null, mulG = null, mulB = null, scrR = null, scrG = null, scrB = null;
        int[] colorOff = null;
        if (v42) {
            colorOff = srcI32(Moc3.S_ROT_KEY_COLOR_OFF, moc.cntRotationKeyforms);
            mulR = srcF32(Moc3.S_KEYFORM_MUL_R, moc.cntKeyformMulColors);
            mulG = srcF32(Moc3.S_KEYFORM_MUL_G, moc.cntKeyformMulColors);
            mulB = srcF32(Moc3.S_KEYFORM_MUL_B, moc.cntKeyformMulColors);
            scrR = srcF32(Moc3.S_KEYFORM_SCR_R, moc.cntKeyformScrColors);
            scrG = srcF32(Moc3.S_KEYFORM_SCR_G, moc.cntKeyformScrColors);
            scrB = srcF32(Moc3.S_KEYFORM_SCR_B, moc.cntKeyformScrColors);
        }
        int offset = 0;
        for (int i = 0; i < rotationCount; i++) {
            int bi = rotations[i].bindingIdx;
            if (bi < 0) continue;
            Binding b = bindings[bi];
            int cc = b.blendCount;
            if (b.idxDirty || b.weightDirty) {
                rotationInterp.blendCount[i] = cc;
            }
            if (b.idxDirty && cc > 0) {
                for (int j = 0; j < cc; j++) {
                    int kfi = bindingKeyformIdx[b.keyformIdxOff + j] + keyformOff[i];
                    rotationKeyOpacity[offset + j] = opSrc[kfi];
                    rotationKeyAngle[offset + j] = angSrc[kfi];
                    rotationKeyOriginX[offset + j] = oxSrc[kfi];
                    rotationKeyOriginY[offset + j] = oySrc[kfi];
                    rotationKeyScale[offset + j] = scSrc[kfi];
                }
                int kfi0 = bindingKeyformIdx[b.keyformIdxOff] + keyformOff[i];
                rotationReflectX[i] = rfxSrc[kfi0];
                rotationReflectY[i] = rfySrc[kfi0];
            }
            if (b.weightDirty && cc > 0) {
                System.arraycopy(bindingWeights, b.weightsOff, rotationInterp.weights, offset, cc);
            }
            if (v42 && colorOff != null && b.idxDirty && cc > 0) {
                for (int j = 0; j < cc; j++) {
                    int kfi = bindingKeyformIdx[b.keyformIdxOff + j] + keyformOff[i];
                    int ci = colorOff[kfi];
                    if (ci >= 0 && ci < mulR.length) {
                        rotationKeyMulR[offset + j] = mulR[ci];
                        rotationKeyMulG[offset + j] = mulG[ci];
                        rotationKeyMulB[offset + j] = mulB[ci];
                    }
                    if (ci >= 0 && ci < scrR.length) {
                        rotationKeyScrR[offset + j] = scrR[ci];
                        rotationKeyScrG[offset + j] = scrG[ci];
                        rotationKeyScrB[offset + j] = scrB[ci];
                    }
                }
            }
            offset += b.maxBlend;
        }
    }

    private void gatherArtMeshes() {
        if (artMeshCount <= 0) return;
        int[] keyformOff = srcI32(Moc3.S_AM_KEYFORM_OFF, artMeshCount);
        float[] opSrc = srcF32(Moc3.S_AM_KEY_OPACITY, moc.cntArtMeshKeyforms);
        float[] doSrc = srcF32(Moc3.S_AM_KEY_DRAW_ORDER, moc.cntArtMeshKeyforms);
        int[] posSrc = srcI32(Moc3.S_AM_KEY_POS_OFF, moc.cntArtMeshKeyforms);
        boolean v42 = version >= Moc3.VER_42;
        float[] mulR = null, mulG = null, mulB = null, scrR = null, scrG = null, scrB = null;
        int[] colorOff = null;
        if (v42) {
            colorOff = srcI32(Moc3.S_AM_KEY_COLOR_OFF, moc.cntArtMeshKeyforms);
            mulR = srcF32(Moc3.S_KEYFORM_MUL_R, moc.cntKeyformMulColors);
            mulG = srcF32(Moc3.S_KEYFORM_MUL_G, moc.cntKeyformMulColors);
            mulB = srcF32(Moc3.S_KEYFORM_MUL_B, moc.cntKeyformMulColors);
            scrR = srcF32(Moc3.S_KEYFORM_SCR_R, moc.cntKeyformScrColors);
            scrG = srcF32(Moc3.S_KEYFORM_SCR_G, moc.cntKeyformScrColors);
            scrB = srcF32(Moc3.S_KEYFORM_SCR_B, moc.cntKeyformScrColors);
        }
        int offset = 0;
        for (int i = 0; i < artMeshCount; i++) {
            int bi = artMeshes[i].bindingIdx;
            if (bi < 0) continue;
            Binding b = bindings[bi];
            int cc = b.blendCount;
            if (b.idxDirty || b.weightDirty) {
                artMeshInterp.blendCount[i] = cc;
            }
            if (b.idxDirty && cc > 0) {
                for (int j = 0; j < cc; j++) {
                    int kfi = bindingKeyformIdx[b.keyformIdxOff + j] + keyformOff[i];
                    artMeshKeyOpacity[offset + j] = opSrc[kfi];
                    artMeshKeyDrawOrder[offset + j] = doSrc[kfi];
                    artMeshKeyPosOff[offset + j] = posSrc[kfi];
                }
            }
            if (b.weightDirty && cc > 0) {
                System.arraycopy(bindingWeights, b.weightsOff, artMeshInterp.weights, offset, cc);
            }
            if (v42 && colorOff != null && b.idxDirty && cc > 0) {
                for (int j = 0; j < cc; j++) {
                    int kfi = bindingKeyformIdx[b.keyformIdxOff + j] + keyformOff[i];
                    int ci = colorOff[kfi];
                    if (ci >= 0 && ci < mulR.length) {
                        artMeshKeyMulR[offset + j] = mulR[ci];
                        artMeshKeyMulG[offset + j] = mulG[ci];
                        artMeshKeyMulB[offset + j] = mulB[ci];
                    }
                    if (ci >= 0 && ci < scrR.length) {
                        artMeshKeyScrR[offset + j] = scrR[ci];
                        artMeshKeyScrG[offset + j] = scrG[ci];
                        artMeshKeyScrB[offset + j] = scrB[ci];
                    }
                }
            }
            offset += b.maxBlend;
        }
    }

    private void interpWarps() {
        if (warpCount <= 0) return;
        Interp ip = warpInterp;
        interpF32(ip, warpKeyOpacity, warpOpacity, 1, warpEnable);
        // positions
        float[] keyPos = srcF32(Moc3.S_KEY_POS_XY, moc.cntKeyformPos);
        interpF32ArrayOffsets(ip, warpKeyPosOff, warpPos, keyPos, warpEnable);
        if (version < Moc3.VER_42) return;
        interpColors(ip, warpKeyMulR, warpKeyMulG, warpKeyMulB, warpKeyScrR, warpKeyScrG, warpKeyScrB,
                warpMulColor, warpScrColor, warpEnable);
    }

    private void interpRotations() {
        if (rotationCount <= 0) return;
        Interp ip = rotationInterp;
        interpF32(ip, rotationKeyOpacity, rotationOpacity, 1, rotationEnable);
        interpF32(ip, rotationKeyAngle, rotationAngle, 1, rotationEnable);
        interpF32(ip, rotationKeyOriginX, rotationOriginX, 1, rotationEnable);
        interpF32(ip, rotationKeyOriginY, rotationOriginY, 1, rotationEnable);
        interpF32(ip, rotationKeyScale, rotationScale, 1, rotationEnable);
        if (version < Moc3.VER_42) return;
        interpColors(ip, rotationKeyMulR, rotationKeyMulG, rotationKeyMulB, rotationKeyScrR, rotationKeyScrG, rotationKeyScrB,
                rotationMulColor, rotationScrColor, rotationEnable);
    }

    private void interpArtMeshes() {
        if (artMeshCount <= 0) return;
        Interp ip = artMeshInterp;
        interpF32(ip, artMeshKeyOpacity, artMeshOpacity, 1, artMeshEnable);
        interpI32(ip, artMeshKeyDrawOrder, artMeshDrawOrder, artMeshEnable);
        float[] keyPos = srcF32(Moc3.S_KEY_POS_XY, moc.cntKeyformPos);
        interpF32ArrayOffsets(ip, artMeshKeyPosOff, artMeshPos, keyPos, artMeshEnable);
        if (version < Moc3.VER_42) return;
        interpColors(ip, artMeshKeyMulR, artMeshKeyMulG, artMeshKeyMulB, artMeshKeyScrR, artMeshKeyScrG, artMeshKeyScrB,
                artMeshMulColor, artMeshScrColor, artMeshEnable);
    }

    // ------------------------------------------------------------- glue

    private void gatherGlues() {
        if (glueCount <= 0) return;
        int[] keyformOff = srcI32(Moc3.S_GLUE_KEYFORM_OFF, glueCount);
        float[] intSrc = srcF32(Moc3.S_GLUE_KEY_INTENSITY, moc.cntGlueKeyforms);
        int offset = 0;
        for (int i = 0; i < glueCount; i++) {
            int bi = glues[i].bindingIdx;
            if (bi < 0) continue;
            Binding b = bindings[bi];
            int cc = b.blendCount;
            if (b.idxDirty || b.weightDirty) {
                glueInterp.blendCount[i] = cc;
            }
            if (b.idxDirty && cc > 0) {
                for (int j = 0; j < cc; j++) {
                    int kfi = bindingKeyformIdx[b.keyformIdxOff + j] + keyformOff[i];
                    glueKeyIntensity[offset + j] = intSrc[kfi];
                }
            }
            if (b.weightDirty && cc > 0) {
                System.arraycopy(bindingWeights, b.weightsOff, glueInterp.weights, offset, cc);
            }
            offset += b.maxBlend;
        }
    }

    private void interpGlues() {
        if (glueCount <= 0) return;
        interpF32(glueInterp, glueKeyIntensity, glueIntensity, 1, null);
    }

    private void applyGlues() {
        if (glueCount <= 0) return;
        for (int gi = 0; gi < glueCount; gi++) {
            Glue glue = glues[gi];
            int ic = glue.glueInfoCount;
            if (ic <= 0) continue;
            int m0 = glue.meshIdx0, m1 = glue.meshIdx1;
            if (m0 < 0 || m0 >= artMeshCount || m1 < 0 || m1 >= artMeshCount) continue;
            float intensity = glueIntensity[gi];
            float[] p0 = artMeshPos[m0];
            float[] p1 = artMeshPos[m1];
            for (int i = 0; i + 1 < ic; i += 2) {
                int i0 = glueInfoPosIdx[glue.posIdxOff + i];
                int i1 = glueInfoPosIdx[glue.posIdxOff + i + 1];
                float w0 = glueInfoWeight[glue.weightsOff + i];
                float w1 = glueInfoWeight[glue.weightsOff + i + 1];
                if (i0 * 2 + 1 >= p0.length || i1 * 2 + 1 >= p1.length) continue;
                float ax = p0[i0 * 2], ay = p0[i0 * 2 + 1];
                float bx = p1[i1 * 2], by = p1[i1 * 2 + 1];
                float dx = bx - ax, dy = by - ay;
                p0[i0 * 2] = ax + dx * (intensity * w0);
                p0[i0 * 2 + 1] = ay + dy * (intensity * w0);
                p1[i1 * 2] = bx - dx * (intensity * w1);
                p1[i1 * 2 + 1] = by - dy * (intensity * w1);
            }
        }
    }

    // ------------------------------------------------------------- offscreen

    private void enableOffscreens() {
        if (version < Moc3.VER_53) return;
        for (int i = 0; i < offscreenCount; i++) {
            Offscreen o = offscreens[i];
            offscreenEnable[i] = o.ownerPartIdx >= 0 && o.ownerPartIdx < partCount && partEnable[o.ownerPartIdx];
        }
    }

    private void gatherOffscreens() {
        if (version < Moc3.VER_53 || offscreenCount <= 0) return;
        float[] opaSrc = srcF32(Moc3.S_OS_KEY_OPACITY, moc.cntOffscreenKeyforms);
        int[] colorOff = srcI32(Moc3.S_OS_KEY_MUL_OFF, moc.cntOffscreenKeyforms);
        float[] mulR = srcF32(Moc3.S_KEYFORM_MUL_R, moc.cntKeyformMulColors);
        float[] mulG = srcF32(Moc3.S_KEYFORM_MUL_G, moc.cntKeyformMulColors);
        float[] mulB = srcF32(Moc3.S_KEYFORM_MUL_B, moc.cntKeyformMulColors);
        float[] scrR = srcF32(Moc3.S_KEYFORM_SCR_R, moc.cntKeyformScrColors);
        float[] scrG = srcF32(Moc3.S_KEYFORM_SCR_G, moc.cntKeyformScrColors);
        float[] scrB = srcF32(Moc3.S_KEYFORM_SCR_B, moc.cntKeyformScrColors);
        int off = 0;
        for (int i = 0; i < offscreenCount; i++) {
            Offscreen o = offscreens[i];
            int bi = o.bindingIdx;
            if (bi < 0) continue;
            Binding b = bindings[bi];
            if (!b.idxDirty && !b.weightDirty) {
                off += b.maxBlend;
                continue;
            }
            int nc = b.blendCount;
            offscreenInterp.blendCount[i] = nc;
            if (b.idxDirty && nc > 0) {
                int ki = o.keyformIdx;
                if (ki >= 0 && ki < opaSrc.length) {
                    for (int j = 0; j < nc; j++) {
                        int idx = bindingKeyformIdx[b.keyformIdxOff + j] + ki;
                        if (idx >= 0 && idx < opaSrc.length) {
                            offscreenKeyOpacity[off + j] = opaSrc[idx];
                        }
                    }
                }
            }
            if (b.weightDirty && nc > 0) {
                System.arraycopy(bindingWeights, b.weightsOff, offscreenInterp.weights, off, nc);
            }
            if (b.idxDirty) {
                int ki = o.keyformIdx;
                int nc2 = b.blendCount;
                if (ki >= 0 && ki < colorOff.length && nc2 > 0) {
                    int cb = colorOff[ki];
                    if (cb >= 0) {
                        for (int j = 0; j < nc2; j++) {
                            int idx = bindingKeyformIdx[b.keyformIdxOff + j] + cb;
                            if (idx >= 0 && idx < mulR.length) {
                                offscreenKeyMulR[off + j] = mulR[idx];
                                offscreenKeyMulG[off + j] = mulG[idx];
                                offscreenKeyMulB[off + j] = mulB[idx];
                            }
                            if (idx >= 0 && idx < scrR.length) {
                                offscreenKeyScrR[off + j] = scrR[idx];
                                offscreenKeyScrG[off + j] = scrG[idx];
                                offscreenKeyScrB[off + j] = scrB[idx];
                            }
                        }
                    }
                }
            }
            off += b.maxBlend;
        }
    }

    private void interpOffscreens() {
        if (version < Moc3.VER_53 || offscreenCount <= 0) return;
        Interp ip = offscreenInterp;
        interpF32(ip, offscreenKeyOpacity, offscreenOpacity, 1, offscreenEnable);
        interpColors(ip, offscreenKeyMulR, offscreenKeyMulG, offscreenKeyMulB,
                offscreenKeyScrR, offscreenKeyScrG, offscreenKeyScrB,
                offscreenMulColor, offscreenScrColor, offscreenEnable);
    }

    // ------------------------------------------------------------- interpolation

    private void interpF32(Interp interp, float[] targets, float[] out, int outStride, boolean[] enable) {
        if (interp == null || targets == null || out == null) return;
        int objCount = interp.objectCount;
        if (objCount <= 0) return;
        int[] maxComb = interp.maxBlend;
        int[] comb = interp.blendCount;
        float[] wt = interp.weights;
        float[] tmp = interp.tmp;
        int tmpLen = interp.tmpLen;
        if (maxComb == null || comb == null || wt == null) return;

        if (tmp != null && tmpLen > 0) {
            for (int i = 0; i < tmpLen; i++) {
                tmp[i] = targets[i] * wt[i];
            }
        }
        int off = 0;
        for (int i = 0; i < objCount; i++) {
            int mc = maxComb[i];
            if (enable == null || enable[i]) {
                int n = comb[i];
                float sum = 0.0f;
                if (tmp != null) {
                    for (int j = 0; j < n; j++) {
                        sum += tmp[off + j];
                    }
                } else {
                    for (int j = 0; j < n; j++) {
                        sum += targets[off + j] * wt[off + j];
                    }
                }
                out[i * outStride] = sum;
            }
            off += mc;
        }
    }

    private void interpI32(Interp interp, float[] targets, int[] out, boolean[] enable) {
        if (interp == null || targets == null || out == null) return;
        int objCount = interp.objectCount;
        if (objCount <= 0) return;
        int[] maxComb = interp.maxBlend;
        int[] comb = interp.blendCount;
        float[] wt = interp.weights;
        float[] tmp = interp.tmp;
        int tmpLen = interp.tmpLen;
        if (maxComb == null || comb == null || wt == null) return;

        if (tmp != null && tmpLen > 0) {
            for (int i = 0; i < tmpLen; i++) {
                tmp[i] = targets[i] * wt[i];
            }
        }
        int off = 0;
        for (int i = 0; i < objCount; i++) {
            int mc = maxComb[i];
            if (enable == null || enable[i]) {
                int n = comb[i];
                float sum = 0.0f;
                if (tmp != null) {
                    for (int j = 0; j < n; j++) {
                        sum += tmp[off + j];
                    }
                } else {
                    for (int j = 0; j < n; j++) {
                        sum += targets[off + j] * wt[off + j];
                    }
                }
                out[i] = f32ToI32(sum + 0.001f);
            }
            off += mc;
        }
    }

    private void interpF32ArrayOffsets(Interp interp, int[] offsets, float[][] out, float[] pool, boolean[] enable) {
        if (interp == null || offsets == null || out == null) return;
        int objCount = interp.objectCount;
        int[] maxComb = interp.maxBlend;
        int[] comb = interp.blendCount;
        float[] wt = interp.weights;
        if (maxComb == null || comb == null || wt == null || objCount <= 0) return;

        int off = 0;
        for (int i = 0; i < objCount; i++) {
            int mc = maxComb[i];
            if (enable == null || enable[i]) {
                int total = out[i].length;
                if (total <= 0) {
                    off += mc;
                    continue;
                }
                int n = comb[i];
                float[] dst = out[i];
                java.util.Arrays.fill(dst, 0.0f);
                for (int j = 0; j < n; j++) {
                    float w = wt[off + j];
                    int src = offsets[off + j];
                    if (src < 0) continue;
                    for (int k = 0; k < total; k++) {
                        dst[k] += pool[src + k] * w;
                    }
                }
            }
            off += mc;
        }
    }

    private void interpColors(Interp interp, float[] mulR, float[] mulG, float[] mulB,
                              float[] scrR, float[] scrG, float[] scrB,
                              float[] mulOut, float[] scrOut, boolean[] enable) {
        interpF32Offset(interp, mulR, mulOut, 4, 0, enable);
        interpF32Offset(interp, mulG, mulOut, 4, 1, enable);
        interpF32Offset(interp, mulB, mulOut, 4, 2, enable);
        interpF32Offset(interp, scrR, scrOut, 4, 0, enable);
        interpF32Offset(interp, scrG, scrOut, 4, 1, enable);
        interpF32Offset(interp, scrB, scrOut, 4, 2, enable);
    }

    /** Color channel interpolation: writes out[i*outStride + baseOffset]. */
    private void interpF32Offset(Interp interp, float[] targets, float[] out,
                                 int outStride, int baseOffset, boolean[] enable) {
        if (interp == null || targets == null || out == null) return;
        int objCount = interp.objectCount;
        if (objCount <= 0) return;
        int[] maxComb = interp.maxBlend;
        int[] comb = interp.blendCount;
        float[] wt = interp.weights;
        float[] tmp = interp.tmp;
        int tmpLen = interp.tmpLen;
        if (maxComb == null || comb == null || wt == null) return;

        if (tmp != null && tmpLen > 0) {
            for (int i = 0; i < tmpLen; i++) {
                tmp[i] = targets[i] * wt[i];
            }
        }
        int off = 0;
        for (int i = 0; i < objCount; i++) {
            int mc = maxComb[i];
            if (enable == null || enable[i]) {
                int n = comb[i];
                float sum = 0.0f;
                if (tmp != null) {
                    for (int j = 0; j < n; j++) {
                        sum += tmp[off + j];
                    }
                } else {
                    for (int j = 0; j < n; j++) {
                        sum += targets[off + j] * wt[off + j];
                    }
                }
                out[i * outStride + baseOffset] = sum;
            }
            off += mc;
        }
    }

    // ------------------------------------------------------------- blend shapes

    private void blendParts() {
        if (version < Moc3.VER_50 || bsParts.length == 0) return;
        float[] doSrc = srcF32(Moc3.S_PART_KEY_DRAW_ORDER, moc.cntPartKeyforms);
        blendScalarI32(bsParts, partDrawOrder, doSrc);
    }

    private void blendWarps() {
        if (version < Moc3.VER_42 || bsWarps.length == 0) return;
        blendPositions(bsWarps, warpPos);
        if (version < Moc3.VER_50) return;
        float[] opSrc = srcF32(Moc3.S_WARP_KEY_OPACITY, moc.cntWarpKeyforms);
        blendScalarF32(bsWarps, warpOpacity, opSrc, 0.0f, 1.0f);
        blendColors(bsWarps, srcI32(Moc3.S_WARP_KEY_MUL_OFF, moc.cntWarpKeyforms), warpMulColor);
        blendColors(bsWarps, srcI32(Moc3.S_WARP_KEY_SCR_OFF, moc.cntWarpKeyforms), warpScrColor);
    }

    private void blendRotations() {
        if (version < Moc3.VER_50 || bsRotations.length == 0) return;
        float[] oxSrc = srcF32(Moc3.S_ROT_KEY_ORIGIN_X, moc.cntRotationKeyforms);
        blendScalarF32(bsRotations, rotationOriginX, oxSrc, -Float.MAX_VALUE, Float.MAX_VALUE);
        float[] oySrc = srcF32(Moc3.S_ROT_KEY_ORIGIN_Y, moc.cntRotationKeyforms);
        blendScalarF32(bsRotations, rotationOriginY, oySrc, -Float.MAX_VALUE, Float.MAX_VALUE);
        float[] opSrc = srcF32(Moc3.S_ROT_KEY_OPACITY, moc.cntRotationKeyforms);
        blendScalarF32(bsRotations, rotationOpacity, opSrc, 0.0f, 1.0f);
        blendColors(bsRotations, srcI32(Moc3.S_ROT_KEY_MUL_OFF, moc.cntRotationKeyforms), rotationMulColor);
        blendColors(bsRotations, srcI32(Moc3.S_ROT_KEY_SCR_OFF, moc.cntRotationKeyforms), rotationScrColor);
        float[] angSrc = srcF32(Moc3.S_ROT_KEY_ANGLE, moc.cntRotationKeyforms);
        blendScalarF32(bsRotations, rotationAngle, angSrc, -3600.0f, 3600.0f);
        float[] scSrc = srcF32(Moc3.S_ROT_KEY_SCALE, moc.cntRotationKeyforms);
        blendScalarF32(bsRotations, rotationScale, scSrc, 0.0001f, 100.0f);
    }

    private void blendArtMeshes() {
        if (version < Moc3.VER_42 || bsArtMeshes.length == 0) return;
        blendPositions(bsArtMeshes, artMeshPos);
        if (version < Moc3.VER_50) return;
        float[] doSrc = srcF32(Moc3.S_AM_KEY_DRAW_ORDER, moc.cntArtMeshKeyforms);
        blendScalarI32(bsArtMeshes, artMeshDrawOrder, doSrc);
        float[] opSrc = srcF32(Moc3.S_AM_KEY_OPACITY, moc.cntArtMeshKeyforms);
        blendScalarF32(bsArtMeshes, artMeshOpacity, opSrc, 0.0f, 1.0f);
        blendColors(bsArtMeshes, srcI32(Moc3.S_AM_KEY_MUL_OFF, moc.cntArtMeshKeyforms), artMeshMulColor);
        blendColors(bsArtMeshes, srcI32(Moc3.S_AM_KEY_SCR_OFF, moc.cntArtMeshKeyforms), artMeshScrColor);
    }

    private void blendGlues() {
        if (version < Moc3.VER_50 || bsGlues.length == 0) return;
        float[] intSrc = srcF32(Moc3.S_GLUE_KEY_INTENSITY, moc.cntGlueKeyforms);
        blendScalarF32(bsGlues, glueIntensity, intSrc, 0.0f, 1.0f);
    }

    private void blendOffscreens() {
        if (version < Moc3.VER_53 || bsOffscreens.length == 0) return;
        float[] opSrc = srcF32(Moc3.S_OS_KEY_OPACITY, moc.cntOffscreenKeyforms);
        blendScalarF32(bsOffscreens, offscreenOpacity, opSrc, 0.0f, 1.0f);
        blendColors(bsOffscreens, srcI32(Moc3.S_OS_KEY_MUL_OFF, moc.cntOffscreenKeyforms), offscreenMulColor);
        blendColors(bsOffscreens, srcI32(Moc3.S_OS_KEY_SCR_OFF, moc.cntOffscreenKeyforms), offscreenScrColor);
    }

    private float blendInterpF32(BlendBinding binding, float[] keyformSrc) {
        int blendCount = binding.blendCount;
        int off = binding.keySrcOff;
        float value;
        if (blendCount == 0) {
            return 0.0f;
        } else if (blendCount == 1) {
            int idx0 = binding.keyformIdx[0] + off;
            if (idx0 < 0 || idx0 >= keyformSrc.length) return 0.0f;
            value = keyformSrc[idx0] * binding.weights[0];
        } else if (blendCount == 2) {
            int idx0 = binding.keyformIdx[0] + off;
            int idx1 = binding.keyformIdx[1] + off;
            float v0 = (idx0 >= 0 && idx0 < keyformSrc.length) ? keyformSrc[idx0] : 0.0f;
            float v1 = (idx1 >= 0 && idx1 < keyformSrc.length) ? keyformSrc[idx1] : 0.0f;
            value = v0 * binding.weights[0] + v1 * binding.weights[1];
        } else {
            return 0.0f;
        }
        return binding.weight * value;
    }

    private void blendScalarF32(BlendShape[] shapes, float[] values, float[] keyformSrc, float lo, float hi) {
        for (BlendShape shape : shapes) {
            int ti = shape.targetIdx;
            if (ti < 0 || ti >= values.length) continue;
            int bc = shape.bindingCount;
            float value = values[ti];
            if (bc > 0 && shape.bindingOff >= 0) {
                for (int j = 0; j < bc; j++) {
                    value += blendInterpF32(blendBindings[shape.bindingOff + j], keyformSrc);
                }
            }
            values[ti] = clamp(value, lo, hi);
        }
    }

    private void blendScalarI32(BlendShape[] shapes, int[] values, float[] keyformSrc) {
        for (BlendShape shape : shapes) {
            int ti = shape.targetIdx;
            if (ti < 0 || ti >= values.length) continue;
            int bc = shape.bindingCount;
            float value = values[ti];
            if (bc > 0 && shape.bindingOff >= 0) {
                for (int j = 0; j < bc; j++) {
                    value += blendInterpF32(blendBindings[shape.bindingOff + j], keyformSrc);
                }
            }
            float rounded = value + 0.001f;
            rounded = clamp(rounded, 0.0f, 1000.0f);
            values[ti] = (int) rounded;
        }
    }

    private void blendPositions(BlendShape[] shapes, float[][] outPositions) {
        if (shapes.length == 0) return;
        float[] posXY = srcF32(Moc3.S_KEY_POS_XY, moc.cntKeyformPos);
        int[] keyPosOff = null;
        if (shapes == bsWarps) {
            keyPosOff = srcI32(Moc3.S_WARP_KEY_POS_OFF, moc.cntWarpKeyforms);
        } else {
            keyPosOff = srcI32(Moc3.S_AM_KEY_POS_OFF, moc.cntArtMeshKeyforms);
        }
        for (BlendShape shape : shapes) {
            int ti = shape.targetIdx;
            if (ti < 0 || ti >= outPositions.length) continue;
            int bc = shape.bindingCount;
            if (bc <= 0 || shape.bindingOff < 0) continue;
            float[] out = outPositions[ti];
            int pc = out.length;
            if (pc <= 0) continue;
            for (int j = 0; j < bc; j++) {
                BlendBinding binds = blendBindings[shape.bindingOff + j];
                int blendCount = binds.blendCount;
                if (blendCount == 0) continue;
                int off = binds.keySrcOff;
                float cw = binds.weight;
                if (blendCount == 1) {
                    int ki = binds.keyformIdx[0] + off;
                    if (ki < 0 || ki >= keyPosOff.length) continue;
                    int po = keyPosOff[ki];
                    float w0 = binds.weights[0];
                    for (int k = 0; k < pc; k++) {
                        int idx = po + k;
                        if (idx >= 0 && idx < posXY.length) {
                            out[k] += posXY[idx] * w0 * cw;
                        }
                    }
                } else if (blendCount == 2) {
                    int ki0 = binds.keyformIdx[0] + off;
                    int ki1 = binds.keyformIdx[1] + off;
                    if (ki0 < 0 || ki0 >= keyPosOff.length || ki1 < 0 || ki1 >= keyPosOff.length) continue;
                    int po0 = keyPosOff[ki0];
                    int po1 = keyPosOff[ki1];
                    float w0 = binds.weights[0];
                    float w1 = binds.weights[1];
                    for (int k = 0; k < pc; k++) {
                        float v = 0.0f;
                        int a = po0 + k, b = po1 + k;
                        if (a >= 0 && a < posXY.length) v += w0 * posXY[a];
                        if (b >= 0 && b < posXY.length) v += posXY[b] * w1;
                        out[k] += v * cw;
                    }
                }
            }
        }
    }

    private void blendColors(BlendShape[] shapes, int[] colorOff, float[] out) {
        if (shapes.length == 0 || colorOff == null) return;
        float[] srcR = srcF32(Moc3.S_KEYFORM_MUL_R, moc.cntKeyformMulColors);
        float[] srcG = srcF32(Moc3.S_KEYFORM_MUL_G, moc.cntKeyformMulColors);
        float[] srcB = srcF32(Moc3.S_KEYFORM_MUL_B, moc.cntKeyformMulColors);
        boolean scr = (out == warpScrColor || out == rotationScrColor || out == artMeshScrColor || out == offscreenScrColor);
        if (scr) {
            srcR = srcF32(Moc3.S_KEYFORM_SCR_R, moc.cntKeyformScrColors);
            srcG = srcF32(Moc3.S_KEYFORM_SCR_G, moc.cntKeyformScrColors);
            srcB = srcF32(Moc3.S_KEYFORM_SCR_B, moc.cntKeyformScrColors);
        }
        for (BlendShape shape : shapes) {
            int ti = shape.targetIdx;
            if (ti < 0 || ti * 4 + 3 >= out.length) continue;
            int bc = shape.bindingCount;
            int ob = ti * 4;
            if (bc > 0 && shape.bindingOff >= 0) {
                for (int j = 0; j < bc; j++) {
                    BlendBinding binds = blendBindings[shape.bindingOff + j];
                    int blendCount = binds.blendCount;
                    if (blendCount == 0) continue;
                    int off = binds.keySrcOff;
                    float cw = binds.weight;
                    float r, g, b;
                    if (blendCount == 1) {
                        int ki = binds.keyformIdx[0] + off;
                        if (ki < 0 || ki >= colorOff.length) continue;
                        int ci = colorOff[ki];
                        if (ci < 0 || ci >= srcR.length) continue;
                        float w0 = binds.weights[0];
                        r = srcR[ci] * w0;
                        g = srcG[ci] * w0;
                        b = srcB[ci] * w0;
                    } else if (blendCount == 2) {
                        int ki0 = binds.keyformIdx[0] + off;
                        int ki1 = binds.keyformIdx[1] + off;
                        if (ki0 < 0 || ki0 >= colorOff.length || ki1 < 0 || ki1 >= colorOff.length) continue;
                        int ci0 = colorOff[ki0];
                        int ci1 = colorOff[ki1];
                        if (ci0 < 0 || ci0 >= srcR.length || ci1 < 0 || ci1 >= srcR.length) continue;
                        float w0 = binds.weights[0];
                        float w1 = binds.weights[1];
                        r = w0 * srcR[ci0] + srcR[ci1] * w1;
                        g = w0 * srcG[ci0] + srcG[ci1] * w1;
                        b = w0 * srcB[ci0] + srcB[ci1] * w1;
                    } else {
                        continue;
                    }
                    out[ob] += r * cw;
                    out[ob + 1] += g * cw;
                    out[ob + 2] += b * cw;
                }
            }
            out[ob] = clamp01(out[ob]);
            out[ob + 1] = clamp01(out[ob + 1]);
            out[ob + 2] = clamp01(out[ob + 2]);
        }
    }

    // ------------------------------------------------------------- transforms

    private static final class WarpBasis {
        float cx, cy, dux, duy, dvx, dvy;
    }

    private static final class WarpCell {
        float fu, fv;
        float p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y;
    }

    private WarpBasis warpExtrapBasis(float[] pos, int row, int col, int stride) {
        int c00 = 0;
        int c10 = col;
        int c01 = row * stride;
        int c11 = row * stride + col;
        float c00x = pos[c00 * 2], c00y = pos[c00 * 2 + 1];
        float c10x = pos[c10 * 2], c10y = pos[c10 * 2 + 1];
        float c01x = pos[c01 * 2], c01y = pos[c01 * 2 + 1];
        float c11x = pos[c11 * 2], c11y = pos[c11 * 2 + 1];

        float d11_00x = c11x - c00x, d11_00y = c11y - c00y;
        float d10_01x = c10x - c01x, d10_01y = c10y - c01y;

        WarpBasis b = new WarpBasis();
        b.dvx = (d11_00x - d10_01x) * 0.5f;
        b.dvy = (d11_00y - d10_01y) * 0.5f;
        b.dux = (d10_01x + d11_00x) * 0.5f;
        b.duy = (d10_01y + d11_00y) * 0.5f;

        float sumx = c00x + c10x + c01x + c11x;
        float sumy = c00y + c10y + c01y + c11y;
        b.cx = sumx * 0.25f - d11_00x * 0.5f;
        b.cy = sumy * 0.25f - d11_00y * 0.5f;
        return b;
    }

    private WarpCell warpExtrapCell(float u, float v, float gu, float gv,
                                    int row, int col, int stride, float[] pos, WarpBasis basis) {
        float fr = row, fc = col;
        float cenx = basis.cx, ceny = basis.cy;
        float dvx = basis.dvx, dvy = basis.dvy;
        float dux = basis.dux, duy = basis.duy;

        WarpCell cell = new WarpCell();
        int cu = 0, cv = 0;
        float uc = 0, un = 0, vc = 0, vn = 0;

        if (u <= 0.0f) {
            cell.fu = (u + 2.0f) * 0.5f;
        } else if (u >= 1.0f) {
            cell.fu = (u - 1.0f) * 0.5f;
        } else {
            cu = (int) gu;
            if (cu == col) cu = col - 1;
            cell.fu = gu - cu;
            uc = cu / fc;
            un = (cu + 1) / fc;
        }
        if (v <= 0.0f) {
            cell.fv = (v + 2.0f) * 0.5f;
        } else if (v >= 1.0f) {
            cell.fv = (v - 1.0f) * 0.5f;
        } else {
            cv = (int) gv;
            if (cv == row) cv = row - 1;
            cell.fv = gv - cv;
            vc = cv / fr;
            vn = (cv + 1) / fr;
        }

        if (u <= 0.0f) {
            if (v <= 0.0f) {
                cell.p00x = cenx - (dvx * 2.0f + dux * 2.0f);
                cell.p00y = ceny - (dvy * 2.0f + duy * 2.0f);
                cell.p10x = cenx - dvx * 2.0f;
                cell.p10y = ceny - dvy * 2.0f;
                cell.p01x = cenx - dux * 2.0f;
                cell.p01y = ceny - duy * 2.0f;
                cell.p11x = pos[0];
                cell.p11y = pos[1];
            } else if (v < 1.0f) {
                cell.p00x = (cenx - dux * 2.0f) + dvx * vc;
                cell.p00y = (ceny - duy * 2.0f) + dvy * vc;
                cell.p10x = pos[cv * stride * 2];
                cell.p10y = pos[cv * stride * 2 + 1];
                cell.p01x = (cenx - dux * 2.0f) + dvx * vn;
                cell.p01y = (ceny - duy * 2.0f) + dvy * vn;
                cell.p11x = pos[(cv + 1) * stride * 2];
                cell.p11y = pos[(cv + 1) * stride * 2 + 1];
            } else {
                cell.p00x = (cenx - dux * 2.0f) + dvx;
                cell.p00y = (ceny - duy * 2.0f) + dvy;
                cell.p10x = pos[row * stride * 2];
                cell.p10y = pos[row * stride * 2 + 1];
                cell.p01x = (cenx - dux * 2.0f) + dvx * 3.0f;
                cell.p01y = (ceny - duy * 2.0f) + dvy * 3.0f;
                cell.p11x = cenx + dvx * 3.0f;
                cell.p11y = ceny + dvy * 3.0f;
            }
        } else if (u < 1.0f) {
            if (v <= 0.0f) {
                cell.p00x = dux * uc + (cenx - dvx * 2.0f);
                cell.p00y = duy * uc + (ceny - dvy * 2.0f);
                cell.p10x = dux * un + (cenx - dvx * 2.0f);
                cell.p10y = duy * un + (ceny - dvy * 2.0f);
                cell.p01x = pos[cu * 2];
                cell.p01y = pos[cu * 2 + 1];
                cell.p11x = pos[(cu + 1) * 2];
                cell.p11y = pos[(cu + 1) * 2 + 1];
            } else {
                cell.p00x = pos[(row * stride + cu) * 2];
                cell.p00y = pos[(row * stride + cu) * 2 + 1];
                cell.p10x = pos[(row * stride + cu + 1) * 2];
                cell.p10y = pos[(row * stride + cu + 1) * 2 + 1];
                cell.p01x = (cenx + dux * uc) + dvx * 3.0f;
                cell.p01y = (ceny + duy * uc) + dvy * 3.0f;
                cell.p11x = (cenx + dux * un) + dvx * 3.0f;
                cell.p11y = (ceny + duy * un) + dvy * 3.0f;
            }
        } else {
            if (v <= 0.0f) {
                cell.p00x = (cenx - dvx * 2.0f) + dux;
                cell.p00y = (ceny - dvy * 2.0f) + duy;
                cell.p10x = (cenx - dvx * 2.0f) + dux * 3.0f;
                cell.p10y = (ceny - dvy * 2.0f) + duy * 3.0f;
                cell.p01x = pos[col * 2];
                cell.p01y = pos[col * 2 + 1];
                cell.p11x = cenx + dux * 3.0f;
                cell.p11y = ceny + duy * 3.0f;
            } else if (v < 1.0f) {
                cell.p00x = pos[(col + cv * stride) * 2];
                cell.p00y = pos[(col + cv * stride) * 2 + 1];
                cell.p10x = (cenx + dux * 3.0f) + dvx * vc;
                cell.p10y = (ceny + duy * 3.0f) + dvy * vc;
                cell.p01x = pos[(col + (cv + 1) * stride) * 2];
                cell.p01y = pos[(col + (cv + 1) * stride) * 2 + 1];
                cell.p11x = (cenx + dux * 3.0f) + dvx * vn;
                cell.p11y = (ceny + duy * 3.0f) + dvy * vn;
            } else {
                cell.p00x = pos[(row * stride + col) * 2];
                cell.p00y = pos[(row * stride + col) * 2 + 1];
                cell.p10x = (cenx + dux * 3.0f) + dvx;
                cell.p10y = (ceny + duy * 3.0f) + dvy;
                cell.p01x = (cenx + dvx * 3.0f) + dux;
                cell.p01y = (ceny + dvy * 3.0f) + duy;
                cell.p11x = cenx + (dux * 3.0f + dvx * 3.0f);
                cell.p11y = ceny + (duy * 3.0f + dvy * 3.0f);
            }
        }
        return cell;
    }

    private void interpTriangle(WarpCell cell, float[] outXY) {
        float fu = cell.fu, fv = cell.fv;
        if (fu + fv <= 1.0f) {
            float w00 = 1.0f - fu - fv;
            outXY[0] = fv * cell.p01x + (fu * cell.p10x + w00 * cell.p00x);
            outXY[1] = fv * cell.p01y + (fu * cell.p10y + w00 * cell.p00y);
        } else {
            float w10 = 1.0f - fv;
            float w11 = fu + fv - 1.0f;
            float w01 = 1.0f - fu;
            outXY[0] = w01 * cell.p01x + (w11 * cell.p11x + w10 * cell.p10x);
            outXY[1] = w01 * cell.p01y + (w11 * cell.p11y + w10 * cell.p10y);
        }
    }

    private void warpTransform(int di, float[] inputs, float[] outputs, int count) {
        DeformerNode dn = deformerNodes[di];
        int si = dn.localIdx;
        Warp wc = warps[si];
        float[] pos = warpPos[si];
        int row = wc.row, col = wc.col;
        boolean isQuad = wc.quadTransform;
        int stride = col + 1;
        float fr = row, fc = col;

        boolean extrapSetup = false;
        WarpBasis basis = null;
        float[] tmp = new float[2];

        for (int i = 0; i < count; i++) {
            float uvx = inputs[i * 2], uvy = inputs[i * 2 + 1];
            float gu = uvx * fc, gv = uvy * fr;
            if (uvx >= 0.0f && uvx < 1.0f && uvy >= 0.0f && uvy < 1.0f) {
                int cu = (int) gu, cv = (int) gv;
                float fu = gu - cu, fv = gv - cv;
                int bi = cv * stride + cu;
                float p00x = pos[bi * 2], p00y = pos[bi * 2 + 1];
                float p10x = pos[(bi + 1) * 2], p10y = pos[(bi + 1) * 2 + 1];
                float p01x = pos[(bi + stride) * 2], p01y = pos[(bi + stride) * 2 + 1];
                float p11x = pos[(bi + stride + 1) * 2], p11y = pos[(bi + stride + 1) * 2 + 1];
                if (isQuad) {
                    float invU = 1.0f - fu;
                    float x0 = fu * p10x + invU * p00x, y0 = fu * p10y + invU * p00y;
                    float x1 = fu * p11x + invU * p01x, y1 = fu * p11y + invU * p01y;
                    float invV = 1.0f - fv;
                    outputs[i * 2] = fv * x1 + invV * x0;
                    outputs[i * 2 + 1] = fv * y1 + invV * y0;
                } else {
                    WarpCell cell = new WarpCell();
                    cell.fu = fu;
                    cell.fv = fv;
                    cell.p00x = p00x; cell.p00y = p00y;
                    cell.p10x = p10x; cell.p10y = p10y;
                    cell.p01x = p01x; cell.p01y = p01y;
                    cell.p11x = p11x; cell.p11y = p11y;
                    interpTriangle(cell, tmp);
                    outputs[i * 2] = tmp[0];
                    outputs[i * 2 + 1] = tmp[1];
                }
            } else {
                if (!extrapSetup) {
                    basis = warpExtrapBasis(pos, row, col, stride);
                    extrapSetup = true;
                }
                if (uvx > -2.0f && uvx < 3.0f && uvy > -2.0f && uvy < 3.0f) {
                    WarpCell cell = warpExtrapCell(uvx, uvy, gu, gv, row, col, stride, pos, basis);
                    interpTriangle(cell, tmp);
                    outputs[i * 2] = tmp[0];
                    outputs[i * 2 + 1] = tmp[1];
                } else {
                    outputs[i * 2] = basis.dux * uvx + basis.cx + basis.dvx * uvy;
                    outputs[i * 2 + 1] = basis.duy * uvx + basis.cy + basis.dvy * uvy;
                }
            }
        }
    }

    private void rotationTransform(int di, float[] inputs, float[] outputs, int count) {
        int si = deformerNodes[di].localIdx;
        Rotation rc = rotations[si];
        float baseAngle = rc.baseAngle;
        float angle = rotationAngle[si];
        float scale = rotationScale[si];
        float originX = rotationOriginX[si];
        float originY = rotationOriginY[si];
        int rx = rotationReflectX[si];
        int ry = rotationReflectY[si];

        float angleRad = (baseAngle + angle) * PI / 180.0f;
        float sinA = (float) Math.sin(angleRad);
        float cosA = (float) Math.cos(angleRad);
        float rxf = rx != 0 ? -1.0f : 1.0f;
        float ryf = ry != 0 ? -1.0f : 1.0f;

        float m00 = scale * cosA * rxf;
        float m01 = scale * (-sinA) * ryf;
        float m10 = scale * sinA * rxf;
        float m11 = scale * cosA * ryf;

        for (int i = 0; i < count; i++) {
            float px = inputs[i * 2], py = inputs[i * 2 + 1];
            outputs[i * 2] = originX + m00 * px + m01 * py;
            outputs[i * 2 + 1] = originY + m10 * px + m11 * py;
        }
    }

    private float[] deformerTransformPoint(int deformerIdx, float px, float py) {
        float[] in = new float[]{px, py};
        float[] out = new float[2];
        int pt = deformerNodes[deformerIdx].type;
        if (pt == DEFORMER_TYPE_WARP) {
            warpTransform(deformerIdx, in, out, 1);
        } else {
            rotationTransform(deformerIdx, in, out, 1);
        }
        return out;
    }

    private void propagateDeformerColors(float[] mo, float[] so, float[] sm, float[] ss,
                                         int selfIdx, int specIdx, int parentIdx) {
        int s4 = selfIdx * 4, p4 = specIdx * 4;
        if (parentIdx == -1) {
            mo[s4] = sm[p4];
            mo[s4 + 1] = sm[p4 + 1];
            mo[s4 + 2] = sm[p4 + 2];
            mo[s4 + 3] = 1.0f;
            so[s4] = ss[p4];
            so[s4 + 1] = ss[p4 + 1];
            so[s4 + 2] = ss[p4 + 2];
            so[s4 + 3] = 1.0f;
        } else {
            int pi4 = parentIdx * 4;
            mo[s4] = sm[p4] * mo[pi4];
            mo[s4 + 1] = sm[p4 + 1] * mo[pi4 + 1];
            mo[s4 + 2] = sm[p4 + 2] * mo[pi4 + 2];
            mo[s4 + 3] = 1.0f;
            so[s4] = ss[p4] + so[pi4] - ss[p4] * so[pi4];
            so[s4 + 1] = ss[p4 + 1] + so[pi4 + 1] - ss[p4 + 1] * so[pi4 + 1];
            so[s4 + 2] = ss[p4 + 2] + so[pi4 + 2] - ss[p4 + 2] * so[pi4 + 2];
            so[s4 + 3] = 1.0f;
        }
    }

    private void applyWarp(int di) {
        DeformerNode self = deformerNodes[di];
        int pi = self.parentDeformerIdx;
        int si = self.localIdx;
        if (pi == -1) {
            deformerOpacity[di] = warpOpacity[si];
            deformerScale[di] = 1.0f;
        } else {
            float[] pos = warpPos[si];
            int vc = warps[si].vertexCount;
            int pt = deformerNodes[pi].type;
            if (pt == DEFORMER_TYPE_WARP) {
                warpTransform(pi, pos, pos, vc);
            } else {
                rotationTransform(pi, pos, pos, vc);
            }
            deformerOpacity[di] = warpOpacity[si] * deformerOpacity[pi];
            deformerScale[di] = deformerScale[pi];
        }
        if (version < Moc3.VER_42) return;
        propagateDeformerColors(deformerMulColor, deformerScrColor, warpMulColor, warpScrColor, di, si, pi);
    }

    private void applyRotation(int di) {
        DeformerNode self = deformerNodes[di];
        int pi = self.parentDeformerIdx;
        int si = self.localIdx;
        if (pi == -1) {
            deformerOpacity[di] = rotationOpacity[si];
            deformerScale[di] = rotationScale[si];
        } else {
            float originX = rotationOriginX[si], originY = rotationOriginY[si];
            float directionX = 0.0f, directionY = 0.0f;
            int pt = deformerNodes[pi].type;
            float dirDelta = (pt == DEFORMER_TYPE_ROTATION) ? -10.0f : -0.1f;

            float[] tOrigin = deformerTransformPoint(pi, originX, originY);

            float scale = 1.0f;
            int iter;
            for (iter = 0; iter < 16; iter++) {
                float[] tp = new float[]{originX, originY + scale * dirDelta};
                float[] tt = deformerTransformPoint(pi, tp[0], tp[1]);
                float dx = tt[0] - tOrigin[0], dy = tt[1] - tOrigin[1];
                if (dx != 0.0f || dy != 0.0f) {
                    directionX = dx;
                    directionY = dy;
                    break;
                }
                tp = new float[]{originX, originY - scale * dirDelta};
                tt = deformerTransformPoint(pi, tp[0], tp[1]);
                dx = tt[0] - tOrigin[0];
                dy = tt[1] - tOrigin[1];
                if (dx != 0.0f || dy != 0.0f) {
                    directionX = -dx;
                    directionY = -dy;
                    break;
                }
                scale *= 0.1f;
            }

            float[] baseDir = {0.0f, dirDelta};
            float[] dirArr = {directionX, directionY};
            float angleAdj = (signedAngle(baseDir, dirArr) * -180.0f) / PI;

            float[] newOrigin = deformerTransformPoint(pi, originX, originY);
            rotationOriginX[si] = newOrigin[0];
            rotationOriginY[si] = newOrigin[1];
            rotationAngle[si] += angleAdj;

            deformerOpacity[di] = rotationOpacity[si] * deformerOpacity[pi];

            float cs = rotationScale[si] * deformerScale[pi];
            deformerScale[di] = cs;
            rotationScale[si] = cs;
        }
        if (version < Moc3.VER_42) return;
        propagateDeformerColors(deformerMulColor, deformerScrColor, rotationMulColor, rotationScrColor, di, si, pi);
    }

    private float signedAngle(float[] v1, float[] v2) {
        float angle1 = (float) Math.atan2(v1[1], v1[0]);
        float angle2 = (float) Math.atan2(v2[1], v2[0]);
        return (float) (Math.IEEEremainder(angle1 - angle2, TWO_PI));
    }

    private void applyTransforms() {
        for (int i = 0; i < deformerCount; i++) {
            if (deformerEnable[i]) {
                int t = deformerNodes[i].type;
                if (t == DEFORMER_TYPE_WARP) {
                    applyWarp(i);
                } else if (t == DEFORMER_TYPE_ROTATION) {
                    applyRotation(i);
                }
            }
        }
    }

    private void applyTransformsToMeshes() {
        for (int i = 0; i < artMeshCount; i++) {
            if (!artMeshEnable[i]) continue;
            int pdi = artMeshes[i].parentDeformerIdx;
            if (pdi == -1 || pdi < 0 || pdi >= deformerCount) continue;
            int vc = artMeshes[i].vertexCount;
            artMeshOpacity[i] *= deformerOpacity[pdi];
            int dt = deformerNodes[pdi].type;
            if (dt == DEFORMER_TYPE_WARP) {
                warpTransform(pdi, artMeshPos[i], artMeshPos[i], vc);
            } else {
                rotationTransform(pdi, artMeshPos[i], artMeshPos[i], vc);
            }
        }
    }

    private void applyPartsToMeshes() {
        for (int i = 0; i < artMeshCount; i++) {
            if (!artMeshEnable[i]) continue;
            int pp = artMeshes[i].parentPartIdx;
            if (pp != -1 && pp >= 0 && pp < partCount && partOffscreenSrcIdx[pp] == -1) {
                artMeshOpacity[i] *= partOpacity[pp];
            }
        }
        if (version < Moc3.VER_42) return;
        for (int i = 0; i < artMeshCount; i++) {
            int ci = i * 4;
            if (!artMeshEnable[i]) continue;
            int pd = artMeshes[i].parentDeformerIdx;
            if (pd == -1 || pd < 0 || pd >= deformerCount) continue;
            float[] pm = new float[]{deformerMulColor[pd * 4], deformerMulColor[pd * 4 + 1], deformerMulColor[pd * 4 + 2]};
            float[] ps = new float[]{deformerScrColor[pd * 4], deformerScrColor[pd * 4 + 1], deformerScrColor[pd * 4 + 2]};
            artMeshMulColor[ci] = clamp01(artMeshMulColor[ci] * pm[0]);
            artMeshMulColor[ci + 1] = clamp01(artMeshMulColor[ci + 1] * pm[1]);
            artMeshMulColor[ci + 2] = clamp01(artMeshMulColor[ci + 2] * pm[2]);
            artMeshMulColor[ci + 3] = 1.0f;
            artMeshScrColor[ci] = clamp01(artMeshScrColor[ci] + ps[0] - artMeshScrColor[ci] * ps[0]);
            artMeshScrColor[ci + 1] = clamp01(artMeshScrColor[ci + 1] + ps[1] - artMeshScrColor[ci + 1] * ps[1]);
            artMeshScrColor[ci + 2] = clamp01(artMeshScrColor[ci + 2] + ps[2] - artMeshScrColor[ci + 2] * ps[2]);
            artMeshScrColor[ci + 3] = 1.0f;
        }
    }

    private void enableArtMeshes() {
        for (int i = 0; i < artMeshCount; i++) {
            ArtMesh am = artMeshes[i];
            boolean en = am.localEnable;
            int pp = am.parentPartIdx;
            int pd = am.parentDeformerIdx;
            if (en && pp != -1 && pp >= 0 && pp < partCount) en = partEnable[pp];
            if (en && pd != -1 && pd >= 0 && pd < deformerCount) en = deformerEnable[pd];
            if (en && am.bindingIdx >= 0) {
                en = !bindings[am.bindingIdx].outOfRange;
            } else if (en) {
                en = false;
            }
            artMeshEnable[i] = en;
        }
    }

    // ------------------------------------------------------------- render order

    private void reverseY() {
        if (yReversed) return;
        for (int i = 0; i < artMeshCount; i++) {
            if (!artMeshEnable[i]) continue;
            int vc = artMeshes[i].vertexCount;
            float[] p = artMeshPos[i];
            for (int j = 0; j < vc; j++) {
                p[2 * j + 1] = -p[2 * j + 1];
            }
        }
    }

    private void sortRenderOrder() {
        if (drawGroupCount <= 0) return;
        for (int gi = 0; gi < drawGroupCount; gi++) {
            DrawGroup c = drawGroups[gi];
            int n = c.count;
            if (n <= 0 || c.items == null) continue;
            for (int j = 0; j < n; j++) {
                DrawItem item = c.items[j];
                int oi = item.objectIdx;
                if (item.objectType == 1) {
                    if (oi >= 0 && oi < partCount && partEnable[oi]) {
                        item.drawOrder = partDrawOrder[oi];
                    } else {
                        item.drawOrder = c.minOrder;
                    }
                } else {
                    if (oi >= 0 && oi < artMeshCount && artMeshEnable[oi]) {
                        item.drawOrder = artMeshDrawOrder[oi];
                    } else {
                        item.drawOrder = c.minOrder;
                    }
                }
            }
        }

        int maxLevel = 0, maxItems = 0;
        for (int i = 0; i < drawGroupCount; i++) {
            if (drawGroups[i].orderLevel > maxLevel) maxLevel = drawGroups[i].orderLevel;
            if (drawGroups[i].count > maxItems) maxItems = drawGroups[i].count;
        }
        if (maxLevel <= 0 || maxItems <= 0) return;

        for (int gi = 0; gi < drawGroupCount; gi++) {
            DrawGroup c = drawGroups[gi];
            int olevel = c.orderLevel;
            int n = c.count;
            if (olevel <= 0 || n <= 0) continue;
            if (olevel > maxLevel || n > maxItems) continue;

            java.util.Arrays.fill(c.first, 0, olevel, -1);
            java.util.Arrays.fill(c.last, 0, olevel, -1);
            java.util.Arrays.fill(c.next, 0, n, -1);

            for (int j = 0; j < n; j++) {
                int rel = c.items[j].drawOrder - c.minOrder;
                rel = clampIdx(rel, olevel);
                if (c.last[rel] == -1) {
                    c.first[rel] = j;
                } else {
                    c.next[c.last[rel]] = j;
                }
                c.last[rel] = j;
            }

            int pos = c.cursor;
            boolean hasOffscr = version >= Moc3.VER_53;
            for (int oi = 0; oi < olevel; oi++) {
                int di = c.first[oi];
                while (di != -1 && di < n) {
                    DrawItem item = c.items[di];
                    int obj = item.objectIdx;
                    int grp = item.groupIdx;
                    if (item.objectType == 1) {
                        if (hasOffscr && obj >= 0 && obj < partCount) {
                            int oidx = partOffscreenSrcIdx[obj];
                            if (oidx >= 0 && artMeshCount + oidx < renderOrder.length) {
                                renderOrder[artMeshCount + oidx] = pos++;
                            }
                        }
                        if (grp >= 0 && grp < drawGroupCount) {
                            DrawGroup nc = drawGroups[grp];
                            nc.cursor = pos;
                            pos += nc.totalCount;
                        }
                    } else {
                        if (obj >= 0 && obj < artMeshCount) {
                            renderOrder[obj] = pos++;
                        } else {
                            pos++;
                        }
                    }
                    int ni = c.next[di];
                    if (ni <= di) break;
                    di = ni;
                }
            }
        }
    }

    // ------------------------------------------------------------- flags

    private void saveFlags() {
        if (!artMeshStateChanged) return;
        int n = artMeshCount;
        System.arraycopy(renderOrder, 0, artMeshLastRenderOrder, 0, Math.min(n, renderOrder.length));
        System.arraycopy(artMeshDrawOrder, 0, artMeshLastDrawOrder, 0, n);
        System.arraycopy(artMeshOpacity, 0, artMeshLastOpacity, 0, n);
        if (version < Moc3.VER_42) return;
        System.arraycopy(artMeshMulColor, 0, artMeshLastMulColor, 0, 4 * n);
        System.arraycopy(artMeshScrColor, 0, artMeshLastScrColor, 0, 4 * n);
    }

    private void updateFlags() {
        int n = artMeshCount;
        if (forceUpdate) {
            artMeshStateChanged = false;
            for (int i = 0; i < n; i++) {
                if (!artMeshEnable[i] || artMeshOpacity[i] == 0.0f) {
                    artMeshChangeFlags[i] = (byte) FLAG_ALL_CHANGED;
                } else {
                    artMeshChangeFlags[i] = (byte) FLAG_ALL;
                }
            }
            return;
        }
        if (artMeshStateChanged) {
            artMeshStateChanged = false;
            for (int i = 0; i < n; i++) {
                boolean visible = artMeshEnable[i] && artMeshOpacity[i] != 0.0f;
                boolean was = (artMeshChangeFlags[i] & FLAG_IS_VISIBLE) != 0;
                int flags = visible ? 1 : 0;
                if (visible != was) flags |= FLAG_VISIBILITY_CHANGED;
                if (artMeshOpacity[i] != artMeshLastOpacity[i]) flags |= FLAG_OPACITY_CHANGED;
                if (artMeshDrawOrder[i] != artMeshLastDrawOrder[i]) flags |= FLAG_DRAW_ORDER_CHANGED;
                if (i < renderOrder.length && renderOrder[i] != artMeshLastRenderOrder[i]) flags |= FLAG_RENDER_ORDER_CHANGED;
                if (artMeshEnable[i]) flags |= FLAG_VERTEX_CHANGED;
                if (version >= Moc3.VER_42) {
                    if (artMeshMulColor[i * 4] != artMeshLastMulColor[i * 4]
                            || artMeshMulColor[i * 4 + 1] != artMeshLastMulColor[i * 4 + 1]
                            || artMeshMulColor[i * 4 + 2] != artMeshLastMulColor[i * 4 + 2]
                            || artMeshScrColor[i * 4] != artMeshLastScrColor[i * 4]
                            || artMeshScrColor[i * 4 + 1] != artMeshLastScrColor[i * 4 + 1]
                            || artMeshScrColor[i * 4 + 2] != artMeshLastScrColor[i * 4 + 2]) {
                        flags |= FLAG_BLEND_COLOR_CHANGED;
                    }
                }
                artMeshChangeFlags[i] = (byte) flags;
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            if (!artMeshEnable[i] || artMeshOpacity[i] == 0.0f) {
                artMeshChangeFlags[i] &= ~FLAG_IS_VISIBLE;
            } else {
                artMeshChangeFlags[i] |= FLAG_IS_VISIBLE;
            }
        }
    }

    public void resetDynamicFlags() {
        for (int i = 0; i < artMeshCount; i++) {
            artMeshChangeFlags[i] &= FLAG_IS_VISIBLE;
        }
        artMeshStateChanged = true;
    }

    // ------------------------------------------------------------- helpers

    private static float clamp(float v, float lo, float hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    private static float clamp01(float v) {
        return Math.min(Math.max(v, 0.0f), 1.0f);
    }

    private static int f32ToI32(float v) {
        if (Float.isNaN(v)) return 0;
        return (int) clamp(v, -2147483648.0f, 2147483520.0f);
    }

    private static int safeOrderLevel(int maxOrder, int minOrder) {
        if (maxOrder < minOrder) return 0;
        long range = (long) (maxOrder - minOrder);
        if (range > 0x7FFFFFFEL) return 0;
        return (int) (range + 1);
    }

    private static int clampIdx(int idx, int max) {
        if (max <= 0) return 0;
        return Math.min(Math.max(idx, 0), max - 1);
    }

    // ------------------------------------------------------------- accessors

    public float getCanvasWidth() { return canvasWidth; }
    public float getCanvasHeight() { return canvasHeight; }
    public float getCanvasOriginX() { return canvasOriginX; }
    public float getCanvasOriginY() { return canvasOriginY; }
    public float getPixelsPerUnit() { return pixelsPerUnit; }

    public float getBboxAspect() {
        return canvasWidth / Math.max(1.0f, canvasHeight);
    }

    public int getDrawableCount() { return artMeshCount; }
    public int getParameterCount() { return paramCount; }
    public String getParameterId(int i) { return paramIds[i]; }
    public float getParameterValue(int i) { return paramInput[i]; }
    public void setParameterValue(int i, float v) { paramInput[i] = v; }
    public int findParameterIndex(String id) {
        for (int i = 0; i < paramCount; i++) {
            if (paramIds[i].equals(id)) return i;
        }
        return -1;
    }
    public boolean hasParameter(String id) { return findParameterIndex(id) >= 0; }
    public void setParameter(String id, float value) {
        int i = findParameterIndex(id);
        if (i >= 0) paramInput[i] = value;
    }
    public float getParameterMinimum(int i) { return paramMin[i]; }
    public float getParameterMaximum(int i) { return paramMax[i]; }
    public float getParameterDefault(int i) { return paramDefault[i]; }

    public void resetParametersToDefault() {
        System.arraycopy(paramDefault, 0, paramInput, 0, paramCount);
    }

    public boolean isDrawableVisible(int i) {
        return (artMeshChangeFlags[i] & FLAG_IS_VISIBLE) != 0;
    }
    public boolean isDrawableAdditive(int i) { return artMeshBlendMode[i] == 1; }
    public boolean isDrawableMultiplicative(int i) { return artMeshBlendMode[i] == 2; }
    public int getDrawableBlendMode(int i) { return artMeshBlendMode[i]; }
    public int getDrawableTextureIndex(int i) { return amTextureNo[i]; }
    public float getDrawableOpacity(int i) { return artMeshOpacity[i]; }
    public int getDrawableRenderOrder(int i) { return renderOrder[i]; }
    public int getDrawableDrawOrder(int i) { return artMeshDrawOrder[i]; }
    public String getDrawableId(int i) { return moc.idAt(Moc3.S_AM_ID, i); }
    public int getDrawableVertexCount(int i) { return artMeshes[i].vertexCount; }
    public float[] getDrawableVertexPositions(int i) { return artMeshPos[i]; }
    public int getDrawableIndexCount(int i) { return amIdxLen[i]; }
    public int[] getDrawableIndices(int i) {
        int off = amIdxOff[i], len = amIdxLen[i];
        int[] out = new int[len];
        System.arraycopy(indices, off, out, 0, len);
        return out;
    }
    public float[] getDrawableUvs(int i) {
        int off = amUvOff[i], vc = artMeshes[i].vertexCount;
        float[] out = new float[vc * 2];
        System.arraycopy(uvs, off, out, 0, vc * 2);
        return out;
    }
    public int getDrawableMaskCount(int i) { return amMaskLen[i]; }
    public int[] getDrawableMasks(int i) {
        int off = amMaskOff[i], len = amMaskLen[i];
        if (len <= 0) return new int[0];
        int[] masks = srcI32(Moc3.S_MASKS, moc.cntMasks);
        int[] out = new int[len];
        System.arraycopy(masks, off, out, 0, len);
        return out;
    }
    public int getPartCount() { return partCount; }
    public String getPartId(int i) { return moc.idAt(Moc3.S_PART_ID, i); }
    public float getPartOpacity(int i) { return partOpacity[i]; }
    public void setPartOpacity(int i, float v) { partInputOpacity[i] = v; }
    public float[] getDrawableMultiplyColor(int i) {
        return new float[]{artMeshMulColor[i * 4], artMeshMulColor[i * 4 + 1],
                artMeshMulColor[i * 4 + 2], artMeshMulColor[i * 4 + 3]};
    }
    public float[] getDrawableScreenColor(int i) {
        return new float[]{artMeshScrColor[i * 4], artMeshScrColor[i * 4 + 1],
                artMeshScrColor[i * 4 + 2], artMeshScrColor[i * 4 + 3]};
    }
}