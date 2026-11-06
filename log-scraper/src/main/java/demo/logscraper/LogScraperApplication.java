package demo.logscraper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LogScraperApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogScraperApplication.class, args);
    }
}
