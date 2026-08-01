package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.mqtt.VehicleControlCatalog;
import com.overdrive.app.server.Messages;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class VehicleControlAction extends BaseAction {
    private static final String TYPE = "vehicle-control";

    private final Label label;
    private final String description;
    private final List<Type> variables;

    /**
     * An action to send a vehicle control event
     * The variables passed in to this will be concatenated for the payload
     *
     * This is deprecated in favour of the ApiAction
     * Some actions will still use this for backward compatibility
     *
     * @param label       The label for this notification with an id and display name
     * @param description The description for this action
     * @param variables   The variables to concatenate for the payload
     */
    @Deprecated
    public VehicleControlAction(Label label, String description, Type... variables) {
        this.label = label;
        this.description = description;
        this.variables = List.of(variables);
    }

    /**
     * A string id for this action
     *
     * @return String representing this action
     */
    public String getType() {
        return TYPE;
    }

    /**
     * The label that was stored when this Action was initialized
     *
     * @return The Label for this action
     */
    public Label getLabel() {
        return label;
    }

    /**
     * The description for this action
     * Will be translated using the language files
     *
     * @return The description for this action
     */
    public String getDescription() {
        return Messages.get(description);
    }

    /**
     * The variables for this action
     *
     * @return The variables for this action
     */
    public List<Type> getVariables() {
        return variables;
    }

    /**
     * Trigger a vehicle control action
     * The variables stored will be concatenated and sent as the control payload
     * <p>
     * This method needs to be updated to implement the sub variable to allow more vehicle controls
     *
     * @param automationAction The AutomationAction with the variables needed to trigger this action
     */
    public void trigger(AutomationAction automationAction) {
        VehicleControlCatalog.ControlEntity entity = null;
        if (automationAction.getVariables().size() > 1) {
            // Add variables other than the key to the vehicle control id to allow seat_heat_driver and others to be built with variables
            String suffix = this.variables.stream()
                    .filter(variable -> !"payload".equals(variable.getLabel().getId()))
                    .map(variable -> Objects.requireNonNullElse(automationAction.getVariables().get(variable.getLabel().getId()), "").toString())
                    .filter(variable -> !variable.isBlank())
                    .collect(Collectors.joining("_"));
            logger.info("Built entity with variables: " + getLabel().getId() + "_" + suffix);
            if (!suffix.isBlank()) {
                entity = VehicleControlCatalog.get(getLabel().getId() + "_" + suffix);
            }
        }
        if (entity == null) entity = VehicleControlCatalog.get(getLabel().getId());
        if (entity == null) {
            logger.error("Entity for vehicle control automation missing: " + getLabel().getId());
            return;
        }
        // Vehicle control currently only uses a single variable for an action
        String payload = Objects.requireNonNullElse(automationAction.getVariables().get("payload"), "").toString();
        // TODO: Add other variables in to sub for actions like climate control
        // Pass the latest vehicle snapshot so composite commands can preserve sibling state (e.g. a
        // seat_heat_driver command reads the other zones' current levels from the snapshot; a null
        // snapshot would reset them all to 0). getData() may still be null before the first collect,
        // which the catalog handlers already tolerate.
        BydVehicleData snapshot = BydDataCollector.getInstance().getData();
        VehicleControlCatalog.ControlAction action = entity.toAction(null, payload, snapshot);
        if (action == null || action.command == null) {
            logger.error("Action for vehicle control automation entity missing: " + getLabel().getId());
            return;
        }

        VehicleCommandRouter.CommandResult result =
                VehicleCommandRouter.getInstance().execute(action.command);
        // Record what we commanded for a blind-toggle entity once it reached the vehicle, but
        // not when it was refused before getting there (see ControlAction.commitIfAttempted).
        if (result != null) action.commitIfAttempted(result.outcome);
        if (result != null && result.outcome != VehicleCommandRouter.Outcome.SUCCESS) {
            logger.warn("Vehicle control '" + getLabel().getId() + "' payload='" + payload
                    + "' -> " + action.command.name() + " " + result.outcome
                    + " (" + result.displayMessage + ")");
        }
    }
}
