package com.example;


import io.micronaut.context.env.Environment;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.Map;

@Controller("/env")
public class EnvController {

    private final Environment environment;

    public EnvController(Environment environment) {
        this.environment = environment;
    }

    @Get("/")
    public Map<String, Object> getAllEnvVariables() {
        return environment.getProperties(AwsParameterStoreConfigurationLoader.AWS_SSM_CONFIGURATION_NAME);
    }
}