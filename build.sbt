name := "FlatCheck"

version := "1.0"

libraryDependencies += "org.apache.commons" % "commons-email" % "1.3.3"

libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "2.48.2"

libraryDependencies += "org.fluentlenium" % "fluentlenium-core" % "0.10.8"

libraryDependencies += "org.ini4j" % "ini4j" % "0.5.2"

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)