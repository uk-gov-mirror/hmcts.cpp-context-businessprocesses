package uk.gov.moj.cpp.businessprocesses.event.summonsapplication;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.messaging.JsonEnvelope.envelopeFrom;
import static uk.gov.justice.services.test.utils.core.messaging.MetadataBuilderFactory.metadataWithRandomUUID;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.PROCESS_NEW_SUMMONS_APPLICATION;
import static uk.gov.moj.cpp.businessprocesses.shared.Constants.TASK_NEW_PROCESS_NEW_SUMMONS_APPLICATION;
import static uk.gov.moj.cpp.businessprocesses.util.FileUtil.getFileContentAsJson;

import uk.gov.justice.core.courts.Hearing;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;
import uk.gov.justice.services.messaging.JsonEnvelope;
import uk.gov.moj.cpp.businessprocesses.service.HearingService;
import uk.gov.moj.cpp.businessprocesses.service.ReferenceDataService;
import uk.gov.moj.cpp.businessprocesses.util.TestDataProvider;

import java.io.IOException;

import jakarta.json.JsonObject;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SummonsApplicationHandlerTest {

    private static final String COURT_ID_VAL = "f8254db1-1683-483e-afb3-b87fde5a0a26";

    @Mock
    private HearingService hearingService;

    @Mock
    private SummonsApplicationTaskHandler summonsApplicationTaskHandler;

    @Mock
    private ReferenceDataService referenceDataService;

    @InjectMocks
    private SummonsApplicationHandler summonsApplicationHandler;

    private static final ObjectMapper objectMapper = new ObjectMapperProducer().objectMapper();

    @Captor
    protected ArgumentCaptor<SummonsApplicationTaskRequest> summonsApplicationTaskRequestArgumentCaptor;

    @Test
    void handleSummonsApplicationHearingInitiated() throws IOException {
        // given
        final String hearingId = "9c4894cb-0708-4f80-bee5-95236dfdd7e8";
        final JsonObject eventPayload = getFileContentAsJson("json/summon-application/public.hearing.initiated.json");
        final JsonEnvelope requestEnvelope = envelopeFrom(metadataWithRandomUUID("public.hearing.initiated"), eventPayload);

        final JsonObject response = getFileContentAsJson("json/hearing.get.hearing.json");
        final Hearing hearing = objectMapper.readValue(response.get("hearing").toString(), Hearing.class);

        when(hearingService.getHearing(hearingId)).thenReturn(hearing);
        when(referenceDataService.retrieveCourtCentreDetailsByCourtId(COURT_ID_VAL)).thenReturn(TestDataProvider.getReferenceDataCourtRoomsPayload());

        // when
        summonsApplicationHandler.handleSummonsApplicationHearingInitiated(requestEnvelope);

        // then
        verify(summonsApplicationTaskHandler, times(1)).startSummonsApplicationWorkFlow(summonsApplicationTaskRequestArgumentCaptor.capture());
        final SummonsApplicationTaskRequest capturedSummonsApplicationTaskRequest = summonsApplicationTaskRequestArgumentCaptor.getValue();

        assertThat(capturedSummonsApplicationTaskRequest.getHearingId(), is(hearingId));
        assertThat(capturedSummonsApplicationTaskRequest.getHearingDate(), is("2024-06-28T00:00:00.000Z"));
        assertThat(capturedSummonsApplicationTaskRequest.getApplicationId(), is(hearing.getCourtApplications().get(0).getId()));
        assertThat(capturedSummonsApplicationTaskRequest.getApplicationReference(), is(hearing.getCourtApplications().get(0).getApplicationReference()));
        assertThat(capturedSummonsApplicationTaskRequest.getCourtName(), is(hearing.getCourtCentre().getName()));
        assertThat(capturedSummonsApplicationTaskRequest.getCourtCode(), is("B62IZ00"));
        assertThat(capturedSummonsApplicationTaskRequest.getTaskName(), is(TASK_NEW_PROCESS_NEW_SUMMONS_APPLICATION));
        assertThat(capturedSummonsApplicationTaskRequest.getProcessKey(), is(PROCESS_NEW_SUMMONS_APPLICATION));
    }

    @Test
    void handleSummonsApplicationResulted() throws IOException {
        // given
        final String hearingId = "0adf8ff5-5e7e-4abd-b00b-df7585b8a3a5";
        final JsonObject eventPayload = getFileContentAsJson("json/summon-application/public.events.hearing.hearing-resulted.json");
        final JsonEnvelope requestEnvelope = envelopeFrom(metadataWithRandomUUID("public.events.hearing.hearing-resulted"), eventPayload);

        // when
        summonsApplicationHandler.handleSummonsApplicationResulted(requestEnvelope);

        // then
        verify(summonsApplicationTaskHandler, times(1)).completeSummonsApplicationWorkFlow("CPJKJUAK8G", "Summons Approved", hearingId);
    }
}