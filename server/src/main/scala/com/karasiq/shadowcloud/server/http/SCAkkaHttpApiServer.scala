package com.karasiq.shadowcloud.server.http

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import autowire.Core.Request
import play.api.libs.json.Json

import com.karasiq.shadowcloud.ShadowCloudExtension
import com.karasiq.shadowcloud.api.{SCApiUtils, ShadowCloudApi}
import com.karasiq.shadowcloud.api.jvm.SCDefaultApiServer
import com.karasiq.shadowcloud.server.http.api.ShadowCloudApiImpl

private[server] trait SCAkkaHttpApiServer { self: Directives ⇒
  protected val sc: ShadowCloudExtension

  protected object scApiInternal {
    private[this] implicit val executionContext: ExecutionContext = sc.implicits.executionContext
    val apiServer = SCDefaultApiServer

    val apiEncoding = apiServer.encoding
    import apiEncoding.implicits._

    val apiRouter = apiServer.route[ShadowCloudApi](new ShadowCloudApiImpl(sc))
    type RequestT = apiServer.Request
  }

  import scApiInternal._
  import apiEncoding.implicits._

  protected object scApiDirectives {
    def validateContentType(expectedValue: MediaType): Directive0 = {
      extract(_.request.entity.contentType)
        .require(_.mediaType == expectedValue, UnsupportedRequestContentTypeRejection(Set(ContentTypeRange(expectedValue))))
    }

    def validateHeader(name: String, func: String ⇒ Boolean): Directive0 = {
      headerValueByName(name).require(func, MalformedHeaderRejection(name, "Invalid header value"))
    }

    val validateRequestedWith = {
      validateHeader("X-Requested-With", _ == SCApiUtils.requestedWith)
    }

    val extractApiRequest: Directive1[RequestT] = {
      def extractAutowireRequest(timeout: FiniteDuration = 5 seconds): Directive1[RequestT] = {
        extractUnmatchedPath.flatMap {
          case Uri.Path.Slash(path) ⇒
            extractStrictEntity(timeout).map { entity ⇒
              Request(path.toString().split("/"), scApiInternal.apiServer.decodePayload(entity.data))
            }

          case _ ⇒
            reject
        }
      }

      def extractValidRequest: Directive1[RequestT] = {
        extractAutowireRequest()
          .filter(apiRouter.isDefinedAt, MalformedRequestContentRejection("Invalid api request", new IllegalArgumentException))
      }

      val contentType = {
        MediaType.parse(apiServer.payloadContentType).right
          .getOrElse(sys.error(s"Invalid content type: ${apiServer.payloadContentType}"))
      }

      // checkSameOrigin(HttpOriginRange(HttpOrigin("http://localhost:9000"), HttpOrigin("http://127.0.0.1:9000"))) &
      // validateHeader("Content-Type", apiServer.payloadContentType) &

      validateRequestedWith &
        validateContentType(contentType) &
        validateHeader("SC-Accept", _ == apiServer.payloadContentType) &
        extractValidRequest
    }

    def executeApiRequest(request: RequestT): Route = {
      onSuccess(apiRouter(request)) { result ⇒
        complete(Json.stringify(apiServer.write(result)))
      }
    }
  }

  def scApiRoute: Route = (post & pathPrefix("api") & scApiDirectives.extractApiRequest) { request ⇒
    scApiDirectives.executeApiRequest(request)
  }
}
