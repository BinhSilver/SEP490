package com.jlas.shadowing.repository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

abstract class ShadowingRuntimeStore<T> {
    private final AtomicLong ids = new AtomicLong();
    protected final Map<Long, T> rows = new ConcurrentHashMap<>();
    public T save(T value) { Long id = id(value); if (id == null) { id = ids.incrementAndGet(); invoke(value, "setId", id); } rows.put(id, value); return value; }
    public Optional<T> findById(Long id) { return Optional.ofNullable(rows.get(id)); }
    public List<T> findAll() { return new ArrayList<>(rows.values()); }
    public void delete(T value) { Long id = id(value); if (id != null) rows.remove(id); }
    private static Long id(Object value) { Object id = invoke(value, "getId", null); return id instanceof Number n ? n.longValue() : null; }
    private static Object invoke(Object value, String name, Long arg) { try { Method m = arg == null ? value.getClass().getMethod(name) : value.getClass().getMethod(name, Long.class); return arg == null ? m.invoke(value) : m.invoke(value, arg); } catch (ReflectiveOperationException e) { return null; } }
}
