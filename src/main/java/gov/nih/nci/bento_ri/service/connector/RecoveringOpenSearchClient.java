package gov.nih.nci.bento_ri.service.connector;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.RestClient;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Replaces stopped HTTP clients (opensearch-java issue #545) before the next request.
 * Requests themselves are never replayed: a failed scroll or write may already have
 * reached OpenSearch. A running client is retained even when a request fails.
 */
public final class RecoveringOpenSearchClient implements Closeable {
    private static final Logger logger = LogManager.getLogger(RecoveringOpenSearchClient.class);

    private final Supplier<RestClient> factory;
    private RestClient client;
    private boolean closed;

    public RecoveringOpenSearchClient(Supplier<RestClient> factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    /** Serializes replacement, while allowing callers to execute requests concurrently. */
    public synchronized RestClient get() throws IOException {
        if (closed) {
            throw new IOException("OpenSearch client is closed");
        }
        if (client == null) {
            client = Objects.requireNonNull(factory.get());
        } else if (!client.isRunning()) {
            RestClient stopped = client;
            // Publish only after construction succeeds; a later call can retry construction.
            client = Objects.requireNonNull(factory.get());
            logger.warn("Replacing stopped OpenSearch HTTP client; requests will not be replayed");
            try {
                stopped.close();
            } catch (IOException e) {
                logger.warn("Unable to close stopped OpenSearch HTTP client", e);
            }
        }
        return client;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            if (client != null) {
                client.close();
            }
        }
    }
}
