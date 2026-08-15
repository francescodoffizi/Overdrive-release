package com.overdrive.app.bridge;

import android.webkit.JavascriptInterface;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatteryPowerData;
import com.overdrive.app.monitor.BatteryVoltageData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.json.JSONObject;

/**
 * JavaScript bridge for vehicle data access from HTML UI.
 * 
 * Exposes vehicle telemetry data to WebView JavaScript via @JavascriptInterface.
 * Provides both unified and individual data access methods.
 */
public class VehicleDataBridge {
    
    private static final String TAG = "VehicleDataBridge";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private final VehicleDataMonitor monitor;
    private String callbackName;
    
    public VehicleDataBridge() {
        this.monitor = VehicleDataMonitor.getInstance();
    }
    
    // ==================== DATA ACCESS ====================
    
    /**
     * Get all vehicle data as JSON string.
     * 
     * @return JSON string containing all vehicle telemetry
     */
    @JavascriptInterface
    public String getVehicleData() {
        try {
            JSONObject data = monitor.getAllData();
            return data.toString();
        } catch (Exception e) {
            logger.error("Failed to get vehicle data", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Get battery voltage level as JSON string.
     * 
     * @return JSON string with level, levelName, isWarning, timestamp
     */
    @JavascriptInterface
    public String getBatteryVoltageLevel() {
        try {
            BatteryVoltageData data = monitor.getBatteryVoltage();
            
            if (data == null) {
                return "{\"available\": false, \"error\": \"Data not available\"}";
            }
            
            JSONObject json = new JSONObject();
            json.put("available", true);
            json.put("level", data.level);
            json.put("levelName", data.levelName);
            json.put("isWarning", data.isWarning);
            json.put("timestamp", data.timestamp);
            json.put("description", getVoltageDescription(data));
            
            return json.toString();
            
        } catch (Exception e) {
            logger.error("Failed to get battery voltage level", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Get battery power voltage as JSON string.
     * 
     * @return JSON string with voltageVolts, isWarning, isCritical, healthStatus, timestamp
     */
    @JavascriptInterface
    public String getBatteryPowerVoltage() {
        try {
            BatteryPowerData data = monitor.getBatteryPower();
            
            if (data == null) {
                return "{\"available\": false, \"error\": \"Data not available\"}";
            }
            
            JSONObject json = new JSONObject();
            json.put("available", true);
            json.put("voltageVolts", data.voltageVolts);
            json.put("isWarning", data.isWarning);
            json.put("isCritical", data.isCritical);
            json.put("healthStatus", data.getHealthStatus());
            json.put("timestamp", data.timestamp);
            json.put("description", getPowerDescription(data));
            
            return json.toString();
            
        } catch (Exception e) {
            logger.error("Failed to get battery power voltage", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Get charging state as JSON string.
     * 
     * @return JSON string with state, stateName, status, isError, errorType, chargingPowerKW, isDischarging, timestamp
     */
    @JavascriptInterface
    public String getChargingState() {
        try {
            ChargingStateData data = monitor.getChargingState();
            
            if (data == null) {
                return "{\"available\": false, \"error\": \"Data not available\"}";
            }
            
            JSONObject json = new JSONObject();
            json.put("available", true);
            json.put("stateCode", data.stateCode);
            json.put("stateName", data.stateName);
            json.put("status", data.status.name());
            json.put("isError", data.isError);
            json.put("errorType", data.errorType);
            json.put("chargingPowerKW", data.chargingPowerKW);
            // isEstimated travels WITH the number. getChargingState() substitutes a nominal
            // 3.3/7.0 kW placeholder (or an inferred engine-power figure) whenever nothing has
            // resolved a real rate, so a consumer receiving the value alone cannot tell a
            // measurement from a guess and will render the guess as measured power.
            json.put("isEstimated", data.isEstimated);
            json.put("isDischarging", data.isDischarging);
            json.put("timestamp", data.timestamp);
            json.put("description", getChargingDescription(data));
            
            return json.toString();
            
        } catch (Exception e) {
            logger.error("Failed to get charging state", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Get charging power as JSON string.
     * 
     * @return JSON string with chargingPowerKW, isDischarging, timestamp
     */
    @JavascriptInterface
    public String getChargingPower() {
        try {
            ChargingStateData data = monitor.getChargingState();
            
            if (data == null) {
                return "{\"available\": false, \"error\": \"Data not available\"}";
            }
            
            JSONObject json = new JSONObject();
            json.put("available", true);
            json.put("chargingPowerKW", data.chargingPowerKW);
            // isEstimated travels WITH the number. getChargingState() substitutes a nominal
            // 3.3/7.0 kW placeholder (or an inferred engine-power figure) whenever nothing has
            // resolved a real rate, so a consumer receiving the value alone cannot tell a
            // measurement from a guess and will render the guess as measured power.
            json.put("isEstimated", data.isEstimated);
            json.put("isDischarging", data.isDischarging);
            json.put("timestamp", data.timestamp);
            json.put("description", getPowerFlowDescription(data.chargingPowerKW, data.isDischarging));
            
            return json.toString();
            
        } catch (Exception e) {
            logger.error("Failed to get charging power", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    // ==================== CALLBACK REGISTRATION ====================
    
    /**
     * Register a JavaScript callback for vehicle data updates.
     * 
     * @param callbackName The name of the JavaScript function to call
     */
    @JavascriptInterface
    public void onVehicleDataChanged(String callbackName) {
        this.callbackName = callbackName;
        logger.info("JavaScript callback registered: " + callbackName);
    }
    
    // ==================== HUMAN-READABLE DESCRIPTIONS ====================
    
    /**
     * Get human-readable description for voltage level.
     */
    private String getVoltageDescription(BatteryVoltageData data) {
        if (data.isWarning) {
            return "Low Battery - Check 12V battery";
        } else if (data.levelName.equals("NORMAL")) {
            return "Battery Normal";
        } else {
            return "Battery Status Invalid";
        }
    }
    
    /**
     * Get human-readable description for battery power.
     */
    private String getPowerDescription(BatteryPowerData data) {
        if (data.isCritical) {
            return "Critical - Battery voltage very low (" + String.format("%.1f", data.voltageVolts) + "V)";
        } else if (data.isWarning) {
            return "Warning - Battery voltage low (" + String.format("%.1f", data.voltageVolts) + "V)";
        } else {
            return "Battery healthy (" + String.format("%.1f", data.voltageVolts) + "V)";
        }
    }
    
    /**
     * Get human-readable description for charging state.
     */
    private String getChargingDescription(ChargingStateData data) {
        if (data.isError) {
            return "Charging Error: " + data.stateName;
        } else if (data.isDischarging) {
            return "Discharging (" + String.format("%.1f", Math.abs(data.chargingPowerKW)) + " KW)";
        } else if (data.status == ChargingStateData.ChargingStatus.CHARGING || data.isTaperCharging) {
            return "Charging (" + String.format("%.1f", data.chargingPowerKW) + " KW)";
        } else {
            return data.stateName;
        }
    }
    
    /**
     * Get human-readable description for power flow.
     */
    private String getPowerFlowDescription(double powerKW, boolean isDischarging) {
        if (isDischarging) {
            return "Discharging at " + String.format("%.1f", Math.abs(powerKW)) + " KW";
        } else if (powerKW > 0) {
            return "Charging at " + String.format("%.1f", powerKW) + " KW";
        } else {
            return "No power flow";
        }
    }
}
