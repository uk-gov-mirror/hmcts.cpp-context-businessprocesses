package uk.gov.moj.cpp.businessprocesses.event.summonsapplication;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.justice.services.core.dispatcher.SystemUserProvider;
import uk.gov.moj.cpp.businessprocesses.service.TaskTypeService;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.task.TaskQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SummonsApplicationTaskHandlerTest {

    @Mock
    private TaskTypeService taskTypeService;

    @Mock
    private SystemUserProvider systemUserProvider;

    @Mock
    private RuntimeService runtimeService;

    @Mock
    private TaskService taskService;

    @InjectMocks
    private SummonsApplicationTaskHandler summonsApplicationTaskHandler;

    @Captor
    private ArgumentCaptor<Map> argumentCaptor;

    private static final UUID applicationId = UUID.randomUUID();
    private static final String processKey = "processKey";
    private static final String courtName = "courtName";
    private static final String taskName = "taskName";
    private static final String hearingId = UUID.randomUUID().toString();
    private static final String hearingDate = ZonedDateTime.now().toString();
    private static final String applicationReference = "applicationReference";
    private static final String applicationResult = "Summons Approved";
    private static final UUID userId = UUID.randomUUID();

    @Test
    void startSummonsApplicationWorkFlow() {
        // given
        when(taskTypeService.getTaskVariablesFromRefData(taskName, applicationId.toString(), hearingDate, null)).thenReturn(new HashMap<>());

        final SummonsApplicationTaskRequest request =
                new SummonsApplicationTaskRequest
                        .Builder()
                        .withApplicationId(applicationId)
                        .withProcessKey(processKey)
                        .withCourtName(courtName)
                        .withTaskName(taskName)
                        .withHearingId(hearingId)
                        .withHearingDate(hearingDate)
                        .withApplicationReference(applicationReference)
                        .build();

        // when
        summonsApplicationTaskHandler.startSummonsApplicationWorkFlow(request);

        // then
        verify(runtimeService, times(1)).startProcessInstanceByKey(eq(processKey), eq(applicationReference), argumentCaptor.capture());
        final Map<String, Object> map = argumentCaptor.getValue();

        assertThat(map.get("applicationReference"), is(applicationReference));
        assertThat(map.get("applicationId"), is(applicationId));
        assertThat(map.get("courtName"), is(courtName));
        assertThat(map.get("hearingDate"), is(hearingDate));
        assertThat(map.get("hearingId"), is(hearingId));
    }

    @Test
    void completeSummonsApplicationWorkFlow() {
        // given
        final String taskId = "123";
        final Task task = Mockito.mock(Task.class);
        final TaskQuery taskQuery = Mockito.mock(TaskQuery.class);

        when(systemUserProvider.getContextSystemUserId()).thenReturn(Optional.of(userId));
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processDefinitionKey("process_new_summons_application")).thenReturn(taskQuery);
        when(taskQuery.processInstanceBusinessKey(applicationReference)).thenReturn(taskQuery);
        when(taskQuery.processVariableValueEquals("hearingId", hearingId)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(Collections.singletonList(task));
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(taskId);

        // when
        summonsApplicationTaskHandler.completeSummonsApplicationWorkFlow(applicationReference, applicationResult, hearingId);

        // then
        verify(taskQuery, times(1)).processDefinitionKey("process_new_summons_application");
        verify(taskQuery, times(1)).processInstanceBusinessKey(applicationReference);
        verify(taskQuery, times(1)).active();

        verify(taskService).createTaskQuery();
        verify(taskService).setVariableLocal("123", "lastUpdatedByName", "SYSTEM");
        verify(taskService).setVariableLocal("123", "lastUpdatedByID", userId.toString());
        verify(taskService).createComment("123", "123", "The application resulted with Summons Approved");
        verify(taskService).complete("123");
    }
}