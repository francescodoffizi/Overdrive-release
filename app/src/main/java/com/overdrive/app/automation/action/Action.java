package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;

public interface Action {
    /**
     * Get the label of this action with an id and display value
     *
     * @return The label for this type
     */
    Label getLabel();

    /**
     * Trigger the action using the parameters from an AutomationAction
     * The automation action will have been built using the fromJson method in this interface
     * All the variables required to trigger this action should be set in the fromJson method
     *
     * @param automationAction The AutomationAction with the variables needed to trigger this action
     */
    void trigger(AutomationAction automationAction);

    /**
     * Trigger an action and report whether it completed successfully when the implementation has
     * an authoritative result. Most actions have no result channel, so returning from
     * {@link #trigger(AutomationAction)} is their successful completion contract.
     */
    default boolean triggerWithResult(AutomationAction automationAction) {
        trigger(automationAction);
        return true;
    }


    /**
     * Create a JSON object with the fields required for the frontend to display
     *
     * @return JSON representation of this type
     */
    JSONObject toJson();

    /**
     * An automation action with the id of this instance and any variables needed for the action trigger
     *
     * @param input The JSON passed from the frontend
     * @return An AutomationAction that can later be used to trigger this action
     */
    AutomationAction fromJson(JSONObject input);

    /**
     * Whether this action carries NESTED child action lists (a loop body, or if/else
     * branches). Only the control-flow actions override this to true; every ordinary
     * action leaves it false, so the parser never looks for {@code childActions} on a
     * flat action and a pre-existing automation is unaffected.
     *
     * @return true if this action's JSON may contain childActions/elseActions to parse
     */
    default boolean hasChildActions() { return false; }
}
