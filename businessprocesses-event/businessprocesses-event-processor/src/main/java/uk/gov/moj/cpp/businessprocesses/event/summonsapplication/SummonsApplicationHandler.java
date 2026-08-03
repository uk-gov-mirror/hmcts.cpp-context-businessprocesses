package uk.gov.moj.cpp.businessprocesses.event.summonsapplication;

import static java.util.Optional.ofNullable;
import static org.apache.commons.collections.CollectionUtils.isNotEmpty;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.PROCESS_NEW_SUMMONS_APPLICATION;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.TASK_NEW_PROCESS_NEW_SUMMONS_APPLICATION;

import uk.gov.justice.core.courts.CourtApplication;
import uk.gov.justice.core.courts.DefendantJudicialResult;
import uk.gov.justice.core.courts.Hearing;
import uk.gov.justice.core.courts.JudicialResult;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;
import uk.gov.justice.services.messaging.JsonEnvelope;
import uk.gov.moj.cpp.businessprocesses.service.HearingService;
import uk.gov.moj.cpp.businessprocesses.service.ReferenceDataService;
import uk.gov.moj.cpp.businessprocesses.shared.Constants;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.json.JsonObject;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SummonsApplicationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SummonsApplicationHandler.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private static final ObjectMapper objectMapper = new ObjectMapperProducer().objectMapper();
    public static final String HEARING = "hearing";
    public static final String ID = "id";

    private static final List<String> SUMMONS_TYPE_CODES = Arrays.asList(
            "MC80804", "MC80518", "MC80508", "PC00501", "PC00505", "PC00502", "PC00504", "PC00565",
            "PC00595", "PC00700", "CJ08514", "CJ03525", "CJ03510", "SO59501", "CJ08504", "CJ03506",
            "SE20502", "CJ08507", "PC09510", "CJ03526", "CJ08521", "CJ03529", "SE20501", "CJ03531",
            "SE20517", "SE20540", "SE20530", "SE20547", "SE20542", "SE20537", "PC00510", "CJ03524",
            "SE20505", "SE20548", "SE20552", "SE20546", "SE20521");

    // lookup against the code rather than the id ??
    private static final UUID SUMMONS_APPROVED = UUID.fromString("0f44eeb9-2c81-430d-9a60-bbdaf8c4a093");

    private static final UUID SUMMONS_REJECTED = UUID.fromString("d8837a45-8281-49b3-8349-49b423193148");


    @Inject
    private HearingService hearingService;

    @Inject
    private SummonsApplicationTaskHandler summonsApplicationTaskHandler;

    @Inject
    private ReferenceDataService referenceDataService;


    public void handleSummonsApplicationHearingInitiated(final JsonEnvelope jsonEnvelope) {

        final JsonObject eventPayload = jsonEnvelope.payloadAsJsonObject();
        final String hearingId = eventPayload.getString(Constants.HEARING_ID);

        // call the get hearing
        final Hearing hearing = hearingService.getHearing(hearingId);

        final List<CourtApplication> courtApplications =
                ofNullable(hearing.getCourtApplications())
                        .map(Collection::stream)
                        .orElseGet(Stream::empty)
                        .toList();

        if (Boolean.TRUE.equals(hearing.getIsBoxHearing())
                && isNotEmpty(courtApplications)) {
                LOGGER.info("Is Box hearing with {}", hearingId);
                courtApplications.forEach(courtApplication -> {
                    if(SUMMONS_TYPE_CODES.contains(courtApplication.getType().getCode())) {
                        String courtCode = getCourtCode(hearing.getCourtCentre().getId().toString());
                        // build the request object
                        final String applicationReference = courtApplication.getApplicationReference();
                        final String courtName = hearing.getCourtCentre().getName();
                        final SummonsApplicationTaskRequest summonsApplicationTaskRequest = new SummonsApplicationTaskRequest.Builder()
                                .withHearingId(hearingId)
                                .withHearingDate(hearing.getHearingDays().get(0).getSittingDay().format(DATE_TIME_FORMATTER)) // revisit this
                                .withCourtName(courtName)
                                .withCourtCode(courtCode)
                                .withApplicationId(courtApplication.getId())
                                .withApplicationReference(applicationReference)
                                .withTaskName(TASK_NEW_PROCESS_NEW_SUMMONS_APPLICATION)
                                .withProcessKey(PROCESS_NEW_SUMMONS_APPLICATION)
                                .build();

                        // start the workflow task
                        summonsApplicationTaskHandler.startSummonsApplicationWorkFlow(summonsApplicationTaskRequest);

                    }
                });

        }
    }

    public void handleSummonsApplicationResulted(final JsonEnvelope jsonEnvelope) {

        final JsonObject eventPayload = jsonEnvelope.payloadAsJsonObject();

        Hearing hearing;
        try {
            hearing = objectMapper.readValue(eventPayload.getJsonObject(HEARING).toString(), Hearing.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to unmarshal Hearings", e);
        }

        final List<CourtApplication> courtApplications = ofNullable(hearing.getCourtApplications())
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .toList();

        if (hearing.getIsBoxHearing() != null && hearing.getIsBoxHearing()) {
            final List<JudicialResult> summonsApprovedJudicialResults = getJudicialResults(hearing, SUMMONS_APPROVED);
            final List<JudicialResult> summonsRejectedJudicialResults = getJudicialResults(hearing, SUMMONS_REJECTED);

            if (isNotEmpty(courtApplications)) {
                courtApplications.forEach(courtApplication -> {
                    if (isNotEmpty(summonsApprovedJudicialResults) || isNotEmpty(summonsRejectedJudicialResults)) {
                        final String applicationResult =  isNotEmpty(summonsApprovedJudicialResults) ? "Summons Approved" : "Summons Rejected";
                        final String hearingId = hearing.getId().toString();
                        summonsApplicationTaskHandler.completeSummonsApplicationWorkFlow(courtApplication.getApplicationReference(), applicationResult, hearingId);
                    }
                });
            }
        }

    }


    private List<JudicialResult> getJudicialResults(Hearing hearing, final UUID resultDefinitionId) {

        final List<CourtApplication> courtApplications = ofNullable(hearing.getCourtApplications())
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .toList();

        final List<JudicialResult> applicationJudicialResults = courtApplications.stream().map(courtApplication -> getJudicialResults(courtApplication, resultDefinitionId)).flatMap(Collection::stream).toList();

        final List<JudicialResult> defendantJudicialResults = ofNullable(hearing.getDefendantJudicialResults())
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .map(DefendantJudicialResult::getJudicialResult)
                .filter(judicialResult -> judicialResult.getJudicialResultTypeId().equals(resultDefinitionId))
                .filter(judicialResult -> judicialResult.getJudicialResultTypeId().equals(resultDefinitionId))
                .toList();

        final List<JudicialResult> defendantCaseJudicialResults = ofNullable(hearing.getProsecutionCases()).map(Collection::stream).orElseGet(Stream::empty)
                .flatMap(prosecutionCase -> prosecutionCase.getDefendants().stream())
                .map(defendant -> ofNullable(defendant.getDefendantCaseJudicialResults()).map(Collection::stream).orElseGet(Stream::empty).toList())
                .flatMap(Collection::stream)
                .filter(judicialResult -> judicialResult.getJudicialResultTypeId().equals(resultDefinitionId))
                .toList();

        final List<JudicialResult> judicialResults = ofNullable(hearing.getProsecutionCases()).map(Collection::stream).orElseGet(Stream::empty)
                .flatMap(prosecutionCase -> prosecutionCase.getDefendants().stream())
                .flatMap(defendant -> defendant.getOffences().stream())
                .map(offence -> ofNullable(offence.getJudicialResults()).map(Collection::stream).orElseGet(Stream::empty).toList())
                .flatMap(Collection::stream)
                .filter(judicialResult -> judicialResult.getJudicialResultTypeId().equals(resultDefinitionId))
                .toList();

        return Stream.of(applicationJudicialResults, defendantJudicialResults, defendantCaseJudicialResults, judicialResults).flatMap(Collection::stream).toList();
    }

    private List<JudicialResult> getJudicialResults(CourtApplication courtApplication, final UUID resultDefinitionId) {

        final List<JudicialResult> judicialResults1 = ofNullable(courtApplication.getCourtApplicationCases()).map(Collection::stream).orElseGet(Stream::empty)
                .flatMap(courtApplicationCase -> ofNullable(courtApplicationCase.getOffences()).map(Collection::stream).orElseGet(Stream::empty))
                .flatMap(offence -> ofNullable(offence.getJudicialResults()).map(Collection::stream).orElseGet(Stream::empty))
                .filter(judicialResult -> resultDefinitionId.equals(judicialResult.getJudicialResultTypeId()))
                .toList();

        final List<JudicialResult> judicialResults2 = ofNullable(courtApplication.getCourtOrder()).map(courtOrder -> courtOrder.getCourtOrderOffences().stream()).orElseGet(Stream::empty)
                .flatMap(courtOrderOffence -> ofNullable(courtOrderOffence.getOffence()).map(offence -> ofNullable(offence.getJudicialResults()).map(Collection::stream).orElseGet(Stream::empty)).orElseGet(Stream::empty))
                .filter(judicialResult -> resultDefinitionId.equals(judicialResult.getJudicialResultTypeId()))
                .toList();

        final List<JudicialResult> applicationJudicialResults = ofNullable(courtApplication.getJudicialResults()).map(Collection::stream).orElseGet(Stream::empty)
                .filter(judicialResult -> resultDefinitionId.equals(judicialResult.getJudicialResultTypeId()))
                .toList();

        return Stream.of(applicationJudicialResults, judicialResults1, judicialResults2).flatMap(Collection::stream).toList();

    }

    private String getCourtCode(final String courtCentreId) {
        if (courtCentreId != null) {
            JsonObject courtCentreDetailsJson = referenceDataService.retrieveCourtCentreDetailsByCourtId(courtCentreId);
            return ReferenceDataService.getCourtCentreOuCode(courtCentreDetailsJson);
        }
        return null;
    }

}
