package com.edac.extraction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExtractionApplication {
    public static void main(String[] args) {
        var application=new SpringApplication(ExtractionApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try(var context=application.run(args)) {
            int code=context.getBean(RunCoordinator.class).exitCode();
            System.exit(SpringApplication.exit(context,()->code));
        }
    }
}
