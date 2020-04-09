package graal.please.work

import java.util.Date

import com.google.api.core.ApiFunction
import com.google.api.gax.retrying.RetrySettings
import com.google.api.gax.rpc.UnaryCallSettings
import com.google.cloud.spanner._
import org.slf4j.LoggerFactory
import org.threeten.bp.Duration

class Hello {

}

object Hello {

  val Log = LoggerFactory.getLogger(classOf[Hello])

  def main(args: Array[String]): Unit = {

    println("Connecting to spanner.... Current time " + new Date())


    Log.debug("debug")
    Log.info("info")
    Log.warn("warn")
    Log.error("error")

    val retrySettings = RetrySettings.newBuilder()
        .setInitialRpcTimeout(Duration.ofSeconds(5L))
        .setMaxRpcTimeout(Duration.ofSeconds(5L))
        .setMaxAttempts(5)
        .setTotalTimeout(Duration.ofSeconds(10L))
        .build();

    // this will blow up without auth
    val databaseId = DatabaseId.of("akka-team", "chbatey-akka", "akka")
    println("database id: " + databaseId)
    val spannerBuilder = SpannerOptions.newBuilder
      .setProjectId("akka-team")


   spannerBuilder.getSpannerStubSettingsBuilder
      .applyToAllUnaryMethods(new ApiFunction[UnaryCallSettings.Builder[_,_], Void] {
        override def apply(input: UnaryCallSettings.Builder[_, _]): Void = {
          input.setRetrySettings(retrySettings)
          null
        }
      })

    val spannerOptions = spannerBuilder.build()

    println("Spanner builder: " + spannerBuilder)
    val spanner = spannerOptions.getService()
    println("Created spanner instance: " + spanner)
    val client: DatabaseClient = spanner.getDatabaseClient(databaseId)
    println("Got DB client. Executing query...")
    val result = client.singleUse().executeQuery(Statement.of("select * from messages"))
    println("Got result")
    while (result.next()) {
      println(result.getCurrentRowAsStruct)
    }
    println("Done")

    result.close()
  }


}
