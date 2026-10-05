package io.github.liumaishenjian.ccjava.cli.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.subagent.AgentDefinitionCatalog;
import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import io.github.liumaishenjian.ccjava.domain.subagent.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * 扫描固定 User/Project roots 并冻结严格 Agent definition snapshot。
 *
 * <p>每个 definition 使用独立 {@code *.agent} UTF-8 properties 文件；未知字段、同层重复 ID、链接、
 * 越界、未知 Tool 或非法 UTF-8 全部隔离。旧load还拒绝静态目录外Model；
 * 捕获路由入口只冻结模型声明，执行资格必须在可信捕获边界验证。Project与User同ID均隔离而不覆盖。
 * 文件在 {@link #load} 后不再读取，磁盘变化只影响新 Session。</p>
 * @since 0.12.0
 */
public final class FileAgentDefinitionCatalog implements AgentDefinitionCatalog {
    /** 每个来源目录允许扫描的最大定义文件数。 */
    public static final int MAX_FILES_PER_ROOT=64;
    /** 单个 Agent definition 文件允许的最大字节数。 */
    public static final int MAX_FILE_BYTES=64*1024;
    private static final Set<String> FIELDS=Set.of("id","description","instructions","tools","permission","model",
            "max-model-turns","max-tool-calls","max-input-tokens","max-output-characters","timeout-seconds","background");
    private final Map<AgentDefinitionId,AgentDefinitionSnapshot> snapshots;
    private final List<String> diagnostics;

    private FileAgentDefinitionCatalog(Map<AgentDefinitionId,AgentDefinitionSnapshot> snapshots,List<String> diagnostics){
        this.snapshots=Map.copyOf(snapshots); this.diagnostics=List.copyOf(diagnostics);
    }

    /**
     * 读取并冻结用户与项目两个来源；不存在的 root 视为空。
     *
     * @param userRoot 用户级定义目录
     * @param projectRoot 项目级定义目录
     * @param registeredTools 宿主已注册的 Tool 名称
     * @param configuredModels 宿主已配置的 Model 名称
     * @param cancellation 扫描取消令牌
     * @return 完成冲突隔离和稳定排序的定义目录
     */
    public static FileAgentDefinitionCatalog load(Path userRoot,Path projectRoot,Set<String> registeredTools,
            Set<String> configuredModels,CancellationToken cancellation) {
        return load(userRoot, projectRoot, registeredTools, configuredModels, cancellation, true);
    }

    /**
     * 读取并冻结两个来源，项目定义仅在通过精确 trust Gate 后参与。
     *
     * @param userRoot 用户级定义目录
     * @param projectRoot 项目级定义目录
     * @param registeredTools 宿主已注册的 Tool 名称
     * @param configuredModels 宿主已配置的 Model 名称
     * @param cancellation 扫描取消令牌
     * @param projectTrusted 项目级来源是否已通过 S08/Extension trust Gate
     * @return 完成冲突隔离和稳定排序的定义目录
     */
    public static FileAgentDefinitionCatalog load(Path userRoot,Path projectRoot,Set<String> registeredTools,
            Set<String> configuredModels,CancellationToken cancellation, boolean projectTrusted) {
        return loadDefinitions(userRoot, projectRoot, registeredTools, configuredModels, cancellation,
                projectTrusted, false);
    }

    /**
     * 为显式捕获路由加载声明；允许省略model，但不授予目录外模型执行资格。
     * <p>调用者必须在入队前用已捕获父来源验证覆盖模型；此入口不用于没有该Gate的旧组合。</p>
     * @param userRoot 用户定义目录
     * @param projectRoot 项目定义目录
     * @param registeredTools 已注册工具上界
     * @param cancellation 扫描取消
     * @param projectTrusted 项目精确信任状态
     * @return 冻结声明，省略model表示继承，空白model仍拒绝
     */
    public static FileAgentDefinitionCatalog loadForCapturedRoutes(Path userRoot, Path projectRoot,
            Set<String> registeredTools, CancellationToken cancellation, boolean projectTrusted) {
        return loadDefinitions(userRoot, projectRoot, registeredTools, Set.of(), cancellation, projectTrusted, true);
    }

    private static FileAgentDefinitionCatalog loadDefinitions(Path userRoot, Path projectRoot,
            Set<String> registeredTools, Set<String> configuredModels, CancellationToken cancellation,
            boolean projectTrusted, boolean deferredModel) {
        Objects.requireNonNull(registeredTools); Objects.requireNonNull(configuredModels); Objects.requireNonNull(cancellation);
        List<Candidate> candidates=new ArrayList<>(); List<String> diagnostics=new ArrayList<>();
        if (projectTrusted) scan(projectRoot,"project",registeredTools,configuredModels,cancellation,candidates,diagnostics,deferredModel);
        else if (projectRoot != null && Files.exists(projectRoot, LinkOption.NOFOLLOW_LINKS)) diagnostics.add("project:trust_required");
        scan(userRoot,"user",registeredTools,configuredModels,cancellation,candidates,diagnostics,deferredModel);
        Map<AgentDefinitionId,List<Candidate>> grouped=new TreeMap<>(Comparator.comparing(AgentDefinitionId::value));
        candidates.forEach(c->grouped.computeIfAbsent(c.snapshot.id(),ignored->new ArrayList<>()).add(c));
        Map<AgentDefinitionId,AgentDefinitionSnapshot> accepted=new LinkedHashMap<>();
        grouped.forEach((id,group)->{ if(group.size()==1) accepted.put(id,group.getFirst().snapshot); else diagnostics.add("conflict:"+id.value()); });
        return new FileAgentDefinitionCatalog(accepted,diagnostics.stream().sorted().toList());
    }

    @Override public Optional<AgentDefinitionSnapshot> find(AgentDefinitionId id){return Optional.ofNullable(snapshots.get(id));}
    @Override public List<AgentDefinitionSnapshot> snapshots(){return snapshots.values().stream().sorted(Comparator.comparing(v->v.id().value())).toList();}
    /**
     * 返回不含路径或正文的固定诊断。
     *
     * @return 稳定排序的安全诊断
     */
    public List<String> diagnostics(){return diagnostics;}

    private static void scan(Path root,String source,Set<String> tools,Set<String> models,CancellationToken cancellation,
            List<Candidate> out,List<String> diagnostics,boolean deferredModel) {
        if(root==null||!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        try {
            if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)){diagnostics.add(source+":unsafe_root");return;}
            Path real=root.toRealPath(LinkOption.NOFOLLOW_LINKS); int count=0;
            try(DirectoryStream<Path> stream=Files.newDirectoryStream(real,"*.agent")){
                List<Path> files=new ArrayList<>(); stream.forEach(files::add); files.sort(Comparator.comparing(p->p.getFileName().toString()));
                for(Path file:files){
                    if(cancellation.isCancellationRequested())return;
                    if(++count>MAX_FILES_PER_ROOT){diagnostics.add(source+":limit");break;}
                    try{out.add(new Candidate(parse(file,source,tools,models,deferredModel)));}catch(RuntimeException|IOException invalid){diagnostics.add(source+":invalid");}
                }
            }
        }catch(IOException failure){diagnostics.add(source+":unreadable");}
    }

    private static AgentDefinitionSnapshot parse(Path file,String source,Set<String> tools,Set<String> models,
            boolean deferredModel) throws IOException{
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_FILE_BYTES)throw new IllegalArgumentException();
        Object identityBefore=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        long sizeBefore=Files.size(file);java.nio.file.attribute.FileTime modifiedBefore=Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS);
        byte[] bytes=Files.readAllBytes(file);
        Object identityAfter=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        if(bytes.length!=sizeBefore||!Objects.equals(identityBefore,identityAfter)||!modifiedBefore.equals(Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS)))throw new IllegalArgumentException();
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        Map<String,String> values=new LinkedHashMap<>();
        for(String raw:text.split("\\R",-1)){if(raw.isBlank()||raw.stripLeading().startsWith("#"))continue;int split=raw.indexOf('=');
            if(split<1)throw new IllegalArgumentException();String key=raw.substring(0,split).trim();String value=raw.substring(split+1).trim();
            if(!FIELDS.contains(key)||values.putIfAbsent(key,value)!=null)throw new IllegalArgumentException();}
        Set<String> required = new HashSet<>(FIELDS);
        if (deferredModel) required.remove("model");
        if(!values.keySet().containsAll(required)||!FIELDS.containsAll(values.keySet()))throw new IllegalArgumentException();
        Set<String> visible=values.get("tools").isBlank()?Set.of():Set.copyOf(Arrays.stream(values.get("tools").split(",")).map(String::trim).toList());
        if(!tools.containsAll(visible)||(!deferredModel&&!models.contains(values.get("model"))))throw new IllegalArgumentException();
        return new AgentDefinitionSnapshot(new AgentDefinitionId(values.get("id")),values.get("description"),values.get("instructions"),visible,
                PermissionMode.valueOf(values.get("permission")),Optional.ofNullable(values.get("model")),new ChildBudget(integer(values,"max-model-turns"),integer(values,"max-tool-calls"),
                longValue(values,"max-input-tokens"),integer(values,"max-output-characters"),Duration.ofSeconds(longValue(values,"timeout-seconds"))),
                strictBoolean(values.get("background")),sha256(bytes),source);
    }
    private static int integer(Map<String,String> values,String key){return Integer.parseInt(values.get(key));}
    private static long longValue(Map<String,String> values,String key){return Long.parseLong(values.get(key));}
    private static boolean strictBoolean(String value){if("true".equals(value))return true;if("false".equals(value))return false;throw new IllegalArgumentException();}
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private record Candidate(AgentDefinitionSnapshot snapshot){}
}
