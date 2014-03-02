package com.mobidoo.stampserver

case class StampServerConfig(host:String, port:Int,
                             mongoDBServer:MongoDBServer, redisServer:RedisServer)
case class RedisServer(host:String, port:Int)
case class MongoDBServer(hosts:List[String], port:Int)
