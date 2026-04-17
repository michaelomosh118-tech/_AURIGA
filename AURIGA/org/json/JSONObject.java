package org.json;
import java.util.HashMap;
import java.util.Map;

public class JSONObject {
    private Map<String, Object> map = new HashMap<>();
    public JSONObject() {}
    public void put(String key, Object value) { map.put(key, value); }
    public String toString() { return map.toString(); }
}
