package graal.please.work

import com.google.cloud.spanner._

object Hello {

  def main(args: Array[String]): Unit = {
    println("Connecting to spanner...")

    // this will blow up without auth
    val databaseId = DatabaseId.of("akka-team", "chbatey-akka", "akka")
    val spanner: Spanner = SpannerOptions.newBuilder
      .setProjectId("akka-team")
      .build().getService()
    val client: DatabaseClient = spanner.getDatabaseClient(databaseId)
    val result = client.singleUse().executeQuery(Statement.of("select * from messages"))
    while (result.next()) {
      println(result.getCurrentRowAsStruct)
    }
    result.close()

  }


}
