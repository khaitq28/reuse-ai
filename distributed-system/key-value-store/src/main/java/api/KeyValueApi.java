package api;


import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PUT /keys/{key} with a string body → stores the value
 * GET /keys/{key} → returns the value or 404
 * DELETE /keys/{key} → removes the key
 */

@AllArgsConstructor
@RestController
@RequestMapping("/api/key-value")
public class KeyValueApi {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @PutMapping("/keys/{key}")
    public ResponseEntity<Void> putKey(@PathVariable String key, @RequestBody String value) {
        store.put(key, value);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/keys/{key}")
    public ResponseEntity<String> getKey(@PathVariable String key) {
        String value = store.get(key);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }

    @DeleteMapping("/keys/{key}")
    public ResponseEntity<Void> deleteKey(@PathVariable String key) {
        store.remove(key);
        return ResponseEntity.ok().build();
    }

}