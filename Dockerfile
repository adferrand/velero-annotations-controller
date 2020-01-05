FROM openjdk:11

COPY . /velero-annotations-controller
RUN cd velero-annotations-controller \
 && chmod +x ./gradlew \
 && ./gradlew installDist

FROM openjdk:11

# Example: "one-ns,another-ns" to run the controller only for ns one-ns and another-ns.
# Filter is disabled if the environment variable is empty.
ENV VELERO_ANNOTATIONS_CONTROLLER_NS_FILTER ""

COPY --from=0 /velero-annotations-controller/build/install/velero-annotations-controller /application

CMD ["/application/bin/velero-annotations-controller"]
