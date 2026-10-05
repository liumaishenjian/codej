package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.core.CapturedRunSource;
import io.github.liumaishenjian.ccjava.core.ContextPreparationService;
import io.github.liumaishenjian.ccjava.core.ContextSummarizer;
import io.github.liumaishenjian.ccjava.core.RunModelBinding;
import io.github.liumaishenjian.ccjava.domain.RunId;
import io.github.liumaishenjian.ccjava.domain.SessionId;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按宿主实际Session/Run身份登记可委托来源；仅用于入队捕获，不用于模型或摘要动态查路由。
 * <p>登记项只持有冻结来源与空的子Context模板，不持有父绑定网关、摘要器或父可变Context。
 * 关闭登记阻止新的捕获，但已入队的来源仍独立受凭据版本、取消和Session Supervisor约束。</p>
 */
public final class RunModelSourceRegistry {
    private static final ContextSummarizer UNBOUND = (request, cancellation) -> {
        throw new IllegalStateException("Child summary binding unavailable");
    };
    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();

    /** 创建Session宿主拥有的空登记表；不启动工作线程或打开模型资源。 */
    public RunModelSourceRegistry() { }

    /**
     * 在真实RunInitializer内登记来源，不能用猜测或模型给出的Run身份。
     * @param sessionId 宿主生成的Session身份
     * @param runId 宿主生成的Run身份
     * @param binding 本Run的显式端口
     * @param context 本Run的Context配置来源，状态不会传给child
     * @param startupModel 无selected身份时的明确启动模型，不用于覆盖Pi选择
     * @return 幂等解除当前登记的句柄，不关闭任何child
     */
    public Registration register(SessionId sessionId, RunId runId, RunModelBinding binding,
            ContextPreparationService context, Optional<String> startupModel) {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(context);
        Objects.requireNonNull(startupModel);
        Optional<String> actual = binding.selection().map(value -> value.modelId()).or(() -> startupModel);
        actual.ifPresent(RunModelSourceRegistry::requireModel);
        // 先剥离父状态及摘要器；排队期间不能通过模板间接持有父gateway。
        Entry entry = new Entry(binding.childSource(), context.forkForChild(UNBOUND), actual,
                binding.selection().isPresent());
        Key key = new Key(sessionId, runId);
        if (entries.putIfAbsent(key, entry) != null) throw new IllegalStateException("Run source already registered");
        return () -> entries.remove(key, entry);
    }

    /**
     * 在委托入队前固定来源与模型，不开启子租约或进程。
     * @param parentSessionId 来自ToolInvocation的宿主身份
     * @param parentRunId 来自ToolInvocation的宿主身份
     * @param override 定义中的显式模型覆盖，空表示继承
     * @return 每次捕获独占的空Context模板和冻结来源
     */
    public Prepared capture(SessionId parentSessionId, RunId parentRunId, Optional<String> override) {
        Objects.requireNonNull(override);
        override.ifPresent(RunModelSourceRegistry::requireModel);
        Entry entry = entries.get(new Key(parentSessionId, parentRunId));
        if (entry == null) throw new IllegalStateException("Parent run source unavailable");
        String model = override.or(() -> entry.model()).orElseThrow(
                () -> new IllegalStateException("Parent model identity unavailable"));
        Optional<String> requested = override;
        if (!entry.selected() && override.isPresent()) {
            // 旧启动client只允许宿主已确认的同一模型；不是任何Pi错误的fallback。
            if (!entry.model().equals(override)) throw new IllegalArgumentException("Startup model override unavailable");
            requested = Optional.empty();
        }
        CapturedRunSource source = entry.source().forModel(requested);
        ContextPreparationService template = entry.template().forkForChild(UNBOUND,
                Optional.of(model), OptionalLong.empty());
        return new Prepared(source, template, model);
    }

    private static void requireModel(String model) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Model identity unavailable");
    }

    /**
     * 不持有运行资源的排队数据；开启后的模型必须再绑定到contextTemplate。
     * @param source 已校验模型并固定认证版本的来源
     * @param contextTemplate 本次委托独占的空Context，不含父消息或父摘要端口
     * @param modelName 已解析的真实目标模型名，不是inherit占位符
     */
    public record Prepared(CapturedRunSource source, ContextPreparationService contextTemplate, String modelName) {
        /** 校验队列载荷的完整性。 */
        public Prepared {
            Objects.requireNonNull(source);
            Objects.requireNonNull(contextTemplate);
            requireModel(modelName);
        }
    }

    /** 宿主在Run finally中解除登记；无受检异常且幂等。 */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        /** 只移除这个登记，不触碰同身份之后的新登记。 */
        @Override void close();
    }

    private record Key(SessionId sessionId, RunId runId) {
        private Key { Objects.requireNonNull(sessionId); Objects.requireNonNull(runId); }
    }
    private record Entry(CapturedRunSource source, ContextPreparationService template,
                         Optional<String> model, boolean selected) { }
}
