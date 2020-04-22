FROM quay.io/quarkus/ubi-quarkus-native-image:20.0.0-java11 AS build

COPY src /usr/src/app/src
COPY gradle /usr/src/app/gradle
COPY build.gradle.kts settings.gradle.kts gradlew /usr/src/app/

USER root
RUN chown -R quarkus /usr/src/app

USER quarkus
RUN cd /usr/src/app \
 && ./gradlew buildNative

FROM registry.access.redhat.com/ubi8/ubi-minimal:8.1

WORKDIR /work/

COPY --from=build /usr/src/app/build/*-runner /work/application

# set up permissions for user `1001`
RUN chmod 775 /work /work/application \
 && chown -R 1001 /work \
 && chmod -R "g+rwX" /work \
 && chown -R 1001:root /work

EXPOSE 8080

USER 1001

CMD ["./application", "-Dquarkus.http.host=0.0.0.0"]
