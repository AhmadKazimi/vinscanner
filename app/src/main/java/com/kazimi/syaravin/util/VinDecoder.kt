
package com.kazimi.syaravin.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kazimi.syaravin.data.VinInfo
import java.io.IOException

class VinDecoder(private val context: Context) {

    private val vinData: Map<String, Any> by lazy {
        loadVinData()
    }

    private fun loadVinData(): Map<String, Any> {
        return try {
            val json = context.assets.open("vin_data.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: IOException) {
            e.printStackTrace()
            emptyMap()
        }
    }

    fun decode(vin: String): VinInfo? {
        if (vin.length != 17) return null

        val wmi = vin.substring(0, 3)
        val modelYearChar = vin[9]
        val assemblyPlantChar = vin[10]

        val wmiData = vinData["wmi"] as? Map<String, Map<String, String>>
        val modelYearData = vinData["model_year"] as? Map<String, Double>
        val assemblyPlantData = vinData["assembly_plant"] as? Map<String, Map<String, String>>

        val manufacturerInfo = wmiData?.get(wmi)
        val manufacturer = manufacturerInfo?.get("manufacturer") ?: "Unknown"
        val country = manufacturerInfo?.get("country") ?: "Unknown"

        val modelYear = modelYearData?.get(modelYearChar.toString())?.toInt() ?: 0

        val assemblyPlant = assemblyPlantData?.get(manufacturer)?.get(assemblyPlantChar.toString()) ?: "Unknown"

        return VinInfo(
            manufacturer = manufacturer,
            country = country,
            modelYear = modelYear,
            assemblyPlant = assemblyPlant
        )
    }
}
