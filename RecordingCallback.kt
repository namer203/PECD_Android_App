package com.example.speechrecognitionapp

interface RecordingCallback {
    fun onDataUpdated(data: ArrayList<Result>)
}