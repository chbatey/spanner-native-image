import akka.actor.ActorSystem
import akka.grpc.GrpcClientSettings
import akka.stream.{ActorMaterializer, Materializer}
import com.google.auth.oauth2.GoogleCredentials
import io.grpc.auth.MoreCallCredentials
import com.google.spanner.v1.{CreateSessionRequest, DeleteSessionRequest, ExecuteSqlRequest, Session, SpannerClient}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object Main {

  def main(args: Array[String]): Unit = {

    System.getProperties.forEach((key, value) => println(s"$key=$value"))
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace")

    implicit val system = ActorSystem("name")
    implicit val mat: Materializer = ActorMaterializer()
    implicit val ec = system.dispatcher

    val project = "akka-team"
    val instance = "chbatey-akka"
    val database = "akka"

    try {
      val credentials = GoogleCredentials.getApplicationDefault
        .createScoped("https://www.googleapis.com/auth/spanner")

      val callCredentials = MoreCallCredentials.from(credentials)

      val settings = GrpcClientSettings.fromConfig("spanner-client")
        .withCallCredentials(callCredentials)
      val fullyQualifiedDbName: String = s"projects/$project/instances/$instance/databases/$database"

      val spanner = SpannerClient(settings)

      val session = Session()
      val rows = for {
        session <- spanner.createSession(CreateSessionRequest(fullyQualifiedDbName, Some(session)))
        response <- spanner.executeSql(ExecuteSqlRequest(session.name, None, "select * from messages"))
        _ <- spanner.deleteSession(DeleteSessionRequest(session.name))
      } yield {
        println("Session name: " + session.name)
        response.rows
      }

      println(Await.result(rows, Duration.Inf))
    } finally {
      system.terminate()
    }
  }
}
