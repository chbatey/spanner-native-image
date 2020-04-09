FROM bitnami/java:11-prod
COPY out /opt/docker/out
COPY akka.json /opt/docker/akka.json
RUN chmod +x /opt/docker/out
CMD ["/opt/docker/out", "-Djava.library.path=/opt/bitnami/java/lib", "-Dio.grpc.netty.shaded.io.netty.noKeySetOptimization=true", "-Djavax.net.debug=all", "-Djavax.net.ssl.trustStore=/opt/bitnami/java/lib/security/cacerts", "-Djavax.net.ssl.trustAnchors=/opt/bitnami/java/lib/security/cacerts", "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"]