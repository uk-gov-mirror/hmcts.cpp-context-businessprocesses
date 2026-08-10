package uk.gov.moj.cpp.businessprocesses.delegate;

import static java.time.LocalDate.now;
import static java.time.LocalDate.parse;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.toList;
import static uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder;
import static org.apache.commons.collections.CollectionUtils.isEmpty;
import static org.apache.commons.collections.CollectionUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.TASK_VARIABLES_JSON_STRING;
import static uk.gov.moj.cpp.businessprocesses.shared.DateConverter.isFutureDate;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.CASE_URN;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.CUSTODY_TIME_LIMIT;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.HEARING_DATE;
import static uk.gov.moj.cpp.businessprocesses.shared.ProcessVariableConstants.HEARING_TYPE;

import uk.gov.justice.courts.progression.query.Caag;
import uk.gov.justice.courts.progression.query.Defendants;
import uk.gov.justice.listing.events.Hearing;
import uk.gov.justice.listing.events.HearingDay;
import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.ObjectToJsonValueConverter;
import uk.gov.justice.services.common.converter.StringToJsonObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;
import uk.gov.moj.cpp.businessprocesses.create.CreateCustomTask;
import uk.gov.moj.cpp.businessprocesses.service.ListingService;
import uk.gov.moj.cpp.businessprocesses.service.ProgressionService;
import uk.gov.moj.cpp.businessprocesses.shared.StartDateAndTimeComparator;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;

@Named("customTaskServiceTask")
public class CustomTaskServiceTask implements JavaDelegate {

    private static final Logger LOGGER = getLogger(CustomTaskServiceTask.class);
    private static final Comparator<HearingDay> compareByStartDateTime = Comparator.comparing(HearingDay::getStartTime);
    private final ObjectMapper objectMapper = new ObjectMapperProducer().objectMapper();
    private final ObjectToJsonValueConverter objectToJsonValueConverter = new ObjectToJsonValueConverter(objectMapper);

    @Inject
    private ListingService listingService;

    @Inject
    private ProgressionService progressionService;

    @Inject
    private JsonObjectToObjectConverter jsonObjectToObjectConverter;

    @Inject
    private StringToJsonObjectConverter stringToJsonObjectConverter;

    @Override
    public void execute(final DelegateExecution delegateExecution) throws Exception {
        CreateCustomTask createCustomTask;
        if (delegateExecution.getVariable(TASK_VARIABLES_JSON_STRING) instanceof String) {
            final JsonObject taskVariablesJsonString = stringToJsonObjectConverter.convert((String) delegateExecution.getVariable(TASK_VARIABLES_JSON_STRING));
            createCustomTask = jsonObjectToObjectConverter.convert(taskVariablesJsonString, CreateCustomTask.class);
        } else {
            createCustomTask = objectMapper.convertValue(delegateExecution.getVariable(TASK_VARIABLES_JSON_STRING), CreateCustomTask.class);
        }
        final JsonObject taskVariablesPayloadJsonObject = objectMapper.convertValue(createCustomTask, JsonObject.class);
        final String userId = (String) delegateExecution.getVariable("userId");
        LOGGER.info("Task Variables in service task are {} with userid {}", createCustomTask, userId);
        final JsonObjectBuilder taskVariablesJsonObjectBuilder = createObjectBuilder();
        final String caseId = createCustomTask.getCaseId();
        if (nonNull(caseId)) {
            final Caag caag = progressionService.getProsecutionCaseCaag(userId, caseId);

            if (nonNull(caag) && isEmpty(caag.getLinkedApplications())) {
                final List<Hearing> hearingList = listingService.getHearings(userId, caseId);
                updateCtlTimeLimitInJsonObject(caag, taskVariablesJsonObjectBuilder);
                updateLatestHearingDateAndTypeInJsonObject(hearingList, taskVariablesJsonObjectBuilder);
            }
        }

        taskVariablesPayloadJsonObject.forEach(taskVariablesJsonObjectBuilder::add);
        delegateExecution.setVariable(CASE_URN, createCustomTask.getCaseURN());
        delegateExecution.setVariable(TASK_VARIABLES_JSON_STRING, taskVariablesJsonObjectBuilder.build().toString());
    }

    private void updateCtlTimeLimitInJsonObject(final Caag caag, final JsonObjectBuilder taskVariablesJsonObjectBuilder) {
        if (isNull(caag) || isEmpty(caag.getDefendants())) {
            return;
        }

        final List<Defendants> defendantList = caag.getDefendants();
        final Optional<LocalDate> optionalCtlDate = defendantList.stream()
                .filter(d -> isNotEmpty(d.getCtlExpiryDate()))
                .map(defendant -> parse(defendant.getCtlExpiryDate()))
                .filter(ctlDate -> ctlDate.isAfter(now()))
                .min(Comparator.naturalOrder());

        if (optionalCtlDate.isPresent()) {
            LOGGER.info("custody time limit date for case is : {}", optionalCtlDate.get());
            taskVariablesJsonObjectBuilder.add(CUSTODY_TIME_LIMIT, objectToJsonValueConverter.convert(optionalCtlDate.get()));
        }
    }

    private void updateLatestHearingDateAndTypeInJsonObject(final List<Hearing> hearingList, final JsonObjectBuilder taskVariablesJsonObjectBuilder) {
        if (isEmpty(hearingList)) {
            return;
        }
        final List<Hearing> allocatedHearings = hearingList.stream()
                .filter(Hearing::getAllocated)
                .filter(hearing -> isFutureDate(hearing.getStartDate()))
                .collect(toList());

        if (isNotEmpty(allocatedHearings)) {
            allocatedHearings.forEach(hearing -> hearing.getHearingDays().sort(compareByStartDateTime));
            allocatedHearings.sort(new StartDateAndTimeComparator());
            final Hearing latestHearing = allocatedHearings.get(0);
            final HearingDay hearingDay = latestHearing.getHearingDays().get(0);
            final LocalDate latestHearingDate = hearingDay.getStartTime().toLocalDate();
            final String hearingType = latestHearing.getType().getDescription();
            LOGGER.info("hearing date for case is : {} and hearing type for case is {}", latestHearingDate, hearingType);
            taskVariablesJsonObjectBuilder.add(HEARING_DATE, objectToJsonValueConverter.convert(latestHearingDate));
            taskVariablesJsonObjectBuilder.add(HEARING_TYPE, objectToJsonValueConverter.convert(hearingType));
        }
    }
}
