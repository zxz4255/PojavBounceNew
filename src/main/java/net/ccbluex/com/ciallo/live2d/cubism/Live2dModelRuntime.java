package com.ciallo.live2d.cubism;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-level model wrapper: parameter animation, motions, expressions and
 * parameter overrides on top of the pure Java {@link Live2dModel} runtime.
 *
 * This replaces the JNA/native CubismNativeModel from the original mod; the
 * public surface is intentionally identical so the rest of the client works
 * unchanged.
 */
public final class Live2dModelRuntime implements AutoCloseable {

    private final Live2dModel model;

    private final Map<String, CubismExpression> expressions = new LinkedHashMap<>();
    private final Map<String, Live2dMotion> motions = new LinkedHashMap<>();

    private String playingMotion;
    private float motionTime;
    private float motionFadeIn;
    private float motionFadeOut;

    private String playingExpression;
    private float expressionHoldRemaining;
    private float expressionFade;
    private float expressionWeight;

    private static final class ParamOverride {
        float value;
        float hold;
        float fade;
        float elapsed;
    }

    private final Map<String, ParamOverride> paramOverrides = new LinkedHashMap<>();

    public Live2dModelRuntime(byte[] mocBytes) {
        Moc3 moc = new Moc3(mocBytes);
        this.model = new Live2dModel(moc);
    }

    public Live2dModel raw() {
        return model;
    }

    // ------------------------------------------------------------- lifecycle

    public void update() {
        model.update();
    }

    @Override
    public void close() {
        // Pure Java runtime: nothing native to release.
    }

    // ------------------------------------------------------------- geometry

    public int getDrawableCount() {
        return model.getDrawableCount();
    }

    public float getCanvasWidth() {
        return model.getCanvasWidth();
    }

    public float getCanvasHeight() {
        return model.getCanvasHeight();
    }

    public float getOriginX() {
        return model.getCanvasOriginX();
    }

    public float getOriginY() {
        return model.getCanvasOriginY();
    }

    public float getPixelsPerUnit() {
        return model.getPixelsPerUnit();
    }

    public float getBboxAspect() {
        return model.getBboxAspect();
    }

