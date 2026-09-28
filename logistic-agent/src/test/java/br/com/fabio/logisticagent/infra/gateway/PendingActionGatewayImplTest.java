package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.core.gateway.PendingActionGateway;
import br.com.fabio.logisticagent.core.gateway.PendingActionGatewayContractTest;
import br.com.fabio.logisticagent.infra.entity.PendingActionEntity;
import br.com.fabio.logisticagent.infra.repository.PendingActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(PendingActionGatewayImpl.class)
class PendingActionGatewayImplTest extends PendingActionGatewayContractTest {

    @Autowired
    private PendingActionGatewayImpl store;

    @Autowired
    private PendingActionRepository repository;

    @Override
    protected PendingActionGateway createStore() {
        return store;
    }

    @Test
    void expiredActionIsNotResgatable() {
        Instant past = Instant.now().minus(PendingActionGateway.TTL).minusSeconds(60);
        repository.save(new PendingActionEntity("id-expirado", "sessao-1", "deleteDriver", "{}", past, Map.of()));

        assertThat(store.take("id-expirado", "sessao-1")).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentTakeGivesTheActionToExactlyOneThread() throws Exception {
        PendingAction action = store.register("sessao-1", "deleteDriver", "{}");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    awaitUninterruptibly(go);
                    if (store.take(action.id(), "sessao-1") != null) {
                        successes.incrementAndGet();
                    }
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
            repository.deleteAll();
        }

        assertThat(successes.get()).isEqualTo(1);
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
