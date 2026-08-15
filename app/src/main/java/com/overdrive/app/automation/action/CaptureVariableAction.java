package com.overdrive.app.automation.action;

import android.content.Context;
import android.media.AudioManager;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.server.Messages;

import java.util.List;
import java.util.Map;

/**
 * Captures a current vehicle value into a named automation variable.
 *
 * <p>Unlike a dynamic reference, the stored value is a snapshot. This lets a
 * rule remember the current media volume before changing it, or retain the SOC
 * at the point a trip phase starts.
 */
public class CaptureVariableAction extends BaseAction {
    private static final String TYPE = "captureVariable";
    private static final String SOURCE_MEDIA_VOLUME = "mediaVolume";
    private static final String SOURCE_BATTERY_SOC = "batterySoc";

    private final Label label;
    private final String description;
    private final List<Type> variables = List.of(
            new EnumType(new Label("source", "automation.capture_source"),
                    new Label(SOURCE_MEDIA_VOLUME, "automation.capture_media_volume"),
                    new Label(SOURCE_BATTERY_SOC, "automation.capture_battery_soc")),
            new StringType(new Label("name", "automation.variable_name"), SetVariableAction.MAX_NAME));

    public CaptureVariableAction(Label label, String description) {
        this.label = label;
        this.description = description;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Label getLabel() {
        return label;
    }

    @Override
    public String getDescription() {
        return Messages.get(description);
    }

    @Override
    public List<Type> getVariables() {
        return variables;
    }

    @Override
    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String name = string(vars.get("name"));
        if (name == null || name.trim().isEmpty()) {
            logger.warn("CaptureVariableAction: missing variable name, skipping");
            return;
        }
        name = name.trim();
        if (name.length() > SetVariableAction.MAX_NAME) {
            name = name.substring(0, SetVariableAction.MAX_NAME);
        }

        String source = string(vars.get("source"));
        String value;
        if (SOURCE_MEDIA_VOLUME.equals(source)) {
            value = readMediaVolume();
        } else if (SOURCE_BATTERY_SOC.equals(source)) {
            value = readBatterySoc();
        } else {
            logger.warn("CaptureVariableAction: unknown source '" + source + "'");
            return;
        }
        if (value == null) {
            logger.warn("CaptureVariableAction: " + source + " is unavailable; leaving '"
                    + name + "' unchanged");
            return;
        }

        Automations.update(SetVariableAction.variableEvent(name), value);
        logger.info("CaptureVariableAction: " + name + " = " + value + " [" + source + "]");
    }

    private static String readMediaVolume() {
        try {
            Context context = CameraDaemon.getAppContext();
            if (context == null) return null;
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null || audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) <= 0) {
                return null;
            }
            int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            return volume >= 0 ? Integer.toString(volume) : null;
        } catch (Throwable t) {
            logger.warn("CaptureVariableAction: media volume read failed: " + t.getMessage());
            return null;
        }
    }

    private static String readBatterySoc() {
        try {
            com.overdrive.app.automation.value.Value state =
                    Automations.getStateValue(BydEvent.BATTERY_LEVEL);
            if (state instanceof com.overdrive.app.automation.value.BaseValue<?>) {
                Object value = ((com.overdrive.app.automation.value.BaseValue<?>) state).getValue();
                if (value != null) return value.toString();
            }
        } catch (Throwable ignored) {
            // Fall through to the latest snapshot; it can be present before state is seeded.
        }
        try {
            BydVehicleData data = BydDataCollector.getInstance().getData();
            if (data == null || Double.isNaN(data.socPercent)
                    || data.socPercent < 0 || data.socPercent > 100) {
                return null;
            }
            return Integer.toString((int) data.socPercent);
        } catch (Throwable t) {
            logger.warn("CaptureVariableAction: SOC read failed: " + t.getMessage());
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
