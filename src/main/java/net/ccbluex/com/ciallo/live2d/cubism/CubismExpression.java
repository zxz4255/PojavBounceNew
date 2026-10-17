package com.ciallo.live2d.cubism;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * exp3.json parser (pure Java). Ported from the original
 * MinecraftLive2d CubismExpression (MIT).
 */
public final class CubismExpression {

    public static CubismExpression parse(String name, JsonObject json) {
        Map<String, Float> parameters = new LinkedHashMap<>();
        if (json.has("Parameters") && json.get("Parameters").isJsonArray()) {
            for (var el : json.getAsJsonArray("Parameters")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                if (o.has("Id") && o.has("Value") && o.get("Value").isJsonPrimitive()
                        && o.get("Value").getAsJsonPrimitive().isNumber()) {
                    parameters.put(o.get("Id").getAsString(), o.get("Value").getAsFloat());
                }
            }
        } else {
            for (var entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    parameters.put(entry.getKey(), entry.getValue().getAsFloat());
                }
            }
        }
        return new CubismExpression(name, parameters);
    }

    private final String name;
    private final Map<String, Float> parameters;

    private CubismExpression(String name, Map<String, Float> parameters) {
        this.name = name;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public Map<String, Float> getParameters() {
        return parameters;
    }
}