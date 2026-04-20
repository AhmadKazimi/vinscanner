
package com.syarah.vinscanner.util

import android.content.Context
import com.syarah.vinscanner.data.VinInfo
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class VinDecoder(private val context: Context) {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
    }

    private val vinData: JsonObject by lazy {
        loadVinData()
    }

    private fun loadVinData(): JsonObject {
        return try {
            val json = context.assets.open("vin_data.json").bufferedReader().use { it.readText() }
            jsonParser.parseToJsonElement(json).jsonObject
        } catch (e: IOException) {
            e.printStackTrace()
            JsonObject(emptyMap())
        } catch (e: Exception) {
            e.printStackTrace()
            JsonObject(emptyMap())
        }
    }

    fun decode(vin: String): VinInfo? {
        if (vin.length != 17) return null

        val wmi = vin.substring(0, 3)
        val modelYearChar = vin[9]
        val assemblyPlantChar = vin[10]

        val wmiData = vinData["wmi"]?.jsonObject
        val modelYearData = vinData["model_year"]?.jsonObject
        val assemblyPlantData = vinData["assembly_plant"]?.jsonObject

        val manufacturerInfo = wmiData?.get(wmi)?.jsonObject
        val manufacturer = manufacturerInfo
            ?.get("manufacturer")
            ?.jsonPrimitive
            ?.contentOrNull ?: "Unknown"
        val country = manufacturerInfo
            ?.get("country")
            ?.jsonPrimitive
            ?.contentOrNull ?: "Unknown"

        val modelYear = modelYearData
            ?.get(modelYearChar.toString())
            ?.jsonPrimitive
            ?.intOrNull ?: 0

        val assemblyPlant = assemblyPlantData
            ?.get(manufacturer)
            ?.jsonObject
            ?.get(assemblyPlantChar.toString())
            ?.jsonPrimitive
            ?.contentOrNull ?: "Unknown"

        return VinInfo(
            manufacturer = manufacturer,
            country = country,
            modelYear = modelYear,
            assemblyPlant = assemblyPlant
        )
    }
}
