import com.github.sbtliquibase.SbtLiquibase

val commonSettings = Seq(
  organization := "com.github.karasiq",
  version := "1.0.0-SNAPSHOT",
  isSnapshot := version.value.endsWith("SNAPSHOT"),
  scalaVersion := "2.12.4",
  // crossScalaVersions := Seq("2.11.11", "2.12.4"),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  licenses := Seq("Apache License, Version 2.0" → url("http://opensource.org/licenses/Apache-2.0")),
  coverageExcludedPackages := "com.karasiq.shadowcloud.javafx;com.karasiq.shadowcloud.desktop;com.karasiq.shadowcloud.webapp;com.karasiq.shadowcloud.storage"
  //parallelExecution in test := false,
  //fork in test := false
)

val packageSettings = Seq(
  // javaOptions in Universal += "-Xmx2G",
  name in Universal := "shadowcloud",
  version in Universal := version.value.replace("-SNAPSHOT", ""),
  maintainer := "Karasiq",
  packageSummary := "shadowcloud application",
  packageDescription := "shadowcloud - alternative cloud storage client.",
  jdkAppIcon := {
    lazy val iconExt = sys.props("os.name").toLowerCase match {
      case os if os.contains("mac") ⇒ "icns"
      case os if os.contains("win") ⇒ "ico"
      case _ ⇒ "png"
    }
    Some(file(s"setup/icon.$iconExt"))
  },
  jdkPackagerType := "installer",
  jdkPackagerJVMArgs := Seq("-Xmx2G"),
  jdkPackagerProperties := Map("app.name" -> "shadowcloud", "app.version" -> version.value.replace("-SNAPSHOT", "")),
  // antPackagerTasks in JDKPackager := Some(file("/usr/lib/jvm/java-8-oracle/lib/ant-javafx.jar")),
  mappings in Universal += file("setup/shadowcloud_example.conf") → "shadowcloud_example.conf"
)

// -----------------------------------------------------------------------
// Shared
// -----------------------------------------------------------------------
lazy val model = crossProject
  .crossType(CrossType.Pure)
  .settings(commonSettings, name := "shadowcloud-model")
  .settings(
    PB.targets in Compile := Seq(
      scalapb.gen() → (sourceManaged in Compile).value
    ),
    PB.protoSources in Compile := Seq(
      (baseDirectory in Compile).value.getParentFile / "src" / "main" / "protobuf"
    )
  )
  .jvmSettings(libraryDependencies ++= ProjectDeps.akka.actors ++ ProjectDeps.protobuf ++ ProjectDeps.commonsConfigs)
  .jsSettings(ScalaJSDeps.akka.actors, ScalaJSDeps.protobuf, ScalaJSDeps.commonsConfigs)

lazy val modelJVM = model.jvm

lazy val modelJS = model.js

lazy val utils = crossProject
  .crossType(CrossType.Pure)
  .settings(commonSettings, name := "shadowcloud-utils")
  .jvmSettings(
    libraryDependencies ++=
      ProjectDeps.akka.streams ++
      ProjectDeps.lz4 ++
      Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value)
  )
  .dependsOn(model)

lazy val utilsJVM = utils.jvm

lazy val utilsJS = utils.js

lazy val testUtils = (crossProject.crossType(CrossType.Pure) in file("utils") / "test")
  .settings(commonSettings, name := "shadowcloud-test-utils")
  .jvmSettings(
    libraryDependencies ++=
      ProjectDeps.scalaTest ++
      ProjectDeps.akka.testKit
  )
  .dependsOn(utils)

lazy val testUtilsJVM = testUtils.jvm

lazy val testUtilsJS = testUtils.js 

// -----------------------------------------------------------------------
// Core
// -----------------------------------------------------------------------
lazy val core = project
  .settings(commonSettings)
  .settings(
    name := "shadowcloud-core",
    libraryDependencies ++= ProjectDeps.akka.all ++ ProjectDeps.kryo
  )
  .dependsOn(modelJVM, utilsJVM, storageParent, cryptoParent, metadataParent, testUtilsJVM % "test")

lazy val persistence = project
  .settings(commonSettings)
  .settings(
    name := "shadowcloud-persistence",
    libraryDependencies ++= ProjectDeps.akka.persistence ++ ProjectDeps.h2
  )
  .dependsOn(core)

