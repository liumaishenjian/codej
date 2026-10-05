package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.cli.auth.SecretMaterial;
import io.github.liumaishenjian.ccjava.cli.auth.PiCredentialIdentity;
import io.github.liumaishenjian.ccjava.cli.provider.PiProviderCatalog;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** MODEL-13 Picocli 本地控制面命令集合。 */
final class ProviderControlCommands {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private ProviderControlCommands() { }

    /** 创建 providers 根命令。 */
    static Object providers(ProviderAuthApplicationService service, PrintWriter out, PrintWriter err) {
        return new Providers(service, out, err);
    }
    /** 创建 auth 根命令。 */
    static Object auth(ProviderAuthApplicationService service, InputStream input, PrintWriter out, PrintWriter err) {
        return new Auth(service, input, out, err);
    }
    /** 创建 models 根命令。 */
    static Object models(ProviderAuthApplicationService service, PrintWriter out, PrintWriter err) {
        return new Models(service, out, err);
    }

    /** 显式后端路由；不从 Provider 名推断，不把 Pi 失败交给旧实现。 */
    static class BackendOptions {
        @Option(names="--backend", defaultValue="spring-ai") String backend;
        boolean pi() {
            if (!"pi".equals(backend) && !"spring-ai".equals(backend))
                throw new IllegalArgumentException("backend");
            return "pi".equals(backend);
        }
        void legacyOnly() { if (pi()) throw new IllegalArgumentException("unsupported Pi operation"); }
    }

    /** 身份操作使用精确 method；legacy 不接受新增 Pi 身份标志。 */
    static class IdentityOptions extends BackendOptions {
        @Option(names="--auth-method") String authMethod;
        @Override boolean pi() {
            boolean pi = super.pi();
            if (!pi && authMethod != null) throw new IllegalArgumentException("auth method backend");
            if (authMethod != null) PiCredentialIdentity.AuthMethod.valueOf(authMethod);
            return pi;
        }
        PiCredentialIdentity identity(String provider, String profile) {
            if (authMethod == null) throw new IllegalArgumentException("auth method required");
            return new PiCredentialIdentity(provider, PiCredentialIdentity.AuthMethod.valueOf(authMethod), profile);
        }
    }

    @Command(mixinStandardHelpOptions = true,name = "providers", description = "管理本机非秘密 Provider definition",
            subcommands = {ProvidersList.class, ProvidersAdd.class, ProvidersRemove.class})
    static final class Providers implements Callable<Integer> {
        final ProviderAuthApplicationService service; final PrintWriter out; final PrintWriter err;
        Providers(ProviderAuthApplicationService service, PrintWriter out, PrintWriter err) {
            this.service=service; this.out=out; this.err=err;
        }
        @Override public Integer call() { return 2; }
    }

