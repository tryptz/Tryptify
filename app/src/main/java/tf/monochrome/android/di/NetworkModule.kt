package tf.monochrome.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideHttpClient(
        json: Json,
        access: tf.monochrome.android.data.api.ApiServerAccess,
    ): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            level = LogLevel.NONE
        }
        // Only the Discord gateway uses this. The plugin costs nothing until a
        // session is opened, and a second client just for one socket would
        // duplicate the engine and its connection pool.
        install(WebSockets)
        // No timeouts of its own: every request keeps the engine's 30 s below.
        // Installed so one request can ask for longer with timeout {} — the
        // TIDAL Atmos download, which can wait on the server building the file.
        install(HttpTimeout)
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
                // http:// only to servers at home (CleartextPolicy), checked on
                // every hop, redirects included.
                addNetworkInterceptor { chain ->
                    tf.monochrome.android.data.net.CleartextPolicy.require(chain.request().url.toString())
                    chain.proceed(chain.request())
                }
                // A server's password (a TrypT HiFi behind ACCESS_PASSWORD), on
                // every hop to its own origin and never to another: a network
                // interceptor sees each redirect separately.
                addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val password = access.passwordFor(request.url.toString())
                    chain.proceed(
                        if (password == null) request
                        else request.newBuilder().header(tf.monochrome.android.data.api.ApiServerAccess.HEADER, password).build(),
                    )
                }
                // OkHttp runs five requests to one host at a time and queues
                // the rest: a Trypt Mania batch sends a song to every
                // replica of its chart server at once (StepManiaGenerationWorker.MAX_LANES).
                dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = 64
                        maxRequestsPerHost = 48
                    },
                )
            }
        }
    }
}
