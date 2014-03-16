package com.mobidoo.stampserver

/**
 * Response for Stamp Server
 */
trait StampServerResponse
case class ResponseCode(retCode:Int, retMsg:String) extends StampServerResponse
case class StampNumList(retCode:Int, retMsg:String, stampNumList:List[Int]) extends StampServerResponse



