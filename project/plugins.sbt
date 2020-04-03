resolvers += Resolver.bintrayRepo("akka", "maven")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.0")
addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "0.7.1")
