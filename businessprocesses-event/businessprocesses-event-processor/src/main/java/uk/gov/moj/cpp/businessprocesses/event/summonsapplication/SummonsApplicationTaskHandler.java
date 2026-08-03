package uk.gov.moj.cpp.businessprocesses.event.summonsapplication;

import static uk.gov.moj.cpp.businessprocesses.shared.Constants.APPLICATION_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.APPLICATION_REFERENCE;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.PROCESS_NEW_SUMMONS_APPLICATION;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.SYSTEM_USER_NAME;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.CASE_URN;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.COMPLETION_REASON;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.COURT_CODES;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.COURT_NAME;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.HEARING_DATE;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.HEARING_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.LAST_UPDATED_BY_ID;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.LAST_UPDATED_BY_NAME;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.NOTE;

import uk.gov.justice.services.core.dispatcher.SystemUserProvider;
import uk.gov.moj.cpp.businessprocesses.service.TaskTypeService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SummonsApplicationTaskHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SummonsApplicationTaskHandler.class);
    public static final String THE_APPLICATION_RESULTED_WITH = "The application resulted with ";

    @Inject
    private TaskTypeService taskTypeService;

    @Inject
    private SystemUserProvider systemUserProvider;

    @Inject
    private RuntimeService runtimeService;

    @Inject
    private TaskService taskService;


    public void startSummonsApplicationWorkFlow(final SummonsApplicationTaskRequest request) {

        final Map<String, Object> processVariables = taskTypeService.getTaskVariablesFromRefData(request.getTaskName(), request.getApplicationId().toString(), request.getHearingDate(), null);
        processVariables.put(APPLICATION_REFERENCE, request.getApplicationReference());
        processVariables.put(CASE_URN, request.getApplicationReference());
        processVariables.put(APPLICATION_ID, request.getApplicationId());
        processVariables.put(HEARING_ID, request.getHearingId());
        processVariables.put(COURT_NAME, request.getCourtName());
        processVariables.put(HEARING_DATE, request.getHearingDate());

        if (request.getCourtCode() != null) {
            processVariables.put(COURT_CODES, request.getCourtCode());
        }

        final String userId = systemUserProvider.getContextSystemUserId().map(UUID::toString).orElse(null);

        processVariables.put(LAST_UPDATED_BY_ID, userId);
        processVariables.put(LAST_UPDATED_BY_NAME, SYSTEM_USER_NAME);

        runtimeService.startProcessInstanceByKey(request.getProcessKey(), request.getApplicationReference(), processVariables);

        LOGGER.info("Summon Application Work Flow process started  with applicationReference {},  with processKey  {}", request.getApplicationReference(), request.getProcessKey());
    }

    public void completeSummonsApplicationWorkFlow(final String applicationUrn,
                                                   final String applicationResult, final String hearingId) {
        final List<Task> tasks = taskService.createTaskQuery()
                .processDefinitionKey(PROCESS_NEW_SUMMONS_APPLICATION)
                .processInstanceBusinessKey(applicationUrn)
                .processVariableValueEquals(HEARING_ID, hearingId)
                .active()
                .list();

        autoComplete(tasks, applicationResult);
    }

    private void autoComplete(final List<Task> tasks, final String applicationResult) {
        final String userId = systemUserProvider.getContextSystemUserId().map(UUID::toString).orElse(null);
        tasks.forEach(task -> {
            LOGGER.info("closing task {} with taskVariables {} and processKey {} ", task.getId(), taskService.getVariablesLocal(task.getId()), task.getProcessDefinitionId());
            taskService.setVariableLocal(task.getId(), LAST_UPDATED_BY_NAME, SYSTEM_USER_NAME);
            taskService.setVariableLocal(task.getId(), LAST_UPDATED_BY_ID, userId);
            taskService.setVariableLocal(task.getId(), COMPLETION_REASON, THE_APPLICATION_RESULTED_WITH + applicationResult);
            taskService.createComment(task.getId(), task.getProcessInstanceId(), THE_APPLICATION_RESULTED_WITH + applicationResult);
            taskService.complete(task.getId());
        });
    }

}
