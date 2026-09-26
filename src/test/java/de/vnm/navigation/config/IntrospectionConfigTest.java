package de.vnm.navigation.config;

import de.vnm.navigation.introspection.IntrospectionClient;
import de.vnm.navigation.introspection.IntrospectionSettings;
import de.vnm.navigation.introspection.StubCenter;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two variables are read raw. Regression: they went through Spring's {@code ${…}}
 * placeholder resolution, so a secret containing {@code ${nope}} failed startup with the
 * whole secret in the exception message, and one containing {@code ${SERVER_PORT}} started
 * with a silently different secret.
 */
@ExtendWith(OutputCaptureExtension.class)
class IntrospectionConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {"s3cr${nope}et", "s3cr${SERVER_PORT}et", "s3cr${SERVER_PORT:x}et", "#{1+1}${"})
    void aSecretThatLooksLikeAPlaceholderReachesTheCenterByteForByte(String secret, CapturedOutput output) {
        try (StubCenter center = StubCenter.answering(StubCenter.Reply.inactive())) {
            new ApplicationContextRunner()
                    .withUserConfiguration(IntrospectionConfig.class)
                    .withPropertyValues(
                            "SERVER_PORT=8080",
                            IntrospectionSettings.ENV_URL + "=" + center.url(),
                            IntrospectionSettings.ENV_SECRET + "=" + secret)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        context.getBean(IntrospectionClient.class).introspect("t");
                    });

            assertThat(center.received()).hasSize(1);
            assertThat(center.received().get(0).header(IntrospectionSettings.SECRET_HEADER)).isEqualTo(secret);
        }
        assertThat(output.getAll()).doesNotContain(secret);
    }
}
