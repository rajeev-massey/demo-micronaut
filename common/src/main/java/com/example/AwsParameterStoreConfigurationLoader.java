package com.example;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.BootstrapContextCompatible;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.discovery.config.ConfigurationClient;
import io.micronaut.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@BootstrapContextCompatible
@Singleton
@Requires(beans = {JsonMapper.class})
@Requires(property = "aws.ssm.config.path")
@Requires(property = "aws.ssm.config.description", defaultValue = "AWS Configuration Client")
public class AwsParameterStoreConfigurationLoader implements ConfigurationClient {

    private static final Logger LOG = LoggerFactory.getLogger(AwsParameterStoreConfigurationLoader.class);
    public static final String AWS_SSM_CONFIGURATION_NAME = "aws-env";

    @Property(name = "aws.ssm.config.description", defaultValue = "AWS Configuration Client")
    private String description;

    @Property(name = "aws.ssm.config.path")
    private String configurationParameterStorePath;


    private final JsonMapper jsonMapper;
    private final ApplicationContext context;
    private final ConcurrentHashMap<String, Object> cache;


    AwsParameterStoreConfigurationLoader(
            JsonMapper jsonMapper,
            ApplicationContext context) {

        this.jsonMapper = jsonMapper;
        this.context = context;
        this.cache = new ConcurrentHashMap<>();
    }

    @PostConstruct
    private void init() {
        loadConfiguration();
    }

    @Override
    public Publisher<PropertySource> getPropertySources(Environment environment) {
        return Mono.just(getCustomPropertySource());
    }

    @Override
    public @NonNull String getDescription() {
        return description;
    }

    private Object getProperty(String key) {
        return cache.computeIfAbsent(key, k -> {
            try {
                loadConfiguration();
                return cache.get(k);
            } catch (Exception e) {
                LOG.error("Error fetching configuration from SSM for key: " + key, e);
                throw new RuntimeException("Error fetching configuration from SSM for key: " + key, e);
            }
        });
    }

    private void loadConfiguration() {
        try {
            if (cache.isEmpty()) {
                String jsonConfig = provideJsonString();
                Map<String, Object> configMap = jsonMapper.readValue(jsonConfig, Map.class);

                configMap.forEach(cache::put);

                PropertySource resolvedPropertySource = PropertySource.of(AWS_SSM_CONFIGURATION_NAME, configMap, PropertySource.PropertyConvention.ENVIRONMENT_VARIABLE);
                context.getEnvironment().addPropertySource(resolvedPropertySource);

                context.getEnvironment().refresh();

                verifyConfiguration(configMap.keySet());
            }
        } catch (Exception e) {
            throw new RuntimeException("Error loading configuration from SSM", e);
        }
    }

    private String provideJsonString() {
        return "{\n" +
                "\"MCA_DOWNSTREAM_URL\" : \"<Some URL>\",\n" +
                "\"MCA_DOWNSTREAM_TIMEZONE\" : \"<Some Timezone>\"\n" +
                "}";
    }

    private void verifyConfiguration(Set<String> requiredProperties) {
        var missingProperties = requiredProperties.stream().filter(property -> !context.getEnvironment().containsProperty(property)).collect(Collectors.toList());

        if (!missingProperties.isEmpty()) {
            String errorMessage = "The following required properties are missing: " + String.join(", ", missingProperties);
            throw new IllegalStateException(errorMessage);
        }

        LOG.info("All required properties have been successfully loaded and verified.");
    }

    private PropertySource getCustomPropertySource() {
        if (cache.isEmpty()) {
            loadConfiguration();
            verifyConfiguration(cache.keySet());
        }
        return PropertySource.of(AWS_SSM_CONFIGURATION_NAME, cache.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)), PropertySource.PropertyConvention.ENVIRONMENT_VARIABLE);
    }
}