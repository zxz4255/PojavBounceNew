package com.ciallo.live2d.cubism;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * motion3.json parser and curve evaluator (pure Java).
 * Ported from the original MinecraftLive2d Live2dMotion (MIT).
 */
public final class Live2dMotion {

    public static final int LINEAR = 0;
    public static final int BEZIER = 1;
    public static final int STEPPED = 2;
    public static final int INVERSE_STEPPED = 3;

    public static final class Segment {
        final int type;
        final float startT;
        final float startV;
        final float endT;
        final float endV;
        final float c1v;
        final float c2v;

        Segment(int type, float startT, float startV, float endT, float endV, float c1v, float c2v) {
            this.type = type;
            this.startT = startT;
            this.startV = startV;
            this.endT = endT;
            this.endV = endV;
            this.c1v = c1v;
            this.c2v = c2v;
        }

        float valueAt(float t) {
            float span = endT - startT;
            if (span <= 0.00001f) {
                return endV;
            }
            float u = Math.max(0.0f, Math.min(1.0f, (t - startT) / span));
            switch (type) {
                case LINEAR:
                    return startV + (endV - startV) * u;
                case BEZIER: {
                    float omu = 1.0f - u;
                    return omu * omu * omu * startV + 3.0f * omu * omu * u * c1v
                            + 3.0f * omu * u * u * c2v + u * u * u * endV;
                }
                case STEPPED:
                    return startV;
                case INVERSE_STEPPED:
                    return endV;
                default:
                    return startV;
            }
        }
    }

    public static final class Curve {
        public final String target;
        public final String id;
        final List<Segment> segments = new ArrayList<>();

        Curve(String target, String id) {
            this.target = target;
            this.id = id;
        }

        float valueAt(float t) {
            if (segments.isEmpty()) {
                return 0.0f;
            }
            if (t <= segments.get(0).startT) {
                return segments.get(0).startV;
            }
            for (Segment seg : segments) {
                if (t <= seg.endT) {
                    return seg.valueAt(t);
                }
            }
            return segments.get(segments.size() - 1).endV;
        }
    }

    private final String name;
    private final float duration;
    private final boolean loop;
    private final float fadeIn;
    private final float fadeOut;
    private final List<Curve> curves = new ArrayList<>();

    public static Live2dMotion parse(String name, JsonObject json) {
        JsonObject meta = json.has("Meta") ? json.getAsJsonObject("Meta") : new JsonObject();
        float duration = meta.has("Duration") ? meta.get("Duration").getAsFloat() : 1.0f;
        boolean loop = meta.has("Loop") && meta.get("Loop").getAsBoolean();
        float fadeIn = meta.has("FadeInTime") ? meta.get("FadeInTime").getAsFloat() : 0.5f;
        float fadeOut = meta.has("FadeOutTime") ? meta.get("FadeOutTime").getAsFloat() : 0.5f;
        Live2dMotion motion = new Live2dMotion(name, duration, loop, fadeIn, fadeOut);

        if (json.has("Curves") && json.get("Curves").isJsonArray()) {
            for (var el : json.getAsJsonArray("Curves")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject c = el.getAsJsonObject();
                String target = c.has("Target") ? c.get("Target").getAsString() : "Parameter";
                String id = c.has("Id") ? c.get("Id").getAsString() : "";
                Curve curve = new Curve(target, id);

                if (c.has("Segments") && c.get("Segments").isJsonArray()) {
                    JsonArray segs = c.getAsJsonArray("Segments");
                    float prevT = 0.0f;
                    float prevV = 0.0f;
                    int i = 0;
                    if (segs.size() >= 2) {
                        prevT = segs.get(0).getAsFloat();
                        prevV = segs.get(1).getAsFloat();
                        i = 2;
                    }
                    while (i < segs.size()) {
                        int type;
                        try {
                            type = segs.get(i).getAsInt();
                        } catch (NumberFormatException e) {
                            break;
                        }
                        i++;
                        switch (type) {
                            case LINEAR:
                            case STEPPED:
                            case INVERSE_STEPPED: {
                                if (i + 1 < segs.size()) {
                                    float et = segs.get(i).getAsFloat();
                                    float ev = segs.get(i + 1).getAsFloat();
                                    i += 2;
                                    curve.segments.add(new Segment(type, prevT, prevV, et, ev, 0.0f, 0.0f));
                                    prevT = et;
                                    prevV = ev;
                                }
                                break;
                            }
                            case BEZIER: {
                                if (i + 5 < segs.size()) {
                                    float c1v = segs.get(i + 1).getAsFloat();
                                    float c2v = segs.get(i + 3).getAsFloat();
                                    float et = segs.get(i + 4).getAsFloat();
                                    float ev = segs.get(i + 5).getAsFloat();
                                    i += 6;
                                    curve.segments.add(new Segment(BEZIER, prevT, prevV, et, ev, c1v, c2v));
                                    prevT = et;
                                    prevV = ev;
                                }
                                break;
                            }
                            default:
                                i = segs.size();
                        }
                    }
                }
                motion.curves.add(curve);
            }
        }
        return motion;
    }

    private Live2dMotion(String name, float duration, boolean loop, float fadeIn, float fadeOut) {
        this.name = name;
        this.duration = Math.max(0.01f, duration);
        this.loop = loop;
        this.fadeIn = fadeIn;
        this.fadeOut = fadeOut;
    }

    public String getName() {
        return name;
    }

    public float getDuration() {
        return duration;
    }

    public boolean isLoop() {
        return loop;
    }

    public float getFadeIn() {
        return fadeIn;
    }

    public float getFadeOut() {
        return fadeOut;
    }

    public List<Curve> getCurves() {
        return curves;
    }

    public float valueForParameter(String paramId, float t) {
        for (Curve curve : curves) {
            if ("Parameter".equals(curve.target) && curve.id.equals(paramId)) {
                return curve.valueAt(t);
            }
        }
        return Float.NaN;
    }
}