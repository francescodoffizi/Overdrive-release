package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.genai.GenAiContext;
import com.overdrive.app.genai.GenAiInsights;
import com.overdrive.app.server.Messages;

import java.util.List;
import java.util.Map;

/** Generate a stored AI insight without granting an automation vehicle control. */
public final class GenAiInsightAction extends BaseAction {

    private static final String TYPE = "genAiInsight";
    private final Label label = new Label(
            TYPE, "automation.generate_ai_insight");
    private final List<Type> variables = List.of(
            new EnumType(
                    new Label("mode", "automation.ai_insight_type"),
                    new Label(GenAiContext.OVERVIEW,
                            "automation.ai_insight_overview"),
                    new Label(GenAiContext.CURRENT_VEHICLE,
                            "automation.ai_insight_vehicle"),
                    new Label(GenAiContext.LATEST_TRIP,
                            "automation.ai_insight_trip"),
                    new Label(GenAiContext.TRIP_COMPARISON,
                            "automation.ai_insight_trip_comparison"),
                    new Label(GenAiContext.RECENT_EVENTS,
                            "automation.ai_insight_events"),
                    new Label(GenAiContext.ROADSENSE,
                            "automation.ai_insight_roadsense"),
                    new Label(GenAiContext.CHARGING,
                            "automation.ai_insight_charging"),
                    new Label(GenAiContext.DIAGNOSTICS,
                            "automation.ai_insight_diagnostics")),
            new EnumType(
                    new Label(
                            "delivery",
                            "automation.ai_insight_delivery"),
                    new Label(
                            GenAiInsights.DELIVERY_DASHBOARD,
                            "automation.ai_insight_dashboard"),
                    new Label(
                            GenAiInsights.DELIVERY_NOTIFICATION,
                            "automation.ai_insight_notification")),
            new StringType(
                    new Label(
                            "prompt",
                            "automation.ai_insight_focus"),
                    600));

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
        return Messages.get(
                "automation.generate_ai_insight_description");
    }

    @Override
    public List<Type> getVariables() {
        return variables;
    }

    @Override
    public void trigger(AutomationAction automationAction) {
        triggerWithResult(automationAction);
    }

    @Override
    public boolean triggerWithResult(
            AutomationAction automationAction) {
        Map<String, Object> values =
                automationAction.getVariables();
        String mode = string(values.get("mode"));
        String delivery = string(values.get("delivery"));
        String prompt = string(values.get("prompt"));
        return GenAiInsights.requestAsync(
                mode,
                prompt,
                GenAiInsights.DELIVERY_NOTIFICATION.equals(
                        delivery),
                "automation");
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
