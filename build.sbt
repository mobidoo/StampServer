name := "StampServer"

version := "1.0"

scalaVersion := "2.10.3"

libraryDependencies += "io.spray" % "spray-can" % "1.2.0"

libraryDependencies += "io.spray" % "spray-io" % "1.2.0"

libraryDependencies += "io.spray" % "spray-routing" % "1.2.0"

libraryDependencies += "io.spray" % "spray-caching" % "1.2.0"

libraryDependencies += "io.spray" %%  "spray-json" % "1.2.5"

libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "0.8.0"

libraryDependencies ++= Seq(
  "com.etaty.rediscala" %% "rediscala" % "1.3"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3",
  "org.reactivemongo" %% "reactivemongo" % "0.10.0",
  "ch.qos.logback" % "logback-classic" % "1.0.9",
  "com.typesafe.akka" %% "akka-slf4j" % "2.2.3"
)

resolvers += "spray repo" at "http://repo.spray.io"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "rediscala" at "https://github.com/etaty/rediscala-mvn/raw/master/releases/"

