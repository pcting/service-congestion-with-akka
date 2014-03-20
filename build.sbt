organization := "com.patrickting.test"

name := "json-poller-test"

version := "0.1"

scalaVersion := "2.10.3"

resolvers ++= Seq(
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "spray repo" at "http://repo.spray.io/"
)

libraryDependencies ++= {
  val sprayV = "1.2.1"
  val akkaV = "2.2.4"
  Seq(
    "io.spray"                %  "spray-can"            % sprayV,
    "io.spray"                %  "spray-routing"        % sprayV,
    "io.spray"                %  "spray-testkit"        % sprayV % "test",
    "io.spray"                %  "spray-caching"        % sprayV,
    "io.spray"                %  "spray-client"         % sprayV,
    "io.spray"                %  "spray-util"           % sprayV,
    "io.spray"                %  "spray-http"           % sprayV,
    "io.spray"                %  "spray-httpx"          % sprayV,
    "io.spray"                %% "spray-json"           % "1.2.5",
    "com.typesafe.akka"       %% "akka-actor"           % akkaV,
    "org.specs2"              %% "specs2"              % "2.2.3" % "test")
}

seq(Revolver.settings: _*)
