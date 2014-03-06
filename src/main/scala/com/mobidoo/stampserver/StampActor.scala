package com.mobidoo.stampserver

import akka.actor._
import akka.event.Logging

import spray.util._
import spray.json._
import spray.can._
import spray.http._
import HttpMethods._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future

import reactivemongo.core.errors.DatabaseException

/**
 * Stamp log writing Actor
 */
class StampLogWriter extends Actor with SprayActorLogging {
  private val stampDB = StampServer.getResources.getStampDB
 
  /*
  override def preRestart(reason: Throwable, message : Option[Any]) {
    // logging
    super.preRestart(reason, message)
  }
  */
  
  def receive = {
    case l@StampLog(uid, sid, act, sn, status,dateTime) =>
      try {
        stampDB.writeStampLog(l)
      } catch {
        case e : Throwable =>
          log.error("error] when writing a log on mongodb :" + e.getMessage())
          //self ! l
      }
      // handler exceptions
    case _ =>
      Unit
  }
  
}

/**
 * Stamp Actor
 */
class StampActor(logWriter:ActorRef) extends Actor {
  import StampServerResponseJson._
  import spray.httpx.SprayJsonSupport._
  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._
  import scala.concurrent.duration._
  
  import context.dispatcher
  
  println("Debug:" + context.system.toString)
  val accessLog = Logging(context.system, this)

  private val stampDB    = StampServer.getResources.getStampDB
  private val stampCache = StampServer.getResources.getStampCache
  private val stampRedis = StampServer.getResources.getStampRedis

  /*
  override val supervisorStrategy = 
    OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 30 seconds){
    case _ : Throwable => Restart
    }*/
  
  def actorRefFactory = context

  def receive = {
    case _ : Http.Connected =>
      sender ! Http.Register(self)

    case r@HttpRequest(GET, Uri.Path("/StampServer/join_user"), _, _, _) =>
      accessLog.info(r.toString)
      genStampUserFromQuery(r).map { userInfo =>
        try {
          val ret = Await.result(stampDB.insertUser(userInfo), 1 seconds)
          ResponseCode(0, "OK")
        } catch {
          case e : DatabaseException =>
            ResponseCode(-1, e.getMessage())
          case e : Throwable =>
            ResponseCode(-2, e.getMessage)
        }
      }.map { res =>
        sender ! HttpResponse(entity=res.toJson.toString)
      }.getOrElse {
        sender ! HttpResponse(entity=ResponseCode(-3, "invalid parameter").toJson.toString)
      }

    case r@HttpRequest(GET, Uri.Path("/StampServer/join_store"), _, _, _) =>
      accessLog.info(r.toString)
      genStampStoreFromQuery(r).map { storeInfo =>
        try {
          val ret = Await.result(stampDB.insertStore(storeInfo), 1 seconds)
          ResponseCode(0, "OK")
        } catch {
          case e : DatabaseException =>
            ResponseCode(-1, e.getMessage())
          case e : Throwable =>
            ResponseCode(-2, e.getMessage)
        }
      }.map { res =>
        sender ! HttpResponse(entity=res.toJson.toString)
      }.getOrElse {
        sender ! HttpResponse(entity=ResponseCode(-2, "invalid parameter").toJson.toString)
      }

    case r@HttpRequest(GET, Uri.Path("/StampServer/stamp"), _, _, _) =>
      accessLog.info(r.toString)
      genStampLogFromQuery(r).flatMap { stampLog =>
        stampCache.getUserInfo(stampLog.userId).await.flatMap { userInfo =>
          stampCache.getStoreInfo(stampLog.storeId).await.map { storeInfo =>
            // put stamp log
            val fResponse : Future[ResponseCode] = 
              stampRedis.getStamp(userInfo.id, storeInfo.id, stampLog.stampNumber).map { isExist =>
              	if(isExist) ResponseCode(1, "already stamp")
                else {
                  stampRedis.putStamp(userInfo.id, storeInfo.id, stampLog.stampNumber)
                  //stampDB.writeStampLog(stampLog) // move to the writer actor
                  logWriter ! stampLog
                  ResponseCode(0, "OK")
                }
              }          
            //stampRedis.putStamp(userInfo.id, storeInfo.id, stampLog.stampNumber)
            // TODO error logging
            
            Await.result(fResponse, 1 seconds)
            //ResponseCode(0, "OK")
          }
        }
      }.map { res =>
        sender ! HttpResponse(entity=res.toJson.toString)
      }.getOrElse{
        sender ! HttpResponse(entity=ResponseCode(-1, "invalid param").toJson.toString)
      }

    case r@HttpRequest(GET, Uri.Path("/StampServer/reward"), _, _, _) =>
      accessLog.info(r.toString)
      genStampLogFromQuery(r, "reward").flatMap { stampLog =>
        stampCache.getUserInfo(stampLog.userId).await.flatMap { userInfo =>
          stampCache.getStoreInfo(stampLog.storeId).await.map { storeInfo =>
          // put stamp log
            val stampList = Await.result(stampRedis.getStampList(userInfo.id, storeInfo.id), 1 seconds)
            if (stampList.length == storeInfo.rewardStampCnt){
              stampRedis.removeStampList(userInfo.id, storeInfo.id)
              //stampDB.writeStampLog(stampLog)
              logWriter ! stampLog
              ResponseCode(0, "OK")
            } else {
              stampDB.writeStampLog(stampLog)
              ResponseCode(-1, "no enough stamp count")
            }
          }
        }
      }.map { res =>
        sender ! HttpResponse(entity = res.toJson.toString)
      }.getOrElse{
        sender ! HttpResponse(entity=ResponseCode(-2, "invalid param").toJson.toString)
      }

    case r@HttpRequest(GET, Uri.Path("/StampServer/stamp_view"), _, _, _) =>
      accessLog.info(r.toString)
      genStampLogFromQuery(r, "view").flatMap { stampLog =>
        stampCache.getUserInfo(stampLog.userId).await.flatMap { userInfo =>
          stampCache.getStoreInfo(stampLog.storeId).await.map { storeInfo =>
            // put stamp log
            val stampList : List[Int] = Await.result(stampRedis.getStampList(userInfo.id, storeInfo.id), 1 seconds)
            StampNumList(0, "ok", stampList)
            // TODO error logging
          }
        }
      }.map { res =>
        sender ! HttpResponse(entity=res.toJson.toString)
      }.getOrElse{
        sender ! HttpResponse(entity=ResponseCode(-2, "Error").toJson.toString)
      }

    case _ =>
      sender ! HttpResponse(entity = ResponseCode(-2, "invalid url").toJson.toString)
  }

