package com.zhiyu.app.data

import com.zhiyu.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Safe on a user's installed app: anonymous GET only, no activity launch or credential access. */
class UsbBackendConnectivityTest {
    @Test fun realBackendHealthAndCatalogLoadWhenCalledFromTheMainDispatcher() = runBlocking(Dispatchers.Main) {
        val api = HttpApi()
        val health = JSONObject(api.request(BuildConfig.DEFAULT_SERVER_URL, "/health"))
        assertEquals("ok", health.getString("status"))
        val providers = JSONArray(api.request(BuildConfig.DEFAULT_SERVER_URL, "/api/providers"))
        assertTrue(providers.length() > 0)
        assertTrue((0 until providers.length()).any { providers.getJSONObject(it).getString("id") == "deepseek" })
    }
}
