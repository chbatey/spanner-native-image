FROM oracle/graalvm-ce:19.3.0-java11
WORKDIR /opt/graalvm
RUN gu install native-image
ENTRYPOINT ["native-image"]
