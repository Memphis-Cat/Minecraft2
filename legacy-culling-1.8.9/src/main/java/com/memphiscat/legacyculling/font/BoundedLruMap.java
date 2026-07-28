package com.memphiscat.legacyculling.font;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BoundedLruMap<K, V> extends LinkedHashMap<K, V> {
    private final int maximumSize;

    public BoundedLruMap(int maximumSize) {
        super(maximumSize, 0.75F, true);
        this.maximumSize = maximumSize;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maximumSize;
    }
}
