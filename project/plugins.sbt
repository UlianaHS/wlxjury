// Comment to get more information during initialization
// logLevel := Level.Warn

// The Typesafe repository 
resolvers ++= Seq("typesafe" at "http://repo.typesafe.com/typesafe/releases/",
  Resolver.file("file", new File(Path.userHome.absolutePath + "/.ivy2/local/")),
  "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"
)

// The Sonatype snapshots repository
resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

resolvers += Resolver.url(
  "bintray-sbt-plugin-releases",
  url("http://dl.bintray.com/content/sbt/sbt-plugin-releases"))(
  Resolver.ivyStylePatterns)

addSbtPlugin("me.lessis" % "bintray-sbt" % "0.1.1")

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.4.8")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.4")

resolvers += Classpaths.sbtPluginReleases

addSbtPlugin("au.com.onegeek" %% "sbt-dotenv" % "1.1.33")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.0.4")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.0.0.BETA1")