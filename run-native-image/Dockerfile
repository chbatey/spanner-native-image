FROM debian:10
COPY out /opt/docker/out
COPY akka.json /opt/docker/akka.json
COPY libio_grpc_netty_shaded_netty_tcnative_linux_x86_64.so /opt/docker/libio_grpc_netty_shaded_netty_tcnative_linux_x86_64.so
RUN chmod +x /opt/docker/out
CMD ["/opt/docker/out"]