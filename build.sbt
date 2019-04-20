// Copy the ini next to the executable we'll start
import com.typesafe.sbt.SbtNativePackager.Universal

scalaVersion := "2.12.8"

name := "FlatCheck"

version := "1.0"

libraryDependencies += "org.apache.commons" % "commons-email" % "1.3.3"

libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "3.14.0"
libraryDependencies += "com.machinepublishers" % "jbrowserdriver" % "1.0.1"
libraryDependencies += "com.google.guava" % "guava" % "25.0-jre"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.2"

libraryDependencies += "org.ini4j" % "ini4j" % "0.5.4"

libraryDependencies += "org.xerial" % "sqlite-jdbc" % "3.25.2"

libraryDependencies += "com.google.api-client" % "google-api-client" % "1.28.0" 
libraryDependencies += "com.google.apis" % "google-api-services-drive" % "v3-rev136-1.25.0"
libraryDependencies += "com.google.oauth-client" % "google-oauth-client-jetty" % "1.28.0"

libraryDependencies += "com.typesafe.slick" %% "slick" % "3.3.0"

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

javaOptions in Universal ++= Seq(
  "-Dfile.encoding=UTF8",
  "-Dsun.rmi.transport.connectionTimeout=10000",
  "-Dsun.rmi.transport.tcp.handshakeTimeout=10000",
  "-Dsun.rmi.transport.tcp.responseTimeout=10000",
  "-Dsun.rmi.transport.tcp.readTimeout=10000",
  "-Xmx1200m"//,
  //"-XX:+UseG1GC",
  //"-XX:+AggressiveOpts"
)

mappings in Universal ++= {
    Seq(
      file("flatcheck.ini") -> "bin/flatcheck.ini",
      file("auth/flatcheck.json") -> "bin/flatcheck.json",
      file("chrome/chromedriver_73.0.3683.68.exe") -> "bin/chromedriver.exe")
}