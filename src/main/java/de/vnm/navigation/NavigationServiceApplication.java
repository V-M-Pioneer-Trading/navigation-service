package de.vnm.navigation;

import de.vnm.navigation.config.SqliteDirectoryInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NavigationServiceApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(NavigationServiceApplication.class);
        // Registered here rather than as a bean: it has to run before the datasource
        // is opened, which happens before ordinary beans are created.
        application.addListeners(new SqliteDirectoryInitializer());
        application.run(args);
    }
}
