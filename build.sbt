import java.io.File
import java.nio.file.{Files, StandardCopyOption}

import com.typesafe.sbt.packager.docker._

val dockerGraalvmNative = taskKey[Unit]("Create a docker image containing a binary build with GraalVM's native-image.")
val dockerGraalvmNativeImageName = settingKey[String]("Name of the generated docker image, containing the native binary.")

ThisBuild /  scalaVersion := "2.12.9"
ThisBuild / resolvers += Resolver.bintrayRepo("akka", "maven")

val AkkaVersion = "2.6.4"
val GraalAkkaVersion = "0.4.1"
val SpannerVersion = "1.52.0"
// keep in sync with akka grpc, for grpc auth
val GrpcJavaVersion = "1.28.0"
val GraalVersion = "19.3.1"

lazy val rootProject = (project in file("."))
  .settings(publishArtifact := false, name := "graalvm-tests")
  .aggregate(spannerClient, directGrpc)

lazy val directGrpc = (project in file("direct-grpc"))
  .enablePlugins(DockerPlugin)
  .enablePlugins(JavaServerAppPackaging)
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    dockerBaseImage := s"oracle/graalvm-ce:$GraalVersion-java11",
    dockerCommands ++= Seq(
      Cmd("USER", "root"),
      ExecCmd("RUN", "gu", "install", "native-image")
    ),
    dockerEnvVars += "GOOGLE_APPLICATION_CREDENTIALS" -> "/opt/docker/akka.json",
    Universal / javaOptions += "-J-agentlib:native-image-agent=config-output-dir=/data/",
    Universal / javaOptions += "-J-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
    Universal / mappings  += file("akka.json") -> "akka.json",
 )
  .settings(
    name := "direct-grpc",
    packageName in Docker := "direct-grpc",
    dockerUpdateLatest := true,
    dockerGraalvmNativeImageName := "direct-grpc",
    dockerGraalvmNative := {
      val log = streams.value.log

      val stageDir = target.value / "native-docker" / "stage"
      stageDir.mkdirs()

      // copy all jars to the staging directory
      val cpDir = stageDir / "cp"
      cpDir.mkdirs()

      val classpathJars = Seq((packageBin in Compile).value) ++ (dependencyClasspath in Compile).value.map(_.data)
      log.info(s"Class path jars: ${classpathJars}")
      classpathJars.foreach(cpJar => Files.copy(cpJar.toPath, (cpDir / cpJar.name).toPath, StandardCopyOption.REPLACE_EXISTING))

      val resultDir = stageDir / "result"
      resultDir.mkdirs()
      val resultName = "out"

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
        "/opt/cp/*") ++ sharedNativeImageSettings ++ Seq(
       s"-H:Name=$resultName",
        className
      )

      log.info("Running native-image using the 'graalvm-native-image' docker container")
      log.info(s"Running: ${runNativeImageCommand.mkString(" ")}")

      sys.process.Process(runNativeImageCommand, resultDir) ! streams.value.log match {
        case 0 => resultDir / resultName
        case r => sys.error(s"Failed to run docker, exit status: " + r)
      }

      IO.copyFile(new File("akka.json"), resultDir / "akka.json")

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
      "com.google.api.grpc" % "proto-google-cloud-spanner-v1" % SpannerVersion % "protobuf-src",
      "com.google.api.grpc" % "grpc-google-cloud-spanner-admin-database-v1" % SpannerVersion % "protobuf-src",
      "io.grpc" % "grpc-auth" % GrpcJavaVersion,
      "com.google.auth" % "google-auth-library-oauth2-http" % "0.20.0",
      "org.slf4j" % "slf4j-simple" % "1.7.26",
      "org.slf4j" % "slf4j-api" % "1.7.26",
      "org.graalvm.nativeimage" % "svm" % GraalVersion % "provided", // Only needed for compilation
    )
  )

val sharedNativeImageSettings = Seq(
  //"-O1", // Optimization level
//  "-H:ResourceConfigurationFiles=" + targetDir / "resource-config.json",
//  "-H:ReflectionConfigurationFiles=" + targetDir / "reflect-config.json",
//  "-H:DynamicProxyConfigurationFiles=" + targetDir / "proxy-config.json",
  "-H:IncludeResources=.+\\.conf",
  "-H:IncludeResources=.+\\.properties",
  "-H:+AllowVMInspection",
  "-H:-RuntimeAssertions",
  "-H:+ReportExceptionStackTraces",
  "-H:-PrintUniverse", // if "+" prints out all classes which are included
  "-H:-NativeArchitecture", // if "+" Compiles the native image to customize to the local CPU arch
  "--verbose",
  //"--no-server", // Uncomment to not use the native-image build server, to avoid potential cache problems with builds
//  "--report-unsupported-elements-at-runtime",
  "--enable-url-protocols=http,https",
  "--allow-incomplete-classpath",
  "--no-fallback",
//  "--initialize-at-build-time"
//    + Seq(
//    "org.slf4j",
//    "scala",
//    "akka.dispatch.affinity",
//    "akka.util",
//    "com.google.Protobuf"
//  ).mkString("=", ",", ""),
  "--initialize-at-run-time=" +
    Seq(
      "akka.protobuf.DescriptorProtos",
      // We want to delay initialization of these to load the config at runtime
      "com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder",
      "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder",
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
      "io.grpc.netty.shaded.io.netty.handler.ssl.util.ThreadLocalInsecureRandom",
//      "io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel",
    ).mkString(",")
)

lazy val spannerClient: Project = (project in file("spanner-client"))
  .enablePlugins(DockerPlugin)
  .enablePlugins(JavaServerAppPackaging)
  .settings(
    dockerBaseImage := s"oracle/graalvm-ce:$GraalVersion-java11",
    dockerEnvVars += "GOOGLE_APPLICATION_CREDENTIALS" -> "/opt/docker/akka.json",
    mappings in Universal += file("akka.json") -> "akka.json",
    dockerCommands ++= Seq(
      Cmd("USER", "root"),
      ExecCmd("RUN", "gu", "install", "native-image")
    ),
    Universal / javaOptions += "-J-agentlib:native-image-agent=config-output-dir=/data/",
    name := "spanner-client",
    packageName in Docker := "spanner-client-with-java",
    dockerUpdateLatest := true,
    dockerGraalvmNativeImageName := "spanner-client",
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
        "--report-unsupported-elements-at-runtime", // for an unsupported define, not hit it at runtime yet
        "-cp",
        "/opt/cp/*") ++ sharedNativeImageSettings ++ Seq(
        s"-H:Name=$resultName",
        className
      )


      log.info("Running native-image using the 'graalvm-native-image' docker container")
      log.info(s"Running: ${runNativeImageCommand.mkString(" ")}")

      sys.process.Process(runNativeImageCommand, resultDir) ! streams.value.log match {
        case 0 => resultDir / resultName
        case r => sys.error(s"Failed to run docker, exit status: " + r)
      }

      IO.copyFile(new File("akka.json"), resultDir / "akka.json")

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
     "com.google.cloud" % "google-cloud-spanner" % SpannerVersion,
      "org.slf4j" % "slf4j-simple" % "1.7.26",
      "org.graalvm.nativeimage" % "svm" % GraalVersion % "provided", // Only needed for compilation
    )
)
