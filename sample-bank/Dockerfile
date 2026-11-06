FROM mirror.gcr.io/library/eclipse-temurin:21-jre
ENV TZ=Asia/Jakarta
WORKDIR /opt/bank
COPY target/sample-bank.jar /opt/bank/sample-bank.jar
EXPOSE 8080 2222
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx96m", "-jar", "/opt/bank/sample-bank.jar"]
