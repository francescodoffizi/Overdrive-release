package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class GenAiActionTest {

    @Test
    public void climateProposalIsLocallyValidatedAndCanonicalized()
            throws Exception {
        GenAiAction.Proposal proposal =
                GenAiAction.parseProviderProposal(
                        new JSONObject()
                                .put("reply", "Confirm it")
                                .put("needsInput", false)
                                .put("actionType",
                                        "climate_temperature")
                                .put("temperatureC", 22)
                                .put("zone", 0)
                                .put("operation", "none")
                                .put("automationId", "")
                                .toString());

        assertNotNull(proposal.action);
        assertEquals("climate_temperature",
                proposal.action.getString("type"));
        assertEquals(22.0,
                proposal.action.getDouble("temperatureC"), 0.01);
        assertEquals(0, proposal.action.getInt("zone"));
    }

    @Test(expected = GenAiAction.ValidationException.class)
    public void outOfRangeClimateProposalIsRejected()
            throws Exception {
        GenAiAction.parseToolArguments(new JSONObject()
                .put("actionType", "climate_temperature")
                .put("temperatureC", 50)
                .put("zone", 0)
                .put("operation", "none")
                .put("automationId", ""));
    }

    @Test
    public void incompleteActionKeepsClarificationMode()
            throws Exception {
        GenAiAction.Proposal proposal =
                GenAiAction.parseProviderProposal(
                        new JSONObject()
                                .put("reply", "Which temperature?")
                                .put("needsInput", true)
                                .put("actionType", "none")
                                .put("temperatureC", -1)
                                .put("zone", -1)
                                .put("operation", "none")
                                .put("automationId", "")
                                .toString());

        assertTrue(proposal.needsInput);
        assertEquals(null, proposal.action);
    }

    @Test
    public void missingActionAlwaysKeepsClarificationMode()
            throws Exception {
        GenAiAction.Proposal proposal =
                GenAiAction.parseProviderProposal(
                        new JSONObject()
                                .put("reply", "Which temperature?")
                                .put("needsInput", false)
                                .put("actionType", "none")
                                .put("temperatureC", -1)
                                .put("zone", -1)
                                .put("operation", "none")
                                .put("automationId", "")
                                .toString());

        assertTrue(proposal.needsInput);
    }
}
