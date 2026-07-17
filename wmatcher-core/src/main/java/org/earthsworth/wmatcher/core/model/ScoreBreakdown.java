package org.earthsworth.wmatcher.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ScoreBreakdown(double total, Map<String, Double> components) {
    public ScoreBreakdown {
        if (!Double.isFinite(total) || total < 0.0 || total > 1.0) {
            throw new IllegalArgumentException("Score must be between 0 and 1");
        }
        components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }
}
