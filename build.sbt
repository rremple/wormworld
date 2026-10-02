scalaVersion := "3.9.0"

lazy val root = rootProject
  .settings(
    name := "wormworld",
    version := "0.1.0-SNAPSHOT",
    scalacOptions ++= Seq("-source", "future"),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-Wunused:imports",
      "-Wunused:privates",
      "-Wunused:locals",
      "-Wunused:explicits", // not :implicits or :params -- too many false positives
      "-Wunused:nowarn"
    ),
    libraryDependencies ++= Seq(
      "io.github.rremple" %% "intervalidus" % "4.1.0",
      "org.scalatest" %% "scalatest" % "3.2.20" % Test)
  )