lazy val coreAssembly = (project in file("core/assembly"))
  .settings(commonSettings, name := "shadowcloud-core-assembly")
  .dependsOn(
    core % "compile->compile;test->test", persistence,
    bouncyCastleCrypto, libsodiumCrypto,
    imageioMetadata, markdownMetadata,
    googleDriveStorage, mailruCloudStorage, dropboxStorage, webdavStorage
  )
  .dependsOn(
    Seq[ClasspathDep[ProjectReference]](javacvMetadata).filter(_ ⇒ sys.props.getOrElse("enable-javacv", "0") == "1") ++
    Seq[ClasspathDep[ProjectReference]](tikaMetadata).filter(_ ⇒ sys.props.getOrElse("enable-tika", "1") == "1"):_*
  )
  .aggregate(
    core, persistence,
    bouncyCastleCrypto, libsodiumCrypto,
    tikaMetadata, imageioMetadata, markdownMetadata, javacvMetadata,
    googleDriveStorage, mailruCloudStorage, dropboxStorage, webdavStorage
  )

// -----------------------------------------------------------------------
// Plugins
// -----------------------------------------------------------------------

// Crypto plugins
def cryptoPlugin(id: String): Project = {
  val prefixedId = s"crypto-$id"
  Project(prefixedId, file("crypto") / id)
    .settings(
      commonSettings,
      name := s"shadowcloud-$prefixedId"
    )
    .dependsOn(cryptoParent % "provided", testUtilsJVM % "test")
}

lazy val cryptoParent = Project("crypto-parent", file("crypto") / "parent")
  .settings(commonSettings)
  .dependsOn(modelJVM)

lazy val bouncyCastleCrypto = cryptoPlugin("bouncycastle")
  .settings(libraryDependencies ++= ProjectDeps.bouncyCastle)
  .dependsOn(testUtilsJVM % "test")

lazy val libsodiumCrypto = cryptoPlugin("libsodium")
  .settings(libraryDependencies ++= ProjectDeps.libSodiumJni)

// Storage plugins
def storagePlugin(id: String): Project = {
  val prefixedId = s"storage-$id"
  Project(prefixedId, file("storage") / id)
    .settings(
      commonSettings,
      name := s"shadowcloud-$prefixedId"
    )
    .dependsOn(core % "provided", storageParent % "provided", testUtilsJVM % "test")
}

lazy val storageParent = Project("storage-parent", file("storage") / "parent")
  .settings(commonSettings, libraryDependencies ++= ProjectDeps.akka.streams)
  .dependsOn(modelJVM, utilsJVM, testUtilsJVM % "test")

lazy val googleDriveStorage = storagePlugin("gdrive")
  .settings(libraryDependencies ++= ProjectDeps.gdrive)

lazy val dropboxStorage = storagePlugin("dropbox")
  .settings(libraryDependencies ++= ProjectDeps.dropbox)

lazy val mailruCloudStorage = storagePlugin("mailrucloud")
  .settings(libraryDependencies ++= ProjectDeps.mailrucloud)

lazy val webdavStorage = storagePlugin("webdav")
  .settings(libraryDependencies ++= ProjectDeps.sardine)

// Metadata plugins
def metadataPlugin(id: String): Project = {
  val prefixedId = s"metadata-$id"
  Project(prefixedId, file("metadata") / id)
    .settings(
      commonSettings,
      name := s"shadowcloud-$prefixedId"
    )
    .dependsOn(metadataParent % "provided", testUtilsJVM % "test")
}

lazy val metadataParent = Project("metadata-parent", file("metadata") / "parent")
  .settings(commonSettings, libraryDependencies ++= ProjectDeps.akka.streams)
  .dependsOn(modelJVM, utilsJVM)

lazy val tikaMetadata = metadataPlugin("tika")
  .settings(libraryDependencies ++= ProjectDeps.tika)

lazy val imageioMetadata = metadataPlugin("imageio")
  .dependsOn(utilsJVM)

lazy val javacvMetadata = metadataPlugin("javacv")
  .settings(libraryDependencies ++= ProjectDeps.javacv)
  .dependsOn(utilsJVM)

lazy val markdownMetadata = metadataPlugin("markdown")
  .settings(libraryDependencies ++= ProjectDeps.flexmark)

// -----------------------------------------------------------------------
// HTTP
// -----------------------------------------------------------------------
lazy val autowireApi = (crossProject.crossType(CrossType.Pure) in (file("server") / "autowire-api"))
  .settings(commonSettings)
  .jvmSettings(libraryDependencies ++= ProjectDeps.autowire ++ ProjectDeps.playJson ++ ProjectDeps.boopickle ++ ProjectDeps.scalaTest.map(_ % "test"))
  .jsSettings(ScalaJSDeps.autowire, ScalaJSDeps.playJson, ScalaJSDeps.boopickle, ScalaJSDeps.browserDom, ScalaJSDeps.scalaTest)
  .dependsOn(model)

lazy val autowireApiJVM = autowireApi.jvm

lazy val autowireApiJS = autowireApi.js

