package neutrino.project.clientwrapper

import neutrino.project.clientwrapper.util.cookie.DefaultClientCookieHandler
import neutrino.project.clientwrapper.util.cookie.impl.ClientCookieHandler
import neutrino.project.clientwrapper.util.exception.BadRequestException
import neutrino.project.clientwrapper.util.exception.SessionInterruptedException
import okhttp3.*
import okhttp3.Response
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


class OkHttpClientWrapper : Client {

	val baseUrl: String

	val coreClient: OkHttpClient

	var cookieManager: CookieManager? = null

	val cookieHandler = DefaultClientCookieHandler(this)

	constructor(baseUrl: String) {
		this.baseUrl = baseUrl
		createCookieManager()
		this.coreClient = createUnsafeDefault()
	}

	constructor(baseUrl: String, coreClient: OkHttpClient) {
		this.baseUrl = baseUrl
		this.coreClient = coreClient
	}

	override fun getClientCookieHandler(): ClientCookieHandler {
		return cookieHandler
	}

	override fun sendGet(url: String): neutrino.project.clientwrapper.Response {
		val okHttpResponse = newRequestBuilder()
				.url(url)
				.get()
				.execute()
				.orElseThrow { BadRequestException() }

		return OkHttpResponseWrapper(okHttpResponse)

	}

	override fun sendPost(url: String, body: Map<String, String>): neutrino.project.clientwrapper.Response {
		val okHttpResponse = newRequestBuilder()
				.url(url)
				.post(body)
				.execute()
				.orElseThrow { BadRequestException() }

		return OkHttpResponseWrapper(okHttpResponse)
	}

	override fun newRequestBuilder(): RequestBuilder {
		return OkHttpRequestBuilder(Request.Builder())
	}

	inner class OkHttpResponseWrapper(private val response: Response) : neutrino.project.clientwrapper.Response {

		private val body: String?

		private val code: Int

		init {
			code = response.code()
			body = response.body()?.string()
			response.close()
		}

		override fun body(): String? {
			return body
		}

		override fun code(): Int {
			return code
		}

		override fun isAuthorized(checkData: String): neutrino.project.clientwrapper.Response {
			return isAuthorized({ body!!.contains(checkData) })
		}

		override fun isAuthorized(checkFunc: () -> Boolean): neutrino.project.clientwrapper.Response {
			if (body == null)
				throw SessionInterruptedException("Is unauthorized")

			return if (checkFunc.invoke())
				this
			else
				throw SessionInterruptedException("Is unauthorized")
		}
	}

	inner class OkHttpRequestBuilder(private val requestBuilder: Request.Builder) : RequestBuilder {

		override fun url(url: String): OkHttpRequestBuilder {
			println("$baseUrl$url")
			requestBuilder.url("$baseUrl$url")
			return this
		}

		override fun addHeader(name: String, value: String): OkHttpRequestBuilder {
			requestBuilder.header(name, value)
			return this
		}

		override fun get(): OkHttpRequestBuilder {
			requestBuilder.get()
			return this
		}

		override fun post(params: Map<String, String>): OkHttpRequestBuilder {
			val requestBodyBuilder = FormBody.Builder()
			params.forEach { requestBodyBuilder.add(it.key, it.value) }
			val requestBody = requestBodyBuilder.build()

			requestBuilder.post(requestBody)
			return this
		}

		override fun execute(): Optional<Response> {
			val request: Request? = requestBuilder.build()
			val response = coreClient
					.newCall(request)
					.execute()

			return Optional.ofNullable(response)
		}

		override fun executeAndGetBody(): Optional<String> {
			val request: Request? = requestBuilder.build()
			val response = coreClient.newCall(request).execute()
			val body = response.body()?.string()
			response.close()

			return Optional.ofNullable(body)
		}
	}

	private fun createDefault(): OkHttpClient {
		return OkHttpClient.Builder()
				.cookieJar(JavaNetCookieJar(cookieManager))
				.cache(getCache("victoria"))
				.followRedirects(true)
				.connectTimeout(2, TimeUnit.MINUTES)
				.readTimeout(2, TimeUnit.MINUTES)
				.writeTimeout(2, TimeUnit.MINUTES)
				.connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
				.build()
	}

	private fun createUnsafeDefault(): OkHttpClient {
		val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {

			override fun getAcceptedIssuers(): Array<X509Certificate> {
				return emptyArray()
			}

			override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {
				//No need to implement.
			}

			override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {
				//No need to implement.
			}
		})
		val sc = SSLContext.getInstance("SSL")
		sc.init(null, trustAllCerts, java.security.SecureRandom())

		return OkHttpClient.Builder()
				.cookieJar(JavaNetCookieJar(cookieManager))
				.cache(getCache("victoria"))
				.followRedirects(true)
				.connectTimeout(2, TimeUnit.MINUTES)
				.readTimeout(2, TimeUnit.MINUTES)
				.writeTimeout(2, TimeUnit.MINUTES)
				.connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
				//FIXME
				.sslSocketFactory(sc.socketFactory)
				.build()
	}

	private fun getCache(child: String): Cache {
		val cacheDir = File(System.getProperty("java.io.tmpdir"), child)
		return Cache(cacheDir, 1024)
	}

	private fun createCookieManager(): CookieManager {

		val cookieManager = CookieManager()
		cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)

		this.cookieManager = cookieManager
		return cookieManager
	}
}