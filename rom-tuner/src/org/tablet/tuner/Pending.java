package org.tablet.tuner;

import java.util.HashMap;
import java.util.Map;

/**
 * Values the user changed recently. The daemon publishes its state a few seconds after a change (the picture engine restarts first), so a
 * state read in between holds the old value. Overlaying these on every read keeps the number the user set (no Android types: tested on a JVM).
 */
final class Pending {
    static final long TTL_MS = 12000;
    private final Map<String, String> val = new HashMap<>();
    private final Map<String, Long> at = new HashMap<>();

    void put(String k, String v, long now) { val.put(k, v); at.put(k, now); }

    /** Fresh state with the recent changes laid over it; older entries are dropped. */
    Map<String, String> overlay(Map<String, String> fresh, long now) {
        java.util.Iterator<Map.Entry<String, Long>> it = at.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > TTL_MS) { val.remove(e.getKey()); it.remove(); }
            else fresh.put(e.getKey(), val.get(e.getKey()));
        }
        return fresh;
    }
}
