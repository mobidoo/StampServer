package com.mobidoo.stampserver

/**
 * Response for Stamp Server
 */
sealed trait StampServerResponse
sealed case class ResponseCode(retCode:Int, retMsg:String) extends StampServerResponse
sealed case class StampNumList(retCode:Int, retMsg:String, stampNumList:List[Int]) extends StampServerResponse



