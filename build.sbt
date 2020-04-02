import java.nio.file.{Files, StandardCopyOption}
import com.typesafe.sbt.packager.docker._

val dockerGraalvmNative = taskKey[Unit]("Create a docker image containing a binary build with GraalVM's native-image.")
val dockerGraalvmNativeImageName = settingKey[String]("Name of the generated docker image, containing the native binary.")

ThisBuild /  scalaVersion := "2.12.9"

lazy val rootProject = (project in file("."))
  .settings(publishArtifact := false, name := "graalvm-tests")
  .aggregate(core)

lazy val core: Project = (project in file("core"))
  .enablePlugins(DockerPlugin)
  .enablePlugins(JavaServerAppPackaging)
  .settings(
    dockerBaseImage := "oracle/graalvm-ce:20.0.0",
    dockerCommands ++= Seq(
      Cmd("USER", "root"),
      ExecCmd("RUN", "gu", "install", "native-image")
    ),
    Universal / javaOptions += "-J-agentlib:native-image-agent=config-output-dir=/data/"
  )
  .settings(
    name := "core",
    packageName in Docker := "docker-test",
    dockerUpdateLatest := true,
    dockerGraalvmNativeImageName := "docker-graalvm-native-test",
    dockerGraalvmNative := {
      val log = streams.value.log

      val stageDir = target.value / "native-docker" / "stage"
      stageDir.mkdirs()

      // copy all jars to the staging directory
      val cpDir = stageDir / "cp"
      cpDir.mkdirs()

      val classpathJars = Seq((packageBin in Compile).value) ++ (dependencyClasspath in Compile).value.map(_.data)
      classpathJars.foreach(cpJar => Files.copy(cpJar.toPath, (cpDir / cpJar.name).toPath, StandardCopyOption.REPLACE_EXISTING))

      val resultDir = stageDir / "result"
      resultDir.mkdirs()
      val resultName = "out"

//      IO.copyFile(new File("libio_grpc_netty_shaded_netty_tcnative_linux_x86_64.so"), resultDir / "libio_grpc_netty_shaded_netty_tcnative_linux_x86_64.so")

      val className = (mainClass in Compile).value.getOrElse(sys.error("Could not find a main class."))

      val runNativeImageCommand = Seq(
        "docker",
        "run",
        "--rm",
        "-v",
        s"${cpDir.getAbsolutePath}:/opt/cp",
        "-v",
        s"${resultDir.getAbsolutePath}:/opt/graalvm",
        "graalvm-native-image",
        "-cp",
        "/opt/cp/*",
//        "--static",
        "--no-fallback",
        "--verbose",
//        "-H:+AllowVMInspection",
//        "-H:-RuntimeAssertions",
//        "-H:-PrintUniverse", // if "+" prints out all classes which are included
//        "-H:-NativeArchitecture", //
        "-H:+ReportExceptionStackTraces",
        "-H:+AllowIncompleteClasspath", // the shaded netty reflection jsons doesn't update the class to be the shaded version, will need to add our own config
        "-H:EnableURLProtocols=http",
        "-H:EnableURLProtocols=https",
        "-H:IncludeResources=.+\\.conf",
        "-H:IncludeResources=.+\\.properties",
        "--report-unsupported-elements-at-runtime", // some where uses a defineClass that isn't supported
        "--initialize-at-run-time=" +
          Seq(
            // These are to make up for the lack of shaded configuration for svm/native-image in grpc-netty-shaded
            "com.sun.jndi.dns.DnsClient",
            "io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2CodecUtil",
            "io.grpc.netty.shaded.io.netty.handler.codec.http2.DefaultHttp2FrameWriter",
            "io.grpc.netty.shaded.io.netty.handler.codec.http.HttpObjectEncoder",
            "io.grpc.netty.shaded.io.netty.handler.codec.http.websocketx.WebSocket00FrameEncoder",
            "io.grpc.netty.shaded.io.netty.handler.ssl.util.ThreadLocalInsecureRandom",
            "io.grpc.netty.shaded.io.netty.handler.ssl.ConscryptAlpnSslEngine",
            "io.grpc.netty.shaded.io.netty.handler.ssl.JettyNpnSslEngine",
            "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslEngine",
            "io.grpc.netty.shaded.io.netty.handler.ssl.JdkNpnApplicationProtocolNegotiator",
            "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslServerContext",
            "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslClientContext",
            "io.grpc.netty.shaded.io.netty.handler.ssl.util.BouncyCastleSelfSignedCertGenerator",
            "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslContext",
            "io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel",

            "io.grpc.netty.shaded.io.netty.handler.ssl.OpenSsl",
            "io.grpc.netty.shaded.io.netty.handler.codec.http2.DefaultHttp2FrameWriter",
            "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslEngine",
            "io.grpc.netty.shaded.io.netty.handler.ssl.util.BouncyCastleSelfSignedCertGenerator",
            "io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts",
            "io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder",
            "com.google.api.gax.grpc.InstantiatingGrpcChannelProvider",
            "io.grpc.netty.shaded.io.netty.internal.tcnative.SSL"
      ).mkString(","),
        "--initialize-at-build-time"
          + Seq(
          "org.slf4j",
          "scala",
          "com.google.Protobuf"
        ).mkString("=", ",", ""),
        s"-H:Name=$resultName",
        className
      )

      log.info("Running native-image using the 'graalvm-native-image' docker container")
      log.info(s"Running: ${runNativeImageCommand.mkString(" ")}")

      sys.process.Process(runNativeImageCommand, resultDir) ! streams.value.log match {
        case 0 => resultDir / resultName
        case r => sys.error(s"Failed to run docker, exit status: " + r)
      }

//      IO.copyFile(new File("akka.json"), resultDir / "akka.json")

      val buildContainerCommand = Seq(
        "docker",
        "build",
        "-t",
        dockerGraalvmNativeImageName.value,
        "-f",
        (baseDirectory.value.getParentFile / "run-native-image" / "Dockerfile").getAbsolutePath,
        resultDir.absolutePath
      )
      log.info("Building the container with the generated native image")
      log.info(s"Running: ${buildContainerCommand.mkString(" ")}")

      sys.process.Process(buildContainerCommand, resultDir) ! streams.value.log match {
        case 0 => resultDir / resultName
        case r => sys.error(s"Failed to run docker, exit status: " + r)
      }

      log.info(s"Build image ${dockerGraalvmNativeImageName.value}")
    },
    libraryDependencies ++= Seq(
     "com.google.cloud" % "google-cloud-spanner" % "1.52.0"
    )
  )
