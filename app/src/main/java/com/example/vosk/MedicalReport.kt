package com.example.vosk

/**
 * Data class representing a structured medical report extracted by the LLM.
 */
data class MedicalReport(
    val diagnosis: String,      // डॉक्टर
    val medication: String,     // दवाई
    val dosage: String,         // खुराक
    val otherTests: String,     // जांच
    val followUp: String        // फॉलोअप
)
