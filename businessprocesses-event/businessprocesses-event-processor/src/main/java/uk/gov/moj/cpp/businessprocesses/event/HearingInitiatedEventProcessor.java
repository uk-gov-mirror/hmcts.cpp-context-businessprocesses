package uk.gov.moj.cpp.businessprocesses.event;


import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static uk.gov.justice.services.core.annotation.Component.EVENT_PROCESSOR;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.APPLICATION_DETAILS;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.APPLICATION_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.APPLICATION_REFERENCE;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.BPMN_PROCESS_HEARING_LISTED;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.CASEURN;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.CASE_DETAILS;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.CROWN_COURT_ADMIN_WORK_QUEUE_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.CROWN_JURISDICTION_TYPE;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.DEFENDANT_DETAILS;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.HEARING_DATE_TIME;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.JURISDICTION_TYPE;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.SUBJECT;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.SYSTEM_USER_NAME;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.TASK_NAME_BOOK_INTERPRETER_APPLICATION;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.TASK_NAME_BOOK_INTERPRETER_CASE;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.CASE_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.CASE_URN;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.DEFENDANT_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.DEFENDANT_NAME;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.HAS_CASE_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.HAS_INTERPRETER;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.HEARING_DATE;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.HEARING_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.JURISDICTION;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.LAST_UPDATED_BY_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.LAST_UPDATED_BY_NAME;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.NOTE;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.WORK_QUEUE;

