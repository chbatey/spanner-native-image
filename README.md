# Spanner in native image

1. create locally the `graalvm-native-image` container using `graalvm-native-image/build.sh`. This container will be used to build the native image.
2. If you need to update the graal config run in sbt: `docker:publishLocal`: this will generate the `docker-test` container, this is used to get the graal reflection config
    - docker run -v somwherelocal:/data -it docker-test:latest
    - files will created in some where local
    - put them in core/src/main/resources/META-INF/native-image
3. run in sbt: `dockerGraalvmNative`: this will generate the `docker-graalvm-native-test` container containing the image, run this image
