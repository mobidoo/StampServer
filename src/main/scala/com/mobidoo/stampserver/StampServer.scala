package com.mobidoo.stampserver

import akka.io.IO
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import akka.routing._
import spray.can.Http
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConversions._

/**
 * Stamp Server Object
 */
object StampServer extends App {
  implicit val system = ActorSystem()

  // load config
  private val config = ConfigFactory.load()
  private val host = config.getString("stamp_server.host")
  private val port = config.getInt("stamp_server.port")
  private val logActorCnt = config.getInt("stamp_server.log_actor_cnt")

  private val mongoDBHosts = config.getStringList("stamp_server.mongodb.hosts").toList
  private val mongoDBPort  = config.getInt("stamp_server.mongodb.port")

  private val redisDB     = config.getString("stamp_server.redis.host")
  private val redisDBPort = config.getInt("stamp_server.redis.port")

  // load resources
  private val resources =
    new StampServerResources(StampServerConfig(host,port, MongoDBServer(mongoDBHosts, mongoDBPort),
      RedisServer(redisDB, redisDBPort)))

  // supervisor for log writer router
  val escalator = OneForOneStrategy() {
    case e : Throwable => 
      Restart
  }
  
  private val logWriter = system.actorOf(Props(new StampLogWriter)
      .withRouter(RoundRobinRouter(logActorCnt, supervisorStrategy = escalator)), "LogWriterRouter")
  private val handler   = system.actorOf(Props(new StampActor(logWriter)), name = "handler")

  IO(Http) ! Http.Bind(handler, host, port)

  def getResources = resources
}





