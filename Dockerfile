FROM openjdk:11
COPY . /velero-annotations-controller
RUN cd velero-annotations-controller && ./gradlew installDist

FROM openjdk:11
COPY --from=0 /velero-annotations-controller/build/install/velero-annotations-controller /application
CMD ["/application/bin/velero-annotations-controller"]
