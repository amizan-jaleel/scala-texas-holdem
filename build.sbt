import scala.languageFeature.postfixOps

name := "scala-texas-holdem"

version := "0.1"

scalaVersion := "3.0.0-M2"

scalacOptions ++= Seq("-language:postfixOps")


libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.3.0-SNAP3" % Test,
  "org.scalactic" %% "scalactic" % "3.3.0-SNAP3"
)

libraryDependencies += "commons-io" % "commons-io" % "2.6"


// HandRanks.dat is the 130 MB Two Plus Two lookup table that Evaluator reads. It used to be
// stored in Git LFS, but LFS is disabled on this repository, so it is generated locally and
// kept out of git (see .gitignore). tools/HandRankTableGenerator.java needs nothing but a
// JDK 11+ and verifies its own output against the sha256 of the canonical table.
lazy val handRanksFile = settingKey[File]("Generated Two Plus Two hand-rank lookup table")

lazy val generateHandRanks = taskKey[File]("Generate HandRanks.dat unless it is already there")

handRanksFile := (Compile / resourceDirectory).value / "HandRanks.dat"

generateHandRanks := {
  val log = streams.value.log
  val target = handRanksFile.value
  val generator = baseDirectory.value / "tools" / "HandRankTableGenerator.java"

  if (target.isFile && target.length() == 129951336L) {
    log.debug(s"${target.getName} is already generated")
  } else {
    log.info(s"Generating ${target.getName} (130 MB, one time, a few seconds)...")
    if (!generator.isFile) sys.error(s"missing generator source: $generator")
    // Run the generator straight from source, so there is no build step to keep in sync.
    val options = ForkOptions().withRunJVMOptions(Vector("-Xmx1500m"))
    val exitCode = Fork.java(options, Seq(generator.getAbsolutePath, target.getAbsolutePath))
    if (exitCode != 0) sys.error(s"generating ${target.getName} failed with exit code $exitCode")
  }
  target
}

// Compiling is the earliest common ancestor of run and test, so hooking the generator here
// means the table is in place before anything needs to read it.
Compile / compile := (Compile / compile).dependsOn(generateHandRanks).value
