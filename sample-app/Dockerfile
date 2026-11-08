FROM mirror.gcr.io/library/eclipse-temurin:21-jre
ENV TZ=Asia/Jakarta
WORKDIR /opt/sample
COPY target/sample-app.jar /opt/sample/sample-app.jar
EXPOSE 8080 2222
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx96m", "-jar", "/opt/sample/sample-app.jar"]
