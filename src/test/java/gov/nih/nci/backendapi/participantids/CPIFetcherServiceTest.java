package gov.nih.nci.backendapi.participantids;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gov.nih.nci.bento_ri.model.DomainInfo;
import gov.nih.nci.bento_ri.model.FormattedCPIResponse;
import gov.nih.nci.bento_ri.model.ParticipantRequest;
import gov.nih.nci.bento_ri.service.CPIFetcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CPIFetcherServiceTest {

    private static final String TOKEN_URI = "https://identity.example/token";
    private static final String DOMAINS_URI = "https://cpi.example/domains";
    private static final String API_URI = "https://cpi.example/associated-participant-ids";

    @Mock
    private HttpClient httpClient;

    private Cache<String, Object> cache;
    private CPIFetcherService service;

    @BeforeEach
    void setUp() throws Exception {
        cache = Caffeine.newBuilder().build();
        service = new CPIFetcherService(cache);
        setField("httpClient", httpClient);
        setField("clientId", "client-id");
        setField("clientSecret", "client-secret");
        setField("tokenUri", TOKEN_URI);
        setField("apiUrl", API_URI);
        setField("domainsUrl", DOMAINS_URI);
        setField("scope", "custom");
    }

    /** Verifies null and empty requests return immediately without OAuth or CPI network calls. */
    @Test
    void returnsEmptyResultsWithoutCallingCpiForEmptyInput() throws Exception {
        assertEquals(List.of(), service.fetchAssociatedParticipantIds(null));
        assertEquals(List.of(), service.fetchAssociatedParticipantIds(List.of()));

        verifyNoInteractions(httpClient);
    }

    /**
     * Verifies OAuth, domain metadata, and association responses are combined into formatted CPI
     * results and that domain metadata is reused from the Caffeine cache on the second request.
     */
    @Test
    void formatsAssociationsAndCachesDomainMetadata() throws Exception {
        stubSuccessfulHttpResponses();
        List<ParticipantRequest> requests = List.of(new ParticipantRequest("P1", "STUDY-A"));

        List<FormattedCPIResponse> firstResult = service.fetchAssociatedParticipantIds(requests);
        List<FormattedCPIResponse> secondResult = service.fetchAssociatedParticipantIds(requests);

        assertEquals(1, firstResult.size());
        FormattedCPIResponse response = firstResult.get(0);
        assertEquals("P1", response.getParticipantId());
        assertEquals("STUDY-A", response.getStudyId());
        assertEquals(2, response.getCpiData().size());
        FormattedCPIResponse.CPIDataItem knownDomain = response.getCpiData().get(0);
        assertEquals("ASSOC-1", knownDomain.getAssociatedId());
        assertEquals("study-b", knownDomain.getRepositoryOfSynonymId());
        assertEquals("Study B", knownDomain.getDomainDescription());
        assertEquals("study", knownDomain.getDomainCategory());
        assertEquals("https://study-b.example", knownDomain.getDataLocation());
        FormattedCPIResponse.CPIDataItem unknownDomain = response.getCpiData().get(1);
        assertEquals("", unknownDomain.getDomainDescription());
        assertEquals("external", unknownDomain.getDomainCategory());
        assertEquals("", unknownDomain.getDataLocation());
        assertEquals(2, secondResult.get(0).getCpiData().size());
        assertTrue(cache.getIfPresent("cpi:domains") instanceof Map);
        assertEquals(1, cache.getIfPresent("cpi:domains:count"));
        verify(httpClient, times(1)).send(
                argThat(request -> DOMAINS_URI.equals(request.uri().toString())),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());

        service.clearDomainsCache();

        assertNull(cache.getIfPresent("cpi:domains"));
        assertNull(cache.getIfPresent("cpi:domains:count"));
    }

    /** Verifies missing OAuth configuration fails before attempting an HTTP request. */
    @Test
    void rejectsMissingOauthConfiguration() throws Exception {
        setField("clientId", null);

        IllegalStateException missingClientId = assertThrows(IllegalStateException.class,
                () -> service.fetchAssociatedParticipantIds(
                        List.of(new ParticipantRequest("P1", "STUDY-A"))));
        setField("clientId", "client-id");
        setField("clientSecret", null);
        IllegalStateException missingSecret = assertThrows(IllegalStateException.class,
                () -> service.fetchAssociatedParticipantIds(
                        List.of(new ParticipantRequest("P1", "STUDY-A"))));
        setField("clientSecret", "client-secret");
        setField("tokenUri", null);
        IllegalStateException missingTokenUri = assertThrows(IllegalStateException.class,
                () -> service.fetchAssociatedParticipantIds(
                        List.of(new ParticipantRequest("P1", "STUDY-A"))));

        assertTrue(missingClientId.getMessage().contains("OAuth2 configuration is missing"));
        assertTrue(missingSecret.getMessage().contains("OAuth2 configuration is missing"));
        assertTrue(missingTokenUri.getMessage().contains("OAuth2 configuration is missing"));
        verifyNoInteractions(httpClient);
    }

    /** Verifies a rejected OAuth token response is reported with its status and body. */
    @Test
    void reportsOauthTokenFailures() throws Exception {
        HttpResponse<String> unauthorized = httpResponse(401, "denied");
        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(unauthorized);

        Exception exception = assertThrows(Exception.class,
                () -> service.fetchAssociatedParticipantIds(
                        List.of(new ParticipantRequest("P1", "STUDY-A"))));

        assertEquals("Failed to get access token: 401 - denied", exception.getMessage());
    }

    /** Verifies a failed domains lookup prevents the participant-association request. */
    @Test
    void reportsDomainLookupFailures() throws Exception {
        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> switch (requestUri(invocation.getArgument(0))) {
                    case TOKEN_URI -> httpResponse(200, "{\"access_token\":\"token\"}");
                    case DOMAINS_URI -> httpResponse(503, "unavailable");
                    default -> throw new AssertionError("Unexpected URI");
                });

        Exception exception = assertThrows(Exception.class,
                () -> service.fetchAssociatedParticipantIds(
                        List.of(new ParticipantRequest("P1", "STUDY-A"))));

        assertEquals("Failed to fetch domains: 503 - unavailable", exception.getMessage());
    }

    /** Verifies a failed CPI association lookup is reported after successful authentication. */
    @Test
    void reportsAssociationLookupFailures() throws Exception {
        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> switch (requestUri(invocation.getArgument(0))) {
                    case TOKEN_URI -> httpResponse(200, "{\"access_token\":\"token\"}");
                    case DOMAINS_URI -> httpResponse(200, "[]");
                    case API_URI -> httpResponse(500, "failed");
                    default -> throw new AssertionError("Unexpected URI");
                });

        Exception exception = assertThrows(Exception.class,
                () -> service.fetchAssociatedParticipantIds(
                        List.of(new ParticipantRequest("P1", "STUDY-A"))));

        assertEquals("API request failed: 500 - failed", exception.getMessage());
    }

    /** Verifies domain lookup supports exact, upper-, and lower-case keys and rejects null/missing names. */
    @Test
    void findsDomainMetadataCaseInsensitively() throws Exception {
        DomainInfo exact = domain("Exact");
        DomainInfo upper = domain("Upper");
        DomainInfo lower = domain("Lower");
        Map<String, DomainInfo> domains = Map.of(
                "Exact", exact,
                "UPPER", upper,
                "lower", lower);

        assertSame(exact, invokePrivate(
                "findDomainInfo", new Class<?>[]{String.class, Map.class}, "Exact", domains));
        assertSame(upper, invokePrivate(
                "findDomainInfo", new Class<?>[]{String.class, Map.class}, "upper", domains));
        assertSame(lower, invokePrivate(
                "findDomainInfo", new Class<?>[]{String.class, Map.class}, "LOWER", domains));
        assertNull(invokePrivate(
                "findDomainInfo", new Class<?>[]{String.class, Map.class}, "missing", domains));
        assertNull(invokePrivate(
                "findDomainInfo", new Class<?>[]{String.class, Map.class}, null, domains));
    }

    /** Verifies cached domain metadata is accepted even when the optional count entry is absent. */
    @Test
    void returnsCachedDomainsWithoutACachedCount() throws Exception {
        Map<String, DomainInfo> domains = Map.of("STUDY-A", domain("STUDY-A"));
        cache.put("cpi:domains", domains);

        Map<String, DomainInfo> result = invokePrivate(
                "fetchDomainsInfo", new Class<?>[]{String.class}, "token");

        assertSame(domains, result);
        verifyNoInteractions(httpClient);
    }

    /** Verifies a participant with no matching associations receives an empty CPI data list. */
    @Test
    void formatsAnEmptyAssociationListWhenNoParticipantMatches() throws Exception {
        FormattedCPIResponse response = invokePrivate(
                "formatResponse",
                new Class<?>[]{ParticipantRequest.class, Map.class, Map.class},
                new ParticipantRequest("P1", "STUDY-A"),
                Map.of("participant_ids", List.of()),
                Map.of());

        assertEquals("P1", response.getParticipantId());
        assertEquals("STUDY-A", response.getStudyId());
        assertEquals(List.of(), response.getCpiData());
    }

    /** Verifies supplementary-domain metadata is removed without mutating the source response. */
    @Test
    void filtersSupplementaryDomainMetadataFromEverySupportedDataShape() throws Exception {
        Map<String, Object> listItem = new HashMap<>(Map.of(
                "participant_id", "P1", "supplementary_domains", List.of("extra")));
        Map<String, Object> source = new HashMap<>();
        source.put("supplementary_domains", List.of("top"));
        source.put("data", new ArrayList<>(List.of(listItem, "unchanged")));

        Map<String, Object> filtered = invokePrivate(
                "filterResponse", new Class<?>[]{Map.class}, source);

        assertFalse(filtered.containsKey("supplementary_domains"));
        assertFalse(listItem.containsKey("supplementary_domains"));
        assertTrue(source.containsKey("supplementary_domains"));

        Map<String, Object> nestedMap = new HashMap<>(Map.of("supplementary_domains", "extra"));
        invokePrivate("filterResponse", new Class<?>[]{Map.class},
                new HashMap<>(Map.of("data", nestedMap)));
        assertFalse(nestedMap.containsKey("supplementary_domains"));
        assertNull(invokePrivate("filterResponse", new Class<?>[]{Map.class}, (Object) null));
    }

    private void stubSuccessfulHttpResponses() throws Exception {
        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> switch (requestUri(invocation.getArgument(0))) {
                    case TOKEN_URI -> httpResponse(200, """
                            {"access_token":"token","token_type":"Bearer","expires_in":3600}
                            """);
                    case DOMAINS_URI -> httpResponse(200, """
                            [{
                              "domain_name":"STUDY-B",
                              "domain_description":"Study B",
                              "status":"active",
                              "domain_category":"study",
                              "data_location":"https://study-b.example"
                            }]
                            """);
                    case API_URI -> httpResponse(200, """
                            {
                              "participant_ids":[{
                                "participant_id":"P1",
                                "domain_name":"STUDY-A",
                                "associated_ids":[
                                  {"participant_id":"ASSOC-1","domain_name":"study-b",
                                   "domain_category":null},
                                  {"participant_id":"ASSOC-2","domain_name":"MISSING",
                                   "domain_category":"external"}
                                ]
                              }],
                              "supplementary_domains":["ignored"]
                            }
                            """);
                    default -> throw new AssertionError(
                            "Unexpected HTTP URI: " + requestUri(invocation.getArgument(0)));
                });
    }

    private static String requestUri(HttpRequest request) {
        return request.uri().toString();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> httpResponse(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static DomainInfo domain(String name) {
        DomainInfo domain = new DomainInfo();
        domain.setDomainName(name);
        return domain;
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivate(String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = CPIFetcherService.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return (T) method.invoke(service, args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private void setField(String name, Object value) throws Exception {
        Field field = CPIFetcherService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
