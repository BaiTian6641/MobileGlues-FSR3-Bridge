package com.googlebook.fsrbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Selects pure viewport dependents in the shader mod's existing evaluation order. */
public final class ViewportUniformPlan {
    private ViewportUniformPlan() {}

    public static <T> List<T> create(List<T> order, Map<T, ? extends List<T>> dependencies,
                                   Function<T, String> name, Predicate<String> pureExpression) {
        Set<T> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        List<T> result = new ArrayList<>();
        for (T uniform : order) {
            String key = name.apply(uniform);
            boolean needed = "viewWidth".equals(key) || "viewHeight".equals(key);
            if (!needed && pureExpression.test(key)) {
                List<T> inputs = dependencies.get(uniform);
                if (inputs != null) {
                    for (T input : inputs) {
                        if (selected.contains(input)) {
                            needed = true;
                            break;
                        }
                    }
                }
            }
            if (needed && selected.add(uniform)) result.add(uniform);
        }
        return List.copyOf(result);
    }
}