  /**
   * 
   * @param request
   * @return
   */
  private def genStampUserFromQuery(request:HttpRequest) : Option[StampUser] = {
    val query = request.uri.query
    query.get("id").flatMap{ id =>
      query.get("name").flatMap { name =>
        query.get("password").map { password =>
          val gender =  query.get("gender").map(_.toInt).getOrElse(0)
          StampUser(id, name, query.get("birthday").getOrElse(""), gender, password)
        }
      }
    }
  }

  private def genStampStoreFromQuery(request:HttpRequest) : Option[StampStore] = {
    val query = request.uri.query
    query.get("id").flatMap{ id =>
      query.get("name").flatMap { name =>
        query.get("password").map { password =>
          StampStore(id, name, query.get("addr").getOrElse(""), password)
        }
      }
    }
  }

  private def genStampLogFromQuery(request:HttpRequest, act:String="stamp") : Option[StampLog] = {
    val query = request.uri.query
    query.get("uid").flatMap { uid =>
      query.get("sid").flatMap { sid =>
        query.get("action").flatMap { action =>
          if (action == act){
            query.get("snum").map { stampNum =>
              StampLog(uid, sid, action, stampNum.toInt, "OK")
            }
          } else None
        }
      }
    }
  }
}

/*
/**
 *
 * Stamp Service
 */
trait StampHttpService extends HttpService {
  import StampServerJsonResponse._
  import spray.httpx.SprayJsonSupport._

  private val prefix = "StampServer"

  implicit def executionContext = actorRefFactory.dispatcher
  implicit val timeout = Timeout(5 seconds)

  private val stampDB    = StampServer.getResources.getStampDB
  private val stampRedis = StampServer.getResources.getStampRedis
  private val stampCache = StampServer.getResources.getStampCache

  val stampRoute = pathPrefix(prefix) {
    path("join_user") {
      parameters ('id.?, 'name ? "", 'birtyday ? "", 'gender.as[Int] ? 0, 'pass.? ) {
        (id, name, birthday, gender, passwd) =>
          if (id.isDefined && passwd.isDefined) {
            val user = StampUser(id.get, name, birthday, gender, passwd.get)
            complete(stampDB.insertUser(user))
          } else {
            complete(ReturnCode(-5, "query error"))
          }
      }
    } ~
    path("join_store"){
     parameters('id.?, 'name.? , 'addr ? "", 'password.?) { (id, name, addr, passwd) =>
       if(id.isDefined && name.isDefined && passwd.isDefined){
         complete(stampDB.insertStore(StampStore(id.get, name.get, addr, passwd.get)))
       } else {
         complete(ReturnCode(-5, "query error"))
       }
     }
    } ~
    path("view_user"){
      parameter('id.?, 'check_cd.?) { (userId, checkCd) =>
        val userInfo = userId.flatMap { id => Await.result(stampDB.getUser(id), 1 seconds) }
        if (userInfo.isDefined) complete(userInfo.get)
        else complete(ReturnCode(-3, "no such user"))
      }
    } ~
    path("stamp"){
      parameter('uid.?, 'sid.?, 'snum.as[Int].?, 'check_cd.?){
        (userId, storeId, stampNum, checkCd) => {
          if(userId.isDefined && storeId.isDefined && stampNum.isDefined && checkCd.isDefined){
            userId.map(stampCache.getUserinfo(_))


            stampCache.getUserInfo(sid)
            stampCache.getStoreInfo(uid)

            stampRedis.putStamp(uid,sid, stampNum.get)


          } else {
            complete(ReturnCode(-3, "query err"))
          }
        }
      }
    } ~
    path("reward"){
      complete("")
    } ~
    path("view_stamp"){
      complete("")
    }
  }

}
*/





