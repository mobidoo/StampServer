package com.mobidoo.stampserver

import reactivemongo.api._
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson._
import reactivemongo.core.commands.LastError

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor.ActorSystem
import spray.caching.{Cache, ExpiringLruCache}

import redis.RedisClient

import org.joda.time.DateTime

/**
 * This class represents resources used in the stamp server. (ex) databases, cache
 * @param config
 */
class StampServerResources(config:StampServerConfig){
  val stampDB     = new StampDB((new MongoDriver).connection(config.mongoDBServer.hosts))
  val stampRedis  = new StampServerRedis(config.redisServer.host)
  val stampCache  = new StampServerCache(stampDB)

  def getStampDB    = stampDB
  def getStampRedis = stampRedis
  def getStampCache = stampCache

}

/**
 * This class represents a local cache for performance
 * @param stampDB
 */
class StampServerCache(stampDB:StampDB){
  val system = ActorSystem()
  import system.dispatcher

  val userCache : Cache[Option[StampUser]] = new ExpiringLruCache(maxCapacity = 500, initialCapacity = 16,
    timeToLive = 10 minutes, timeToIdle = Duration.Inf)

  val storeCache : Cache[Option[StampStore]] = new ExpiringLruCache(maxCapacity = 500, initialCapacity = 16,
    timeToLive = 10 minutes, timeToIdle = Duration.Inf)


  def getUserInfo(userId:String) : Future[Option[StampUser]] = userCache(userId){
      stampDB.getUser(userId)
    }

  def getStoreInfo(storeId:String) : Future[Option[StampStore]] = storeCache(storeId){
      stampDB.getStore(storeId)
    }

}

/**
 * Stamp MongoDB
 * @param mongoDBConn
 */
class StampDB(mongoDBConn : MongoConnection ) {
  import scala.concurrent.ExecutionContext.Implicits.global

  private val dbName = "stamp"
  val database = mongoDBConn(dbName)
  val userColl  = database.collection[BSONCollection]("user")
  val storeColl = database.collection[BSONCollection]("store")
  val logColl   = database.collection[BSONCollection]("log")

  implicit object BSONDateTimeHandler extends BSONHandler[BSONDateTime, DateTime] {
    def read(time: BSONDateTime) = new DateTime(time.value)
    def write(jdtime: DateTime) = BSONDateTime(jdtime.getMillis)
  }

  implicit object UserWriter extends BSONDocumentWriter[StampUser] {
    def write(user: StampUser): BSONDocument = BSONDocument(
      "userid" -> user.id,
      "name" -> user.name,
      "birthday" -> user.birthDay,
      "gender" -> user.gender,
      "password" -> user.password)
  }

  implicit object UserReader extends BSONDocumentReader[StampUser] {
    def read(doc: BSONDocument): StampUser = {
      StampUser(doc.getAs[String]("userid").getOrElse(""),
        doc.getAs[String]("name").getOrElse(""),
        doc.getAs[String]("birthday").getOrElse(""),
        doc.getAs[Int]("gender").getOrElse(0),
        doc.getAs[String]("password").getOrElse(""))
    }
  }


  implicit object StoreWriter extends BSONDocumentWriter[StampStore] {
    def write(store: StampStore): BSONDocument = BSONDocument(
      "storeid" -> store.id,
      "name" -> store.name,
      "addr" -> store.addr,
      "password" -> store.password,
      "created" -> DateTime.now())
  }

  implicit object StoreReader extends BSONDocumentReader[StampStore] {
    def read(doc: BSONDocument): StampStore = {
      StampStore(doc.getAs[String]("storeid").getOrElse(""),
        doc.getAs[String]("name").getOrElse(""),
        doc.getAs[String]("addr").getOrElse(""),
        "")
    }
  }

  implicit object LogWriter extends BSONDocumentWriter[StampLog] {
    def write(log: StampLog): BSONDocument = BSONDocument(
      "userId"    -> log.userId,
      "storeId"   -> log.storeId,
      "action"    -> log.action,
      "stamp_num" -> log.stampNumber,
      "status"    -> log.status,
      "datetime"  -> log.dateTime)
  }

  def getUser(userId:String) : Future[Option[StampUser]] = {
    val query = BSONDocument(
      "userid" -> userId
    )
    userColl.find(query).one[StampUser]
  }

  def getStore(storeId:String) : Future[Option[StampStore]] = {
    val query = BSONDocument(
      "storeid" -> storeId
    )
    storeColl.find(query).one[StampStore]
  }

  def insertUser(u:StampUser) : Future[Boolean] = {
    userColl.insert(u).map {
      case LastError(true, _,_,_,Some(doc),_,_) =>
        true
      case _ =>
        false
    }
  }

  def insertStore(store:StampStore) : Future[Boolean] = {
    storeColl.insert(store).map {
      case LastError(true, _,_,_,Some(doc),_,_) =>
        true
      case LastError(false, err, code, msg, _, _, _) =>
        false
    }
  }

  def writeStampLog(log:StampLog)  = {
    logColl.insert(log).map {
      case LastError(true, _,_,_,Some(doc),_,_) =>
        true
      case LastError(false, err, code, msg, _, _, _) =>
        false
    }
  }
}

/**
 * Redis for stamp action
 * @param host
 */
class StampServerRedis(host:String){
  implicit val akkaSystem = akka.actor.ActorSystem()
  import akkaSystem.dispatcher

  private val redis = RedisClient(host)

  def putStamp(userId:String, storeId:String, stampNum:Int) : Unit = {
    redis.hset(s"$userId:$storeId", stampNum.toString, "y")
  }

  def getStampList(userId:String, storeId:String) : Future[List[Int]] = {
    redis.hgetall(s"$userId:$storeId").map(_.keys.map(_.toInt).toList)
  }

  def removeStampList(userId:String, storeId:String) : Unit = {
    redis.del(s"$userId:$storeId")
  }

  def getStamp(userId:String, storeId:String, stampNum:Int) : Future[Boolean] = {
    redis.hexists(s"$userId:$storeId", stampNum.toString)
  }

}