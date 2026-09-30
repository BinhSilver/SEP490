package com.jlas.kaiwa.repository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Small process-local adapter for scenario flow fragments stored in scenario.flow_json. */
abstract class RuntimeScenarioStore<T> {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, T> rows = new ConcurrentHashMap<>();

    public T save(T value) {
        Long id = number(value, "getId");
        if (id == null) {
            id = ids.incrementAndGet();
            invoke(value, "setId", Long.class, id);
        }
        rows.put(id, value);
        return value;
    }

    public Optional<T> findById(Long id) { return Optional.ofNullable(rows.get(id)); }

    public List<T> findAll() { return new ArrayList<>(rows.values()); }

    protected List<T> byScenario(Long scenarioId) {
        return rows.values().stream()
                .filter(value -> {
                    Object scenario = invoke(value, "getScenario");
                    return scenario != null && scenarioId.equals(number(scenario, "getId"));
                })
                .sorted(Comparator.comparingInt(value -> {
                    Number order = number(value, "getDisplayOrder");
                    return order == null ? 0 : order.intValue();
                }))
                .toList();
    }

    protected static Object invoke(Object target, String name, Class<?> type, Object value) {
        try { return target.getClass().getMethod(name, type).invoke(target, value); }
        catch (ReflectiveOperationException ignored) { return null; }
    }

    protected static Object invoke(Object target, String name) {
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (ReflectiveOperationException ignored) { return null; }
    }

    protected static Long number(Object target, String getter) {
        Object value = invoke(target, getter);
        return value instanceof Number number ? number.longValue() : null;
    }
}
