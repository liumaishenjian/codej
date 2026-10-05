package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.model.springai.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** selected route 只创建单 route，并在 Run 边界冻结 selection/lease 的集成契约。 */
class SelectedProviderRouteFactoryTest {
    @TempDir Path temporary;
    @Test void runFreezesSelectionAndClosesCredentialAndGatewayExactlyOnce() throws Exception {
        Path home=Files.createDirectory(temporary.resolve("home"));
        ProviderDefinitionStore definitions=new ProviderDefinitionStore(home);
        FakeStore store=new FakeStore(); CredentialLeaseRegistry leases=new CredentialLeaseRegistry();
        AtomicInteger creates=new AtomicInteger(),calls=new AtomicInteger(),closes=new AtomicInteger();
        ProviderGatewayFactory factory=new ProviderGatewayFactory(){
            @Override public ProviderGatewayKind kind(){return ProviderGatewayKind.ANTHROPIC;}
            @Override public ModelGateway create(ProviderGatewayConfiguration ignored){creates.incrementAndGet();return new FakeGateway(calls,closes);}
        };
        ProviderGatewayFactoryRegistry registry=new ProviderGatewayFactoryRegistry(List.of(factory,
                passthrough(ProviderGatewayKind.OPENAI_COMPATIBLE,calls,closes),
                passthrough(ProviderGatewayKind.OPENROUTER,calls,closes)));
        var routes=new SelectedProviderRouteFactory(definitions,new CredentialResolver(store,Map.of("CC_TEST","route-test-secret")),leases,registry);
        String model=definitions.snapshot(CancellationToken.none()).catalog().require("anthropic").defaultModelId();
        var gateway=routes.lazyGateway(()->Optional.of(new ProviderSelectionSnapshot("anthropic","personal",model)));
        try(var run=gateway.openRun()) {
            gateway.complete(new ModelRequest(new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                    new io.github.liumaishenjian.ccjava.domain.RunId("run"),1,List.of(),List.of()));
            assertThat(leases.activeCount("anthropic","personal")).isOne();
            assertThat(creates).hasValue(1); assertThat(calls).hasValue(1);
        }
        assertThat(leases.activeCount("anthropic","personal")).isZero();
        assertThat(closes).hasValue(1);
    }

    @Test void selectedRouteRetriesTransientFailureWithoutRecreatingProviderOrLease() throws Exception {
        Path home=Files.createDirectory(temporary.resolve("retry-home"));
        ProviderDefinitionStore definitions=new ProviderDefinitionStore(home);
        FakeStore store=new FakeStore(); CredentialLeaseRegistry leases=new CredentialLeaseRegistry();
        AtomicInteger creates=new AtomicInteger(),calls=new AtomicInteger(),closes=new AtomicInteger();
        ProviderGatewayFactory factory=new ProviderGatewayFactory(){
            @Override public ProviderGatewayKind kind(){return ProviderGatewayKind.ANTHROPIC;}
            @Override public ModelGateway create(ProviderGatewayConfiguration ignored){
                creates.incrementAndGet();
                return new TransientGateway(calls,closes,2);
            }
        };
        ProviderGatewayFactoryRegistry registry=new ProviderGatewayFactoryRegistry(List.of(factory,
                passthrough(ProviderGatewayKind.OPENAI_COMPATIBLE,new AtomicInteger(),new AtomicInteger()),
                passthrough(ProviderGatewayKind.OPENROUTER,new AtomicInteger(),new AtomicInteger())));
        var policy=new io.github.liumaishenjian.ccjava.core.ModelRetryPolicy(
                3,List.of(java.time.Duration.ZERO,java.time.Duration.ZERO));
        io.github.liumaishenjian.ccjava.core.ModelRetryRuntime runtime=
                new io.github.liumaishenjian.ccjava.core.ModelRetryRuntime(){
                    public double nextRandom(){return 0d;}
                    public void await(java.time.Duration delay,CancellationToken cancellation){}
                };
        var routes=new SelectedProviderRouteFactory(definitions,
                new CredentialResolver(store,Map.of("CC_TEST","route-test-secret")),leases,registry,policy,runtime);
        String model=definitions.snapshot(CancellationToken.none()).catalog().require("anthropic").defaultModelId();
        var gateway=routes.lazyGateway(()->Optional.of(new ProviderSelectionSnapshot("anthropic","personal",model)));

        try(var run=gateway.openRun(java.time.Duration.ofSeconds(1))){
            ModelTurn turn=gateway.complete(new ModelRequest(
                    new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                    new io.github.liumaishenjian.ccjava.domain.RunId("run"),1,List.of(),List.of()));
            assertThat(turn.assistantMessage().text()).isEqualTo("ok");
            assertThat(creates).hasValue(1);
            assertThat(calls).hasValue(3);
            assertThat(leases.activeCount("anthropic","personal")).isOne();
        }
        assertThat(closes).hasValue(1);
        assertThat(leases.activeCount("anthropic","personal")).isZero();
    }

