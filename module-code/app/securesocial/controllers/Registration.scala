/**
 * Copyright 2012-2014 Jorge Aliss (jaliss at gmail dot com) - twitter: @jaliss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package securesocial.controllers

import org.joda.time.DateTime
import play.api.data.Forms._
import play.api.data._
import play.api.data.validation.Constraints._
import play.api.i18n.Messages
import play.filters.csrf._
import play.api.mvc.{ AnyContent, Request, Action }
import securesocial.core._
import securesocial.core.authenticator.CookieAuthenticator
import securesocial.core.providers.UsernamePasswordProvider
import securesocial.core.providers.utils._
import securesocial.core.services.SaveMode

import scala.concurrent.{ Await, Future }

/**
 * A default Registration controller that uses the BasicProfile as the user type
 *
 * @param env the environment
 */
class Registration(override implicit val env: RuntimeEnvironment[BasicProfile]) extends BaseRegistration[BasicProfile]

/**
 * A trait that provides the means to handle user registration
 *
 * @tparam U the user type
 */
trait BaseRegistration[U] extends MailTokenBasedOperations[U] {

  import securesocial.controllers.BaseRegistration._

  private val logger = play.api.Logger("securesocial.controllers.Registration")

  val providerId = UsernamePasswordProvider.UsernamePassword

  val UserName = "userName"
  val FirstName = "firstName"
  val LastName = "lastName"
  val CheckNewsletter = "newsletter"
  val CheckAcceptTermsAndConditions = "agreement"

  val formWithUsername = Form[RegistrationInfo](
    mapping(
      UserName -> nonEmptyText.verifying(Messages(UserNameAlreadyTaken), userName => {
        // todo: see if there's a way to avoid waiting here :-\
        import scala.concurrent.duration._
        Await.result(env.userService.find(providerId, userName), 20.seconds).isEmpty
      }),
      FirstName -> nonEmptyText,
      LastName -> nonEmptyText,
      Password ->
        tuple(
          Password1 -> nonEmptyText.verifying(PasswordValidator.constraint),
          Password2 -> nonEmptyText
        ).verifying(Messages(PasswordsDoNotMatch), passwords => passwords._1 == passwords._2),
      Email -> email.verifying(nonEmpty),
        CheckNewsletter -> boolean,
      CheckAcceptTermsAndConditions -> checked("securesocial.signup.error.invalidTermsAndConditions")
    ) // binding
    ((userName, firstName, lastName, password, email, checkGetInfo, checkAgreement) => RegistrationInfo(Some(userName), firstName, lastName, password
        ._1,
        email, checkGetInfo, UserAgreement(checkAgreement))) // unbinding
        (info => Some((info.userName.getOrElse(""), info.firstName, info.lastName, ("", ""), info.email, info.newsletterSubscription, info.userAgreement.acceptTermsAndConditions)))
  )

  val formWithoutUsername = Form[RegistrationInfo](
    mapping(
      FirstName -> nonEmptyText,
      LastName -> nonEmptyText,
      Password ->
        tuple(
          Password1 -> nonEmptyText.verifying(PasswordValidator.constraint),
          Password2 -> nonEmptyText
        ).verifying(Messages(PasswordsDoNotMatch), passwords => passwords._1 == passwords._2),
      Email -> email.verifying(nonEmpty),
      CheckNewsletter -> boolean,
      CheckAcceptTermsAndConditions -> checked("securesocial.signup.error.invalidTermsAndConditions")
    ) // binding
    ((firstName, lastName, password, email, checkGetInfo, checkAgreement) => RegistrationInfo(None, firstName, lastName, password._1, email, checkGetInfo, UserAgreement(checkAgreement))) //
    // unbinding
    (info => Some((info.firstName, info.lastName, ("", ""), info.email, info.newsletterSubscription, info.userAgreement.acceptTermsAndConditions)))
  )

  val form = if (UsernamePasswordProvider.withUserNameSupport) formWithUsername else formWithoutUsername

  /**
   * Starts the sign up process
   */
  def startSignUp = CSRFAddToken {
    Action {
      implicit request =>
        if (SecureSocial.enableRefererAsOriginalUrl) {
          SecureSocial.withRefererAsOriginalUrl(Ok(env.viewTemplates.getStartSignUpPage(formWithoutUsername)))
        } else {
          Ok(env.viewTemplates.getStartSignUpPage(formWithoutUsername))
        }
    }
  }

  def confirmedSignUp = {
    Action {
      implicit request => Ok(env.viewTemplates.getConfirmedSignUpPage())
    }
  }

  def handleStartSignUp = CSRFCheck {
    Action.async {
      implicit request =>
        form.bindFromRequest.fold(
          errors => {
            Future.successful(BadRequest(env.viewTemplates.getStartSignUpPage(errors)))
          },
          (registrationInfo: RegistrationInfo) => {
            handleStartSignUpSuccess(registrationInfo: RegistrationInfo)
          }
        )
    }
  }

