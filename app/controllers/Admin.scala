package controllers

import db.scalikejdbc.{ContestJuryJdbc, RoundJdbc, UserJdbc, UserRoleJdbc}
import org.intracer.wmua._
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.Query
import org.scalawiki.dto.cmd.query.list.{UsGender, _}
import play.api.Play.current
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.Messages.Implicits._
import play.api.i18n.{Lang, Messages}
import play.api.mvc.Results._
import play.api.mvc.{Controller, Result}
import spray.util.pimpFuture

import scala.collection.immutable.ListMap
import scala.util.Try

/**
  * Controller for admin views
  */
object Admin extends Controller with Secured {

  val sendMail = SMTPOrWikiMail

  /**
    * @param contestIdParam optional contest Id. If not set, contest of the admin user is used
    * @return List of users in admin view
    */
  def users(contestIdParam: Option[Long] = None) = withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
    user =>
      implicit request =>
        (for (contestId <- user.currentContest.orElse(contestIdParam);
              contest <- ContestJuryJdbc.findById(contestId)) yield {
          val users = UserJdbc.findByContest(contestId)
          val withWiki = wikiAccountInfo(users)

          Ok(views.html.users(user, withWiki, editUserForm.copy(data = Map("roles" -> "jury")), contest))
        }).getOrElse(Redirect(routes.Login.index())) // TODO message
  }

  /**
    * Checks if wiki accounts are valid and can be emailed via wiki acount
    *
    * @param users list of users to fetch wiki project account information
    * @return users with hasWikiEmail and accountValid flags set
    */
  def wikiAccountInfo(users: Seq[User]): Seq[User] = {
    val names = users.flatMap(_.wikiAccount)

    val accounts = Global.commons.run(
      Action(Query(
        ListParam(Users(
          UsUsers(names),
          UsProp(UsEmailable, UsGender)
        ))
      ))
    ).await.flatMap(_.lastRevisionUser).collect {
      case u: org.scalawiki.dto.User if u.id.isDefined => u
    }

    users.map { u =>
      val account = accounts.find(a => a.login == u.wikiAccount)

      u.copy(
        hasWikiEmail = account.flatMap(_.emailable).getOrElse(false),
        accountValid = account.isDefined
      )
    }
  }

  /**
    * Executes code block if currentUser is allowed to edit other user, redirects to Login page otherwise
    *
    * @param currentUser currently logged user
    * @param otherUser   other user to edit
    * @param block       code block that operates on other user
    * @return view returned from code block or Login page if not allowed
    */
  def havingEditRights(currentUser: User, otherUser: User)(block: => Result): Result = {
    if (!currentUser.canEdit(otherUser)) {
      Redirect(routes.Login.index()) // TODO message
    } else {
      block
    }
  }

  /**
    * Shows edit user form
    *
    * @param userId userId of the user to edit
    * @return edit user form view
    */
  def editUser(userId: Long) = withAuth(rolePermission(Set(User.ADMIN_ROLE, User.ROOT_ROLE, s"USER_ID_$userId"))) {
    user =>
      implicit request =>

        val editedUser = UserJdbc.findById(userId).get

        havingEditRights(user, editedUser) {

          val filledForm = editUserForm.fill(editedUser)

          Ok(views.html.editUser(user, filledForm, RoundJdbc.current(user), user.currentContest))
        }
  }

  /**
    * Saves user on user editing form submitting
    *
    * @return list of users
    */
  def saveUser() = withAuth() {
    user =>
      implicit request =>

        editUserForm.bindFromRequest.fold(
          formWithErrors => // binding failure, you retrieve the form containing errors,
            BadRequest(
              views.html.editUser(
                user,
                formWithErrors,
                RoundJdbc.current(user),
                contestId = Some(formWithErrors.data("contest").toLong)
              )
            ),
          formUser => {
            havingEditRights(user, formUser) {

              val userId = formUser.id.get
              val count = UserJdbc.countByEmail(userId, formUser.email)
              if (count > 0) {
                BadRequest(
                  views.html.editUser(
                    user,
                    editUserForm.fill(formUser).withError("email", "email should be unique"),
                    RoundJdbc.current(user),
                    contestId = formUser.contest
                  )
                )
              } else {
                if (userId == 0) {
                  createNewUser(user, formUser)
                } else {

                  // only admin can update roles
                  val newRoles = if (user.canEdit(formUser)) {
                    if (!user.hasRole(User.ROOT_ROLE, None) && formUser.roles.map(_.role).contains(User.ROOT_ROLE)) {
                      userRolesFromDb(formUser)
                    } else {
                      formUser.roles
                    }
                  } else {
                    userRolesFromDb(formUser)
                  }

                  UserJdbc.updateUser(userId, formUser.fullname, formUser.wikiAccount, formUser.email, newRoles, formUser.lang)

                  for (password <- formUser.password) {
                    val hash = UserJdbc.hash(formUser, password)
                    UserJdbc.updateHash(userId, hash)
                  }
                }

                val result = if (user.hasAnyRole(User.ADMIN_ROLES)) {
                  Redirect(routes.Admin.users(formUser.contest))
                } else {
                  Redirect(routes.Login.index())
                }
                val lang = for (lang <- formUser.lang; if formUser.id == user.id) yield lang

                lang.fold(result)(l => result.withLang(Lang(l)))
              }
            }
          }
        )
  }

  /**
    *
    * @param user user with id
    * @return user roles from database
    */
  def userRolesFromDb(user: User): Set[String] = {
    (for (userId <- user.id;
          dbUser <- UserJdbc.findById(userId))
      yield dbUser.roles).getOrElse(Set.empty)
  }

  def showImportUsers(contestIdParam: Option[Long]) = withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
    user =>
      implicit request =>

        val contestId = contestIdParam.orElse(user.currentContest).get
        Ok(views.html.importUsers(user, importUsersForm, contestId))
  }

  def importUsers(contestIdParam: Option[Long] = None) = withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
    user =>
      implicit request =>
        val contestId = contestIdParam.orElse(user.currentContest).get

        importUsersForm.bindFromRequest.fold(
          formWithErrors => // binding failure, you retrieve the form containing errors,
            BadRequest(views.html.importUsers(user, importUsersForm, contestId)),
          formUsers => {
            val contest = ContestJuryJdbc.findById(contestId)
            val parsed = User.parseList(formUsers)
              .map(_.copy(
                lang = user.lang,
                contest = Some(contestId)
              ))

            val results = parsed.map(pu => Try(createUser(user, pu.copy(roles = Set("jury")), contest)))

            Redirect(routes.Admin.users(Some(contestId)))
          })

  }

  def appConfig = play.Play.application.configuration

  def editGreeting(contestIdParam: Option[Long], substituteJurors: Boolean = true) = withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
    user =>
      implicit request =>

        (for (contestId <- contestIdParam.orElse(user.currentContest);
              contest <- ContestJuryJdbc.findById(contestId)) yield {

          val greeting = getGreeting(contest)

          val recipient = new User(fullname = "Recipient Full Name", email = "Recipient email", id = None, contest = contest.id)

          val substitution = if (substituteJurors) {
            val users = UserJdbc.findByContest(contestId)
            users.map {
              recipient =>
                fillGreeting(greeting.text.get, contest, user, recipient.copy(password = Some("***PASSWORD***")))
            }
          } else {
            Seq.empty
          }

          Ok(views.html.greetingTemplate(
            user, greetingTemplateForm.fill(greeting), contestId, variables(contest, user, recipient), substitution
          ))
        }).getOrElse(Redirect(routes.Login.index()))
  }

  def getGreeting(contest: ContestJury): Greeting = {
    val defaultGreeting = appConfig.getString("wlxjury.greeting")

    contest.greeting.text.fold(contest.greeting.copy(text = Some(defaultGreeting)))(_ => contest.greeting)
  }

  def fillGreeting(template: String, contest: ContestJury, sender: User, user: User) = {
    variables(contest, sender, user).foldLeft(template) {
      case (s, (k, v)) =>
        s.replace(k, v)
    }
  }

  def variables(contest: ContestJury, sender: User, recipient: User): Map[String, String] = {
    val host = appConfig.getString("wlxjury.host")

    ListMap(
      "{{ContestType}}" -> contest.name,
      "{{ContestYear}}" -> contest.year.toString,
      "{{ContestCountry}}" -> contest.country,
      "{{ContestCountry}}" -> contest.country,
      "{{JuryToolLink}}" -> host,
      "{{AdminName}}" -> sender.fullname,
      "{{RecipientName}}" -> recipient.fullname,
      "{{Login}}" -> recipient.email,
      "{{Password}}" -> recipient.password.getOrElse("")
    )
  }

  def saveGreeting(contestIdParam: Option[Long] = None) = withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
    user =>
      implicit request =>
        val contestId = contestIdParam.orElse(user.currentContest).get

        greetingTemplateForm.bindFromRequest.fold(
          formWithErrors => // binding failure, you retrieve the form containing errors,
            BadRequest(views.html.importUsers(user, importUsersForm, contestId)),
          formGreeting => {

            ContestJuryJdbc.updateGreeting(contestId, formGreeting)
            Redirect(routes.Admin.users(Some(contestId)))
          })

  }

  def createNewUser(user: User, formUser: User): User = {
    val contest: Option[ContestJury] = formUser.currentContest.flatMap(ContestJuryJdbc.findById)
    createUser(user, formUser, contest)
  }

  def createUser(creator: User, formUser: User, contestOpt: Option[ContestJury]): User = {
    val password = formUser.password.getOrElse(UserJdbc.randomString(12))
    val hash = UserJdbc.hash(formUser, password)

    val toCreate = formUser.copy(password = Some(hash), contest = contestOpt.flatMap(_.id).orElse(creator.contest))

    val createdUser = UserJdbc.create(toCreate)

    formUser.roles.headOption.map { role =>
      UserRoleJdbc.addRole(createdUser.id.get, contestOpt.flatMap(_.id), role)
    }

    contestOpt.foreach { contest =>
      if (contest.greeting.use) {
        sendMail(creator, createdUser, contest, password)
      }
    }
    createdUser
  }

  def sendMail(creator: User, recipient: User, contest: ContestJury, password: String): Unit = {
    val greeting = getGreeting(contest)
    val subject = Messages("welcome.subject", contest.name)
    val message = fillGreeting(greeting.text.get, contest, creator, recipient.copy(password = Some(password)))
    sendMail.sendMail(creator, recipient, subject, message)
  }

  val editUserForm = Form(
    mapping(
      "id" -> longNumber,
      "fullname" -> nonEmptyText,
      "wikiAccount" -> optional(text),
      "email" -> email,
      "password" -> optional(text),
      "roles" -> optional(text),
      "contest" -> optional(longNumber),
      "lang" -> optional(text)
    )(User.applyEdit)(User.unapplyEdit)
  )

  val importUsersForm = Form(
    single(
      "userstoimport" -> nonEmptyText
    )
  )

  val greetingTemplateForm = Form(
    mapping(
      "greetingtemplate" -> optional(text),
      "use" -> boolean
    )(Greeting.apply)(Greeting.unapply)
  )

  def resetPassword(id: Long) = withAuth(rolePermission(Set(User.ROOT_ROLE))) {
    user =>
      implicit request =>
        val editedUser = UserJdbc.findById(id).get

        val password = UserJdbc.randomString(8)
        val contest: Option[ContestJury] = editedUser.currentContest.flatMap(ContestJuryJdbc.findById)
        val contestName = contest.fold("")(_.name)
        val hash = UserJdbc.hash(editedUser, password)

        UserJdbc.updateHash(editedUser.id.get, hash)

        val juryhome = "http://localhost:9000"
        //        User.updateUser(formUser.fullname, formUser.email, hash, formUser.roles, formUser.contest)
        val subject: String = s"Password changed for $contestName jury"
        val message: String = s"Password changed for $contestName jury\n" +
          s" Please login to our jury tool $juryhome \nwith login: ${editedUser.email} and password: $password\n" +
          s"Regards, ${user.fullname}"
        // sendMail.sendMail(from = (user.fullname, user.email), to = Seq(user.email), bcc = Seq(user.email), subject = subject, message = message)

        Redirect(routes.Admin.editUser(id)).flashing("password-reset" -> s"Password reset. New Password sent to ${editedUser.email}")

  }
}

case class Greeting(text: Option[String], use: Boolean)
