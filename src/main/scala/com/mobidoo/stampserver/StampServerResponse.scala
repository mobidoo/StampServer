package com.mobidoo.stampserver

/**
 * Return code message for json format
 * @param retCode
 * @param retMsg
 */
sealed trait StampServerResponse
sealed case class ResponseCode(retCode:Int, retMsg:String) extends StampServerResponse
sealed case class StampNumList(retCode:Int, retMsg:String, stampNumList:List[Int]) extends StampServerResponse



