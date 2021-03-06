package neutrino.project.clientwrapper.frame.processor.params

import neutrino.project.clientwrapper.Client
import neutrino.project.clientwrapper.Params
import neutrino.project.clientwrapper.frame.RequestMethod
import neutrino.project.clientwrapper.frame.RequestMethodModel
import neutrino.project.clientwrapper.frame.ResponseConsumer
import neutrino.project.clientwrapper.frame.content.Countable
import neutrino.project.clientwrapper.util.ext.collect
import neutrino.project.clientwrapper.util.ext.executeAsync
import okhttp3.Request
import java.util.concurrent.CompletableFuture


class CountableParamsProcessor<T : Any>(
		private val method: RequestMethod<*>,
		private val client: Client,
		private val countableParam: Pair<String, Countable>,
		private val modifications: Map<String, (Params, RequestMethodModel) -> RequestMethodModel>
) : ParamsProcessor<T>() {

	private var isInterrupt: Boolean = false

	override fun process(namedParams: Map<String, Params>): ResponseConsumer<T> {
		val responseConsumers = mutableListOf<ResponseConsumer<T>>()

		while (true) {
			val countable = countableParam.second
			val methodModel = createMethodModel(namedParams.toMutableMap())

			val call = client.processAndSend(methodModel.build())!!
			val response = call.execute()
			val body = response.body()

			val doubleResponse = doubleResponse(response, body)
			val toCheckResponse = doubleResponse?.first
			val toMapResponse = doubleResponse?.second

			val isContinue = !countable.limit(countable.count, toCheckResponse)

			if (isInterrupt)
				break

			if (isContinue) {
				if (toMapResponse != null)
					responseConsumers.add(applyMapper(method.responseMapper, toMapResponse))

				countable.count = countable.count + countable.step
			} else {
				break
			}
		}

		return responseConsumers.collect()
	}

	override fun processAsync(namedParams: Map<String, Params>): CompletableFuture<ResponseConsumer<T>> {
		return CompletableFuture.supplyAsync { process(namedParams) }
	}

	private fun createMethodModel(namedParams: MutableMap<String, Params>): RequestMethodModel {
		val countable = countableParam.second
		val param = Pair(countable.name, countable.count.toString())
		namedParams[countableParam.first]!!.replace(param)

		var methodModel = RequestMethodModel(requestBuilder = Request.Builder(), apiUrl = method.url,
				customUrl = method.customUrl, baseUrl = client.getBaseUrl())

		methodModel.setMethod(method)

		namedParams.forEach { name, params ->
			methodModel = modifications[name]!!(params, methodModel)
		}

		return methodModel
	}

	override fun interrupt() {
		isInterrupt = true
	}
}