    @Command(mixinStandardHelpOptions = true,name = "list", description = "列出本机 Provider catalog")
    static final class ProvidersList extends BackendOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Providers parent;
        @Option(names="--json") boolean json;
        /** 按显式后端展示安全目录，不探测账号。@return 退出码 */
        @Override public Integer call() { return execute(parent.err, () -> {
            if (pi()) {
                boolean available = parent.service.piComponentAvailable();
                var values = new PiProviderCatalog().list().stream().map(value -> Map.<String,Object>of(
                        "backend", "pi", "providerId", value.id(), "brandId", value.brandId(),
                        "label", value.label(), "componentAvailable", available,
                        "authMethods", value.authMethods().stream().map(method -> method.id().toUpperCase(java.util.Locale.ROOT)).toList(),
                        "modelCount", value.models().size())).toList();
                if (json) writeJson(parent.out, Map.of("version", 1, "providers", values));
                else values.forEach(value -> parent.out.println(value.get("providerId")+"\tcomponentAvailable="+available));
                return 0;
            }
            var values=parent.service.listProviders(CancellationToken.none());
            if(json) writeJson(parent.out, Map.of("version",1,"providers",values.stream().map(value -> {
                Map<String,Object> item=new LinkedHashMap<>(); item.put("providerId",value.providerId());
                item.put("kind",value.kind().name()); item.put("modelCount",value.modelCount());
                item.put("defaultModelId",value.defaultModelId()); item.put("selectedDefault",value.selectedDefault());
                return item; }).toList()));
            else values.forEach(value -> parent.out.println(value.providerId()+"\t"+value.kind()+"\tmodels="
                    +value.modelCount()+"\tdefault="+value.defaultModelId()));
            return 0;
        }); }
    }

    @Command(mixinStandardHelpOptions = true,name = "add", description = "新增 OpenAI-compatible Provider")
    static final class ProvidersAdd extends BackendOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Providers parent;
        @Option(names="--id",required=true) String id;
        @Option(names="--kind",required=true) String kind;
        @Option(names="--display-name") String displayName;
        @Option(names="--base-url",required=true) String baseUrl;
        @Option(names="--model",required=true,arity="1..*") List<String> models;
        @Option(names="--default-model") String defaultModel;
        @Option(names="--connect-timeout-seconds",defaultValue="10") long connect;
        @Option(names="--request-timeout-seconds",defaultValue="300") long request;
        /** 自定义 Provider 只在兼容后端允许。@return 退出码 */
        @Override public Integer call() { return execute(parent.err, () -> {
            legacyOnly();
            if(!"openai-compatible".equals(kind)) throw new IllegalArgumentException("kind");
            ProviderDefinition definition=new ProviderDefinition(id,ProviderDefinition.Kind.OPENAI_COMPATIBLE,
                    displayName==null?id:displayName,URI.create(baseUrl),
                    ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,models,
                    defaultModel==null?models.getFirst():defaultModel,Map.of(),
                    Duration.ofSeconds(connect),Duration.ofSeconds(request));
            parent.service.addProvider(definition,CancellationToken.none());
            parent.out.println("provider saved: "+id); return 0;
        }); }
    }

    @Command(mixinStandardHelpOptions = true,name = "remove", description = "删除 custom Provider")
    static final class ProvidersRemove extends BackendOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Providers parent;
        @Option(names="--id",required=true) String id;
        @Option(names="--yes",required=true) boolean yes;
        /** 确认后删除兼容后端定义；拒绝 Pi fallback。@return 退出码 */
        @Override public Integer call() { return execute(parent.err, () -> {
            if(!yes) throw new IllegalArgumentException("confirmation");
            legacyOnly();
            parent.service.removeProvider(id,CancellationToken.none());
            parent.out.println("provider removed: "+id); return 0;
        }); }
    }

    @Command(mixinStandardHelpOptions = true,name="auth",description="管理本机 Provider credential",
            subcommands={AuthLogin.class,AuthList.class,AuthStatus.class,AuthProbe.class,AuthLogout.class,AuthMigrate.class})
    static final class Auth implements Callable<Integer> {
        final ProviderAuthApplicationService service; final InputStream input; final PrintWriter out; final PrintWriter err;
        Auth(ProviderAuthApplicationService service,InputStream input,PrintWriter out,PrintWriter err){
            this.service=service;this.input=input;this.out=out;this.err=err;
        }
        @Override public Integer call(){return 2;}
    }

    @Command(mixinStandardHelpOptions = true,name="login",description="创建或替换 credential profile")
    static final class AuthLogin extends IdentityOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--api-key-stdin") boolean stdin;
        @Option(names="--from-env") String environmentName;
        @Option(names="--set-default") boolean setDefault;
        @Option(names="--tui-preview", hidden=true) boolean tuiPreview;
        @Option(names="--browser", description="显式浏览器授权：legacy OpenRouter 或 Pi OAuth") boolean browser;
        /** Pi 输入归属 Node helper，ENV 只保存名称，不隐式选模。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            if (pi()) {
                var identity = identity(provider, profile);
                if (setDefault || tuiPreview || (stdin && environmentName != null)
                        || (browser && (stdin || environmentName != null))
                        || (identity.authMethod() == PiCredentialIdentity.AuthMethod.OAUTH
                            ? !browser || stdin || environmentName != null : browser))
                    throw new IllegalArgumentException("Pi login flags");
                if (environmentName != null) parent.service.loginPiEnvironment(identity, environmentName, CancellationToken.none());
                else new PiCliLogin().login(identity, stdin, CancellationToken.none());
                parent.out.println("profile saved (configured, unverified): pi/"+provider+"/"+profile);
                return 0;
            }
            if(stdin&&environmentName!=null) throw new IllegalArgumentException("secret source");
            if(browser && (stdin || environmentName != null || !"openrouter".equals(provider)))
                throw new IllegalArgumentException("browser auth source");
            ProviderAuthApplicationService.LoginRequest request;
            ProviderAuthApplicationService.SecretInput input=null;
            AtomicReference<String> preview=new AtomicReference<>();
            if(environmentName!=null){
                request=new ProviderAuthApplicationService.LoginRequest(provider,profile,
                        ProviderAuthApplicationService.RefKind.ENV,environmentName,setDefault);
            }else{
                request=new ProviderAuthApplicationService.LoginRequest(provider,profile,
                        ProviderAuthApplicationService.RefKind.STORE,null,setDefault);
                if (browser) {
                    var configuration = io.github.liumaishenjian.ccjava.cli.auth.BrowserAuthConfiguration.resolve();
                    input = () -> new io.github.liumaishenjian.ccjava.cli.auth.OpenRouterBrowserLogin(
                            configuration.nodeExecutable(), configuration.bridgeEntrypoint()).login(
                                    CancellationToken.none(), url -> {
                                        parent.out.println("请在浏览器打开授权地址（不要将回调内容粘贴到对话）：");
                                        parent.out.println(url); parent.out.flush();
                                    });
                } else {
                    input=stdin?()->readStdinSecret(parent.input):consoleInput(tuiPreview ? preview::set : ignored -> { });
                }
            }
            var saved=parent.service.login(request,input,CancellationToken.none());
            parent.out.println("profile saved: "+saved.providerId()+"/"+saved.profileId());
            if(tuiPreview&&preview.get()!=null) parent.err.println("CODEJ_CREDENTIAL_PREVIEW="+preview.get());
            return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="list",description="列出本机 credential metadata")
    static final class AuthList extends IdentityOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider") String provider;
        @Option(names="--json") boolean json;
        /** 仅投影封闭元数据，不泄露代次或 ENV 名。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            if (pi()) {
                var values = parent.service.listPiProfiles(Optional.ofNullable(provider), CancellationToken.none()).stream()
                        .filter(value -> authMethod == null || value.authMethod().equals(authMethod)).toList();
                if (json) writeJson(parent.out, Map.of("version", 1, "profiles", values.stream()
                        .map(ProviderControlCommands::piProfileJson).toList()));
                else values.forEach(value -> parent.out.println(value.providerId()+"/"+value.profileId()
                        +"\t"+value.authMethod()+"\t"+value.status()));
                return 0;
            }
            var values=parent.service.listProfiles(Optional.ofNullable(provider),CancellationToken.none());
            if(json) writeJson(parent.out,Map.of("version",1,"profiles",values.stream().map(
                    ProviderControlCommands::profileJson).toList()));
            else values.forEach(value->parent.out.println(value.providerId()+"\t"+value.profileId()+"\t"
                    +value.authMethod()+"\t"+value.refKind()+"\t"+value.status()
                    +(value.providerDefault()?"\tdefault":"")));
            return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="status",description="读取单个 credential 本机状态")
    static final class AuthStatus extends IdentityOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--json") boolean json;
        /** 按精确 method/provider/profile 查询本地状态。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            if (pi()) {
                var identity = identity(provider, profile);
                var value = parent.service.listPiProfiles(Optional.of(identity.providerId()), CancellationToken.none()).stream()
                        .filter(item -> item.profileId().equals(identity.profileId())
                                && item.authMethod().equals(identity.authMethod().name())).findFirst()
                        .orElseThrow(() -> new ProviderAuthException(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN,
                                ProviderAuthException.Action.LOGIN, false));
                if (json) writeJson(parent.out, Map.of("version", 1, "profile", piProfileJson(value)));
                else parent.out.println(value.providerId()+"/"+value.profileId()+"\t"+value.status());
                return 0;
            }
            var value=parent.service.status(provider,profile,CancellationToken.none());
            if(json)writeJson(parent.out,Map.of("version",1,"profile",profileJson(value)));
            else parent.out.println(value.providerId()+"/"+value.profileId()+"\t"+value.status());return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="probe",description="显式验证 Provider credential（Batch C）")
    static final class AuthProbe extends IdentityOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--model") String model;
        @Option(names="--timeout-seconds",defaultValue="5") long timeoutSeconds;
        @Option(names="--json") boolean json;
        /** Pi 不支持探测，不得调用旧网络入口。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            legacyOnly();
            String selectedModel=model;
            if(selectedModel==null){
                var models=parent.service.listModels(Optional.of(provider),CancellationToken.none());
                selectedModel=models.stream().filter(ProviderAuthApplicationService.ModelSummary::providerDefault)
                        .findFirst().orElseThrow(()->new IllegalArgumentException("model")).modelId();
            }
            var result=parent.service.probe(new ProviderAuthApplicationService.ProbeRequest(provider,profile,
                    selectedModel,Duration.ofSeconds(timeoutSeconds)),CancellationToken.none());
            if(json)writeJson(parent.out,Map.of("version",1,"providerId",result.providerId(),
                    "profileId",result.profileId(),"modelId",result.modelId(),
                    "status",result.outcome().name(),"probedAt",result.probedAt().toString()));
            else parent.out.println(result.providerId()+"/"+result.profileId()+"\t"+result.outcome());
            return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="logout",description="删除本机 credential；不会远端 revoke")
    static final class AuthLogout extends IdentityOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--yes",required=true) boolean yes;
        /** 确认后按精确身份本地退出，不宣称远端撤销。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            if(!yes)throw new IllegalArgumentException("confirmation");
            if (pi()) parent.service.logoutPi(identity(provider, profile), CancellationToken.none());
            else parent.service.logout(provider,profile,CancellationToken.none());
            parent.out.println("local credential deleted: "+provider+"/"+profile);
            parent.out.println("Provider-side credential was not revoked; rotate or delete it in the Provider console.");
            return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="migrate-legacy",description="显式复制固定 legacy properties")
    static final class AuthMigrate extends IdentityOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--set-default") boolean setDefault;
        /** 旧配置迁移不适用于 Pi。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            legacyOnly();
            var result=parent.service.migrateLegacy(provider,profile,setDefault,CancellationToken.none());
            parent.out.println(result.code()+": "+provider+"/"+profile);return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="models",description="列出、维护并选择本地模型",
            subcommands={ModelsList.class,ModelsAdd.class,ModelsRemove.class,ModelsUse.class})
    static final class Models implements Callable<Integer> {
        final ProviderAuthApplicationService service;final PrintWriter out;final PrintWriter err;
        Models(ProviderAuthApplicationService service,PrintWriter out,PrintWriter err){this.service=service;this.out=out;this.err=err;}
        @Override public Integer call(){return 2;}
    }

    @Command(mixinStandardHelpOptions = true,name="list",description="列出本地 catalog 模型")
    static final class ModelsList extends BackendOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Models parent;
        @Option(names="--provider")String provider;
        @Option(names="--json")boolean json;
        /** Pi 只展示静态目录，不启动组件或读取账号。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            if (pi()) {
                var catalog = new PiProviderCatalog();
                var providers = provider == null ? catalog.list() : List.of(catalog.require(provider));
                var values = providers.stream().flatMap(p -> p.models().stream().map(m -> Map.<String,Object>of(
                        "backend", "pi", "providerId", p.id(), "modelId", m.id()))).toList();
                if (json) writeJson(parent.out, Map.of("version", 1, "models", values));
                else values.forEach(value -> parent.out.println(value.get("providerId")+"\t"+value.get("modelId")));
                return 0;
            }
            var values=parent.service.listModels(Optional.ofNullable(provider),CancellationToken.none());
            if(json)writeJson(parent.out,Map.of("version",1,"models",values));
            else values.forEach(value->parent.out.println(value.providerId()+"\t"+value.modelId()
                    +(value.providerDefault()?"\tprovider-default":"")));return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="add",description="给 built-in Provider 增加模型 overlay")
    static final class ModelsAdd extends BackendOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Models parent;
        @Option(names="--provider",required=true)String provider;
        @Option(names="--model",required=true)String model;
        @Option(names="--set-default")boolean setDefault;
        /** 模型 overlay 只在兼容后端允许。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            legacyOnly();
            parent.service.addModel(provider,model,setDefault,CancellationToken.none());
            parent.out.println("model added: "+provider+"/"+model);return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="remove",description="从 built-in Provider 隐藏模型 overlay")
    static final class ModelsRemove extends BackendOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Models parent;
        @Option(names="--provider",required=true)String provider;
        @Option(names="--model",required=true)String model;
        /** 不允许借 Pi 删除旧模型。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            legacyOnly();
            parent.service.removeModel(provider,model,CancellationToken.none());
            parent.out.println("model removed: "+provider+"/"+model);return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="use",description="持久化默认 provider/model；可同时指定下一 Run profile")
    static final class ModelsUse extends IdentityOptions implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Models parent;
        @Option(names="--provider",required=true)String provider;
        @Option(names="--model",required=true)String model;
        @Option(names="--profile")String profile;
        @Option(names="--session-only",description="只影响当前进程下一 Run，不写入用户默认")boolean sessionOnly;
        @Option(names="--set-default", description="Pi 显式保存下一 Run 的默认模型")boolean setDefault;
        /** Pi 身份必须完整，只有显式标志才持久化默认。@return 退出码 */
        @Override public Integer call(){return execute(parent.err,()->{
            if (pi()) {
                if (setDefault && sessionOnly) throw new IllegalArgumentException("model scope");
                var value = parent.service.selectPiModel(identity(provider, profile), model, setDefault, CancellationToken.none());
                parent.out.println("next run model: pi/"+value.providerId()+"/"+value.modelId());
                return 0;
            }
            if (setDefault) throw new IllegalArgumentException("Pi-only option");
            var value=parent.service.selectModel(new ProviderAuthApplicationService.ModelSelectionRequest(
                    provider,model,Optional.ofNullable(profile),!sessionOnly),CancellationToken.none());
            parent.out.println("next run model: "+value.providerId()+"/"+value.modelId());return 0;
        });}
    }

    private static Map<String,Object> piProfileJson(ProviderAuthApplicationService.PiProfileSummary value) {
        return Map.of("backend", "pi", "providerId", value.providerId(), "profileId", value.profileId(),
                "authMethod", value.authMethod(), "refKind", value.refKind(), "localStatus", value.status());
    }

    private static Map<String,Object> profileJson(ProviderAuthApplicationService.ProfileSummary value){
        Map<String,Object> item=new LinkedHashMap<>();item.put("providerId",value.providerId());
        item.put("profileId",value.profileId());item.put("authMethod",value.authMethod());
        item.put("refKind",value.refKind());item.put("localStatus",value.status().name());
        item.put("default",value.providerDefault());value.lastProbeCode().ifPresent(v->item.put("lastProbeCode",v));
        value.lastProbeAt().ifPresent(v->item.put("lastProbeAt",v.toString()));return item;
    }

    private static ProviderAuthApplicationService.SecretInput consoleInput(){
        return consoleInput(ignored -> { });
    }

    private static ProviderAuthApplicationService.SecretInput consoleInput(
            java.util.function.Consumer<String> previewConsumer){
        Console console=System.console();
        if(console==null)throw new ProviderAuthException(ProviderAuthException.Code.AUTH_SECRET_INPUT_REQUIRED,
                ProviderAuthException.Action.LOGIN,false);
        return consoleInput(() -> console.readPassword("API Key（输入时隐藏）: "),previewConsumer);
    }

    static ProviderAuthApplicationService.SecretInput consoleInput(PasswordReader reader) {
        return consoleInput(reader,ignored -> { });
    }

    static ProviderAuthApplicationService.SecretInput consoleInput(
            PasswordReader reader,java.util.function.Consumer<String> previewConsumer) {
        java.util.Objects.requireNonNull(reader, "reader 不能为空");
        java.util.Objects.requireNonNull(previewConsumer, "previewConsumer 不能为空");
        return () -> {
            char[] value = reader.readPassword();
            if (value == null) {
                throw new ProviderAuthException(
                        ProviderAuthException.Code.AUTH_SECRET_INPUT_REQUIRED,
                        ProviderAuthException.Action.LOGIN,
                        false);
            }
            try {
                previewConsumer.accept(credentialPreview(value));
                return new SecretMaterial(value);
            } finally {
                java.util.Arrays.fill(value, '\0');
            }
        };
    }

    /**
     * 生成仅供当前 TUI 配置结果展示的有界摘要；短值或非普通 ASCII 值不投影任何片段。
     */
    static String credentialPreview(char[] value) {
        if (value.length < 12) return "已保存（已隐藏）";
        String first = new String(value,0,3);
        String last = new String(value,value.length-4,4);
        if (!first.matches("[A-Za-z0-9_-]{3}") || !last.matches("[A-Za-z0-9_-]{4}")) {
            return "已保存（已隐藏）";
        }
        return first+"..."+last;
    }

    @FunctionalInterface
    interface PasswordReader {
        char[] readPassword();
    }

    static SecretMaterial readStdinSecret(InputStream input){
        Objects.requireNonNull(input);byte[] bytes;
        try{ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[1024];
            int read;while((read=input.read(buffer))>=0){if(output.size()+read>16*1024)throw new IllegalArgumentException("stdin too large");output.write(buffer,0,read);}bytes=output.toByteArray();}
        catch(java.io.IOException failure){throw new IllegalArgumentException("stdin read");}
        try{int length=bytes.length;if(length>0&&bytes[length-1]=='\n'){length--;if(length>0&&bytes[length-1]=='\r')length--;}
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            char[] chars=decoder.decode(ByteBuffer.wrap(bytes,0,length)).toString().toCharArray();
            try{return new SecretMaterial(chars);}finally{java.util.Arrays.fill(chars,'\0');}}
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("stdin utf8");}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
    }

    private static int execute(PrintWriter err,Action action){
        try{return action.run();}
        catch(ProviderAuthException failure){err.println("cc-java: "+failure.code());return switch(failure.code()){
            case PROVIDER_UNKNOWN,MODEL_UNKNOWN,AUTH_PROFILE_UNKNOWN,AUTH_PROFILE_REQUIRED,
                 AUTH_SECRET_UNAVAILABLE,LEGACY_CONFIGURATION_INCOMPLETE->3;
            case AUTH_STORE_INSECURE,AUTH_STORE_LOCKED,AUTH_STORE_CORRUPT,AUTH_TRANSACTION_CONFLICT,
                 AUTH_STORE_DELETE_FAILED->4;
            case AUTH_PROBE_REJECTED,AUTH_PROBE_RATE_LIMITED,AUTH_PROBE_UNSUPPORTED,
                 AUTH_PROBE_UNREACHABLE->5;
            case AUTH_PROBE_TIMED_OUT,AUTH_CANCELLED->6;
            case AUTH_LOGOUT_DRAIN_FAILED->7;
            default->2;};}
        catch(PiCliLogin.Failure failure){err.println("cc-java: "+failure.code());
            return failure.code()==PiCliLogin.Code.PI_AUTH_CANCELLED?6:4;}
        catch(IllegalArgumentException failure){err.println("cc-java: invalid provider/auth input");return 2;}
        catch(RuntimeException failure){err.println("cc-java: provider/auth operation failed");return 4;}
    }
    private static void writeJson(PrintWriter out,Object value){out.println(JSON.writeValueAsString(value));}
    @FunctionalInterface private interface Action{int run();}
}