  def handleStartSignUpSuccess(registrationInfo: RegistrationInfo)(implicit request: Request[AnyContent]) = {
    val email = registrationInfo.email.toLowerCase
    // check if there is already an account for this email address
    env.userService.findByEmailAndProvider(email, UsernamePasswordProvider.UsernamePassword).map {
      maybeUser =>
        maybeUser match {
          case Some(user) =>
            // user signed up already, send an email offering to login/recover password
            env.mailer.sendAlreadyRegisteredEmail(user)
          case None =>
            createToken(registrationInfo, isSignUp = true).flatMap { token =>
              val savedToken = env.userService.saveToken(token)
              env.mailer.sendSignUpEmail(email, token.uuid)
              savedToken
            }
        }
        handleStartResult().flashing(Success -> Messages(ThankYouCheckEmail), Email -> email)
    }
  }

  /**
   * Renders the sign up page
   * @return
   */
  def signUp(token: String) = CSRFAddToken {
    Action.async {
      implicit request =>
        logger.debug("[securesocial] trying sign up with token %s".format(token))
        executeForToken(token, true, {
          _ =>
            Future.successful(Ok(env.viewTemplates.getSignUpPage()))
        })
    }
  }

  /**
   * Handles posts from the sign up page
   */
  def handleSignUp(token: String) = {
    Action.async {
      implicit request =>
        executeForToken(token, true, {
          t =>
            val id = if (UsernamePasswordProvider.withUserNameSupport) t.userName.get else t.email
            val newUser = BasicProfile(
              providerId,
              id,
              firstName = t.registrationInfo.map(info => info.firstName),
              lastName = t.registrationInfo.map(info => info.lastName),
              fullName = t.registrationInfo.map(info => "%s %s".format(info.firstName, info.lastName)),
              email = Some(t.email),
              avatarUrl = None,
              authMethod = AuthenticationMethod.UserPassword,
              passwordInfo = t.registrationInfo.map(info => env.currentHasher.hash(info.password)),
              userAgreement = t.registrationInfo.map(info => info.userAgreement)
            )

            val withAvatar = env.avatarService.map {
              _.urlFor(t.email).map { url =>
                if (url != newUser.avatarUrl) newUser.copy(avatarUrl = url) else newUser
              }
            }.getOrElse(Future.successful(newUser))

            import securesocial.core.utils._
            val result = for (
              toSave <- withAvatar;
              saved <- env.userService.save(toSave, SaveMode.SignUp);
              deleted <- env.userService.deleteToken(t.uuid)
            ) yield {
              if (UsernamePasswordProvider.sendWelcomeEmail)
                env.mailer.sendWelcomeEmail(newUser)
              val eventSession = Events.fire(new SignUpEvent(saved)).getOrElse(request.session)
              if (UsernamePasswordProvider.signupSkipLogin) {
                env.authenticatorService.find(CookieAuthenticator.Id).map {
                  _.fromUser(saved).flatMap { authenticator =>
                    confirmationResult(gatewayRegistration = true).flashing(Success -> Messages(SignUpDone)).startingAuthenticator(authenticator)
                  }
                } getOrElse {
                  logger.error(s"[securesocial] There isn't CookieAuthenticator registered in the RuntimeEnvironment")
                  Future.successful(confirmationResult().flashing(Error -> Messages("There was an error signing you up")))
                }
              } else {
//                TODO: handle gatewayRegistration parameter dynamically!
                Future.successful(confirmationResult(gatewayRegistration = true).flashing(Success -> Messages(SignUpDone)).withSession(eventSession))
              }
            }
            result.flatMap(f => f)
        })
    }
  }
}

object BaseRegistration {
  val UserNameAlreadyTaken = "securesocial.signup.userNameAlreadyTaken"
  val ThankYouCheckEmail = "securesocial.signup.thankYouCheckEmail"
  val InvalidLink = "securesocial.signup.invalidLink"
  val SignUpDone = "securesocial.signup.signUpDone"
  val Password = "password"
  val Password1 = "password1"
  val Password2 = "password2"
  val Email = "email"

  val PasswordsDoNotMatch = "securesocial.signup.passwordsDoNotMatch"
}

case class UserAgreement(acceptTermsAndConditions: Boolean,
                         acceptTermsAndConditionsDate: DateTime = DateTime.now())
/**
 * The data collected during the registration process
 */
case class RegistrationInfo(userName: Option[String],
                            firstName: String,
                            lastName: String,
                            password: String,
                            email: String,
                            newsletterSubscription: Boolean,
                            userAgreement: UserAgreement
                            )
