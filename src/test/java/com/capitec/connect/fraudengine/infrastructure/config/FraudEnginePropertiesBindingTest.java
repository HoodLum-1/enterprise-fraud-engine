package com.capitec.connect.fraudengine.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEnginePropertiesBindingTest {

    private static final String MAIN_APPLICATION_YAML = "src/main/resources/application.yml";
    private static final String PAST_SKEW_KEY = "fraud.engine.evaluation.max-past-skew";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class))
        .withUserConfiguration(FraudEngineProperties.class);

    @Test
    void theRealApplicationYamlBindsPastSkewUnderEvaluation() {
        Map<String, String> yamlValues = mainApplicationYamlValues();

        // The key itself must sit under evaluation, not directly under fraud.engine.
        assertThat(yamlValues).containsKey(PAST_SKEW_KEY);
        assertThat(yamlValues).doesNotContainKey("fraud.engine.max-past-skew");

        contextRunner.withPropertyValues(pairsOf(yamlValues)).run(context -> {
            assertThat(context).hasNotFailed();
            FraudEngineProperties properties = context.getBean(FraudEngineProperties.class);

            assertThat(properties.getEvaluation().getMaxPastSkew())
                .isEqualTo(Duration.parse(yamlValues.get(PAST_SKEW_KEY)));
            assertThat(properties.getEvaluation().getMaxFutureSkew()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.getEvaluation().getHistoryWindow()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.getMessaging().isDlqMetricsEnabled()).isTrue();
            assertThat(properties.getRules().getVelocity().getWindow()).isEqualTo(Duration.ofMinutes(2));
        });
    }

    @Test
    void aMisplacedKeyFailsBinding() {
        // Valid under fraud.engine.evaluation, but unknown directly under fraud.engine.
        contextRunner.withPropertyValues("fraud.engine.max-past-skew=P7D")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure().rootCause()
                    .isInstanceOf(UnboundConfigurationPropertiesException.class)
                    .hasMessageContaining("fraud.engine.max-past-skew");
            });
    }

    @Test
    void anUnknownKeyFailsBinding() {
        contextRunner.withPropertyValues("fraud.engine.evaluation.max-past-skewy=P7D")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure().rootCause()
                    .isInstanceOf(UnboundConfigurationPropertiesException.class);
            });

        contextRunner.withPropertyValues("fraud.engine.messaging.batch-sizes=20")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure().rootCause()
                    .isInstanceOf(UnboundConfigurationPropertiesException.class);
            });

        // The correctly placed spelling is accepted, so the failures above are not a broken runner.
        contextRunner.withPropertyValues(PAST_SKEW_KEY + "=P14D")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FraudEngineProperties.class).getEvaluation().getMaxPastSkew())
                    .isEqualTo(Duration.ofDays(14));
            });
    }

    @Test
    void settingsThatMustBePositiveOrNonNegativeFailBinding() {
        contextRunner.withPropertyValues("fraud.engine.rules.velocity.threshold=0")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure().rootCause()
                    .isInstanceOf(BindValidationException.class)
                    .hasMessageContaining("must be greater than 0");
            });

        contextRunner.withPropertyValues("fraud.engine.rules.velocity.window=-1s")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure().rootCause()
                    .isInstanceOf(BindValidationException.class);
            });

        contextRunner.withPropertyValues("fraud.engine.messaging.batch-size=0")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure().rootCause()
                    .isInstanceOf(BindValidationException.class);
            });

        contextRunner.withPropertyValues("fraud.engine.rules.velocity.risk-score=-1")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure().rootCause()
                    .isInstanceOf(BindValidationException.class);
            });

        // A zero clock-skew allowance is meaningful, so it must still be accepted.
        contextRunner.withPropertyValues("fraud.engine.evaluation.max-future-skew=PT0S")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FraudEngineProperties.class).getEvaluation().getMaxFutureSkew())
                    .isZero();
            });
    }

    private Map<String, String> mainApplicationYamlValues() {
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader()
                .load("mainApplicationYaml", new FileSystemResource(MAIN_APPLICATION_YAML));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + MAIN_APPLICATION_YAML, ex);
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (PropertySource<?> source : sources) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            Object raw = enumerable.getSource();
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                values.put(String.valueOf(entry.getKey()), asText(unwrap(entry.getValue())));
            }
        }
        return values;
    }

    private String[] pairsOf(Map<String, String> values) {
        return values.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new);
    }

    private Object unwrap(Object value) {
        return value instanceof OriginTrackedValue tracked ? tracked.getValue() : value;
    }

    private String asText(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        }
        return String.valueOf(value);
    }
}