    /** Computes the union bounding box of all enabled drawable vertices. */
    public float[] computeBounds() {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int count = model.getDrawableCount();
        for (int i = 0; i < count; i++) {
            if (!model.artMeshEnable[i]) continue;
            float[] verts = model.getDrawableVertexPositions(i);
            int vc = model.getDrawableVertexCount(i);
            for (int j = 0; j < vc; j++) {
                float x = verts[j * 2];
                float y = verts[j * 2 + 1];
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (minX == Float.POSITIVE_INFINITY) {
            minX = model.getCanvasOriginX() - model.getCanvasWidth() * 0.5f;
            maxX = model.getCanvasOriginX() + model.getCanvasWidth() * 0.5f;
            minY = model.getCanvasOriginY() - model.getCanvasHeight() * 0.5f;
            maxY = model.getCanvasOriginY() + model.getCanvasHeight() * 0.5f;
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    // ------------------------------------------------------------- parameters

    public void setParameter(String id, float value) {
        model.setParameter(id, value);
    }

    public boolean hasParameter(String id) {
        return model.hasParameter(id);
    }

    public void resetParametersToDefault() {
        model.resetParametersToDefault();
    }

    // ------------------------------------------------------------- expressions

    public boolean hasExpression(String name) {
        return expressions.containsKey(name);
    }

    public void addExpression(String name, CubismExpression expr) {
        expressions.put(name, expr);
    }

    public Map<String, CubismExpression> getExpressions() {
        return Collections.unmodifiableMap(expressions);
    }

    public void applyExpression(String name, float weight) {
        CubismExpression expr = expressions.get(name);
        if (expr == null) return;
        for (Map.Entry<String, Float> e : expr.getParameters().entrySet()) {
            int idx = model.findParameterIndex(e.getKey());
            if (idx < 0) continue;
            float base = model.getParameterDefault(idx);
            float min = model.getParameterMinimum(idx);
            float max = model.getParameterMaximum(idx);
            float value = Math.max(min, Math.min(max, base + e.getValue() * weight));
            model.setParameterValue(idx, value);
        }
    }

    // ------------------------------------------------------------- motions

    public void addMotion(String name, Live2dMotion motion) {
        motions.put(name, motion);
    }

    public boolean hasMotion(String name) {
        return motions.containsKey(name);
    }

    public Map<String, Live2dMotion> getMotions() {
        return Collections.unmodifiableMap(motions);
    }

    public String getPlayingMotion() {
        return playingMotion;
    }

    public void playMotion(String name) {
        Live2dMotion motion = motions.get(name);
        if (motion == null) {
            return;
        }
        playingMotion = name;
        motionTime = 0.0f;
        motionFadeIn = motion.getFadeIn();
        motionFadeOut = motion.getFadeOut();
    }

    public void stopMotion() {
        playingMotion = null;
    }

    public void playExpression(String name, float holdSeconds, float fadeSeconds) {
        if (!expressions.containsKey(name)) {
            return;
        }
        playingExpression = name;
        expressionHoldRemaining = Math.max(0.0f, holdSeconds);
        expressionFade = Math.max(0.05f, fadeSeconds);
        expressionWeight = 0.0f;
    }

    public void stopTransientExpression() {
        playingExpression = null;
        expressionWeight = 0.0f;
    }

    public void setParameterOverride(String id, float value, float hold, float fade) {
        if (!model.hasParameter(id)) {
            return;
        }
        ParamOverride o = new ParamOverride();
        o.value = value;
        o.hold = Math.max(0.0f, hold);
        o.fade = Math.max(0.05f, fade);
        o.elapsed = 0.0f;
        paramOverrides.put(id, o);
    }

    public void stopAllTransients() {
        playingMotion = null;
        playingExpression = null;
        expressionWeight = 0.0f;
        paramOverrides.clear();
    }

    public void updateTransientAnimations(float dt) {
        updateMotion(dt);
        updateExpressionOverlay(dt);
        updateParamOverrides(dt);
    }

    private void updateMotion(float dt) {
        if (playingMotion == null) {
            return;
        }
        Live2dMotion motion = motions.get(playingMotion);
        if (motion == null) {
            playingMotion = null;
            return;
        }
        motionTime += dt;
        if (motion.isLoop()) {
            motionTime = motionTime % motion.getDuration();
        } else if (motionTime >= motion.getDuration()) {
            playingMotion = null;
            return;
        }
        float weight = 1.0f;
        if (motionFadeIn > 0.0f && motionTime < motionFadeIn) {
            weight *= motionTime / motionFadeIn;
        }
        float remaining = motion.getDuration() - motionTime;
        if (motionFadeOut > 0.0f && remaining < motionFadeOut) {
            weight *= remaining / motionFadeOut;
        }
        if (weight <= 0.0001f) {
            return;
        }
        for (Live2dMotion.Curve curve : motion.getCurves()) {
            if (!"Parameter".equals(curve.target)) {
                continue;
            }
            int idx = model.findParameterIndex(curve.id);
            if (idx < 0) {
                continue;
            }
            float value = curve.valueAt(motionTime);
            if (Float.isNaN(value)) {
                continue;
            }
            float def = model.getParameterDefault(idx);
            setParameterRaw(idx, def + (value - def) * weight);
        }
    }

    private void updateExpressionOverlay(float dt) {
        if (playingExpression == null) {
            return;
        }
        if (expressionWeight < 1.0f) {
            expressionWeight = Math.min(1.0f, expressionWeight + dt / expressionFade);
        }
        if (expressionHoldRemaining > 0.0f) {
            expressionHoldRemaining -= dt;
            if (expressionHoldRemaining <= 0.0f) {
                expressionHoldRemaining = 0.0f;
            }
        } else if (expressionWeight > 0.0f) {
            expressionWeight = Math.max(0.0f, expressionWeight - dt / expressionFade);
        }
        applyExpression(playingExpression, expressionWeight);
        if (expressionWeight <= 0.0f) {
            playingExpression = null;
        }
    }

    private void updateParamOverrides(float dt) {
        var it = paramOverrides.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ParamOverride o = entry.getValue();
            o.elapsed += dt;
            int idx = model.findParameterIndex(entry.getKey());
            if (idx < 0) {
                it.remove();
                continue;
            }
            float weight;
            if (o.elapsed <= o.fade) {
                weight = o.elapsed / o.fade;
            } else if (o.elapsed <= o.fade + o.hold) {
                weight = 1.0f;
            } else if (o.elapsed <= o.fade + o.hold + o.fade) {
                weight = 1.0f - (o.elapsed - o.fade - o.hold) / o.fade;
            } else {
                it.remove();
                continue;
            }
            float def = model.getParameterDefault(idx);
            float clamped = Math.max(0.0f, Math.min(1.0f, weight));
            setParameterRaw(idx, def + (o.value - def) * clamped);
        }
    }

    private void setParameterRaw(int idx, float value) {
        float min = model.getParameterMinimum(idx);
        float max = model.getParameterMaximum(idx);
        model.setParameterValue(idx, Math.max(min, Math.min(max, value)));
    }

    // ------------------------------------------------------------- lists

    public List<String> getMotionNames() {
        return new ArrayList<>(motions.keySet());
    }

    public List<String> getExpressionNames() {
        return new ArrayList<>(expressions.keySet());
    }
}