FROM mirror.gcr.io/library/eclipse-temurin:21-jre
ENV TZ=Asia/Jakarta
WORKDIR /opt/log-scraper
COPY target/log-scraper.jar /opt/log-scraper/log-scraper.jar
EXPOSE 8090
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/opt/log-scraper/log-scraper.jar"]
