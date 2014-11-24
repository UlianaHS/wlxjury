package org.intracer.wmua

import akka.actor.ActorSystem
import client.dto.Namespace
import client.wlx.dto.{SpecialNomination, Contest}
import client.wlx.query.MonumentQuery
import client.{HttpClientImpl, MwBot}
import controllers.GlobalRefactor
import org.joda.time.DateTime
import scalikejdbc.{ConnectionPool, GlobalSettings, LoggingSQLAndTimeSettings}

import scala.concurrent.Await

object Tools {

  //  db.default.driver=com.mysql.jdbc.Driver
  //  db.default.url="jdbc:mysql://localhost/wlxjury"
  //  db.default.user="***REMOVED***"
  //  db.default.password="***REMOVED***"

  val regions = Set(
//    "01", // Crimea
//    "05", // Vinnitsa
//    "07", // Volyn
//    "12", // Dnipro
//    "18", // Dnipro
//    "23", // Zaporizzia
//     "26", // IF
//     "32",  // Kyivska
//    "35",  // Kirovohradska,
//    "48", // Mykolaivska
//    "53", // Poltavska
//    "56", // RIvnenska
//    "61", //Ternopilska
//      "65", //Khersonska
//    "71", //Khersonska
//    "85" //Sevastopol
  "44"  // Luhanska
  )




  def main(args: Array[String]) {
    Class.forName("com.mysql.jdbc.Driver")
    ConnectionPool.singleton("jdbc:mysql://jury.wikilovesearth.org.ua/wlxjury", "***REMOVED***", "***REMOVED***")
//    ConnectionPool.singleton("jdbc:mysql://localhost/wlxjury", "***REMOVED***", "***REMOVED***")

    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(
      enabled = true,
      singleLineMode = false,
      printUnprocessedStackTrace = false,
      stackTraceDepth = 15,
      logLevel = 'info,
      warningEnabled = false,
      warningThresholdMillis = 3000L,
      warningLogLevel = 'warn
    )

     //users()
    initImages()
    val wlmContest = Contest.WLMUkraine(2014, "09-15", "10-15")
    //wooden(wlmContest)

//    GlobalRefactor.initLists(wlmContest)
//    return

    //    ConnectionPool.singleton("jdbc:mysql://localhost/wlxjury", "***REMOVED***", "***REMOVED***")

//    for (contest <- ContestJury.findAll()) {
//
//      if (contest.country == "Ghana") {
//        println(contest)

        //        controllers.GlobalRefactor.initContest("Category:Images from Wiki Loves Earth 2014 in " + contest.country,  contest)

        //roundAndUsers(contest)

        //updateResolution(contest)

        //Admin.distributeImages(contest, Round.findByContest(contest.id).head)
//        createNextRound()
//      }
//    }
  }

  def roundAndUsers(contest: ContestJury) {
    val rounds = Round.findByContest(contest.id)
    val newRoundNum = 2
    val round = if (rounds.size < newRoundNum) {
      val r = Round.create(newRoundNum, Some(""), contest.id.toInt, "jury", 0, 10, Some(1), Some(1), None)
      ContestJury.setCurrentRound(contest.id.toInt, r.id.toInt)
      r
    } else {
      rounds.find(_.number == newRoundNum).head
    }

    if (round.jurors.isEmpty) {
      for (i <- 1 to 10) {
        val login = contest.country.replaceAll("[ \\-\\&]", "")
        User.create("Test user " + contest.country + i, login + i + "@test", User.sha1(contest.country + "/" + "123"), Set("jury"), contest.id.toInt, Some("en"))
      }
    }

    val jurors = round.jurors

    ContestJury.setCurrentRound(contest.id.toInt, round.id.toInt)

    createNextRound(round, jurors, rounds.find(_.number == newRoundNum - 1).get)
  }

