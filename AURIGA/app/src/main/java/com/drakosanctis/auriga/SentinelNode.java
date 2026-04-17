package com.drakosanctis.auriga;

import org.json.JSONObject;
import java.util.UUID;

/**
 * SentinelNode: Implements vectorized security logic for the "God's Eye" mesh.
 * Focuses on metadata-first (JSON) data flow instead of raw video streaming.
 */
public class SentinelNode {
    private String nodeId;
    private String variant; // "CLASSIC" or "CAMALEO"
    private boolean isLowPowerMode = true;
    private float mountingHeight; // Inferred from calibration
    private float tiltAngle; // Inferred from calibration

    public SentinelNode(String variant) {
        this.nodeId = "SN-" + UUID.randomUUID().toString().substring(0, 8);
        this.variant = variant;
    }

    /**
     * Logic: Analyzes change vectors locally. 
     * Only triggers full logic if a "Skeleton" is identified.
     */
    public String processMotion(float deltaX, float deltaY, float width, float height) {
        if (!isHumanSignature(width, height)) {
            return null; // Silent Guardian: Ignore false positives (trees, shadows)
        }

        return generateThreatVector(deltaX, deltaY, height);
    }

    private boolean isHumanSignature(float width, float height) {
        // Human aspect ratio logic: height usually > 2x width
        return (height / width) > 1.8f && height > 0.5f;
    }

    /**
     * Vectorization: Converts 5MB video event into a 0.5KB JSON Packet.
     */
    private String generateThreatVector(float x, float y, float subjHeight) {
        try {
            JSONObject packet = new JSONObject();
            packet.put("constellation", "Sentinel");
            packet.put("node_id", nodeId);
            packet.put("variant", variant);
            packet.put("threat_level", "High");
            packet.put("spatial_id", "Subj_" + (int)(Math.random() * 1000));
            packet.put("height", String.format("%.2fm", subjHeight));
            
            JSONObject vector = new JSONObject();
            vector.put("bearing", (int)(Math.atan2(x, y) * 180 / Math.PI));
            vector.put("velocity", Math.sqrt(x*x + y*y));
            
            packet.put("vector", vector);
            packet.put("timestamp", System.currentTimeMillis());

            return packet.toString();
        } catch (Exception e) {
            return "{\"error\": \"Vectorization failed\"}";
        }
    }

    public void setLowPowerMode(boolean active) {
        this.isLowPowerMode = active;
        // Logic: If true, use Low-Power Frame Differencing at lower FPS
    }
}
