/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.homeofficesettledstatusproxy.connectors

import java.net.URL

import com.codahale.metrics.MetricRegistry
import com.google.inject.Singleton
import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import play.api.libs.json.Json
import play.mvc.Http.{HeaderNames, MimeTypes}
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.homeofficesettledstatusproxy.connectors.HomeOfficeRightToPublicFundsConnector.{ensureHasError, extractResponseBody}
import uk.gov.hmrc.homeofficesettledstatusproxy.models.{OAuthToken, StatusCheckByNinoRequest, StatusCheckError, StatusCheckResponse}
import uk.gov.hmrc.homeofficesettledstatusproxy.wiring.{AppConfig, ProxyHttpClient}
import uk.gov.hmrc.http._
import uk.gov.hmrc.homeofficesettledstatusproxy.connectors.ErrorCodes._
import uk.gov.hmrc.http.hooks.HookData

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class HomeOfficeRightToPublicFundsConnector @Inject()(
  appConfig: AppConfig,
  http: ProxyHttpClient,
  metrics: Metrics)
    extends HttpAPIMonitor {

  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  val HEADER_X_CORRELATION_ID = "X-Correlation-Id"

  def token(correlationId: String)(implicit ec: ExecutionContext): Future[OAuthToken] = {

    implicit val hc: HeaderCarrier =
      HeaderCarrier().withExtraHeaders(HEADER_X_CORRELATION_ID -> correlationId)

    val url = new URL(
      appConfig.rightToPublicFundsBaseUrl,
      appConfig.rightToPublicFundsPathPrefix + "/status/public-funds/token").toString

    val form: Map[String, Seq[String]] = Map(
      "grant_type"    -> Seq("client_credentials"),
      "client_id"     -> Seq(appConfig.homeOfficeClientId),
      "client_secret" -> Seq(appConfig.homeOfficeClientSecret)
    )

    monitor(s"ConsumedAPI-Home-Office-Right-To-Public-Funds-Status-Token") {
      http.POSTForm[OAuthToken](url, form)
    }
  }

  def statusPublicFundsByNino(
    request: StatusCheckByNinoRequest,
    correlationId: String,
    token: OAuthToken)(implicit ec: ExecutionContext): Future[StatusCheckResponse] = {

    implicit val hc: HeaderCarrier =
      HeaderCarrier().withExtraHeaders(
        HEADER_X_CORRELATION_ID   -> correlationId,
        HeaderNames.AUTHORIZATION -> s"${token.token_type} ${token.access_token}")

    val url = new URL(
      appConfig.rightToPublicFundsBaseUrl,
      appConfig.rightToPublicFundsPathPrefix + "/status/public-funds/nino").toString

    val headers = Seq()

    monitor(s"ConsumedAPI-Home-Office-Right-To-Public-Funds-Status-By-Nino") {
      http
        .POST[StatusCheckByNinoRequest, HttpResponse](url, request, headers)
        .map { response =>
          Option(response.body)
            .filter(!_.trim.isEmpty)
            .fold(StatusCheckResponse(correlationId))(_ => response.json.as[StatusCheckResponse])
        }
        .recover {
          case e: BadRequestException =>
            ensureHasError(
              Json
                .parse(extractResponseBody(e.message, "Response body '"))
                .as[StatusCheckResponse],
              ERR_REQUEST_INVALID)

          case e: NotFoundException =>
            ensureHasError(
              Json
                .parse(extractResponseBody(e.message, "Response body: '"))
                .as[StatusCheckResponse],
              ERR_NOT_FOUND)

          case UpstreamErrorResponse.Upstream4xxResponse(e) if e.statusCode == 409 =>
            ensureHasError(
              Json
                .parse(extractResponseBody(e.message, "Response body: '"))
                .as[StatusCheckResponse],
              ERR_CONFLICT)
        }
    }
  }

  def statusPublicFundsByNinoRaw(request: String, correlationId: String, token: OAuthToken)(
    implicit ec: ExecutionContext): Future[HttpResponse] = {

    implicit val hc: HeaderCarrier =
      HeaderCarrier().withExtraHeaders(
        HEADER_X_CORRELATION_ID   -> correlationId,
        HeaderNames.AUTHORIZATION -> s"${token.token_type} ${token.access_token}",
        HeaderNames.CONTENT_TYPE  -> MimeTypes.JSON
      )

    val url = new URL(
      appConfig.rightToPublicFundsBaseUrl,
      appConfig.rightToPublicFundsPathPrefix + "/status/public-funds/nino").toString

    val headers = Seq()

    monitor(s"ConsumedAPI-Home-Office-Right-To-Public-Funds-Status-By-Nino-Raw") {
      val response = http.doPostString(url, request, headers)
      http.hooks.foreach(hook => hook(url, "POST", Option(HookData.FromString(request)), response))
      response
    }
  }

}

object HomeOfficeRightToPublicFundsConnector {

  def extractResponseBody(message: String, prefix: String): String = {
    val pos = message.indexOf(prefix)
    val body =
      if (pos >= 0) message.substring(pos + prefix.length, message.length - 1)
      else s"""{"error":{"errCode":"$message"}}"""
    body
  }

  def ensureHasError(
    response: StatusCheckResponse,
    alternativeErrorCode: String): StatusCheckResponse =
    if (response.error.isDefined) response
    else response.copy(error = Some(StatusCheckError(alternativeErrorCode)))

}