    @Test void productionDefaultExhaustsElevenAttemptsOnOneProviderLeaseWithoutProfileSwitch() throws Exception {
        Path home=Files.createDirectory(temporary.resolve("production-retry-home"));
        ProviderDefinitionStore definitions=new ProviderDefinitionStore(home);
        FakeStore store=new FakeStore(); CredentialLeaseRegistry leases=new CredentialLeaseRegistry();
        AtomicInteger creates=new AtomicInteger(),calls=new AtomicInteger(),closes=new AtomicInteger();
        ProviderGatewayFactory factory=new ProviderGatewayFactory(){
            @Override public ProviderGatewayKind kind(){return ProviderGatewayKind.ANTHROPIC;}
            @Override public ModelGateway create(ProviderGatewayConfiguration ignored){
                creates.incrementAndGet();
                return new TransientGateway(calls,closes,Integer.MAX_VALUE);
            }
        };
        ProviderGatewayFactoryRegistry registry=new ProviderGatewayFactoryRegistry(List.of(factory,
                passthrough(ProviderGatewayKind.OPENAI_COMPATIBLE,new AtomicInteger(),new AtomicInteger()),
                passthrough(ProviderGatewayKind.OPENROUTER,new AtomicInteger(),new AtomicInteger())));
        List<java.time.Duration> waits=new ArrayList<>();
        io.github.liumaishenjian.ccjava.core.ModelRetryRuntime runtime=
                new io.github.liumaishenjian.ccjava.core.ModelRetryRuntime(){
                    public double nextRandom(){return 0d;}
                    public void await(java.time.Duration delay,CancellationToken cancellation){waits.add(delay);}
                };
        var routes=new SelectedProviderRouteFactory(definitions,
                new CredentialResolver(store,Map.of("CC_TEST","route-test-secret")),leases,registry,runtime);
        String model=definitions.snapshot(CancellationToken.none()).catalog().require("anthropic").defaultModelId();
        AtomicInteger selections=new AtomicInteger();
        var gateway=routes.lazyGateway(()->{
            selections.incrementAndGet();
            return Optional.of(new ProviderSelectionSnapshot("anthropic","personal",model));
        });
        ModelRequest request=new ModelRequest(
                new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                new io.github.liumaishenjian.ccjava.domain.RunId("run"),1,List.of(),List.of());

        try(var run=gateway.openRun(java.time.Duration.ofHours(1))){
            assertThatThrownBy(()->gateway.complete(request))
                    .isInstanceOfSatisfying(
                            io.github.liumaishenjian.ccjava.core.ModelGatewayException.class,
                            failure->{
                                assertThat(failure.kind()).isEqualTo(
                                        io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRY_EXHAUSTED);
                                assertThat(failure.summary()).hasValueSatisfying(summary ->
                                        assertThat(summary.attempts()).isEqualTo(11));
                            });
            assertThat(calls).hasValue(11);
            assertThat(waits).hasSize(10);
            assertThat(creates).hasValue(1);
            assertThat(selections).hasValue(1);
            assertThat(store.snapshotCalls).hasValue(1);
            assertThat(leases.activeCount("anthropic","personal")).isOne();
        }
        assertThat(closes).hasValue(1);
        assertThat(leases.activeCount("anthropic","personal")).isZero();
    }

