package com.example.speechrecognitionapp
import org.json.JSONArray

object FirebaseLogSerializer {

    fun toJson(logs: List<Map<String, String>>): String {
        return JSONArray(logs).toString()
    }

    fun fromJson(json: String): List<Map<String, String>> {
        val array = JSONArray(json)
        val list = mutableListOf<Map<String, String>>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                mapOf(
                    "keyword" to obj.getString("keyword"),
                    "confidence" to obj.getString("confidence"),
                    "timestamp" to obj.getString("timestamp")
                )
            )
        }
        return list
    }
}
