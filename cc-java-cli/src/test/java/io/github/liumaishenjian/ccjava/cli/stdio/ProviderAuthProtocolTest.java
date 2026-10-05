package io.github.liumaishenjian.ccjava.cli.stdio;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderAuthProtocolTest {
    private final StdioProtocolCodec codec = new StdioProtocolCodec();
    private String frame(String intent, String arguments) {
        return "{\"version\":0,\"type\":\"provider.control\",\"requestId\":\"auth-1\",\"sessionId\":\"session\",\"sequence\":2,\"payload\":{\"controlId\":\"control-1\",\"intent\":\""
                + intent + "\",\"arguments\":" + arguments + "}}";
    }
    @Test void lifecycleControlsOnlyAcceptNonSecretExactArguments() throws Exception {
        for (String intent : new String[]{"auth.activate", "auth.logout.prepare"}) {
            assertThat(codec.decodeCommand(frame(intent, "{\"providerId\":\"anthropic\",\"profileId\":\"default\"}")).type()).isEqualTo("provider.control");
            assertThatThrownBy(() -> codec.decodeCommand(frame(intent,
                    "{\"providerId\":\"anthropic\",\"profileId\":\"default\",\"apiKey\":\"canary\"}"))).isInstanceOf(StdioProtocolException.class);
            assertThatThrownBy(() -> codec.decodeCommand(frame(intent, "{\"providerId\":\"anthropic\"}"))).isInstanceOf(StdioProtocolException.class);
        }
    }
    @Test void commitNeedsExplicitTrueAndCannotSubstituteTargetOrPassSecret() throws Exception {
        String ticket = "c7c35a64-32d6-4d8e-9da6-833df9e1f83d";
        assertThat(codec.decodeCommand(frame("auth.logout.commit", "{\"confirmationId\":\"" + ticket + "\",\"confirmed\":true}")).type()).isEqualTo("provider.control");
        for (String args : new String[]{"{}", "{\"confirmationId\":\"x\",\"confirmed\":false}",
                "{\"confirmationId\":\"x\",\"confirmed\":true,\"providerId\":\"anthropic\"}",
                "{\"confirmationId\":\"x\",\"confirmed\":true,\"token\":\"canary\"}"}) {
            assertThatThrownBy(() -> codec.decodeCommand(frame("auth.logout.commit", args))).isInstanceOf(StdioProtocolException.class);
        }
    }
}