    @Test void missingSelectionBecomesFastPrivacySafeModelFailureAndScopeCanReopen() throws Exception {
        Path home=Files.createDirectory(temporary.resolve("missing-home"));
        ProviderDefinitionStore definitions=new ProviderDefinitionStore(home);
        FakeStore store=new FakeStore(); CredentialLeaseRegistry leases=new CredentialLeaseRegistry();
        AtomicInteger calls=new AtomicInteger(),closes=new AtomicInteger();
        ProviderGatewayFactoryRegistry registry=new ProviderGatewayFactoryRegistry(List.of(
                passthrough(ProviderGatewayKind.ANTHROPIC,calls,closes),
                passthrough(ProviderGatewayKind.OPENAI_COMPATIBLE,calls,closes),
                passthrough(ProviderGatewayKind.OPENROUTER,calls,closes)));
        var routes=new SelectedProviderRouteFactory(definitions,new CredentialResolver(store,Map.of()),leases,registry);
        var gateway=routes.lazyGateway(Optional::<ProviderSelectionSnapshot>empty);
        ModelRequest request=new ModelRequest(new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                new io.github.liumaishenjian.ccjava.domain.RunId("run"),1,List.of(),List.of());

        for (int attempt=0;attempt<2;attempt++) {
            long started=System.nanoTime();
            try(var run=gateway.openRun(java.time.Duration.ofSeconds(1))) {
                assertThatThrownBy(()->gateway.complete(request))
                        .isInstanceOfSatisfying(io.github.liumaishenjian.ccjava.core.ModelGatewayException.class,
                                failure->assertThat(failure.getMessage())
                                        .isEqualTo("Provider configuration unavailable")
                                        .doesNotContain("MODEL_SELECTION_AMBIGUOUS"));
            }
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofSeconds(1));
        }
        assertThat(calls).hasValue(0);
        assertThat(leases.activeCount("anthropic","personal")).isZero();
    }

    @Test void concurrentRunsKeepIndependentRoutesVisibleToExplicitlyBoundModelWorkers() throws Exception {
        Path home=Files.createDirectory(temporary.resolve("concurrent-home"));
        ProviderDefinitionStore definitions=new ProviderDefinitionStore(home);
        FakeStore store=new FakeStore(); CredentialLeaseRegistry leases=new CredentialLeaseRegistry();
        CountDownLatch bothEntered=new CountDownLatch(2), release=new CountDownLatch(1);
        List<String> observed=java.util.Collections.synchronizedList(new ArrayList<>());
        ProviderGatewayFactory factory=new ProviderGatewayFactory(){
            @Override public ProviderGatewayKind kind(){return ProviderGatewayKind.ANTHROPIC;}
            @Override public ModelGateway create(ProviderGatewayConfiguration configuration){
                String model=configuration.modelId();
                return request->{
                    observed.add(model+":"+request.runId().value());
                    bothEntered.countDown();
                    try {
                        if(!bothEntered.await(2,TimeUnit.SECONDS)||!release.await(2,TimeUnit.SECONDS))
                            throw new AssertionError("concurrent route timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return ModelTurn.text(model);
                };
            }
        };
        ProviderGatewayFactoryRegistry registry=new ProviderGatewayFactoryRegistry(List.of(factory,
                passthrough(ProviderGatewayKind.OPENAI_COMPATIBLE,new AtomicInteger(),new AtomicInteger()),
                passthrough(ProviderGatewayKind.OPENROUTER,new AtomicInteger(),new AtomicInteger())));
        var routes=new SelectedProviderRouteFactory(definitions,new CredentialResolver(store,Map.of("CC_TEST","route-test-secret")),leases,registry);
        String model=definitions.snapshot(CancellationToken.none()).catalog().require("anthropic").defaultModelId();
        var gateway=routes.lazyGateway(()->Optional.of(new ProviderSelectionSnapshot("anthropic","personal",model)));
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Runnable first=()->runConcurrent(gateway,"run-a",failure);
        Runnable second=()->runConcurrent(gateway,"run-b",failure);
        Thread a=Thread.ofPlatform().start(first), b=Thread.ofPlatform().start(second);
        assertThat(bothEntered.await(2,TimeUnit.SECONDS)).isTrue();
        assertThat(leases.activeCount("anthropic","personal")).isEqualTo(2);
        release.countDown(); a.join(2_000); b.join(2_000);
        assertThat(failure.get()).isNull();
        assertThat(observed).containsExactlyInAnyOrder(model+":run-a",model+":run-b");
        assertThat(leases.activeCount("anthropic","personal")).isZero();
    }

    @Test void piTaggedSelectionNeverResolvesSameNamedLegacyCredential() throws Exception {
        var definitions = new ProviderDefinitionStore(Files.createDirectory(temporary.resolve("pi-tag")));
        var store = new FakeStore();
        try (var leases = new CredentialLeaseRegistry()) {
            var calls = new AtomicInteger(); var closes = new AtomicInteger();
            var registry = new ProviderGatewayFactoryRegistry(List.of(
                    passthrough(ProviderGatewayKind.ANTHROPIC, calls, closes),
                    passthrough(ProviderGatewayKind.OPENAI_COMPATIBLE, calls, closes),
                    passthrough(ProviderGatewayKind.OPENROUTER, calls, closes)));
            var routes = new SelectedProviderRouteFactory(definitions,
                    new CredentialResolver(store, Map.of("CC_TEST", "synthetic")), leases, registry);
            String model = definitions.snapshot(CancellationToken.none()).catalog().require("anthropic").defaultModelId();
            var gateway = routes.lazyGateway(() -> Optional.of(new ProviderSelectionSnapshot(
                    "anthropic", "personal", model, "pi", "API_KEY")));
            try (var run = gateway.openRun()) {
                assertThatThrownBy(() -> gateway.complete(new ModelRequest(
                        new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                        new io.github.liumaishenjian.ccjava.domain.RunId("run"), 1, List.of(), List.of())))
                        .isInstanceOf(io.github.liumaishenjian.ccjava.core.ModelGatewayException.class);
            }
            assertThat(store.snapshotCalls).hasValue(0);
            assertThat(calls).hasValue(0);
        }
    }

    @Test void providerCloseFailureCannotFalselyDrainItsLease() throws Exception {
        var definitions = new ProviderDefinitionStore(Files.createDirectory(temporary.resolve("failed-close")));
        var store = new FakeStore();
        class FailedClose implements ModelGateway, AutoCloseable {
            public ModelTurn complete(ModelRequest request) { return ModelTurn.text("ok"); }
            public void close() { throw new IllegalStateException("synthetic-close-failure"); }
        }
        try (var leases = new CredentialLeaseRegistry()) {
            var registry = new ProviderGatewayFactoryRegistry(List.of(new ProviderGatewayFactory() {
                public ProviderGatewayKind kind() { return ProviderGatewayKind.ANTHROPIC; }
                public ModelGateway create(ProviderGatewayConfiguration configuration) { return new FailedClose(); }
            }, passthrough(ProviderGatewayKind.OPENAI_COMPATIBLE, new AtomicInteger(), new AtomicInteger()),
                    passthrough(ProviderGatewayKind.OPENROUTER, new AtomicInteger(), new AtomicInteger())));
            var routes = new SelectedProviderRouteFactory(definitions,
                    new CredentialResolver(store, Map.of("CC_TEST", "synthetic")), leases, registry);
            String model = definitions.snapshot(CancellationToken.none()).catalog().require("anthropic").defaultModelId();
            var gateway = routes.lazyGateway(() -> Optional.of(new ProviderSelectionSnapshot("anthropic", "personal", model)));
            try (var run = gateway.openRun()) {
                gateway.complete(new ModelRequest(new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                        new io.github.liumaishenjian.ccjava.domain.RunId("run"), 1, List.of(), List.of()));
            }
            assertThat(leases.activeCount("anthropic", "personal")).isOne();
            assertThat(leases.fenceAndDrain("anthropic", "personal", java.time.Duration.ofMillis(20), CancellationToken.none())).isFalse();
        }
    }

    private static void runConcurrent(io.github.liumaishenjian.ccjava.core.RunScopedModelGateway gateway,
                                      String runId,AtomicReference<Throwable> failure){
        try(var run=gateway.openRun(java.time.Duration.ofSeconds(2))){
            AtomicReference<ModelTurn> result=new AtomicReference<>();
            // 仍验证两个Run的精确路由/资源隔离，只将跨线程传递改为显式所有权而非线程继承。
            ModelGateway bound = run.binding().orElseThrow().gateway();
            Thread worker=Thread.ofVirtual().start(()->{
                try{result.set(bound.complete(new ModelRequest(
                        new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                        new io.github.liumaishenjian.ccjava.domain.RunId(runId),1,List.of(),List.of())));}
                catch(Throwable thrown){failure.compareAndSet(null,thrown);}
            });
            worker.join(2_000);
            assertThat(result.get()).isNotNull();
        }catch(Throwable thrown){failure.compareAndSet(null,thrown);}
    }

    private static ProviderGatewayFactory passthrough(ProviderGatewayKind kind,AtomicInteger calls,AtomicInteger closes){
        return new ProviderGatewayFactory(){public ProviderGatewayKind kind(){return kind;}public ModelGateway create(ProviderGatewayConfiguration c){return new FakeGateway(calls,closes);}};
    }
    private record FakeGateway(AtomicInteger calls,AtomicInteger closes) implements ModelGateway,AutoCloseable {
        public ModelTurn complete(ModelRequest request){calls.incrementAndGet();return ModelTurn.text("ok");}
        public void close(){closes.incrementAndGet();}
    }
    private record TransientGateway(AtomicInteger calls,AtomicInteger closes,int failures)
            implements ModelGateway,AutoCloseable {
        public ModelTurn complete(ModelRequest request) throws io.github.liumaishenjian.ccjava.core.ModelGatewayException {
            if(calls.incrementAndGet()<=failures) {
                throw new io.github.liumaishenjian.ccjava.core.ModelGatewayException(
                        io.github.liumaishenjian.ccjava.core.ModelGatewayException.FailureKind.RETRYABLE,
                        "temporary");
            }
            return ModelTurn.text("ok");
        }
        public void close(){closes.incrementAndGet();}
    }
    private static final class FakeStore implements CredentialStore {
        final AtomicInteger closed=new AtomicInteger();
        final AtomicInteger snapshotCalls=new AtomicInteger();
        final CredentialProfile profile=new CredentialProfile("personal","anthropic",new SecretRef.Env("CC_TEST"),Instant.EPOCH,Instant.EPOCH,Optional.empty());
        public Snapshot snapshot(CancellationToken c){snapshotCalls.incrementAndGet();return new Snapshot(9,List.of(profile),Map.of("anthropic","personal"));}
        @Override public CredentialProfile saveProbe(String a,String b,CredentialProfile.ProbeRecord p,SecretRef r,CancellationToken c){throw new AssertionError();}
        public SecretMaterial readSecret(SecretRef.Store r,CancellationToken c){throw new AssertionError();}
        public boolean secretExists(SecretRef.Store r,CancellationToken c){return false;}
        public CredentialProfile saveStore(String a,String b,SecretMaterial d,boolean e,CancellationToken c){throw new AssertionError();}
        public CredentialProfile saveEnv(String a,String b,String d,boolean e,CancellationToken c){throw new AssertionError();}
        public void delete(String a,String b,long g,CancellationToken c){throw new AssertionError();}
    }
}