  def createNextRound(round: Round, jurors: Seq[User], prevRound: Round) = {
    val newImages = ImageJdbc.byRatingMerged(0, round.id.toInt)
    if (true || newImages.isEmpty) {

      //      val selectedRegions = Set("01", "07", "14", "21", "26", "44", "48", "74")
      //
      val images = ImageJdbc.byRatingMerged(1, prevRound.id.toInt).toArray
      //ImageJdbc.byRoundMerged(prevRound.id.toInt)//.filter(_.image.region.exists(regions.contains)).sortBy(-_.totalRate)
      //.toArray



      //      ImageJdbc.findAll().filter(_.region == Some("44"))

      val imagesByJurors = images.filter {
        iwr => iwr.selection.exists {
          s => jurors.exists(j => j.id == s.juryId)
        }
      }
      //
      val selection = jurors.flatMap { juror =>
        imagesByJurors.map(img => new Selection(0, img.pageId, 0, juror.id, round.id, DateTime.now))
      }

      //      val images = ImageJdbc.byRoundMerged(prevRound.id.toInt).sortBy(-_.totalRate).take(21)
      //      val selection = images.flatMap(_.selection).map(_.copy(id = 0, round = round.id))

      Selection.batchInsert(selection)
    }
  }

  def insertMonumentsWLM() = {

    val wlmContest = Contest.WLMUkraine(2014, "09-15", "10-15")

    val monumentQuery = MonumentQuery.create(wlmContest)
    val allMonuments = monumentQuery.byMonumentTemplate(wlmContest.listTemplate)
    println(allMonuments.size)
  }


  def updateResolution(contest: ContestJury) = {

    val system = ActorSystem()
    val http = new HttpClientImpl(system)

    import system.dispatcher

    val commons = new MwBot(http, system, controllers.Global.COMMONS_WIKIMEDIA_ORG)

    import scala.concurrent.duration._

    Await.result(commons.login("***REMOVED***", "***REMOVED***"), 1.minute)

    val category = "Category:Images from Wiki Loves Earth 2014 in Ghana"
    val query = commons.page(category)

    query.imageInfoByGenerator("categorymembers", "cm", Set(Namespace.FILE)).map {
      filesInCategory =>
        val newImages = filesInCategory.flatMap(page => ImageJdbc.fromPage(page, contest)).groupBy(_.pageId)
        val existing = ImageJdbc.findAll().toSet

        for (i1 <- existing;
             i2 <- newImages.get(i1.pageId).map(seq => seq.head)
             if i1.width != i2.width || i1.height != i2.height) {
          println(s"${i2.pageId} ${i1.title}  ${i1.width}x${i1.height} -> ${i2.width}x${i2.height}")
          ImageJdbc.updateResolution(i1.pageId, i2.width, i2.height)
        }
    }
  }

  def users() = {
    for (i <- 1 to 12) {
      val password = User.randomString(8)
      val hash = User.sha1("Ukraine/" + password)

      println(s"pw: $password, hash: $hash")

    }

  }

  def initImages(): Unit = {

    val contest = ContestJury.find(20L).get

//    val category: String = "User:***REMOVED***/files" // "Commons:Wiki Loves Earth 2014/Finalists"
//    GlobalRefactor.appendImages(category, contest)

    val prevRound = Round.find(30L).get
    val round = Round.find(33L).get

//    val selection = Selection.byRound(22L)

   // ImageDistributor.distributeImages(contest, round)

    createNextRound(round, round.jurors, prevRound)
  }

  def wooden(wlmContest: Contest) = {
    val page = "Commons:Images from Wiki Loves Monuments 2014 in Ukraine special nomination Пам'ятки дерев'яної архітектури України"
    val monumentQuery = MonumentQuery.create(wlmContest)
    val nomination = SpecialNomination.wooden
    val monumentsListsId = monumentQuery.byPage(nomination.pages.head, nomination.listTemplate).map(_.id).toSet
    val nanaRound = Round.find(27L).get
    val monumentIdsByNana = ImageJdbc.byRatingMerged(1, nanaRound.id.toInt).flatMap(_.image.monumentId).toSet

    val onlyNana = monumentIdsByNana -- monumentsListsId

    val allIds = monumentIdsByNana ++ monumentsListsId

    val category = "Category:Images from Wiki Loves Monuments 2014 in Ukraine"
    val contest = ContestJury.find(20L).get
    GlobalRefactor.appendImages(category, contest, allIds)

  }

}