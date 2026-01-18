package com.example.speechrecognitionapp;

public class Result {
    private String label;
    private Double confidence;

    public Result(String label, Double confidence) {
        this.label = label;
        this.confidence = confidence;
    }

    public Result() {
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }


}
