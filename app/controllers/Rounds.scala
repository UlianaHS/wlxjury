package controllers

import controllers.Admin._
import org.intracer.wmua.{Selection, User, Contest, Round}
import play.api.mvc.Controller

object Rounds extends Controller with Secured {

  def rounds() = withAuth({
    user =>
      implicit request =>
        val rounds = Round.findByContest(user.contest)
        val contest = Contest.byId(user.contest).get

        Ok(views.html.rounds(user, rounds, editRoundForm,
          imagesForm.fill(Some(contest.getImages)),
          selectRoundForm.fill(contest.currentRound.toString),
          Round.current(user)))
  }, Set(User.ADMIN_ROLE))

  def roundStat() = withAuth({
    user =>
      implicit request =>
        //        val rounds = Round.findByContest(user.contest)
        val contest = Contest.byId(user.contest).get
        val round: Round = Round.current(user)

        val selection = Selection.byRound(round.id);

        val byUserCount = selection.groupBy(_.juryId).mapValues(_.size)
        val byUserRateCount = selection.groupBy(_.juryId).mapValues(_.groupBy(_.rate).mapValues(_.size))

        val totalCount = selection.map(_.pageId).toSet.size
        val totalByRateCount = selection.groupBy(_.rate).mapValues(_.map(_.pageId).toSet.size)

        Ok(views.html.roundStat(user, round, byUserCount, byUserRateCount, totalCount, totalByRateCount))
  }, Set(User.ADMIN_ROLE) ++ User.ORG_COM_ROLES)

}
