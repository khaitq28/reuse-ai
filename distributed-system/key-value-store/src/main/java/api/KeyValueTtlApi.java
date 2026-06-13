package api;

import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/key-value-ttl")
public class KeyValueTtlApi {

    /**
     * Two expiration strategies:
     *
     * Lazy expiration (on GET): check expiresAt at read time, return 404 if expired. Simple, no background thread. Downside: expired keys stay in memory until someone reads them — memory leak for write-heavy, read-light patterns.
     * Eager expiration (background sweep): a ScheduledExecutorService runs every N seconds, scans all keys, removes expired ones. Memory is reclaimed proactively. Downside: extra thread, sweep cost proportional to store size.
     * Production systems (Redis) use both: lazy check on every access + a background sampler that randomly picks a bucket and sweeps expired keys. This bounds both memory waste and CPU cost.
     * API change needed:
     * PUT /keys/{key}?ttl=30    → stores the value, expires in 30 seconds
     * GET /keys/{key}           → returns 404 if expired (same as missing)
     * Data structure:
     * record Value(String value, int version, long expiresAt) {}
     * // expiresAt = System.currentTimeMillis() + ttlSeconds * 1000
     * // expiresAt = 0 means no expiry
     */

    record Value(String value, long expiresAt) {}

    private final Map<String, Value> store = new ConcurrentHashMap<>();

    @PutMapping("/keys/{key}")
    public ResponseEntity<Void> putKey(@PathVariable String key,
                                       @RequestParam(required = false) Long ttlSeconds,
                                       @RequestBody String value) {
        if (ttlSeconds == null) {
            store.put(key, new Value(value, 0));
        } else {
            store.put(key, new Value(value, System.currentTimeMillis() + ttlSeconds * 1000));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/keys/{key}")
    public ResponseEntity<String> getKey(@PathVariable String key) {
        Value value = store.get(key);
        if (value != null && value.expiresAt > 0 && value.expiresAt <= System.currentTimeMillis()) {
            store.remove(key);
            value = null;
        }
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value.value());
    }

    @DeleteMapping("/keys/{key}")
    public ResponseEntity<Void> deleteKey(@PathVariable String key) {
        store.remove(key);
        return ResponseEntity.ok().build();
    }


    @Scheduled(fixedRate = 60000) // every 60 seconds
    private void evictExpiredKeys() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> entry.getValue().expiresAt > 0 && entry.getValue().expiresAt <= now);
    }
}