lazy val server = project
  .settings(commonSettings, name := "shadowcloud-server")
  .dependsOn(`server-api-routes` % "compile->compile;test->test", `server-static-routes`, `server-webzinc-routes`)
  .aggregate(`server-api-routes`, `server-static-routes`, `server-webzinc-routes`)

lazy val `server-api-routes` = (project in file("server") / "api-routes")
  .settings(
    commonSettings,
    name := "shadowcloud-server-api",
    libraryDependencies ++=
      ProjectDeps.akka.streams ++
      ProjectDeps.akka.http ++
      ProjectDeps.akka.testKit.map(_ % "test")
  )
  .dependsOn(core % "compile->compile;test->test", autowireApiJVM, coreAssembly % "test")

lazy val `server-static-routes` = (project in file("server") / "static-routes")
  .settings(
    commonSettings,
    name := "shadowcloud-server-static",
    scalaJsBundlerAssets in Compile += {
      import com.karasiq.scalajsbundler.dsl._
      Bundle(
        "index",
        WebDeps.indexHtml, WebDeps.bootstrap, WebDeps.videoJS, WebDeps.markedJS,
        scalaJsApplication(webapp, fastOpt = false, launcher = false).value
      )
    },
    scalaJsBundlerCompile in Compile := (scalaJsBundlerCompile in Compile)
      .dependsOn(fullOptJS in Compile in webapp)
      .value
  )
  .dependsOn(`server-api-routes`)
  .enablePlugins(SJSAssetBundlerPlugin)

lazy val `server-webzinc-routes` = (project in file("server") / "webzinc-routes")
  .settings(commonSettings, name := "shadowcloud-server-webzinc", libraryDependencies ++= ProjectDeps.webzinc)
  .dependsOn(`server-api-routes`)

lazy val webapp = (project in file("server") / "webapp")
  .settings(commonSettings)
  .settings(
    name := "shadowcloud-webapp",
    scalaJSUseMainModuleInitializer := true,
    ScalaJSDeps.bootstrap,
    ScalaJSDeps.videoJS,
    ScalaJSDeps.markedJS,
    ScalaJSDeps.java8Time,
    ScalaJSDeps.scalaCss
  )
  .dependsOn(autowireApiJS)
  .enablePlugins(ScalaJSPlugin)

// -----------------------------------------------------------------------
// Desktop app
// -----------------------------------------------------------------------
lazy val javafx = (project in file("javafx"))
  .settings(commonSettings)
  .settings(
    name := "shadowcloud-javafx-gui",
    libraryDependencies ++= ProjectDeps.scalafx
  )
  .dependsOn(core)

lazy val desktopApp = (project in file("desktop-app"))
  .settings(commonSettings, packageSettings)
  .settings(
    name := "shadowcloud-desktop",
    libraryDependencies ++= ProjectDeps.akka.slf4j ++ ProjectDeps.logback
  )
  .dependsOn(coreAssembly, server, javafx)
  .enablePlugins(JavaAppPackaging, ClasspathJarPlugin, JDKPackagerPlugin)

// -----------------------------------------------------------------------
// Misc
// -----------------------------------------------------------------------
lazy val `meta-tests` = (project in file("target") / "meta-tests")
  .settings(commonSettings, name := "shadowcloud-meta-tests")
  .aggregate(coreAssembly, `server-api-routes`, autowireApiJVM)

lazy val shell = (project in file("."))
  .settings(commonSettings)
  .settings(
    name := "shadowcloud-shell",
    mainClass in Compile := Some("com.karasiq.shadowcloud.test.Benchmark"),
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-simple" % "1.7.25"
    ),
    initialCommands in console :=
      """import com.karasiq.shadowcloud.shell.Shell._
        |init()
        |test()
        |""".stripMargin,
    liquibaseUsername := "sa",
    liquibasePassword := s"${sys.props("shadowcloud.persistence.h2.password").ensuring(_.ne(null), "No password").replace(' ', '_')} sa",
    liquibaseDriver := "org.h2.Driver",
    liquibaseUrl := {
      val path = sys.props.getOrElse("shadowcloud.persistence.h2.path", s"${sys.props("user.home")}/.shadowcloud/shadowcloud")
      val cipher = sys.props.getOrElse("shadowcloud.persistence.h2.cipher", "AES")
      val compress = sys.props.getOrElse("shadowcloud.persistence.h2.compress", true)
      s"jdbc:h2:file:$path;CIPHER=$cipher;COMPRESS=$compress"
    },
    liquibaseChangelog := file("src/main/migrations/changelog.sql")
  )
  .dependsOn(coreAssembly, javafx)
  .enablePlugins(SbtLiquibase)