package com.mobidoo.stampserver

import org.joda.time.DateTime
import spray.json.DefaultJsonProtocol

/**
 * @param birthDay
 * @param gender
 */
sealed trait StampServerType
case class StampUser(id:String, name:String, birthDay:String, gender:Int, password:String) extends StampServerType
case class StampStore(id:String, name:String, addr:String, password:String, rewardStampCnt:Int=12) extends StampServerType
case class StampLog(userId:String, storeId:String, action:String, stampNumber:Int=0, status:String="OK", gender:Int=0,
                    birthday:String="", rewardStampCnt:Int=0, dateTime:DateTime=DateTime.now()) extends StampServerType

/**
 * for Json format
 */
object StampServerResponseJson extends DefaultJsonProtocol {
  implicit val RetCodeJson      = jsonFormat2(ResponseCode)
  implicit val StampNumListJson = jsonFormat3(StampNumList)
  implicit val StampUserJson    = jsonFormat5(StampUser)
  implicit val StampStoreJson   = jsonFormat5(StampStore)
}