import uk.gov.justice.services.core.annotation.Handles;
import uk.gov.justice.services.core.annotation.ServiceComponent;
import uk.gov.justice.services.core.dispatcher.SystemUserProvider;
import uk.gov.justice.services.core.featurecontrol.FeatureControlGuard;
import uk.gov.justice.services.messaging.JsonEnvelope;
import uk.gov.moj.cpp.businessprocesses.event.summonsapplication.SummonsApplicationHandler;
import uk.gov.moj.cpp.businessprocesses.service.TaskTypeService;
import uk.gov.moj.cpp.businessprocesses.shared.Constants;
import uk.gov.moj.cpp.businessprocesses.shared.HearingInitiatedCaseApplicationDetailsNotFoundException;
import uk.gov.moj.cpp.businessprocesses.shared.InterpreterForWelshActivityHandler;
import uk.gov.moj.cpp.businessprocesses.shared.NotesGenerator;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.camunda.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServiceComponent(EVENT_PROCESSOR)
@SuppressWarnings({"squid:S3457"})
public class HearingInitiatedEventProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(HearingInitiatedEventProcessor.class);

    @Inject
    private RuntimeService runtimeService;

    @Inject
    private TaskTypeService taskTypeService;

    @Inject
    private SystemUserProvider systemUserProvider;

    @Inject
    private FeatureControlGuard featureControlGuard;

    @Inject
    private NotesGenerator notesGenerator;

    @Inject
    private InterpreterForWelshActivityHandler interpreterForWelshActivityHandler;

    @Inject
    private SummonsApplicationHandler summonsApplicationTaskHandler;

    private static final String FEATURE_FLAG_INTERPRETER = "camunda-interpreter";

    @Handles("public.hearing.initiated")
    public void handleHearingInitiatedProcessor(final JsonEnvelope jsonEnvelope) {
        LOGGER.info("Event public.hearing.initiated with payload {} ", jsonEnvelope);
        if (featureControlGuard.isFeatureEnabled("camunda-hearing-initiated")) {
            handleHearingInitiated(jsonEnvelope);
        }
    }

    public void handleHearingInitiated(final JsonEnvelope jsonEnvelope) {

        final JsonObject eventPayload = jsonEnvelope.payloadAsJsonObject();
        final String hearingId = eventPayload.getString(Constants.HEARING_ID);
        final String hearingDate = eventPayload.getString(HEARING_DATE_TIME);
        final String jurisdiction = eventPayload.getString(JURISDICTION_TYPE);

        LOGGER.info("Received event 'public.hearing.initiated' for hearing: {}", hearingId);

        if (isApplicationDetailsExists(eventPayload)) {
            handleApplicationDetails(eventPayload.getJsonArray(APPLICATION_DETAILS), hearingId, hearingDate, jurisdiction);
            summonsApplicationTaskHandler.handleSummonsApplicationHearingInitiated(jsonEnvelope);
        } else {
            handleCaseDetails(eventPayload.getJsonArray(CASE_DETAILS), hearingId, hearingDate, jurisdiction);
        }
    }

    private boolean isApplicationDetailsExists(final JsonObject eventPayload) {
        return eventPayload.containsKey(APPLICATION_DETAILS)
                && eventPayload.getJsonArray(APPLICATION_DETAILS) != null
                && !eventPayload.getJsonArray(APPLICATION_DETAILS).isEmpty();
    }

    private void handleCaseDetails(final JsonArray caseDetails, final String hearingId, final String hearingDate, final String jurisdiction) {
        if (featureControlGuard.isFeatureEnabled(FEATURE_FLAG_INTERPRETER)) {
            extractCaseDetailsAndStartProcessForHearingInitiated(caseDetails, hearingId, hearingDate, jurisdiction);
            interpreterForWelshActivityHandler.handleWelshInterpreterForCaseInitiated(hearingId);
        }
    }

    private void handleApplicationDetails(final JsonArray applicationDetails, final String hearingId, final String hearingDate, final String jurisdiction) {
        if (featureControlGuard.isFeatureEnabled(FEATURE_FLAG_INTERPRETER)) {
            extractApplicationDetailsAndStartProcessForHearingInitiated(applicationDetails, hearingId, hearingDate, jurisdiction);
            interpreterForWelshActivityHandler.handleWelshInterpreterForApplicationInitiated(hearingId);
        }
    }

    private void extractCaseDetailsAndStartProcessForHearingInitiated(final JsonArray caseArray, final String hearingId, final String hearingDate, final String jurisdiction) {

        final JsonObject caseDetails = caseArray.getJsonObject(0);
        final String id = caseDetails.getString(CASE_ID);
        final JsonArray defendantsArray = caseDetails.getJsonArray(DEFENDANT_DETAILS);
        final String urn = caseDetails.containsKey(CASEURN) ? caseDetails.getString(CASEURN) : EMPTY;
        final String notes = notesGenerator.getNotesFromCaseDefendantsArray(caseArray);

        if (!defendantsArray.isEmpty()) {
            final JsonObject defendantDetails = defendantsArray.getJsonObject(0);
            extractDetailsAndStartProcessForHearingInitiated(id, CASE_ID, defendantDetails, hearingId, hearingDate, jurisdiction, TASK_NAME_BOOK_INTERPRETER_CASE, true, urn, notes);
        } else {
            throw new HearingInitiatedCaseApplicationDetailsNotFoundException(format("hearing initiated case details are empty need to investigate payload ", caseArray));
        }
    }

    private void extractApplicationDetailsAndStartProcessForHearingInitiated(final JsonArray applicationArray, final String hearingId, final String hearingDate, final String jurisdiction) {

        final JsonObject applicationDetails = applicationArray.getJsonObject(0);
        final String id = applicationDetails.getString(APPLICATION_ID);
        final String applicationReference = applicationDetails.containsKey(APPLICATION_REFERENCE) ? applicationDetails.getString(APPLICATION_REFERENCE) : EMPTY;
        final String notes = notesGenerator.getNotesFromApplicationDefendantsArray(applicationArray);

        if (!applicationDetails.isEmpty()) {
            final JsonObject defendantDetails = applicationDetails.getJsonObject(SUBJECT);
            extractDetailsAndStartProcessForHearingInitiated(id, APPLICATION_ID, defendantDetails, hearingId, hearingDate, jurisdiction, TASK_NAME_BOOK_INTERPRETER_APPLICATION, false, applicationReference, notes);
        } else {
            throw new HearingInitiatedCaseApplicationDetailsNotFoundException(format("hearing initiated application details are empty need to investigate payload ", applicationArray));
        }
    }

    private void extractDetailsAndStartProcessForHearingInitiated(final String id, final String idType, final JsonObject defendantDetails, final String hearingId, final String hearingDate, final String jurisdiction, final String taskName, final boolean hasCases, final String urn, final String notes) {

        final String defendantId = defendantDetails.getString(DEFENDANT_ID);
        final String defendantName = notesGenerator.buildDefendantName(defendantDetails);

        startProcessForHearingInitiated(hearingId, hearingDate, id, idType, defendantId, defendantName, jurisdiction, taskName, hasCases, urn, notes);
    }

    private void startProcessForHearingInitiated(final String hearingId, final String hearingDate, final String id, final String idType, final String defendantId, final String defendantName, final String jurisdiction, final String taskName, final boolean hasCases, final String urn, final String notes) {

        final Map<String, Object> processVariables = taskTypeService.getTaskVariablesFromRefDataWithPrefix(taskName, id, getPrefix(taskName), hearingDate);

        processVariables.put(HAS_INTERPRETER, !(notes.isEmpty()));
        processVariables.put(HAS_CASE_ID, hasCases);

        processVariables.put(idType, id);
        processVariables.put(DEFENDANT_ID, defendantId);
        processVariables.put(HEARING_ID, hearingId);
        processVariables.put(HEARING_DATE, hearingDate);
        processVariables.put(DEFENDANT_NAME, defendantName);
        processVariables.put(NOTE, notes);

        processVariables.put(CASE_URN, Objects.toString(urn, ""));

        if (CROWN_JURISDICTION_TYPE.equals(jurisdiction)) {
            processVariables.put(WORK_QUEUE, CROWN_COURT_ADMIN_WORK_QUEUE_ID);
        }

        final String userId = systemUserProvider.getContextSystemUserId().map(UUID::toString).orElse(null);
        processVariables.put(LAST_UPDATED_BY_ID, userId);
        processVariables.put(LAST_UPDATED_BY_NAME, SYSTEM_USER_NAME);
        processVariables.put(JURISDICTION, jurisdiction);
        runtimeService.startProcessInstanceByKey(BPMN_PROCESS_HEARING_LISTED, hearingId, processVariables);
        LOGGER.info("Hearing Initiated Process Started for hearingId {} processVariables {}", hearingId, processVariables);
    }

    private static String getPrefix(final String taskName) {
        return taskName + "_";
    }

}
