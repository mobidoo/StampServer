package com.mobidoo.stampserver

import akka.actor._
import akka.event.Logging
import akka.routing.RoutedActorRef
import akka.routing.Router

import spray.util._
import spray.json._
import spray.can._
import spray.http._
import spray.routing._
import HttpMethods._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.{future, Future}

import reactivemongo.core.errors.DatabaseException

/**
 * Stamp log writing Actor
 */
class StampLogWriter extends Actor with SprayActorLogging {
  private val stampDB = StampServer.getResources.getStampDB
 
  override def preRestart(reason: Throwable, message : Option[Any]) {
    // logging
    log.error("preRestart! : "+ reason.getMessage())
    message map (self ! _ )
  }
  
  def receive = {
    case l@StampLog(uid, sid, act, sn, status,dateTime) =>
      stampDB.writeStampLog(l)
  }
  
}

/**
 * Stamp Actor
 */
class StampActor extends Actor with StampHttpService {
  import StampServerResponseJson._
  import spray.httpx.SprayJsonSupport._
  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._
  import scala.concurrent.duration._
  
  import context.dispatcher
  
  println("Debug:" + context.system.toString)
  val accessLog = Logging(context.system, this)

  /*
  override val supervisorStrategy = 
    OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 30 seconds){
    case _ : Throwable => Restart
    }*/
  
  def actorRefFactory = context

  def receive = runRoute(stampRoute)
  /*
  {
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
                  logWriterRouter ! stampLog
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
              logWriterRouter ! stampLog
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
  } */
}


/**
 *
 * Stamp Service
 */
trait StampHttpService extends HttpService {
  import StampServerResponseJson._
  import spray.httpx.SprayJsonSupport._

  private val prefix = "StampServer"

  implicit def executionContext = actorRefFactory.dispatcher
  //implicit val timeout = Timeout(5 seconds)

  private val stampDB    = StampServer.getResources.getStampDB
  private val stampRedis = StampServer.getResources.getStampRedis
  private val stampCache = StampServer.getResources.getStampCache
  private val logWriter  = StampServer.getLogWriter
  
  val stampRoute = pathPrefix(prefix) {
    path("join_user") {
      parameters ('id, 'name ? "", 'birtyday ? "", 'gender.as[Int] ? 0, 'pass.? ) {
        (id, name, birthday, gender, passwd) =>
          val user = StampUser(id, name, birthday, gender, passwd.get)
          try {
            stampDB.insertUser(user)
            complete(ResponseCode(0, "OK"))
          } catch {
            case e : Throwable =>
              complete(ResponseCode(-1, e.getMessage()))
          } 
      }
    } ~
    path("join_store"){
      parameters('id, 'name , 'addr ? "", 'password) { (id, name, addr, passwd) =>
        val store = StampStore(id, name, addr, passwd)
        try {
          stampDB.insertStore(store)
          complete(ResponseCode(0, "OK"))
        } catch {
          case e : Throwable =>
            complete(ResponseCode(-1, e.getMessage()))
        }
      }
    } ~
    path("view_user"){
      parameters('id, 'check_cd ? "") { (userId, checkCd) =>
        complete(stampDB.getUser(userId))
      }
    } ~
    path("stamp"){
      parameters('uid, 'sid, 'snum.as[Int], 'check_cd ? ""){ (userId, storeId, stampNum, checkCd) => 
        val fRes = stampCache.getUserInfo(userId).flatMap {
          case Some(uId) =>
            stampCache.getStoreInfo(storeId).flatMap {
              case Some(sId) =>
                stampRedis.getStamp(userId, storeId, stampNum).map { res =>
                  if (res) 
                    ResponseCode(1, "already stamp")
                  else {
                    stampRedis.putStamp(userId, storeId, stampNum)
                    //TODO :logWriterRouter !
                    logWriter ! StampLog(userId, storeId, "stamp", stampNum)
                    ResponseCode(0, "OK")
                  }
                }
              case None =>
                future{ ResponseCode(-1, "No such store") }
            }
          case None =>
            future {ResponseCode(-1, "No such User")}
        }
        complete(fRes)
      }
    } ~
    path("reward"){
      parameters('uid, 'sid, 'action, 'check_cd ? ""){ (userId, storeId, action, checkCd) => 
        if (action == "reward") {
          val fRes = stampCache.getUserInfo(userId).flatMap {
            case Some(uId) =>
              stampCache.getStoreInfo(storeId).flatMap {
                case Some(sId) =>
                  stampRedis.getStampList(userId, storeId).map { stampList =>
                    if (stampList.length == sId.rewardStampCnt){
                      stampRedis.removeStampList(userId, storeId)
                      logWriter ! StampLog(userId, storeId, "reward")
                      ResponseCode(0, "OK")
                    } else {
                      
                      ResponseCode(-1, "no enough stamp cnt")
                    }
                  }
                case None =>
                  future{ ResponseCode(-1, "No such store") }
              }
            case None =>
              future {ResponseCode(-1, "No such User")}
          }
          
          complete(fRes)
        } else complete(ResponseCode(-1, "no"))
      }
    } ~
    path("view_stamp"){
      parameters('uid, 'sid, 'action, 'check_cd ? "") { (userId, storeId, action, checkCd) =>
        complete(stampRedis.getStampList(userId, storeId))
      }
    }
  }

}







