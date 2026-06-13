package api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class KeyValueExtendApi {

    /**
     * Follow-up questions interviewers will ask at this stage:
     *
     * "What if two clients PUT the same key at the same time?" — Last write wins in ConcurrentHashMap.
         Is that acceptable? Could you add a version/ETag for conditional writes?
     * "What is the memory limit? What happens when the store is full?" —
        You need an eviction policy (LRU, LFU, FIFO).
        Implement LRU with LinkedHashMap(capacity, 0.75f, true) in access-order mode, wrapped with synchronizedMap.
     * "How would you add a TTL (time-to-live) per key?" — Store (value, expiresAt) pairs; run a background ScheduledExecutorService to sweep expired keys; or use lazy expiration on GET.
     */

    /**
     *  we want versioning. Here's the real-world motivation:
     *
     *   Imagine a config store where two services both read timeout=30, one wants to set it to 60, the other to 15. Without versioning, the last write silently overwrites the other — no error, no warning. The
     *   losing service doesn't even know. That's a silent data loss bug in production.
     *   So the requirement is:
     *   ▎ A client that wants to update a key must prove it's working from the latest version. If someone else updated the key since the client last read it, the write must be rejected — not silently
     *   ▎ overwritten.
     *   This is called optimistic locking — you don't lock before writing, you detect conflicts at write time.
     */

    /**
     * add LRU eviction policy
     * The store will evict the least recently used key when it reaches a certain capacity,
     */
    record Value(String value, int version) {}

    private final Map<String, Value> store = Collections.synchronizedMap(new LinkedHashMap<>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Value> eldest) {
            return size() > 100;
            // Evict the least recently used key when the store reaches a capacity of 100
        }
    });

    @PutMapping("/keys/{key}")
    public ResponseEntity<Void> putKey(@PathVariable String key, @RequestBody String value,
                                        @RequestHeader(value = "If-Match", required = false) Long versionToUpdate ) {

        Value existing = store.putIfAbsent(key, new Value(value, 1));
        if (existing == null) {
            return ResponseEntity.noContent().build();
        }

        if (versionToUpdate == null) {
            // just update, don't care about version, but we need to increment the version
            Value current = existing;
            while (!store.replace(key, current, new Value(value, current.version + 1))) {
                current = store.get(key);
            }
            return ResponseEntity.noContent().build();
        }

        if (existing.version != versionToUpdate) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build(); // Precondition Failed
        }
        boolean inserted = store.replace(key, existing, new Value(value, existing.version + 1));
        if (!inserted) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/keys/{key}")
    public ResponseEntity<Value> getKey(@PathVariable String key) {
        Value value = store.get(key);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }

    @DeleteMapping("/keys/{key}")
    public ResponseEntity<Void> deleteKey(@PathVariable String key) {
        store.remove(key);
        return ResponseEntity.ok().build();
    }

}
