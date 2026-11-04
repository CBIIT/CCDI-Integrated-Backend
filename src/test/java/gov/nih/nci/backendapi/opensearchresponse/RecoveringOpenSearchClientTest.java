package gov.nih.nci.backendapi.opensearchresponse;

import gov.nih.nci.bento_ri.service.connector.RecoveringOpenSearchClient;
import org.junit.jupiter.api.Test;
import org.opensearch.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveringOpenSearchClientTest {
    @Test
    void reusesRunningClientAndReplacesStoppedClient() throws Exception {
        RestClient first = mock(RestClient.class);
        RestClient replacement = mock(RestClient.class);
        when(first.isRunning()).thenReturn(true, false);
        when(replacement.isRunning()).thenReturn(true);
        Supplier<RestClient> factory = mock(Supplier.class);
        when(factory.get()).thenReturn(first, replacement);
        try (var clients = new RecoveringOpenSearchClient(factory)) {
            assertSame(first, clients.get());
            assertSame(first, clients.get());
            assertSame(replacement, clients.get());
            assertSame(replacement, clients.get());
            verify(first).close();
            verify(factory, times(2)).get();
        }
        verify(replacement).close();
    }

    @Test
    void concurrentCallersCreateOnlyOneReplacement() throws Exception {
        RestClient first = mock(RestClient.class);
        RestClient replacement = mock(RestClient.class);
        when(first.isRunning()).thenReturn(false);
        when(replacement.isRunning()).thenReturn(true);
        Supplier<RestClient> factory = mock(Supplier.class);
        when(factory.get()).thenReturn(first, replacement);
        var executor = Executors.newFixedThreadPool(8);
        try (var clients = new RecoveringOpenSearchClient(factory)) {
            assertSame(first, clients.get());
            CountDownLatch start = new CountDownLatch(1);
            var results = new ArrayList<Future<RestClient>>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return clients.get();
                }));
            }
            start.countDown();
            for (var result : results) {
                assertSame(replacement, result.get(5, TimeUnit.SECONDS));
            }
            verify(factory, times(2)).get();
            verify(first).close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void replacementSurvivesFailureToCloseStoppedClient() throws Exception {
        RestClient first = mock(RestClient.class);
        RestClient replacement = mock(RestClient.class);
        when(first.isRunning()).thenReturn(false);
        doThrow(new IOException("close failed")).when(first).close();
        Supplier<RestClient> factory = mock(Supplier.class);
        when(factory.get()).thenReturn(first, replacement);
        try (var clients = new RecoveringOpenSearchClient(factory)) {
            assertSame(first, clients.get());
            assertSame(replacement, clients.get());
        }
    }

    @Test
    void retriesConstructionOnLaterCallIfReplacementCreationFails() throws Exception {
        RestClient first = mock(RestClient.class);
        RestClient replacement = mock(RestClient.class);
        when(first.isRunning()).thenReturn(false);
        Supplier<RestClient> factory = mock(Supplier.class);
        when(factory.get()).thenReturn(first).thenThrow(new IllegalStateException("creation failed"))
                .thenReturn(replacement);
        try (var clients = new RecoveringOpenSearchClient(factory)) {
            assertSame(first, clients.get());
            assertThrows(IllegalStateException.class, clients::get);
            verify(first, never()).close();
            assertSame(replacement, clients.get());
            verify(first).close();
        }
    }

    @Test
    void shutdownClosesOnceAndNeverRecreates() throws Exception {
        RestClient client = mock(RestClient.class);
        Supplier<RestClient> factory = mock(Supplier.class);
        when(factory.get()).thenReturn(client);
        var clients = new RecoveringOpenSearchClient(factory);
        assertSame(client, clients.get());
        clients.close();
        clients.close();
        assertThrows(IOException.class, clients::get);
        verify(factory).get();
        verify(client).close();
    }

    @Test
    void shutdownBeforeFirstUseDoesNotCreateClient() throws Exception {
        Supplier<RestClient> factory = mock(Supplier.class);
        var clients = new RecoveringOpenSearchClient(factory);
        clients.close();
        assertThrows(IOException.class, clients::get);
        verifyNoInteractions(factory);
    }
}
