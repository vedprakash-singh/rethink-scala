package com.rethinkscala.net

import org.jboss.netty.channel.Channel
import ql2.Ql2.{Query, VersionDummy}
import org.jboss.netty.handler.queue.{BlockingReadTimeoutException, BlockingReadHandler}
import org.jboss.netty.buffer.ChannelBuffer
import java.util.concurrent.{Executors, TimeUnit}
import java.io.IOException
import java.nio.charset.Charset
import scala.concurrent.ExecutionContext
import com.typesafe.scalalogging.slf4j.LazyLogging
import scala.beans.BeanProperty
import com.rethinkscala.Term
import com.rethinkscala.ast.{WithDB, DB}
import org.jboss.netty.buffer.ChannelBuffers._
import com.rethinkscala.ast.DB
import scala.Some
import com.rethinkscala.net.RethinkDriverError
import java.nio.ByteOrder
import com.rethinkscala.ast.DB
import scala.Some
import com.rethinkscala.net.RethinkDriverError


/**
 * Created with IntelliJ IDEA.
 * User: keyston
 * Date: 7/3/13
 * Time: 5:22 PM
 *
 */

trait CompiledQuery{

  protected final def newBuffer(size:Int) = buffer(ByteOrder.LITTLE_ENDIAN,size)

  def encode: ChannelBuffer
}
class ProtoBufCompiledQuery(underlying:Query) extends CompiledQuery{
  override def encode = {
    val size = underlying.getSerializedSize
    val b = newBuffer(size+4)
    b.writeInt(size)

    b.writeBytes(underlying.toByteArray)
    b
  }
}
class JsonCompiledQuery(underlying:String) extends CompiledQuery{
  override def encode = ???
}
abstract class Version extends LazyLogging {

  val host: String
  val port: Int
  val maxConnections: Int
  val db: Option[String]
  val timeout: Int = 10



  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))

  def configure(c: Channel)
  def toQuery(term: Term, token: Int, db: Option[String] = None, opts: Map[String, Any] = Map()):CompiledQuery
}


trait ProvidesProtoBufQuery{


  def toQuery(term: Term, token: Int, db: Option[String], opts: Map[String, Any]) = {

    def scopeDB(q: Query.Builder, db: DB) = q.addGlobalOptargs(Query.AssocPair.newBuilder.setKey("db").setVal(db.ast))

    val query = Some(
      Query.newBuilder().setType(Query.QueryType.START)
        .setQuery(term.ast).setToken(token).setAcceptsRJson(true)

    ).map(q => {

      opts.get("db").map {
        case name: String => scopeDB(q, DB(name))
      }.getOrElse {
        term match {
          case d: WithDB => d.db.map(scopeDB(q, _)).getOrElse(db.map {
            name => scopeDB(q, DB(name))
          }.getOrElse(q))
          case _ => db.map {
            name => scopeDB(q, DB(name))
          }.getOrElse(q)
        }

      }


    }).get

    new ProtoBufCompiledQuery(query.build())

  }
}
case class Version1(host: String = "localhost", port: Int = 28015, db: Option[String] = None, maxConnections: Int = 5)
  extends Version with ProvidesProtoBufQuery {

  type CompiledQuery = Query
  def configure(c: Channel) {
    c.write(VersionDummy.Version.V0_1).await()
  }


}

object Version1 {
  val builder = new Builder {

    def build = Version1(host, port, Option(db), maxConnections)
  }
}

abstract class Builder {

  @BeanProperty
  var host: String = "localhost"
  @BeanProperty
  var port: Int = 2801
  @BeanProperty
  var maxConnections: Int = 5
  @BeanProperty
  var db: String = ""
  @BeanProperty
  var timeout: Int = 10

  def build: Version

}

object Version2 {
  val builder = new Builder {
    @BeanProperty
    val authKey = ""

    def build = Version2(host, port, Option(db), maxConnections, authKey)
  }
}


case class Version2( host: String = "localhost",  port: Int = 28015,
                     db: Option[String] = None,  maxConnections: Int = 5,
                    authKey: String = "") extends Version with ProvidesProtoBufQuery {


  private[this] val AUTH_RESPONSE = "SUCCESS"

   def configure(c: Channel) {

    logger.debug("Configuring channel")
    val pipeline = c.getPipeline
    val authHandler = new BlockingReadHandler[ChannelBuffer]()
    pipeline.addFirst("authHandler", authHandler)


    c.write(VersionDummy.Version.V0_2)
    c.write(authKey).await()


    try {
      val response = Option(authHandler.read(timeout, TimeUnit.SECONDS)).map(b => b.toString(Charset.forName("US-ASCII"))).getOrElse("")

      logger.debug(s"Server auth responsed with : $response")

      if (!response.startsWith(AUTH_RESPONSE))
        throw new RethinkDriverError(s"Server dropped connection with message: '$response'")


    } catch {
      case e: BlockingReadTimeoutException => logger.error("Timeout error", e)
      case e: IOException => logger.error("Unable to read from socket", e)
    } finally {
      pipeline.remove(authHandler)
    }


  }
}